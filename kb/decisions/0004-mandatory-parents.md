---
type: decision
status: accepted
date: 2026-07-08
title: Event parents are mandatory; the log is a Merkle DAG
supersedes: null
---

# ADR 0004: Mandatory parents — the log is a Merkle DAG

## Decision

Every event carries `:event/parents`: the set of head event ids the actor
observed when minting. `:ticket/create` is the unique root with `#{}`; for
every other event type an empty parent set is a mint-time error. Since
parents are inside the content-addressed, signed region, the log is a
Merkle DAG: one head hash commits to the entire history.

## Context

Parents were optional "Phase 1" metadata. Review of the axes showed that
optionality here is ambiguity, not flexibility, and that mandatory parents
simplify or strengthen nearly every other axis simultaneously:

- **Sync** becomes head comparison + ancestry walking instead of full id
  enumeration.
- **Concurrency** becomes structural (two events, neither an ancestor of
  the other) instead of heuristic — the precondition for ADR 0003's
  conflict semantics.
- **Witnessing** collapses in cost: one countersignature over a head
  timestamps every ancestor event at once.
- **Federation attestations** can pin claims to a head hash, making them
  *reproducible* (hand over the log, re-derive) rather than trust-me.
- **Store integrity**: a missing ancestor is detectable corruption, not
  silent loss (verify ladder L0).

## Consequences

- Porcelain must track heads (it needs them for sync anyway); the kernel
  gains `tik.dag`.
- This changes the event schema, which is why it is decided now — before
  any real data exists. Old-style events without parents will never exist.
- Reduction order remains `(at, id)`; parents are for integrity,
  concurrency detection, and sync — deliberately not for ordering, so
  derivation stays a pure function of the event *set*.
