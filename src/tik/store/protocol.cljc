;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store.protocol
  "The storage seam. Anything that can store immutable, content-addressed
  event blobs and reconcile sets is a valid backend: EDN files in git
  (Phase 0), SQLite (all-in-one container), PostgreSQL, XTDB.

  Sync between any two stores is git-style have/want set reconciliation over
  event ids — the server is just a well-connected replica.")

(defprotocol EventStore
  (append! [store event]
    "Persist an event. Idempotent: appending an already-present event id is
    a no-op (union semantics).")
  (events [store ticket-id]
    "All events for a ticket, unordered (the reducer orders).")
  (ticket-ids [store]
    "All known ticket ids.")
  (has-event? [store event-id]
    "Membership test — the primitive of have/want reconciliation."))
