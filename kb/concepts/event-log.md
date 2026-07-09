---
type: concept
title: The event log
---

# The event log

A ticket is an append-only set of immutable, content-addressed events
(canonical-EDN SHA-256). Replica merge is set union — conflict-free by
construction. Ticket state and stage are pure functions of the log; any
materialized view is a rebuildable cache. See
[decisions/0001](../decisions/0001-event-log-acceptance-test.md).

Fact semantics: **assert** supersedes (history kept), **retract** removes
("wrong, no replacement"), **dispute** rejects with a reason — a disputed
fact stops satisfying guards, so the stage regresses by derivation.
