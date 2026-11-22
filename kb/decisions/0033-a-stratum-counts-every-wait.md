---
type: decision
status: accepted
date: 2026-11-22
title: A negated stage must enter for good before its negator is first judged, counting every stage reference it waits on and every negation parity
supersedes: null
---

# ADR 0033: A stratum counts every wait

## Decision

**A stage may negate another only when the target's latest possible entry
comes strictly before the sweep the negating stage is first judged in.**
This sharpens ADR 0005's rule, "negate only strictly earlier strata", in
two ways.

1. **The target's entry counts every wait.** A stage enters no later than
   one sweep after the latest of the stages it waits for, through `:after`
   or through a positive `[:stage-reached X]` anywhere in its guards, since
   a guard reads the snapshot its sweep started from. A negated reference
   only ever blocks, so it does not delay entry. A stage on a cycle of such
   waits has no bound, and negating it is an error.
2. **Negation is read by parity.** A `[:stage-reached X]` wrapped in an odd
   number of `:not`, through any `:and` and `:or`, negates `X`; an even
   number reads it positively.

The negating stage is first judged in the sweep of its `:after` depth,
which stays the stratum `tik.stage/strata` computes and the sweep admits
the sticky carry by (ADR 0032).

## Context

ADR 0005 measured both sides by `:after` depth and recognised only the
spelling `[:not [:stage-reached X]]`, noting nested parity as a TODO. Both
gaps let a lint-clean definition derive a stage whose guard is false at the
fixpoint, the outcome stratification exists to rule out:

```clojure
;; :c has depth 0 but waits for :b at depth 1, so it enters in sweep 2;
;; :d at depth 1 is judged in sweep 1, finds :c absent, and fires
[{:stage/id :a} {:stage/id :b :after [:a]}
 {:stage/id :c :guards [[:stage-reached :b]]}
 {:stage/id :d :after [:a] :guards [[:not [:stage-reached :c]]]}]

;; :b negates :a through :and, in the same stratum
[{:stage/id :a} {:stage/id :b :guards [[:not [:and [:stage-reached :a]]]]}]
```

The direct-spelling check also misjudged the other way:
`[:not [:or [:fact f] [:not [:stage-reached :a]]]]` reads `:a` positively,
but the lint reported it as a negation.

The generative ordering tests reached both gaps once their generator
referenced any stage and nested negation; before, it referenced only stages
listed earlier and spelled negation directly.

## Consequences

- No definition in this repository or the metio store changes its lint
  verdict; none negates a stage that a positive reference holds back or
  negates through a compound.
- `tik.stage-order-prop-test` generates references to every stage and
  negation through `:and`, `:or` and `:not`, and keeps only the definitions
  the lint accepts. Against the previous lint it fails.
- ADR 0005's nested-parity TODO is closed.
