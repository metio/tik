---
type: decision
status: accepted
date: 2026-07-08
title: Stage negation must be stratified
supersedes: null
---

# ADR 0005: Stratified negation over stages

## Decision

A stage may apply `[:not [:stage-reached X]]` only to stages in a
**strictly earlier stratum** of the process graph (stratum = longest
`:after` path depth). `tik lint` enforces this as an error. (A dedicated
`:not-stage` alias for the same guard existed through plan v5 and was
removed in the v6 subtraction — one spelling means the linter polices one
shape.)

## Context

`[:not [:stage-reached X]]` is negation inside a fixpoint — non-monotone.
Without stratification, two stages in the same stratum can both derive in
the same fixpoint sweep against the pre-sweep snapshot (e.g. `:escalated`
guarded by `[:not [:stage-reached :triaged]]` co-deriving with
`:triaged`), producing a state
that is deterministic only by accident of iteration strategy. Datalog
solved this decades ago: evaluate strata in order, negate only what earlier
strata have finished deciding. Adopting the same rule makes determinism
*provable* rather than incidental and connects the guard language to
well-understood theory.

Negation over **facts** (`[:not [:fact …]]`) is unaffected: facts are
inputs to the fixpoint, not derived by it.

## Consequences

- The sample support process was itself in violation and was remodeled:
  "escalated = 48h and not yet triaged" became "48h and no category fact" —
  a fact-level negation, monotone-safe, and arguably the more honest claim.
- Cost: one linter check. The kernel's fixpoint is unchanged.
- Lint currently detects the direct `[:not [:stage-reached X]]` spelling;
  arbitrarily nested negation parity is a known lint TODO, documented here
  so it is a tracked gap rather than a silent one.
- **Stratification is necessary but not sufficient** — model checking
  (`spec/ChaoticFixpoint.tla`) exhibits a linter-clean process (`:d` in a
  later stratum negating `:c`) whose result is order-dependent under
  fire-one-stage-at-a-time iteration: firing `:d` before `:c` has entered
  yields a different fixpoint. Determinism additionally requires the
  evaluator to use **synchronous sweeps** (all enabled stages against the
  sweep-start snapshot, as `tik.stage/reached-set` does) or explicit
  stratum-ordered evaluation. This is a conformance requirement on any
  second implementation, pinned executably by the corpus case
  `sweep-order-negation` and explained by `spec/SweepFixpoint.tla`.
