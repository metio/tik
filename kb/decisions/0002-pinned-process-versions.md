---
type: decision
status: accepted
date: 2026-07-08
title: Tickets pin their process definition hash; migration is an event
supersedes: null
---

# ADR 0002: Pinned process versions, explicit migration

## Decision

Tickets **pin the process definition hash** in effect at creation — the
hash is the identity (ADR 0006); the human-readable version number is a
label carried as metadata. Re-evaluation under a newer definition happens
only via an explicit, signed `:process/migrate` event carrying the new
hash. `tik migrate --dry-run` shows the derived-stage diff before anyone
commits to it — a migration is a consequence-bearing decision ("under the
new rules, security-review is now missing"), not a version-number edit.

## Context

The derivation function is part of the evidence context. The conclusion
is not `events → stage` but `(events + process-definition +
evaluation-time) → stage`: silently changing the definition changes the
meaning of the historical record. The choice here is **reproducibility
over freshness** — a live workflow product asks "what is the correct
state under today's rules?"; an evidence system asks "what conclusion did
*these* rules produce from *these* facts?" — and the auditor, regulator,
and customer dispute all need the second question answered.

The original design floated tickets to the latest version by default
("stage is derived, so migration is free re-evaluation"). External review
correctly identified this as an audit integrity failure: a ticket that was
`:resolved` yesterday silently ceasing to be resolved because a definition
changed violates least surprise and poisons any compliance narrative built
on `verify`.

The inversion is cheap because migration passes ADR 0001: it is just
another event — with an actor, a timestamp, parents, signatures, the new
definition hash, and a derivable consequence. Mutable
`ticket.process_version` metadata would instead raise "who changed it,
when, was it authorized, what did it mean before?" as special questions;
as an event they are ordinary log questions with no special trust path.

## Consequences

- `verify` evaluates each ticket under its pinned version: reproducible
  audits.
- **Grandfathering loophole**: pinning lets open tickets close under old,
  laxer rules after a security-motivated process fix. Mitigation: migration
  sweeps are first-class porcelain, and processes can declare
  `:process/migration-policy` (e.g. required-within a duration of a version
  bump) enforced by lint/CI and the `next` inbox.
- Floating remains available as an explicit per-process opt-in for pre-1.0
  process development, never as the default. The kernel never secretly
  overrides pinning — that would recreate the audit problem the pin
  exists to solve; overdue migrations are policy, surfaced by lint/CI and
  `next`.
- The boundary in one line: **facts can accumulate and interpretations
  can evolve, but old interpretations must remain reproducible** — that
  is what makes a changing process compatible with an immutable log.
