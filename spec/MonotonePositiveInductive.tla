----------------------- MODULE MonotonePositiveInductive -----------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* MonotonePositive.tla's theorem over EVERY negation-free process of N     *)
(* stages and its facts, proved by Apalache with an inductive invariant.    *)
(* TLC checks one process; here After and Needs are symbolic constants,     *)
(* with :after acyclic and guards built only from fact presence and         *)
(* reached prerequisites. A guard may name any fact of Universe; only the   *)
(* facts of Facts ever arrive, so a stage needing another one never         *)
(* derives.                                                                 *)
(*                                                                          *)
(* Model(F) is the least fixpoint for the facts F: a stage derives exactly  *)
(* when it and every stage above it along :after need only facts in F.      *)
(* Above, a symbolic constant, is that relation; its defining               *)
(* equation has one solution because :after is acyclic.                     *)
(*                                                                          *)
(* UniqueFixpoint as MonotonePositive.tla states it is not inductive for    *)
(* the same reason as SweepFixpoint's: an unreachable state can satisfy it  *)
(* vacuously and still lead to a wrong terminal state. The strengthening    *)
(* is that reached never runs ahead of the evidence,                        *)
(*                                                                          *)
(*     reached \subseteq Model(available)                                   *)
(*                                                                          *)
(* which is inductive because Model is monotone in the facts. From it       *)
(* follows UniqueFixpoint, a terminal state is Model(Facts) whatever order  *)
(* the facts arrived in, and Monotone, reached and available only grow.     *)
EXTENDS Integers, FiniteSets

CONSTANTS
    \* @type: Set(Int);
    Stages,
    \* @type: Set(Str);
    Universe,
    \* @type: Set(Str);
    Facts,
    \* @type: Int -> Set(Int);
    After,
    \* @type: Int -> Set(Str);
    Needs,
    \* @type: Int -> Set(Int);
    Above

VARIABLES
    \* @type: Set(Int);
    reached,
    \* @type: Set(Str);
    available

Enabled(s) == /\ s \notin reached
              /\ After[s] \subseteq reached
              /\ Needs[s] \subseteq available

Init == reached = {} /\ available = {}

ArriveFact == /\ \E f \in Facts \ available : available' = available \cup {f}
              /\ UNCHANGED reached

Sweep == /\ reached' = reached \cup {s \in Stages : Enabled(s)}
         /\ UNCHANGED available

Next == ArriveFact \/ Sweep

Model(F) == {s \in Stages : \A p \in Above[s] : Needs[p] \subseteq F}

Terminal == available = Facts /\ \A s \in Stages : ~Enabled(s)

TypeOK == reached \in SUBSET Stages /\ available \in SUBSET Facts

IndInv == TypeOK /\ reached \subseteq Model(available)

(* State properties. ModelIsFull pins CInitTLC to MonotonePositive.tla's    *)
(* Full = {a, b, c, d}; it is not claimed for every process.                *)
UniqueFixpoint == Terminal => reached = Model(Facts)
ModelIsFull == Model(Facts) = Stages

(* Step property.                                                           *)
Monotone == reached \subseteq reached' /\ available \subseteq available'

(* UniqueFixpoint as MonotonePositive.tla states it, refuted by             *)
(* spec/check.sh.                                                           *)
WrittenInv == TypeOK /\ UniqueFixpoint

(* Vacuity controls, each must be violated.                                 *)
NoState == FALSE
NoStep == ~(reached \subseteq reached')

Process(n, universe) ==
    /\ Stages = 1..n
    /\ Universe = universe
    /\ Facts \in SUBSET Universe
    /\ After \in [Stages -> SUBSET Stages]
    /\ Needs \in [Stages -> SUBSET Universe]
    /\ Above \in [Stages -> SUBSET Stages]
    /\ \E depth \in [Stages -> 0..n] :
         \A s \in Stages : \A p \in After[s] : depth[p] < depth[s]
    /\ \A s \in Stages : Above[s] = {s} \cup UNION {Above[p] : p \in After[s]}

CInit6 == Process(6, {"x", "y", "z"})
CInit10 == Process(10, {"x", "y", "z"})

(* a=1, b=2, c=3, d=4: the process of MonotonePositive.tla.                 *)
CInitTLC ==
    /\ Stages = 1..4
    /\ Universe = {"x", "y"}
    /\ Facts = {"x", "y"}
    /\ After = [s \in 1..4 |-> IF s = 1 THEN {} ELSE IF s = 4 THEN {3} ELSE {1}]
    /\ Needs = [s \in 1..4 |-> IF s = 2 THEN {"x"} ELSE IF s = 4 THEN {"y"} ELSE {}]
    /\ Above \in [Stages -> SUBSET Stages]
    /\ \A s \in Stages : Above[s] = {s} \cup UNION {Above[p] : p \in After[s]}
=============================================================================
