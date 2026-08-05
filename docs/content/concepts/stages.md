---
title: Stages
description: Position in a process as a fixpoint over the reached set — synchronous sweeps, sticky milestones, and regression by derivation.
tags: [stages, fixpoint, sticky, derivation]
---

A process defines stages. Each stage names its prerequisites (`:after`) and
the conditions that must hold to reach it (`:guards`). Reaching a stage is
never an action somebody takes; it is a conclusion.

```clojure
{:stage/id :resolved
 :after [:triaged]
 :guards [[:fact [:resolution :ref]]
          [:or [:not [:fact= [:category] :technical]]
           [:stage-reached :reproducible]]]}
```

That second guard reads "technical implies reproduced" — material
implication written out, because the vocabulary has `:or` and `:not` and
needs no separate conditional.

## The reached set is a fixpoint

Derivation computes the set of reached stages by iterating to closure:
every stage whose prerequisites are reached and whose guards hold joins the
set, which may in turn enable further stages, until nothing changes.

Each iteration is a **synchronous sweep**: every stage is evaluated against
the snapshot taken at the start of the sweep, and all newly enabled stages
are added at once. Firing one stage at a time gives order-dependent answers
even on definitions the linter accepts, because a later stratum negating an
earlier one can jump the queue. The synchronous sweep is normative — a TLA+
model exhibits the counterexample, and a conformance corpus case pins the
correct answer.

Because `[:not [:stage-reached …]]` is negation inside a fixpoint, the
linter enforces **stratified negation**: a definition may only negate
stages in a strictly earlier stratum. That is what makes determinism
provable rather than incidental.

## Regression is by derivation

Nothing rolls a ticket back. Withdraw the evidence and the conclusion stops
following:

```console
$ tik retract 3184 resolution.ref --reason "wrong commit"
$ tik status 3184
stage:   triaged (reached: received, triaged)
```

The same holds for a dispute, and for a fact that a schema no longer
accepts. The board cannot show a stage the evidence does not support,
because there is no stored stage to go stale.

## Sticky milestones

Some stages are milestones: reaching them once is a historical fact that
later evidence does not undo. `:stage/sticky? true` says so, and the fold
carries such a stage forward once any prefix of the log reached it.

```clojure
{:stage/id :closed :after [:resolved] :stage/sticky? true
 :guards [[:fact [:customer :ack]]]}
```

A customer who withdraws their acknowledgement does not un-close the
ticket. The log shows both truths — it was closed, and the ack was
withdrawn — and the derived present misrepresents neither.

Sticky is monotone **in fold position**, not in the event set. The reached
set is a function of the whole trajectory, so an event that arrives by
merge carrying an earlier timestamp splices a new prefix into that
trajectory and every later prefix is re-derived. A replica can therefore
hold a sticky reach, sync, and no longer derive it. Convergence is
unaffected — the same event set always derives the same answer — but a
reach observed before a sync is not promised to survive it, which is why an
effect pipeline records that it fired as its own fact.

## Time

Time enters derivation as an explicit `now` argument. The kernel reads no
clock of its own, which is what makes a re-derivation years from now
reproducible.

Guards read the claimed clock — the `:at` an actor asserted — by default,
and each fold step evaluates at its event's `:at` **clamped to the read's
`now`**. No step acts as though more time has passed than actually has, so
a postdated event cannot buy the 48 hours an `:elapsed-since` guard is
waiting for. The clamp lifts by itself once that time really passes.

## Several stages at once

A process is a graph, not a line, so a ticket can sit at several current
stages at the same time — two branch tips, both maximal. `tik status`
reports the reached set and the current tips; `tik debug` shows every
sweep and every guard verdict that produced them.
