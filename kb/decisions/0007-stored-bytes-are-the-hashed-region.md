---
type: decision
status: accepted
date: 2026-07-08
title: Stored bytes are exactly the hashed region; signatures are detached
supersedes: null
---

# ADR 0007: Stored bytes = hashed region; detached signatures

## Decision

An event file contains exactly the canonical bytes of the hashed region —
the event map WITHOUT `:event/id` (the filename is the id; storing it
inside would be redundant and, decisively, breaks `sha256sum(file) =
filename`). Signatures never live inside the event either:
`:event/key`/`:event/sig` are removed from the schema entirely; signatures
are detached sidecar files (`<event-id>.sig.<key-fingerprint>`) verifying
against the exact stored bytes, allowing multiple signatures per event
without touching the hashed region.

## Context

Found by the discipline this ADR family exists to protect: internal
`verify` passed while an independent `sha256sum` check failed, because the
file embedded the very id that its hash was supposed to equal. The claim
"an auditor can check store integrity with coreutils" was almost true —
and "almost verifiable" is a bug class of its own.

## Consequences

- `sha256sum` over any event file yields its filename. Verify L0 is
  literally coreutils.
- Clean separation of concerns: the .edn file is the *claim*; .sig files
  are *endorsements of the claim*. Witness countersignatures remain events
  (they carry semantics); actor signatures are sidecars (they carry only
  authenticity).
- Readers attach `:event/id` from the filename; `verify` recomputes it from
  the bytes.
- The Event schema shrinks. Simplification found by verification — the
  best kind.
