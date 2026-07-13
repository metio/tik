;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.guard
  "The closed guard vocabulary, v1.

  Guards are deterministic pure functions of (state, now, reached). Effects
  live in edge admission checks, never here: `verify` must re-evaluate every
  guard identically, offline, years later.

  eval-guard returns {:satisfied? bool :reasons [reason-map ...]}.
  Reasons are DATA — the kernel speaks EDN, never English. Rendering to
  text/forms/MCP task specs is the lens's job (tik.explain for the CLI).
  Reason keys: :reason (namespaced keyword), plus :path/:schema/:role/
  :stage/:by/:note/:expected/:actual/:prefix/:since/:duration/:due/
  :errors/:options/:guard as applicable.

  All fact inspection goes through tik.reduce/fact-status — the single
  choke point for why a fact does or does not satisfy guards.

  Vocabulary v2 — twelve operators: :fact :fact= :artifact :signed-by
  :stage-reached :elapsed-since :attested-within :different-person
  :and :or :not :malli. v2 is additive over v1 (ADR 0006 discipline
  applied to semantics: old definitions evaluate unchanged forever).
  :attested-within closes §18's stale-evidence gap; :different-person
  is separation of duties. Both read the LOG (which lives in state), so
  guards remain pure functions of (state, now, reached) — attestations
  stay out of ticket-state (ADR 0009) yet inside derivation's reach.
  (:fact= was demoted to :malli-expanding sugar in v6 and RESTORED by
  dogfood evidence: the expansion double-reported an absent fact —
  :fact/missing plus a redundant schema error — and ADR 0016 makes
  structured reasons the API, so reason quality is a contract concern,
  not a rendering nicety.) Note ADR 0005: [:not [:stage-reached ...]]
  is lint-restricted to strictly earlier strata — negation over
  `reached` must be stratified; negation over facts is monotone-safe
  and unrestricted."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [tik.reduce :as red])
  #?(:clj (:import (java.time Duration Instant))))

(defn- ->instant
  "Type-hint pass-through: the single canonical time type is Instant
  (the readers produce it, the printer rejects everything else); a
  non-Instant here is hostile data and fails in the guarded op."
  ^Instant [t]
  t)

(defn fact-map
  "Facts as a simple {path value} map of effective values."
  [state]
  (into {}
        (keep (fn [[path _]]
                (when-some [v (red/fact-value state path)] [path v])))
        (:facts state)))

(def ^:private ok {:satisfied? true :reasons []})
(defn- fail [& reasons] {:satisfied? false :reasons (vec reasons)})

(declare eval-guard)

(defn- fact-status*
  "fact-status through the ctx memo when one rides along: guard
  evaluation asks about the same (state, path) many times per sweep
  and per fixpoint, and conflict detection walks the DAG each time —
  the memo makes each pair cost one walk per EVALUATION, not one per
  ask. Purely an evaluation-local cache: same inputs, same answer."
  [ctx path]
  (if-let [memo (:fact-memo ctx)]
    (or (get @memo path)
        (let [r (red/fact-status (:state ctx) path)]
          (vswap! memo assoc path r)
          r))
    (red/fact-status (:state ctx) path)))

(defn- eval-fact [[_ path] {:keys [process] :as ctx}]
  (let [{:keys [status] :as fs} (fact-status* ctx path)
        schema (get-in process [:process/facts path])]
    (case status
      :absent    (fail {:reason :fact/missing :path path :schema schema})
      :retracted (fail {:reason :fact/retracted :path path
                        :by (:by fs) :note (:note fs)})
      :disputed  (fail {:reason :fact/disputed :path path
                        :by (:by fs) :note (:note fs)})
      :conflicted (fail {:reason :fact/conflicted :path path
                         :claims (:claims fs)})
      :present
      (if (and schema (not (m/validate schema (:value fs))))
        (fail {:reason :fact/invalid :path path :value (:value fs)
               :schema schema
               :errors (me/humanize (m/explain schema (:value fs)))})
        ok))))

