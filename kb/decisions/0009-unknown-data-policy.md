---
type: decision
status: accepted
date: 2026-07-09
title: Unknown data handling differs by layer, on purpose
supersedes: null
---

# ADR 0009: Unknown data policy

## Decision

Three layers, three different answers — because the cost of being wrong
differs:

| Layer              | Unknown data                                        |
| ------------------ | --------------------------------------------------- |
| Event types        | **preserved, hashed, ignored** by the reducer       |
| Guard operators    | **rejected**: lint error at authoring, throw at eval |
| Definition fields  | **carried and hashed, never interpreted**           |

## Context

- **Events must tolerate the future**: a replicated store cannot
  retroactively reject an event that already exists on three replicas,
  so the reducer is total — unknown types stay in the log (and in the
  hash domain) and simply do not contribute to ticket state. Ignoring
  is safe because an event the reducer skips cannot silently change
  truth.
- **Guards must not tolerate the unknown**: a guard the evaluator does
  not understand *would* change truth if guessed at. There is no safe
  default for "some condition I cannot evaluate" — neither
  satisfied-by-default nor failed-by-default is honest. So the guard
  vocabulary is closed (ADR 0001, PLAN §5): unknown operators are lint
  errors, and evaluation throws rather than improvising.
- **Definitions may carry annotations** (`:hint`, `:purpose`, lint
  config): they are part of the pinned bytes — two definitions
  differing only in annotations are different definitions, honestly —
  but the kernel never reads them; only lenses do.

## Consequences

- Forward compatibility is asymmetric by design: new *evidence* flows
  through old kernels harmlessly; new *semantics* (guards) require a
  version bump everywhere.
- `tik lint`'s closed-basis check is the enforcement point for the
  middle row; the reducer-totality property test enforces the top row.
- Nobody gets to add meaning by sneaking a field into a definition:
  if the kernel doesn't interpret it, it is annotation, whatever it is
  named.
