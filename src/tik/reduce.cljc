;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.reduce
  "The reducer: an append-only event SET -> derived ticket state.

  Three laws, all property-tested:
  - TOTAL over well-formed events (a replicated system cannot retroactively
    reject an event that exists on three replicas; unknown types are logged
    and otherwise ignored)
  - COMMUTATIVE: ordered internally by (at, id), so any permutation of the
    same set reduces identically
  - IDEMPOTENT under duplication: events are deduplicated by id before the
    fold, so 'merge is set union' is literal, not aspirational.

  The handler map is CLOSED and versioned alongside the guard vocabulary:
  the semantics of a verifiable kernel must be enumerable, not extensible
  by mutation at a distance.

  fact-status is the single choke point for 'why does this fact (not)
  satisfy guards': :present | :absent | :retracted | :disputed |
  :conflicted. A dispute rejects the value that stood when it was
  raised, so only a DIFFERENT value (or a retraction, or the disputer
  withdrawing) answers it — see `surviving-disputes`. Guards consult
  nothing else about facts. :conflicted is
  structural (ADR 0003/0004): causally concurrent writes that disagree,
  computed from the parents DAG — in a single linear history it cannot
  occur."
  (:refer-clojure :exclude [reduce])
  (:require [clojure.core :as core]))

(defn dedupe-events
  "Union semantics: duplicates (same content address) collapse to one.
  Retention order is irrelevant — the caller re-sorts by (at, id) via
  `order`, and same-id events carry the same bytes.

  Keyed on the id ALONE, not on the (id, event) pair: an id is a claim
  about the whole hashed region, so two maps sharing one must be one
  event, and a store that offers two is malformed rather than holding
  two facts. Deduping the pair would keep both and make 'merge is set
  union' hold only for stores that were already well-formed."
  [events]
  (first
   (core/reduce (fn [[acc seen] e]
                  (let [id (:event/id e)]
                    (if (contains? seen id)
                      [acc seen]
                      [(conj acc e) (conj seen id)])))
                [[] #{}]
                events)))

(defn order [events]
  (sort-by (juxt :event/at :event/id) events))

(defn- well-formed!
  "The fold's entry contract: whatever claims to be an event must carry
  the fields ordering and folding depend on. A hostile or corrupted
  store fails WELL here — one data-carrying rejection naming the
  offender — instead of a type error deep inside a comparator."
  [events]
  (doseq [e events]
    (when-not (and (map? e)
                   (string? (:event/id e))
                   (inst? (:event/at e))
                   (keyword? (:event/type e)))
      (throw (ex-info "malformed event in the log"
                      {:reason :event/malformed :event e}))))
  events)

(defn ordered
  "Dedupe, then order — the reducer's canonical input."
  [events]
  (order (dedupe-events (well-formed! events))))

(def empty-state
  {:facts {} :artifacts {} :log [] :writes {} :parents {} :linear-writes {}})

(declare ancestor?)

(defn- push-history [prev entry]
  (assoc entry :history
         (if prev (conj (:history prev []) (dissoc prev :history)) [])))

(defn- h-create [state {:keys [event/body event/at event/actor]}]
  (assoc state
         :title (:ticket/title body)
         :process (:ticket/process body)
         :process-version (:ticket/process-version body)   ; pinned (ADR 0002)
         :process-hash (:ticket/process-hash body)         ; hash-pinned (ADR 0006)
         :created-at at
         :created-by actor))

(defn- h-migrate [state {:keys [event/body event/at event/actor]}]
  (-> state
      (assoc :process-version (:process/version body))
      (cond-> (:process/hash body) (assoc :process-hash (:process/hash body)))
      (update :migrations (fnil conj [])
              {:to (:process/version body) :hash (:process/hash body)
               :by actor :at at :reason (:migrate/reason body)})))

(defn- surviving-disputes
  "Disputes a new assertion of `value` does NOT answer.

  A dispute is rejection of a specific claim, and PLAN §3 says it holds
  until the fact is 'superseded by a corrected value'. Corrected means
  different: an assertion clears the disputes that rejected some other
  value, and leaves standing any dispute that rejected exactly the value
  now being claimed again. So the disputed party cannot clear a dispute
  by re-typing the same fact — the only ways out are a genuinely
  different value, a retraction, or the disputer withdrawing.

  A dispute raised while the path held no value (:rejected absent)
  rejects nothing in particular, so the first assertion answers it —
  which is also what keeps a dispute on a never-asserted path from
  blocking the path forever."
  [prev value]
  (filterv #(and (contains? % :rejected) (= value (:rejected %)))
           (:disputes prev [])))

(defn- h-assert [state {:keys [event/body event/at event/actor event/id]}]
  (let [path (:fact/path body)
        value (:fact/value body)
        live (surviving-disputes (get-in state [:facts path]) value)]
    (update-in state [:facts path]
               push-history
               (cond-> {:value value :asserted-by actor :at at :event id}
                 (seq live) (assoc :disputes live)))))

(defn- h-retract
  "Retraction withdraws the claim with no replacement, so every dispute
  of that claim has lost its subject and does not carry over."
  [state {:keys [event/body event/at event/actor event/id]}]
  (let [path (:fact/path body)]
    (update-in state [:facts path]
               push-history
               {:retracted {:by actor :at at :event id
                            :reason (:retract/reason body)}})))

(defn- h-dispute
  "Raise a dispute, or withdraw this actor's own. Disputes accumulate:
  several people may reject the same claim, and one of them withdrawing
  says nothing about the others."
  [state {:keys [event/body event/at event/actor event/id]}]
  (let [path (:fact/path body)
        entry (get-in state [:facts path])]
    (cond
      (:dispute/withdraw? body)
      (if entry
        (assoc-in state [:facts path :disputes]
                  (filterv #(not= actor (:by %)) (:disputes entry [])))
        state)

      :else
      (update-in state [:facts path :disputes] (fnil conj [])
                 (cond-> {:by actor :at at :event id
                          :reason (:dispute/reason body)}
                   (contains? entry :value) (assoc :rejected (:value entry)))))))

(defn- h-artifact [state {:keys [event/body event/at event/actor]}]
  (assoc-in state [:artifacts (:artifact/path body)]
            {:hash (:artifact/hash body) :attached-by actor :at at}))

(def handlers
  "Closed, versioned with the vocabulary. Attestations (including :work
  claims) are read by lenses, not by ticket-state — hence absent here on
  purpose, not by omission. Comments are artifact attaches of text blobs;
  links are facts under a [:link ...] path — neither needs a handler of
  its own."
  {:ticket/create   h-create
   :process/migrate h-migrate
   :fact/assert     h-assert
   :fact/retract    h-retract
   :fact/dispute    h-dispute
   :artifact/attach h-artifact})

(defn- index-event
  "The fold's two INDEXES, maintained incrementally.

  Both answer questions conflict detection used to ask by scanning the
  whole log on every call — once per fact per stage per sweep per event,
  which is what made derivation quadratic. Neither is new authoritative
  state: both are rebuilt from the event set on every fold and hold
  nothing that is not already in :log. They are the indexes CLAUDE.md
  permits, not a cache of a derived answer.

  :writes  path -> the claims about it, in fold order (which is
           (at, id) order, since the fold consumes `ordered` events)
  :parents event-id -> its parent ids, or nil when :event/parents is not
           a collection — a corrupt store must leave the DAG walk total
           rather than raw-throwing, exactly as tik.dag/parent-ids does.
  :linear-writes
           path -> whether every write on it so far observed the one
           before, i.e. the history on this path is a chain and no
           conflict is possible. Each new write costs ONE ancestry walk
           here instead of a walk per write on every conflict query.
           Sound but not complete: a write folded before its own parent
           (a backdated claim) reads as non-linear and simply falls
           through to the full maximality check, which is correct and
           merely slower. A `true` is never wrong — ancestor? answers
           true only when it finds the ancestor."
  [state {:keys [event/id event/type event/at event/actor event/parents
                 event/body]}]
  (let [state (assoc-in state [:parents id] (when (coll? parents) parents))
        write (case type
                :fact/assert {:kind :assert :value (:fact/value body)
                              :by actor :at at :event id}
                :fact/retract {:kind :retract :by actor :at at :event id}
                nil)]
    (if-not write
      state
      (let [path (:fact/path body)
            prior (peek (get-in state [:writes path]))]
        (-> state
            (update-in [:writes path] (fnil conj []) write)
            (assoc-in [:linear-writes path]
                      (and (get-in state [:linear-writes path] true)
                           (or (nil? prior)
                               (ancestor? (:parents state)
                                          (:event prior) id)))))))))

(defn apply-event
  "Apply one event (handler if known, identity otherwise), log it, index it."
  [state event]
  (let [h (get handlers (:event/type event) (fn [s _] s))]
    (-> (h state event)
        (update :log conj event)
        (index-event event))))

(defn ticket-state [events]
  (core/reduce apply-event empty-state (ordered events)))

(defn fact-entry [state path] (get-in state [:facts path]))

;; ---------------------------------------------------------------- conflicts
;; ADR 0003/0004: causally concurrent writes (neither an ancestor of the
;; other via :event/parents) that disagree about a path make the fact
;; :conflicted until a write that OBSERVED all competitors supersedes
;; them. Parents answer only "did these writes see each other?" — the
;; effective value of a non-conflicted fact stays with (at, id) order.
;; THE ANSWER is computed from the complete write set, never carried
;; forward: an incrementally maintained frontier is order-dependent when
;; a backdated intermediate event folds late, and commutativity is a law.
;;
;; What the fold does carry (:linear-writes) is not that answer but a
;; one-way shortcut to it — "these writes form a chain, so exactly one
;; is maximal". It is sound in one direction only: `true` is a proof (an
;; ancestry walk found the link) and lets the query skip the maximality
;; check; `false` proves nothing and falls through to the full
;; computation. A late backdated event can therefore cost the shortcut,
;; never the verdict — which is exactly why this may be incremental
;; where a frontier may not.

(defn- ancestor?
  "Is event id `a` an ancestor of event id `b`, per the parents index?"
  [index a b]
  (loop [frontier (get index b) seen #{}]
    (cond
      (contains? frontier a) true
      (empty? frontier) false
      :else (let [seen (into seen frontier)]
              (recur (into #{} (comp (mapcat index) (remove seen))
                           frontier)
                     seen)))))

(defn- path-writes
  "The claims about a path's state: asserts and retracts, in fold order.
  Disputes are meta (challenges of a claim) and precede conflicts in
  fact-status. Read from the fold's :writes index rather than by
  scanning the log, so the cost is the number of writes ON THIS PATH,
  never the length of the whole history."
  [state path]
  (get-in state [:writes path] []))

(defn conflicting-claims
  "The causally-maximal writes on `path` when they disagree, else nil.
  Concurrent writes that agree (same value, or both retracts) are not a
  conflict — there is no disagreement to surface.

  Fast path for the overwhelmingly common shape: when every write
  observed the one before, exactly one write is maximal and no conflict
  can exist. The fold answers that as the writes arrive, one walk each
  (:linear-writes), so the common case costs a map lookup rather than
  re-deriving linearity per query — and the pairwise maximality check
  below, quadratic in the writes on ONE path, runs only for histories
  that actually forked."
  [state path]
  (let [writes (path-writes state path)]
    (when (and (< 1 (count writes))
               ;; the fold already proved this path linear one walk at a
               ;; time; a `true` here means exactly one maximal write
               (not (get-in state [:linear-writes path] false)))
      ;; the parents index comes from the fold (see index-event), so it
      ;; is built once per history instead of rebuilt per call. It holds
      ;; a parents value only when it is a collection, mirroring
      ;; tik.dag/parent-ids: a corrupt/hostile store whose :event/parents
      ;; is a scalar folds cleanly (well-formed! does not check the shape),
      ;; and ancestor?'s contains?/mapcat must stay total over it rather
      ;; than raw-throwing — this walk carries the same guarantee the DAG
      ;; walk does. verify L0's schema check names the malformation.
      (let [index (:parents state)
            maximal (filterv (fn [w]
                               (not-any? #(and (not= (:event %) (:event w))
                                               (ancestor? index (:event w)
                                                          (:event %)))
                                         writes))
                             writes)
            outcomes (distinct (map #(if (= :assert (:kind %))
                                       [:value (:value %)]
                                       [:retracted])
                                    maximal))]
        (when (and (< 1 (count maximal)) (< 1 (count outcomes)))
          maximal)))))

(defn fact-status
  "THE choke point. Returns
  {:status :present|:absent|:retracted|:disputed|:conflicted, ...}
  with :by/:at/:note/:value as applicable. Guards consult only this."
  [state path]
  (let [{:keys [disputes retracted] :as entry} (fact-entry state path)
        ;; the earliest live dispute is the one reported: it is the one
        ;; that first made the claim unusable, and later ones do not
        ;; change the answer, only who else agrees.
        disputed (first disputes)]
    (cond
      (nil? entry)
      {:status :absent :path path}

      disputed
      {:status :disputed :path path
       :by (:by disputed) :at (:at disputed) :note (:reason disputed)
       :disputes disputes}

      :else
      (if-let [claims (conflicting-claims state path)]
        {:status :conflicted :path path :claims claims}
        (cond

          retracted
          {:status :retracted :path path
           :by (:by retracted) :at (:at retracted) :note (:reason retracted)}

          (contains? entry :value)
          {:status :present :path path
           :value (:value entry) :by (:asserted-by entry) :at (:at entry)}

          :else {:status :absent :path path})))))

(defn fact-value
  "Effective value: non-nil only when :status is :present."
  [state path]
  (let [{:keys [status value]} (fact-status state path)]
    (when (= :present status) value)))
