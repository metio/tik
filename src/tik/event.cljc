;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.event
  "Event schemas and minting.

  An event is an immutable, content-addressed claim by an actor. The id is
  the content address of the event WITHOUT :event/id. Signatures are
  DETACHED sidecars over the exact stored bytes (ADR 0007) — never fields
  inside the event — so the .edn file is the claim and .sig files are
  endorsements of the claim.

  :event/parents is MANDATORY (ADR 0004): the head ids the actor observed.
  :ticket/create is the unique root with #{}; every other type must carry a
  non-empty set. Parents live inside the signed region, so the log is a
  Merkle DAG — one head commits to all of history. Parents are for
  integrity, concurrency detection, and sync; deliberately NOT for
  reduction order, which stays (at, id) over the event set."
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [malli.core :as m]
            [tik.canonical :as canonical]))

(def event-types
  "Seven types — everything else is expressible: a comment is an
  :artifact/attach of a text blob; a link is a fact ([:link ...] path); a
  work record is an :attestation/add with a :work claim body; a witness
  countersignature is a detached sidecar over a head, not an event (an
  event would move the head it witnesses)."
  [:enum
   :ticket/create
   :fact/assert
   :fact/retract        ; "this fact should not exist, no replacement"
   :fact/dispute        ; rejection with reason; regression happens by derivation
   :artifact/attach
   :attestation/add     ; identity/role/stage/work claims, read by lenses
   :process/migrate])   ; explicit, signed re-pin (ADR 0002)

(def Event
  [:map
   [:event/id :string]
   [:event/ticket :uuid]
   [:event/type event-types]
   [:event/actor :string]
   [:event/at inst?]
   [:event/parents [:set :string]]          ; mandatory, ADR 0004
   [:event/body {:optional true} [:map-of :any :any]]])

(def valid? (m/validator Event))
(def explain-event (m/explainer Event))

(defn event-id
  "Content address of `event`, excluding only :event/id itself."
  [event]
  (canonical/content-address (dissoc event :event/id)))

(defn mint
  "Attach the content-addressed :event/id and validate. Enforces ADR 0004:
  only :ticket/create may (and must) have empty parents."
  [event]
  (when-not (map? event)
    (throw (ex-info "an event must be a map"
                    {:reason :event/malformed :event event})))
  (let [e (assoc event :event/id (event-id event))]
    (when-not (valid? e)
      (throw (ex-info "invalid event" {:explain (explain-event e)})))
    ;; the stored bytes must READ BACK to the event, or the store gains
    ;; a file whose hash is honest and whose content is unreachable
    ;; forever (a keyword with a space, an unprintable value). Refusing
    ;; at mint closes corrupt-on-write for every producer at once.
    (let [bytes (canonical/emit (dissoc e :event/id))
          reread (try (edn/read-string bytes)
                      (catch #?(:clj Exception :cljs :default) _ ::unreadable))]
      ;; byte-level round trip: what parses back must re-emit to the
      ;; very same bytes (Instant and Date print identically, so time
      ;; types pass; a keyword with a space cannot)
      (when (or (= ::unreadable reread)
                (not= bytes (canonical/emit reread)))
        (throw (ex-info "event does not survive serialization — a value cannot be written that could never be read"
                        {:reason :event/unwritable :event e}))))
    (let [root? (= :ticket/create (:event/type e))
          empty-parents? (empty? (:event/parents e))]
      (when (and root? (not empty-parents?))
        (throw (ex-info ":ticket/create is the root; parents must be #{}" {:event e})))
      (when (and (not root?) empty-parents?)
        (throw (ex-info "non-root events must reference observed heads (ADR 0004)"
                        {:event e}))))
    e))

(defn create-ticket
  "Tickets PIN both the process version label and, more importantly, the
  CONTENT HASH of the process definition (ADR 0002 + 0006): `verify` then
  never depends on trusting file naming — the ruleset is hash-pinned from
  inside the log."
  [{:keys [ticket actor at title process version process-hash]}]
  (mint {:event/ticket ticket :event/type :ticket/create
         :event/actor actor :event/at at :event/parents #{}
         :event/body (cond-> {:ticket/title title :ticket/process process}
                       version (assoc :ticket/process-version version)
                       process-hash (assoc :ticket/process-hash process-hash))}))

(defn migrate-process [{:keys [ticket actor at parents version process-hash reason]}]
  (mint {:event/ticket ticket :event/type :process/migrate
         :event/actor actor :event/at at :event/parents (set parents)
         :event/body (cond-> {:process/version version}
                       process-hash (assoc :process/hash process-hash)
                       reason (assoc :migrate/reason reason))}))

(defn assert-fact [{:keys [ticket actor at parents path value]}]
  (mint {:event/ticket ticket :event/type :fact/assert
         :event/actor actor :event/at at :event/parents (set parents)
         :event/body {:fact/path path :fact/value value}}))

(defn dispute-fact [{:keys [ticket actor at parents path reason]}]
  (mint {:event/ticket ticket :event/type :fact/dispute
         :event/actor actor :event/at at :event/parents (set parents)
         :event/body {:fact/path path :dispute/reason reason}}))

(defn retract-fact [{:keys [ticket actor at parents path reason]}]
  (mint {:event/ticket ticket :event/type :fact/retract
         :event/actor actor :event/at at :event/parents (set parents)
         :event/body (cond-> {:fact/path path}
                       reason (assoc :retract/reason reason))}))

(defn attach-artifact
  [{:keys [ticket actor at parents path hash derived-from]}]
  (mint {:event/ticket ticket :event/type :artifact/attach
         :event/actor actor :event/at at :event/parents (set parents)
         :event/body (cond-> {:artifact/path path :artifact/hash hash}
                       ;; provenance edge, the attacher's claim (ADR 0014)
                       derived-from
                       (assoc :artifact/derived-from derived-from))}))

(defn add-attestation [{:keys [ticket actor at parents claim]}]
  (mint {:event/ticket ticket :event/type :attestation/add
         :event/actor actor :event/at at :event/parents (set parents)
         :event/body claim}))

(defn chain
  "Convenience for linear histories (tests, scripts): each step is
  (fn [parents] event); parents thread automatically."
  [& steps]
  (reduce (fn [evs step]
            (conj evs (step (if (seq evs) #{(:event/id (peek evs))} #{}))))
          []
          steps))