(defn- eval-artifact [[_ prefix] {:keys [state]}]
  (if (some #(str/starts-with? % prefix) (keys (:artifacts state)))
    ok
    (fail {:reason :artifact/missing :prefix prefix})))

(defn- eval-signed-by [[_ role path] {:keys [roles] :as ctx}]
  (let [base (eval-fact [:fact path] ctx)]
    (if-not (:satisfied? base)
      base
      (let [members (set (get-in roles [role :members]))
            by (:by (fact-status* ctx path))]
        (if (contains? members by)
          ok
          (fail {:reason :role/unsatisfied :role role :path path :by by}))))))

(defn- eval-stage-reached [[_ stage-id] {:keys [reached]}]
  (if (contains? reached stage-id)
    ok
    (fail {:reason :stage/not-reached :stage stage-id})))

(defn- eval-elapsed [[_ ref dur-str] {:keys [state now]}]
  (let [start (case ref
                :ticket/create (:created-at state)
                (throw (ex-info "unknown :elapsed-since reference" {:ref ref})))
        due (some-> start ->instant (.plus (Duration/parse dur-str)))]
    (if (and due (not (.isBefore ^Instant now due)))
      ok
      (fail {:reason :time/not-elapsed :since ref :duration dur-str :due due}))))

(defn- eval-malli [[_ schema] {:keys [state]}]
  (let [facts (fact-map state)]
    (if (m/validate schema facts)
      ok
      (fail {:reason :schema/unsatisfied :schema schema
             :errors (me/humanize (m/explain schema facts))}))))

(defn- eval-fact=
  "Present AND equal, with exactly one reason per failure mode: absent
  reports :fact/missing carrying :expected (the lens can say what to
  set it TO); a different value reports :fact/mismatch."
  [[_ path expected] ctx]
  (let [{:keys [status value]} (fact-status* ctx path)]
    (cond
      (not= :present status)
      (fail {:reason :fact/missing :path path :expected expected})

      (not= expected value)
      (fail {:reason :fact/mismatch :path path
             :expected expected :actual value})

      :else ok)))

(defn- eval-attested-within
  "[:attested-within claim duration]: a fresh-enough attestation of
  `claim` exists — an :attestation/add event whose body :claim equals
  `claim`, no older than `duration` before `now` ON THE CLAIMED CLOCK
  (ADR 0012: claimed is the default; a witnessed variant arrives with
  witness infrastructure). Closes the stale-evidence gap: a replayed
  \"CI green\" from last month is cryptographically valid and fails
  this guard honestly."
  [[_ claim dur-str] {:keys [state now]}]
  (let [cutoff (.minus (->instant now) (Duration/parse dur-str))
        attests? (fn [e] (and (= :attestation/add (:event/type e))
                              (= claim (get-in e [:event/body :claim]))))
        fresh (filter (fn [e] (and (attests? e)
                                   (not (.isBefore (->instant (:event/at e))
                                                   cutoff))))
                      (:log state))]
    (if (seq fresh)
      ok
      (let [any (filter attests? (:log state))]
        (if (seq any)
          (fail {:reason :attestation/stale :claim claim
                 :within dur-str
                 :last-at (:event/at (last (sort-by :event/at any)))})
          (fail {:reason :attestation/missing :claim claim
                 :within dur-str}))))))

(defn- eval-different-person
  "[:different-person path-a path-b]: separation of duties — both facts
  present AND asserted by different actors. The four-eyes principle as
  a derivable condition."
  [[_ path-a path-b] ctx]
  (let [a (fact-status* ctx path-a)
        b (fact-status* ctx path-b)]
    (cond
      (not= :present (:status a))
      (fail {:reason :fact/missing :path path-a})

      (not= :present (:status b))
      (fail {:reason :fact/missing :path path-b})

      (= (:by a) (:by b))
      (fail {:reason :role/same-person :paths [path-a path-b]
             :by (:by a)})

      :else ok)))

(defn- eval-guard*
  [guard ctx]
  (case (first guard)
    :fact          (eval-fact guard ctx)
    :attested-within (eval-attested-within guard ctx)
    :different-person (eval-different-person guard ctx)
    :fact=         (eval-fact= guard ctx)
    :artifact      (eval-artifact guard ctx)
    :signed-by     (eval-signed-by guard ctx)
    :stage-reached (eval-stage-reached guard ctx)
    :elapsed-since (eval-elapsed guard ctx)
    :malli         (eval-malli guard ctx)
    :and (let [rs (map #(eval-guard* % ctx) (rest guard))]
           {:satisfied? (every? :satisfied? rs)
            :reasons (into [] (mapcat :reasons) rs)})
    :or  (let [rs (map #(eval-guard* % ctx) (rest guard))]
           (if (some :satisfied? rs)
             ok
             (fail {:reason :alternatives
                    :options (mapv :reasons rs)})))
    :not (let [r (eval-guard* (second guard) ctx)]
           (if (:satisfied? r)
             (fail {:reason :must-not-hold :guard (second guard)})
             ok))
    (throw (ex-info "unknown guard operator (closed vocabulary v1)"
                    {:guard guard}))))

(defn eval-guard
  "ctx: {:state :process :now :reached :roles}. Total or fails well:
  any throw escaping evaluation is ex-info carrying the guard — lint
  keeps malformed guards out of real definitions, but a hostile or
  hand-built caller must get a data-carrying rejection, never a bare
  type error from inside an operator."
  [guard ctx]
  (try
    (eval-guard* guard ctx)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      (throw e))
    (catch #?(:clj Exception :cljs :default) e
      (throw (ex-info "malformed guard" {:reason :guard/malformed
                                         :guard guard} e)))))
