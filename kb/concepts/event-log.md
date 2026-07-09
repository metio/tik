---
type: concept
title: The event log
---

# The event log

A ticket is an append-only set of immutable, content-addressed events
(canonical-EDN SHA-256). Replica merge is set union: replicas converge
without coordination — *merge* is conflict-free by construction, *truth*
is deliberately not. Conflicting claims are never hidden or resolved by
merge; they remain in the log and surface as derived fact states
([decisions/0003](../decisions/0003-conflicts-block.md)). Ticket state
and stage are pure functions of the log; any materialized view is a
rebuildable cache
([decisions/0001](../decisions/0001-event-log-acceptance-test.md),
[decisions/0013](../decisions/0013-derived-state-never-authoritative.md)).

Fact semantics:

- **assert** establishes the current effective value for a fact path
  while preserving every prior claim in history — replacement by a new
  claim, never "later timestamp wins".
- **retract** withdraws a fact from satisfying guards without asserting
  a replacement ("wrong, no replacement").
- **dispute** records a signed rejection with a reason; a disputed fact
  stops satisfying guards until superseded by a corrected assertion.

Stage regression is therefore never a mutation or workflow transition:
when the evidence no longer entails a stage, the stage simply ceases to
be derivable.

The invariant underneath all of it: **the log records claims; derivation
decides which claims currently participate in truth.** Append-only does
not mean every claim stays true; immutable does not mean authoritative;
signed does not mean correct; merged does not mean agreed.
