;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.review
  "tik review: sign, in one batch, what waits on nobody but the reviewer —
  process definitions the reviewer has not published, and tickets whose
  next stage misses nothing the reviewer cannot supply.

  A ticket qualifies when every reason its frontier stage gives is either
  the reviewer's signature on a fact already on record, or a fact the
  reviewer passes as `k=v`. Approving re-asserts the recorded values
  exactly, so each signature names the commit or hash it covers, and
  asserts the passed ones. A dry run over the same events must show the
  stage landing and nothing regressing before anything is offered.

  With `--batch` (ADR 0037) the reviewer's key signs one delegation on the
  identity registry instead of every fact: the delegation scopes the exact
  ticket heads listed and the fact paths the plans set, and stays valid
  for five minutes. A throwaway key made in a private directory signs the
  facts and is deleted afterwards, so a security key is touched once for
  the whole list.

  Porcelain over explain and the whatif fold: it reads derivations and
  writes ordinary signed facts, and stores nothing of its own."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.args :refer [actor parse-key typed-value]]
            [tik.cli-core :refer [append!* archive-process! available-processes call-with-temp-dir
                                  by-hash-file die load-process now
                                  registry-events resolve-id root
                                  signing-key stage-delta the-store ticket-ctx
                                  ticket-row]]
            [tik.dag :as dag]
            [tik.delegation :as delegation]
            [tik.design :as design]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.inspect :as inspect]
            [tik.lint :as lint]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.render :refer [emit-data shash]]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.protocol :as store])
  (:import (java.nio.file.attribute PosixFilePermissions)))

;; ------------------------------------------------------------------ plan

(def ^:private settable
  "Reasons a fact the reviewer passes discharges. A disputed or conflicted
  path needs a judgment about the other claim, so it never qualifies."
  #{:fact/missing :fact/mismatch :fact/invalid :role/unsatisfied})

(defn- step-for
  "The fact that discharges reason `r` for `who`, as [path value], or nil
  when the reviewer cannot discharge it."
  [r state roles who given]
  (let [{:keys [reason role path]} r]
    (cond
      (and path (contains? given path) (contains? settable reason)
           (or (not= :role/unsatisfied reason)
               (contains? (set (get-in roles [role :members])) who)))
      [path (get given path)]

      (and (= :role/unsatisfied reason)
           (contains? (set (get-in roles [role :members])) who))
      (let [{:keys [status value]} (red/fact-status state path)]
        (when (= :present status) [path value])))))

(defn- block-steps
  "Every fact one frontier block needs from `who`, in first-mention order,
  or nil when some reason is not the reviewer's to discharge."
  [{:keys [missing]} state roles who given]
  (when (seq missing)
    (let [steps (map #(step-for % state roles who given) missing)]
      (when (every? some? steps)
        (vec (distinct steps))))))

(defn plan
  "What `who` would sign on one ticket: {:stage :steps [[path value] …]}
  for the first frontier stage the reviewer alone completes, or nil.
  `given` maps paths to the values the reviewer passes. A plan is offered
  only when replaying its steps as `who` derives the stage and loses no
  stage already reached."
  [process events t roles who given]
  (let [state (red/ticket-state events)
        before (stage/effective-reached process events t roles)]
    (first
     (for [block (explain/explain process events t roles before)
           :let [steps (block-steps block state roles who given)]
           :when steps
           :let [after (stage/effective-reached
                        process
                        (:events (reduce design/apply-step
                                         {:events (vec (red/ordered events))
                                          :now t :actor who}
                                         (for [[path value] steps]
                                           [:set path value])))
                        t roles)
                 {:keys [lost]} (stage-delta before after)]
           :when (and (contains? after (:stage block)) (empty? lost))]
       {:stage (:stage block) :steps steps}))))

;; ----------------------------------------------------------- definitions

(defn- published-by?
  "Does a publication signature over the archived definition `hash`
  verify as `who`'s, against the store's actors registry?"
  [hash who]
  (let [dir (io/file (root) "processes" "by-hash")
        f (by-hash-file hash)
        signers (io/file (root) "actors")]
    (and (.exists f) (.exists signers)
         (boolean
          (some #(and (= who (first (sign/find-principals
                                     signers f % sign/namespace-process)))
                      (sign/verify signers f % who sign/namespace-process))
                (sign/sidecars dir hash))))))

