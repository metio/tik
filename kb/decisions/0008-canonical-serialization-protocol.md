---
type: decision
status: accepted
date: 2026-07-09
title: Canonical serialization is a versioned wire protocol
supersedes: null
---

# ADR 0008: Canonical serialization is a protocol boundary

## Decision

Canonical EDN is a **versioned wire protocol**, not an implementation
detail. A format version defines the *complete* byte representation;
implementations MUST NOT serialize equivalent structures differently.
The permanent answers, per format-version 1:

- **Numbers**: integers only, printed as longs. Floats, ratios, and
  bigdecimals are *rejected at emit* — no numeric normalization exists
  because no ambiguous numerics are admitted.
- **Keywords and symbols**: printed textually (`:ns/name`, `name`);
  namespaces are part of identity forever.
- **UUIDs**: lowercase textual form, tagged (`#uuid "..."`).
- **Timestamps**: UTC, truncated to millisecond precision, tagged
  (`#inst "..."` in `java.time.Instant` ISO-8601 form).
- **Maps**: entries sorted by the canonical encoding of the key (never
  by a host-language print function — `pr-str` was tried and is
  identity-hash-unstable for types without a print-method).
- **Sets**: members sorted by their canonical encoding.
- **Vectors and seqs**: preserve order (`[..]`, `(..)`).
- **Unknown types**: rejected at emit, never "best-effort" printed.
- **Whitespace**: single space between elements; no other whitespace.

Any change to canonical output — however "equivalent" — is a
format-version bump.

## Context

A future developer will be tempted by "this is semantically equivalent,
let's make the serializer nicer." **That is a hash fork**: every event
id and signature in every store is derived from these exact bytes. This
is the most immutable layer after the hash function itself (ADR 0006),
and it is where the one real encoding bug so far lived (the `pr-str`
map-ordering instability, found by property test).

## Consequences

- Golden byte tests pin the encoding; breaking one means the format
  changed, whether or not that was intended.
- The rejection list is a feature: admitting floats "for convenience"
  would smuggle cross-runtime print instability into the hash domain.
- A second implementation targets this ADR plus the corpus, not the
  Clojure serializer's incidental behavior (ADR 0018).
