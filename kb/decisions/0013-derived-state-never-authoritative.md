---
type: decision
status: accepted
date: 2026-07-09
title: Derived material may be cached only if disposable and untrusted
supersedes: null
---

# ADR 0013: Derived state is never persisted as authoritative

## Decision

Derived material — stages, frontiers, explain output, totals, indexes,
reduction summaries — may be cached, indexed, or materialized **only if
it is disposable and verification never trusts it**. Deleting any cache
must be a no-op for correctness. `verify` re-derives from the log,
always; it never reads a cache, an index, or a checkpoint as input.

Checkpoints (a verified reduction summary pinned to a head) are
**untrusted accelerators**: an implementation may use one to skip work,
but replay always outranks it, and any disagreement is resolved in favor
of replay.

## Context

This is the central law (PLAN §1) given ADR status because
implementation pressure will attack it specifically, with reasonable
requests: "cache stages", "store current status", "index the frontier",
"persist explain output". All are fine as *porcelain* — and each becomes
a system-corrupting bug the moment anything treats the stored copy as
truth, because a stored aggregate can drift from its log and a derived
one cannot (PLAN §13). `database.stage = "approved"` is how workflow
engines are born.

## Consequences

- The question for any persistence PR is mechanical: *if this data were
  deleted right now, would anything be wrong?* If yes, it is
  authoritative state and rejected.
- Performance work targets indexes, caches, and alternative stores
  behind the EventStore seam — never new authoritative state.
- Lenses may serve stale caches for speed (an inbox a few seconds old
  is fine); anything making a *claim* — verify, witness attestations,
  evidence bundles — derives fresh.
