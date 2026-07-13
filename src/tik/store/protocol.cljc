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
    "Membership test — the primitive of have/want reconciliation.")
  (event-bytes [store ticket-id event-id]
    "The EXACT stored bytes of one event (the hashed region), for
    signature verification, or nil if absent. Bytes, not a parsed event —
    signatures endorse bytes.")
  (put-sidecar! [store ticket-id name bytes]
    "Persist a detached sidecar (an ssh-keygen signature or witness) under
    `name` for `ticket-id`. Idempotent by name. Sidecars are endorsements
    OF stored bytes, never events themselves (ADR 0007) — where they live
    is the backend's business: files beside the event, or a table row.")
  (sidecar-names [store ticket-id]
    "All sidecar names stored for `ticket-id` (callers filter by the
    `<id>.sig.`/`<head>.witness.` prefix they care about).")
  (read-sidecar [store ticket-id name]
    "The bytes of one sidecar, or nil if absent."))
