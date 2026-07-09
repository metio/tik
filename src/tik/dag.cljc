;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.dag
  "The event DAG (ADR 0004). Parents are inside the signed, content-addressed
  region, so a head id commits to its entire ancestry — the basis for cheap
  sync (compare heads, walk ancestry), single-signature witnessing, and
  reproducible federation attestations pinned to a head.")

(defn heads
  "Event ids not referenced as a parent by any other event — the tips."
  [events]
  (let [referenced (into #{} (mapcat :event/parents) events)]
    (into #{} (comp (map :event/id) (remove referenced)) events)))

(defn missing-parents
  "Parent ids referenced but not present. Non-empty means the store is
  incomplete or corrupted — detectable, never silent (verify L0)."
  [events]
  (let [ids (into #{} (map :event/id) events)]
    (into #{} (comp (mapcat :event/parents) (remove ids)) events)))

(defn roots
  "Events with no parents. A well-formed ticket has exactly one:
  its :ticket/create."
  [events]
  (filterv (comp empty? :event/parents) events))
