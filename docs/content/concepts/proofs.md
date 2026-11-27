---
title: Machine-checked proofs
description: What a Lean 4 model of the derivation function proves about stages, what it leaves out, and how CI holds the model and the Clojure kernel to the same answers.
tags: [stages, fixpoint, lean, conformance, verification]
---

Three laws of derivation are proved in Lean 4 over an executable model of
`tik.stage/reached-set`, `evolve` and `effective-reached`, and CI runs that
model beside the Clojure kernel on every pull request and fails on any
disagreement. The proofs say nothing about the Clojure code directly; the
differential run is what ties the two together, case by case.

## What is proved

The model and its proofs live in `formal/lean/`. Every theorem below rests
on Lean's three standard axioms (`propext`, `Classical.choice`,
`Quot.sound`) and nothing else; `bb lean` fails on a `sorry`, an added
axiom or `native_decide` anywhere in a proof.

- **Event order.** Two lists holding the same events, in any order and with
  any duplicates, derive the same stages, sticky carry included
  (`Tik.derive_order_independent`). The one assumption is the
  content-addressing promise: two events with the same id are the same
  event.
- **Sweep order.** Listing a definition's stages in another order derives
  the same reached set, for any facts and any sticky carry, as long as
  stage ids are distinct (`Tik.reachedSet_perm`).
- **One fixpoint.** For a stratified process, exactly one set of stages is
  closed under the rules (a stage is in it exactly when its `:after`
  stages are and its guards hold reading it), and the synchronous sweep
  from an empty carry computes that set
  (`Tik.stratified_unique_fixpoint`).
- **Monotonicity.** Adding facts, keeping every fact already present with
  its value and author, never removes a stage whose guards, and the guards
  of every stage it waits for, contain no `:not`
  (`Tik.reachedSet_mono_cone`).

Stratified here means ADR 0033's rule in certificate form: every stage a
stage waits for, through `:after` or a positive `[:stage-reached …]`, has a
smaller entry bound, and every stage it negates enters for good before the
stage is first judged. The model checks the certificate for every case it
runs; every process in `processes/` passes it.

## Counterexamples

Each stronger statement below is false, and the model proves so on a
concrete process:

- Firing one stage at a time reaches two different terminal sets on
  `processes/sweep-order.edn`, depending on the order; the synchronous
  sweep reaches one.
- Two stages that wait for each other through positive
  `[:stage-reached …]` guards have two fixpoints, the empty set and both
  stages. The lint accepts such a pair, since nothing negates either
  stage; the sweep derives the empty one.
- A stage with no guard at all drops out when a fact arrives that a stage
  it waits for negates. Monotonicity therefore needs every stage upstream
  to be free of `:not` too.
- A later assertion that changes a fact's value can remove a stage gated by
  `:fact=`. Adding an event is adding a fact only when the event names a
  path that held no value.

## What is left out

The model's guard basis is `:fact`, `:fact=`, `:signed-by`,
`:different-person`, `:stage-reached`, `:and`, `:or` and `:not`. Its
reduction knows assertions and retractions, last write wins in `(at, id)`
order. Left out, each because its verdict is fixed for one evaluation of the
fixpoint and adds nothing to the laws above:

- `:artifact`, `:elapsed-since`, `:attested-within` and `:malli`, and the
  schemas a definition declares for its facts. The kernel computes these and
  hands the model the verdicts.
- disputes, conflicts between concurrent writes and the `:now` clamp, which
  the reducer resolves before guards run;
- signatures, key bindings and the role register, which decide which events
  and members exist, before derivation starts.

The fixpoint and monotonicity theorems are stated for an empty sticky carry.
With a carry, derivation depends on the history it was carried through, and
the differential run covers it.

## The differential run

`bb lean` builds the model, checks the axioms, replays the proofs with
`leanchecker`, and then derives every case twice: once with `tik.stage`, once
with the model's executable `tik-lean`. The cases are the conformance corpus,
every scripted case under `processes/*.tests.edn` and a few thousand
generated processes and histories, lint-clean or not. A case whose guards
and events fit the model is replayed from its events; any other case gets the
kernel's facts and sticky carry, with every guard outside the basis replaced
by its verdict.

`bb lean controls` shows that each check can fail: a planted `sorry` must
fail the source check and the axiom check, and four deliberately broken
models (firing one stage per sweep, seeding the carry into the first
snapshot, ignoring `:after`, ignoring retractions) must each disagree with
the kernel on some case.

## Toolchain

The Lean release is pinned in `formal/lean/lean-toolchain` and installed by
breto for the CI job; `bb lean` refuses a `lean` on `PATH` of any other
version. The model uses Lean's core library only, without Mathlib or
Batteries.
