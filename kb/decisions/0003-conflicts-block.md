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

Most replicated systems ask "how do we make replicas converge?"; the
prior question is "what does convergence mean when humans disagree?"
Last-write-wins is deterministic but destroys information: an engineer
asserting `severity=low` concurrently with a manager asserting
`severity=critical` is a *disagreement*, and `severity = conflicted` is
the actual state of the evidence — the disagreement itself is
information. Silently picking a winner by timestamp hides exactly the
thing the process should surface, and every resolution shortcut fails
the same way: latest-wins makes clocks into authority, role-priority
embeds organizational judgment in the truth engine ("why did tik choose
the manager's claim?" — "because the kernel says so" is the
accountability hole), and per-process resolvers grow into a second
governance language hidden in configuration. The causal DAG is what
makes the distinction computable: a later assertion that *observed* the
earlier one (an ancestor) is a correction — history, not conflict;
only causally concurrent claims disagree.

## Consequences

- Dispute, retraction, and conflict are now symmetric: three reasons a fact
  stops satisfying guards, all visible in `explain`, all resolved by new
  events. With absence, that completes the fact lifecycle — retracted
  (withdrawn), disputed (challenged), conflicted (independent claims
  disagree), absent (never established) — and lets guards ask exactly one
  question with no per-scenario special cases: *is this fact currently
  trustworthy enough to derive from?* (fact-status, the choke point).
- Conflict volume is a health metric, not a merge problem: chronic
  conflicts indicate overly broad fact paths, unclear ownership, or a
  noisy integration (PLAN §5, §18). A "conflict topology" lens — which
  paths conflict most, which actors disagree, which integration is the
  source — fits the governance-observability family (IDEAS).
- Built on `:event/parents` (ADR 0004): the causally-maximal writes on a
  path conflict when they disagree. Concurrent agreement (same value from
  independent replicas) is corroboration, not conflict. Resolution is any
  write that observed all competitors — a superseding assert or a retract,
  either way a judgment on the record. Detection is computed from the
  complete log, never an incremental frontier: a backdated intermediate
  event would make the incremental version order-dependent, and
  commutativity is a law. Pinned by the corpus case `concurrent-conflict`
  and by `tik.conflict-test` (including the backdating counterexample).
- Escape hatches (per-fact `:on-conflict`) are deferred until dogfooding
  demonstrates a real need; adding one later is compatible, removing one is
  not. (The honest operational valve already exists without kernel
  support: a policy bot authorized to sign superseding facts under
  declared rules is an accountable actor, not protocol semantics —
  PLAN §5.)
- The job, in one line: tik does not eliminate conflict — it **makes
  important conflicts impossible to hide.**
