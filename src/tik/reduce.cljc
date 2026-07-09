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
  :conflicted. Guards consult nothing else about facts. (:conflicted is
  reserved for Phase 1 structural concurrency per ADR 0003/0004; in a
  single linear history it cannot occur.)"
  (:refer-clojure :exclude [reduce])
  (:require [clojure.core :as core]))

(defn dedupe-events
  "Union semantics: duplicates (same content address) collapse to one."
  [events]
  (into [] (comp (map (juxt :event/id identity)) (distinct) (map second))
        (sort-by :event/id events)))

(defn order [events]
  (sort-by (juxt :event/at :event/id) events))

(defn ordered
  "Dedupe, then order — the reducer's canonical input."
  [events]
  (order (dedupe-events events)))

(def empty-state
  {:facts {} :artifacts {} :log []})

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

(defn- h-assert [state {:keys [event/body event/at event/actor event/id]}]
  (let [path (:fact/path body)]
    (update-in state [:facts path]
               push-history
               {:value (:fact/value body) :asserted-by actor :at at :event id})))

(defn- h-retract [state {:keys [event/body event/at event/actor event/id]}]
  (let [path (:fact/path body)]
    (update-in state [:facts path]
               push-history
               {:retracted {:by actor :at at :event id
                            :reason (:retract/reason body)}})))

(defn- h-dispute [state {:keys [event/body event/at event/actor event/id]}]
  (update-in state [:facts (:fact/path body)] assoc
             :disputed {:by actor :at at :event id
                        :reason (:dispute/reason body)}))

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

(defn apply-event
  "Apply one event (handler if known, identity otherwise) and log it."
  [state event]
  (let [h (get handlers (:event/type event) (fn [s _] s))]
    (update (h state event) :log conj event)))

(defn ticket-state [events]
  (core/reduce apply-event empty-state (ordered events)))

(defn fact-entry [state path] (get-in state [:facts path]))

(defn fact-status
  "THE choke point. Returns
  {:status :present|:absent|:retracted|:disputed|:conflicted, ...}
  with :by/:at/:note/:value as applicable. Guards consult only this."
  [state path]
  (let [{:keys [disputed retracted conflicted] :as entry} (fact-entry state path)]
    (cond
      (nil? entry)
      {:status :absent :path path}

      disputed
      {:status :disputed :path path
       :by (:by disputed) :at (:at disputed) :note (:reason disputed)}

      conflicted   ; Phase 1: causally concurrent competing asserts (ADR 0003)
      {:status :conflicted :path path :claims conflicted}

      retracted
      {:status :retracted :path path
       :by (:by retracted) :at (:at retracted) :note (:reason retracted)}

      (contains? entry :value)
      {:status :present :path path
       :value (:value entry) :by (:asserted-by entry) :at (:at entry)}

      :else {:status :absent :path path})))

(defn fact-value
  "Effective value: non-nil only when :status is :present."
  [state path]
  (let [{:keys [status value]} (fact-status state path)]
    (when (= :present status) value)))
