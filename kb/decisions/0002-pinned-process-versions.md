---
type: decision
status: accepted
date: 2026-07-08
title: Tickets pin their process version; migration is an event
supersedes: null
---

# ADR 0002: Pinned process versions, explicit migration

## Decision

Tickets **pin** the process version in effect at creation. Re-evaluation
under a newer version happens only via an explicit, signed
`:process/migrate` event. `tik migrate --dry-run` shows the derived-stage
diff before anyone commits to it.

## Context

The original design floated tickets to the latest version by default
("stage is derived, so migration is free re-evaluation"). External review
correctly identified this as an audit integrity failure: a ticket that was
`:resolved` yesterday silently ceasing to be resolved because a definition
changed violates least surprise and poisons any compliance narrative built
on `verify`.

The inversion is cheap because migration passes ADR 0001: it is just another
event — attributable, timestamped, witnessable, and visible in the log.

## Consequences

- `verify` evaluates each ticket under its pinned version: reproducible
  audits.
- **Grandfathering loophole**: pinning lets open tickets close under old,
  laxer rules after a security-motivated process fix. Mitigation: migration
  sweeps are first-class porcelain, and processes can declare
  `:process/migration-policy` (e.g. required-within a duration of a version
  bump) enforced by lint/CI and the `next` inbox.
- Floating remains available as an explicit per-process opt-in for pre-1.0
  process development, never as the default.
