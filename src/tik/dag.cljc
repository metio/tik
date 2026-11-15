;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.dag
  "The event DAG (ADR 0004). Parents are inside the signed, content-addressed
  region, so a head id commits to its entire ancestry — the basis for cheap
  sync (compare heads, walk ancestry), single-signature witnessing, and
  reproducible federation attestations pinned to a head.

  Every walk here is total over whatever a store holds: an element that
  is not an event map contributes no id and no parents, and malformed
  parents read as none (tik.shape/parent-ids). verify L0's schema check
  is what names the malformation; these walks only must not raise on it."
  (:require [tik.shape :as shape]))

(def ^:private parent-ids shape/parent-ids)

(defn- event-id [e] (shape/field e :event/id))

(defn heads
  "Event ids not referenced as a parent by any other event — the tips."
  [events]
  (let [events (shape/plain-coll :events events)
        referenced (into #{} (mapcat parent-ids) events)]
    (into #{} (comp (map event-id) (remove referenced)) events)))

(defn missing-parents
  "Parent ids referenced but not present. Non-empty means the store is
  incomplete or corrupted — detectable, never silent (verify L0)."
  [events]
  (let [events (shape/plain-coll :events events)
        ids (into #{} (map event-id) events)]
    (into #{} (comp (mapcat parent-ids) (remove ids)) events)))

(defn roots
  "Events with no parents. A well-formed ticket has exactly one:
  its :ticket/create."
  [events]
  (filterv (comp empty? parent-ids) (shape/plain-coll :events events)))