(defn unsigned-definitions
  "The store's current definitions that lint clean and carry no
  publication signature of `who`, as {:process :hash}. A definition with
  lint errors stays unsigned and is reported on stderr."
  [who wanted?]
  (vec
   (for [nm (available-processes)
         :when (wanted? nm nil)
         :let [proc (load-process nm)
               errors (filter #(= :error (:level %)) (lint/lint proc))]
         :when (or (empty? errors)
                   (binding [*out* *err*]
                     (println (str "tik review: " nm " does not lint, so it stays"
                                   " unsigned: " (:msg (first errors))))
                     false))
         :let [hash (process/process-hash proc)]
         :when (not (published-by? hash who))]
     {:process nm :hash hash})))

(defn sign-definition!
  "Archive the definition `nm` by hash and sign its canonical bytes in
  the tik-process namespace. Returns the hash."
  [key nm]
  (let [hash (archive-process! (load-process nm))]
    (sign/sign! key (by-hash-file hash) hash sign/namespace-process)
    hash))

;; --------------------------------------------------------------- tickets

(defn- process-name [proc] (some-> (:process/id proc) name))

(defn waiting-tickets
  "Every open ticket with a plan for `who`, as {:id :title :process
  :stage :steps}. A ticket whose log lacks ancestors, or whose upstream
  dependencies are unsettled, is left out: either would make the plan's
  dry run disagree with the replica that holds the whole story."
  [s t who given-raw wanted?]
  (vec
   (for [id (sort-by str (store/ticket-ids s))
         :let [row (ticket-row s t id)]
         :when (and (nil? (:error row)) (not (:settled? row)))
         :let [{:keys [events process roles state missing-parents]} (ticket-ctx s id)
               pname (process-name process)]
         :when (and (empty? missing-parents)
                    (wanted? pname (str id))
                    (empty? (inspect/unmet-deps s t id)))
         :let [given (into {} (for [[k v] given-raw
                                    :let [path (parse-key k)]
                                    :when (contains? (:process/facts process) path)]
                                [path (typed-value process path v)]))
               p (plan process events t roles who given)]
         :when p]
     (assoc p :id id :title (:title state) :process pname
            :heads (set (dag/heads events))))))

(defn approve!
  "Record a plan's steps as signed facts of `who`, one chained event each."
  [s {:keys [id steps]} opts]
  (doseq [[path value] steps]
    (append!* s (event/assert-fact
                 {:ticket id :actor (actor opts) :at (now)
                  :parents (dag/heads (store/events s id))
                  :path path :value value})
              opts)))

;; ----------------------------------------------------------------- batch

(defn- registry-id
  "The identity-registry ticket a batch delegation goes on: `--registry`,
  else the store's only one."
  [s opts]
  (if-let [ref (:registry opts)]
    (resolve-id s ref)
    (let [ids (distinct (keep :event/ticket (registry-events s)))]
      (case (count ids)
        0 (die (str "a batch review delegates on an identity-registry ticket, and the"
                    " store has none — mint one with\n"
                    "  tik new identity-registry --title 'identity registry'"))
        1 (first ids)
        (die "the store has several identity-registry tickets — pass --registry <id>")))))

(defn- throwaway-key!
  "A fresh Ed25519 key in a new directory only this user can read."
  [dir]
  (let [k (io/file dir "key")
        r (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "tik-batch" "-f" (str k))]
    (when-not (zero? (:exit r))
      (die (str "could not make the throwaway key: " (str/trim (:err r)))))
    k))

(defn moved
  "The ids of `shown` tickets whose heads in `current` (id -> heads)
  differ from the heads they were listed with: something happened on them
  that the reviewer did not see."
  [shown current]
  (into [] (keep (fn [{:keys [id heads]}]
                   (when (not= (set heads) (set (get current id))) id)))
        shown))

(defn approve-batch!
  "Sign one delegation with the reviewer's key, then every step of
  `tickets` with a throwaway key it names. Each fact's parents are the
  heads the reviewer was shown, because the delegation's scope names
  exactly those; chaining the facts would put all but the first outside
  it. Returns the delegation event."
  [s tickets opts]
  (let [who (actor opts)
        reg (registry-id s opts)
        heads (into {} (for [{:keys [id heads]} tickets] [id heads]))
        stale (moved tickets (into {} (for [{:keys [id]} tickets]
                                        [id (set (dag/heads (store/events s id)))])))
        _ (when (seq stale)
            (die (str "signed nothing: " (str/join ", " (map #(subs (str %) 0 8) stale))
                      " changed after the list was drawn; review again")))
        owner-only [(PosixFilePermissions/asFileAttribute
                     (PosixFilePermissions/fromString "rwx------"))]]
    (call-with-temp-dir
     "tik-batch" owner-only
     (fn [dir]
      (let [tkey (throwaway-key! dir)
            at (now)
            not-after (.plus ^java.time.Instant at ^java.time.Duration delegation/max-window)
            d (event/add-attestation
               {:ticket reg :actor who :at at
                :parents (dag/heads (store/events s reg))
                :claim {:claim delegation/claim-type
                        :delegation/public-key (sign/pubkey (str tkey))
                        :delegation/not-after not-after
                        :delegation/scope (set (for [[id hs] heads h hs] [id h]))
                        :delegation/facts (set (for [{:keys [steps]} tickets
                                                     [path _] steps]
                                                 path))}})]
        (append!* s d opts)
        (doseq [{:keys [id steps]} tickets
                [path value] steps
                :let [t (now)]]
          (when (.isAfter ^java.time.Instant t not-after)
            (die (str "the delegation expired before " (subs (str id) 0 8)
                      " was signed; review the rest again")))
          (append!* s (event/assert-fact {:ticket id :actor who :at t
                                          :parents (get heads id)
                                          :path path :value value})
                    (assoc opts :key (str tkey))))
        d)))))

;; --------------------------------------------------------------- command

(defn- wanted-fn
  "Positional filters: a process name, or a prefix of a ticket id. No
  filters means everything."
  [filters]
  (if (empty? filters)
    (constantly true)
    (fn [pname id]
      (boolean (some #(or (= % pname) (and id (str/starts-with? id %)))
                     filters)))))

(defn- show-path [path] (str/join "." (map name path)))

(defn- print-list [definitions tickets]
  (let [n (atom 0)]
    (doseq [{:keys [process hash]} definitions]
      (println (format "%3d  definition %-24s %s…" (swap! n inc) process (shash hash))))
    (doseq [{:keys [id title process stage steps]} tickets]
      (println (format "%3d  %-10s %-24s %s" (swap! n inc) (subs (str id) 0 8)
                       (str process " → " (name stage)) title))
      (doseq [[path value] steps]
        (println (str "       " (show-path path) " = " (pr-str value)))))))

(defn- confirmed? [n who]
  (print (str "Sign all " n " as " who "? [y/N] "))
  (flush)
  (contains? #{"y" "Y" "yes"} (some-> (read-line) str/trim)))

(defn cmd-review
  "review [k=v …] [PROCESS|TICKET …] [--list] [--yes]: list what waits on
  this actor alone, then sign it in one batch."
  [{:keys [pos opts]}]
  (let [s (the-store)
        t (now)
        who (actor opts)
        {given true filters false} (group-by #(str/includes? % "=") pos)
        given (for [kv given] (str/split kv #"=" 2))
        wanted? (wanted-fn filters)
        definitions (unsigned-definitions who wanted?)
        tickets (waiting-tickets s t who given wanted?)
        total (+ (count definitions) (count tickets))]
    (cond
      (emit-data opts {:definitions definitions :tickets tickets}) nil

      (zero? total) (println "nothing waits for" who)

      :else
      (do
        (print-list definitions tickets)
        (when-not (:list opts)
          (when (and (seq definitions) (nil? (signing-key opts)))
            (die "publishing a definition needs a key: pass --key or set TIK_KEY"))
          (when (and (:batch opts) (seq tickets) (nil? (signing-key opts)))
            (die "a batch review signs its delegation: pass --key or set TIK_KEY"))
          (when (and (:batch opts) (seq tickets))
            (registry-id s opts))
          (if-not (or (:yes opts) (confirmed? total who))
            (println "signed nothing")
            (do
              (doseq [{nm :process} definitions]
                (sign-definition! (signing-key opts) nm)
                (println "published" nm))
              (if (and (:batch opts) (seq tickets))
                (let [d (approve-batch! s tickets opts)]
                  (println (str "delegated " (count tickets) " ticket(s) to a throwaway key"
                                " under delegation " (shash (:event/id d)) "…")))
                (doseq [p tickets] (approve! s p opts)))
              (doseq [{:keys [id stage] :as p} tickets]
                (let [{:keys [events process roles]} (ticket-ctx s id)
                      reached (stage/effective-reached process events (now) roles)]
                  (println (str (if (contains? reached stage) "approved " "signed, but not yet ")
                                (:process p) " " (subs (str id) 0 8) " → "
                                (name stage))))))))))))
