------------------------ MODULE SweepFixpointInductive ------------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* SweepFixpoint.tla's claim over EVERY stratified process of N stages,     *)
(* proved by Apalache with an inductive invariant. TLC checks one process   *)
(* (a -> b -> d, a -> c, d guarded by "not reached c"); here After, Neg     *)
(* and the strata are symbolic constants constrained only by ADR 0005:      *)
(* Depth is the longest :after path, as tik.stage/strata computes it, and   *)
(* every negated stage sits in a strictly earlier stratum.                  *)
(*                                                                          *)
(* Model is the stratified model, the set closed under the rules            *)
(* (IsModel). Stratification makes that set unique; it is decided stratum   *)
(* by stratum, so it needs no fold to define.                               *)
(*                                                                          *)
(* UniqueFixpoint as SweepFixpoint.tla states it is NOT inductive: the      *)
(* state reached = {d} is not terminal, so it satisfies the property, yet   *)
(* its sweeps end in {a, b, c, d}. WrittenInv below is that property and    *)
(* spec/check.sh asserts Apalache refutes it. The strengthening is the      *)
(* exact shape of the sweeps: after t sweeps, reached holds the stages of   *)
(* the model below stratum t,                                               *)
(*                                                                          *)
(*     reached = {s \in Model : Depth[s] < sweeps}                          *)
(*                                                                          *)
(* which is inductive because a stage of stratum t first becomes            *)
(* prerequisite-enabled in sweep t + 1, and by then every stage it negates  *)
(* has been decided. From it follow:                                        *)
(*                                                                          *)
(*   - UniqueFixpoint: a terminal state is the stratified model;            *)
(*   - Bounded: after |Stages| sweeps reached is the model, the safety      *)
(*     form of SweepFixpoint.tla's Convergence with an explicit bound;      *)
(*   - Monotone: reached only grows.                                        *)
(*                                                                          *)
(* ChaoticNext is the fire-one-stage-at-a-time evaluator of                 *)
(* ChaoticFixpoint.tla over the same symbolic processes; spec/check.sh      *)
(* asserts Apalache finds a stratified process on which it violates         *)
(* UniqueFixpoint.                                                          *)
(*                                                                          *)
(* Out of scope: facts, the sticky carry and positive [:stage-reached]      *)
(* waits (ADR 0033), which the kernel and the corpus cover.                 *)
EXTENDS Integers, FiniteSets

CONSTANTS
    \* @type: Set(Int);
    Stages,
    \* @type: Int -> Set(Int);
    After,
    \* @type: Int -> Set(Int);
    Neg,
    \* @type: Int -> Int;
    Depth,
    \* @type: Set(Int);
    Model

VARIABLES
    \* @type: Set(Int);
    reached,
    \* @type: Int;
    sweeps

Enabled(s) == /\ s \notin reached
              /\ After[s] \subseteq reached
              /\ Neg[s] \cap reached = {}

Init == reached = {} /\ sweeps = 0

Next == /\ reached' = reached \cup {s \in Stages : Enabled(s)}
        /\ sweeps' = sweeps + 1

Fire == /\ \E s \in Stages : Enabled(s) /\ reached' = reached \cup {s}
        /\ sweeps' = sweeps + 1
ChaoticNext == Fire \/ ((\A s \in Stages : ~Enabled(s)) /\ UNCHANGED <<reached, sweeps>>)

Terminal == \A s \in Stages : ~Enabled(s)

Stratified ==
    /\ After \in [Stages -> SUBSET Stages]
    /\ Neg \in [Stages -> SUBSET Stages]
    /\ Depth \in [Stages -> Int]
    /\ \A s \in Stages :
         /\ Depth[s] >= 0
         /\ After[s] = {} => Depth[s] = 0
         /\ After[s] /= {} =>
              /\ \A p \in After[s] : Depth[p] < Depth[s]
              /\ \E p \in After[s] : Depth[p] = Depth[s] - 1
         /\ \A q \in Neg[s] : Depth[q] < Depth[s]

IsModel(M) == \A s \in Stages : s \in M <=> (After[s] \subseteq M /\ Neg[s] \cap M = {})

TypeOK == reached \in SUBSET Stages /\ sweeps \in Int

IndInv == /\ TypeOK
          /\ sweeps >= 0
          /\ reached = {s \in Model : Depth[s] < sweeps}

(* State properties.                                                        *)
UniqueFixpoint == Terminal => reached = Model
Bounded == sweeps >= Cardinality(Stages) => reached = Model

(* Step property.                                                           *)
Monotone == reached \subseteq reached'

(* Not inductive, each refuted by spec/check.sh. WeakInv holds on every     *)
(* reachable state, but from a subset of the model holding b and not c a    *)
(* sweep admits d. WrittenInv is UniqueFixpoint as SweepFixpoint.tla        *)
(* states it.                                                               *)
WeakInv == TypeOK /\ sweeps >= 0 /\ reached \subseteq Model
WrittenInv == TypeOK /\ sweeps >= 0 /\ UniqueFixpoint

(* Vacuity controls, each must be violated.                                 *)
NoState == FALSE
NoStep == ~(reached \subseteq reached')

Process(n) == /\ Stages = 1..n
              /\ Stratified
              /\ Model \in SUBSET Stages
              /\ IsModel(Model)

CInit4 == Process(4)
CInit6 == Process(6)

(* a=1, b=2, c=3, d=4: the process of SweepFixpoint.tla.                    *)
CInitTLC == /\ Stages = 1..4
            /\ After = [s \in 1..4 |-> IF s = 1 THEN {} ELSE IF s = 4 THEN {2} ELSE {1}]
            /\ Neg = [s \in 1..4 |-> IF s = 4 THEN {3} ELSE {}]
            /\ Depth = [s \in 1..4 |-> IF s = 1 THEN 0 ELSE IF s = 4 THEN 2 ELSE 1]
            /\ Model = {1, 2, 3}
            /\ Stratified
            /\ IsModel(Model)
=============================================================================
