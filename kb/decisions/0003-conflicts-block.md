---
type: decision
status: accepted
date: 2026-07-08
title: Concurrent conflicting assertions block guards; humans resolve
supersedes: null
---

# ADR 0003: Conflicts are facts about disagreement

## Decision

When causally concurrent assertions (neither an ancestor of the other via
`:event/parents`) target the same fact path with different values, the fact
becomes **conflicted**. A conflicted fact — like a disputed one — does not
satisfy guards. `explain` surfaces both claims, both actors, and asks for a
superseding assertion. Resolution is a new signed event: a human judgment on
the record.

tik ships **no conflict-resolution policy language**. No latest-wins, no
role-priority, no per-process resolution rules.

## Context

Last-write-wins is deterministic but destroys information: an engineer
asserting `severity=low` concurrently with a manager asserting
`severity=critical` is a *disagreement*, and silently picking a winner by
timestamp hides exactly the thing the process should surface. Per-process
resolution policies were considered and rejected: they grow into a second
language and move judgment out of the log.

## Consequences

- Dispute, retraction, and conflict are now symmetric: three reasons a fact
  stops satisfying guards, all visible in `explain`, all resolved by new
  events.
- Requires `:event/parents` (Phase 1). Until then, single-replica ordering
  by `(at, id)` cannot produce concurrency, so nothing is silently wrong in
  Phase 0.
- Escape hatches (per-fact `:on-conflict`) are deferred until dogfooding
  demonstrates a real need; adding one later is compatible, removing one is
  not.
