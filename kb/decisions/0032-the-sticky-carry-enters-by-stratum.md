---
type: decision
status: accepted
date: 2026-11-22
title: Guards read the sticky carry from the first sweep, and :after admits a carried stage only in its own stratum
supersedes: null
---

# ADR 0032: The sticky carry enters by stratum

## Decision

**The fixpoint starts from the empty set on every read. Guards see the
sticky carry as reached from the first sweep, and :after admits a carried
stage of depth d only from sweep d, the sweep it would enter in had it
derived again.** Depth is the stratum ADR 0005 judges negation by: 0 for a
stage without :after, else one more than its deepest prerequisite.

1. **Guards read the carry as history.** A sticky stage that was reached is
   reached for good, so `[:not [:stage-reached s]]` over a carried stage is
   false from the first sweep and `[:stage-reached s]` true.
2. **:after reads the carry by stratum.** A stage whose prerequisite is
   carried waits for the prerequisite's stratum, like any other stage, so
   every stratum earlier than its own has entered the snapshot before it is
   judged.
3. **Both kernels state it.** `tik.stage/reached-set` and the reference
   kernel's `reached-fixpoint` implement the rule independently; the
   differential tests compare them.

## Context

`reached-set` used to seed the carry into the first snapshot. A stage whose
:after named a carried stage then fired in the first sweep, before an
earlier stratum had entered, so a stage negating that stratum fired beside
its target and stayed reached with its guard false. One event exhibits it:

```clojure
[{:stage/id :a} {:stage/id :b} {:stage/id :c :stage/sticky? true}
 {:stage/id :d :after [:c] :guards [[:not [:stage-reached :a]]]}]
```

The fold derived `#{:a :b :c}` for the ticket's first event; the read of the
same state with the carry `#{:c}` derived `#{:a :b :c :d}`. `status`,
`explain` and `next` read the second. The definition is lint-clean, so the
stratification guarantee of ADR 0005 did not hold whenever the carry was
non-empty. The reference kernel seeded the carry the same way, which is why
the differential tests agreed with the defect.

The generative tests in `tik.stage-order-prop-test` found it: over random
lint-clean processes and logs, every prefix must derive each reached stage
outside the carry with its :after reached and its guards true, and leave out
no stage that could derive.

Admitting the carry only by stratum, and hiding it from guards until then,
breaks the other half: a stage negating a carried stage of its own stratum
would fire beside it, although the carried stage was reached long before.
Reading the carry as history in guards keeps that answer.

## Consequences

- The corpus case `sticky-carry-strata` pins the counterexample; the
  previous kernel fails it.
- A ticket whose definition negates a stage that a carried prerequisite
  makes reachable early may derive differently than before. No process in
  this repository or in the metio store combines the two.
- `stage-order-prop-test` checks four mutations of the fixpoint: dropping the
  :after check, firing one stage per sweep, seeding the carry into the first
  snapshot, and hiding the carry from guards. Each fails it.
