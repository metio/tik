------------------------ MODULE PlanReadinessInductive ------------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* PlanReadiness.tla's readiness semantics over EVERY dependency graph of   *)
(* N nodes, proved by Apalache with an inductive invariant. TLC checks one  *)
(* graph (a chain and a 2-cycle); here Prereq is a symbolic constant, so    *)
(* the proof covers all [Nodes -> SUBSET Nodes] at once.                    *)
(*                                                                          *)
(* A cycle is stated without transitive closure, as a TRAP: a non-empty     *)
(* set of nodes each of which waits on another node of the set. Every       *)
(* dependency cycle is a trap, and so is a cycle together with any nodes    *)
(* that wait on it, so the largest trap is exactly the set of nodes that    *)
(* can never settle. Trap is a symbolic constant too: the proof holds for   *)
(* every trap of every graph.                                               *)
(*                                                                          *)
(*   - NoTrapSettles: no node of a trap ever settles (PlanReadiness.tla's   *)
(*     NoCyclicSettles, generalised). Stated as written it is already       *)
(*     inductive: a trap node needs a prerequisite inside the trap, and     *)
(*     none of those has settled.                                           *)
(*   - DeadlockIsExact: when nothing is ready, the unsettled nodes form a   *)
(*     trap, so a deadlock is reported only where a cycle really blocks     *)
(*     the work, never as a mislabel. This is the safety half of            *)
(*     PlanReadiness.tla's Completes; the liveness half stays with TLC.     *)
(*   - Monotone: settled only grows.                                        *)
EXTENDS Integers, FiniteSets

CONSTANTS
    \* @type: Set(Int);
    Nodes,
    \* @type: Int -> Set(Int);
    Prereq,
    \* @type: Set(Int);
    Trap

VARIABLE
    \* @type: Set(Int);
    settled

Ready(n) == n \notin settled /\ Prereq[n] \subseteq settled

Init == settled = {}

Next == \E n \in Nodes : Ready(n) /\ settled' = settled \cup {n}

IsTrap(T) == T /= {} /\ \A n \in T : Prereq[n] \cap T /= {}

Terminal == \A n \in Nodes : ~Ready(n)

TypeOK == settled \in SUBSET Nodes

IndInv == TypeOK /\ settled \cap Trap = {}

(* State properties.                                                        *)
NoTrapSettles == settled \cap Trap = {}
DeadlockIsExact == Terminal /\ settled /= Nodes => IsTrap(Nodes \ settled)

(* Step property.                                                           *)
Monotone == settled \subseteq settled'

(* Vacuity controls, each must be violated.                                 *)
NoState == FALSE
NoStep == ~(settled \subseteq settled')

Graph(n) == /\ Nodes = 1..n
            /\ Prereq \in [Nodes -> SUBSET Nodes]
            /\ Trap \in SUBSET Nodes
            /\ IsTrap(Trap)

CInit10 == Graph(10)
CInit40 == Graph(40)

(* a=1, b=2, c=3, d=4, e=5: the graph of PlanReadiness.tla, whose cycle     *)
(* {d, e} is its Cyclic set.                                                *)
CInitTLC == /\ Nodes = 1..5
            /\ Prereq = [n \in 1..5 |-> IF n = 1 THEN {2}
                                        ELSE IF n = 2 THEN {3}
                                        ELSE IF n = 3 THEN {}
                                        ELSE IF n = 4 THEN {5}
                                        ELSE {4}]
            /\ Trap = {4, 5}
=============================================================================
