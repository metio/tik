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

Parents are not metadata — they change what kind of object the log is:
without them, an append-only collection of claims; with them, an
**authenticated history graph** where every claim carries what its author
knew. Parents were originally optional "Phase 1" metadata. Review of the
axes showed that optionality here is ambiguity, not flexibility, and that
mandatory parents simplify or strengthen nearly every other axis
simultaneously:

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
  silent loss (verify ladder L0) — distinct from a missing *blob*, which
  is verifiable absence (L3). "History incomplete" and "artifact absent"
  are different failure classes and verify reports them differently.

**What "observed" means, operationally.** The minting layer must track
known heads; `:event/parents` states "I created this event knowing
exactly these heads." Creating an event while offline or behind is not an
error — a newer head existing elsewhere is precisely the distributed
model, and the resulting structural concurrency is honest. The error is
*pretending no head existed*: an empty parent set on a non-root event is
a lie about what the actor knew, which is why it is rejected at mint
time rather than tolerated as a degenerate case.

## Consequences

- Porcelain must track heads (it needs them for sync anyway); the kernel
  gains `tik.dag`.
- This changes the event schema, which is why it is decided now — before
  any real data exists. Old-style events without parents will never exist.
- Reduction order remains `(at, id)`; parents are for integrity,
  concurrency detection, and sync — deliberately not for ordering, so
  derivation stays a pure function of the event *set*. The two concepts
  answer different questions and must not mix: parents answer *"what did
  this actor know?"*; the reducer answers *"given the complete set of
  evidence, what is derivable?"*. Processing children after parents would
  couple evaluation to causal topology and forfeit commutativity.
- A **causal view** lens falls out for free — which assertions were made
  from which evidence, who worked from an outdated head, where branches
  diverged, which conflicts came from independent replicas. Pure DAG
  analysis, no kernel change (IDEAS).
- With ADRs 0001–0003 this completes the base: truth enters only as
  claims, interpretation is pinned, disagreement is preserved, and claims
  carry their causal history. The log is not merely append-only — it is
  an **argument graph**, where every conclusion points back through the
  evidence that made it derivable.
