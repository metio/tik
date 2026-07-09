---
type: decision
status: accepted
date: 2026-07-09
title: The EventStore contract every backend must honor
supersedes: null
---

# ADR 0020: The EventStore contract

## Decision

Any storage backend (file/git today; SQLite next; anything later) is
valid iff it honors this contract:

- **Append-only**: no operation deletes or mutates an event, ever
  (ADR 0017; blobs are separate and deletable, ADR 0014).
- **Append is idempotent by id**: appending an already-present event id
  is a no-op — union semantics are the store's job to preserve, which
  is what makes replica merge trivial.
- **Events are returned unordered**: the reducer orders by `(at, id)`;
  a store that returns "helpful" ordering invites callers to depend on
  it (ADR 0004: parents are not ordering either).
- **`has-event?` is the reconciliation primitive**: sync between any
  two stores is git-style have/want set reconciliation over event ids;
  a server is just a well-connected replica.
- **The store holds bytes, not interpretations**: no store may index
  its way into authority — any derived index it keeps is disposable
  (ADR 0013). For the file store this is literal (`sha256sum(file) =
  filename = id`, ADR 0007); other backends must preserve the exact
  canonical bytes so the same guarantee is testable
  (`events(id TEXT PRIMARY KEY, bytes BLOB)` — never a parsed-columns
  schema that re-serializes on read).

## Context

The storage seam is where "just this once" optimizations concentrate:
a parsed-column schema that re-serializes (hash fork risk, ADR 0008), a
store that orders by insertion (hidden coupling), a cleanup job that
prunes "obsolete" events (corruption, ADR 0004). Writing the contract
before the second backend exists means SQLite gets built against rules,
not against the file store's incidental behavior.

## Consequences

- A backend is validated by the same corpus and property tests as the
  kernel: store round-trip must preserve bytes exactly.
- Quotas, retention (blobs), and access control live at this seam —
  policy about payloads and actors, never about which events exist.
- Nothing in the kernel knows which backend is underneath; nothing in
  a backend knows what events mean.
