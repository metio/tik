--------------------------- MODULE PlanReadiness ---------------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* The plan lens' dependency-readiness semantics, model-checked. Tickets   *)
(* settle over time, but a ticket may only settle once all its             *)
(* `:depends-on` prerequisites have settled (it is "ready"). The graph     *)
(* mixes an acyclic chain and a dependency CYCLE:                          *)
(*                                                                          *)
(*     a -> b -> c          (chain: c has no prerequisite)                  *)
(*     d -> e -> d          (cycle: each waits on the other)               *)
(*                                                                          *)
(* TLC verifies the two properties tik.plan promises: the cyclic nodes can *)
(* NEVER settle (a deadlock is detected, never a mislabel or an infinite   *)
(* loop), and the acyclic nodes ALWAYS all settle regardless of the order  *)
(* ready nodes are taken — order-independence and liveness together.       *)
(* tik.spec-conformance-test drives the real tik.plan over this same graph *)
(* and must agree.                                                         *)
EXTENDS FiniteSets

Nodes == {"a", "b", "c", "d", "e"}

Prereq(n) == CASE n = "a" -> {"b"}
               [] n = "b" -> {"c"}
               [] n = "c" -> {}
               [] n = "d" -> {"e"}
               [] n = "e" -> {"d"}

VARIABLE settled

Ready(n) == /\ n \notin settled
            /\ Prereq(n) \subseteq settled

Init == settled = {}

Settle == \E n \in Nodes : Ready(n) /\ settled' = settled \cup {n}

Spec == Init /\ [][Settle]_settled /\ WF_settled(Settle)

Cyclic  == {"d", "e"}
Acyclic == {"a", "b", "c"}

(* A dependency cycle is a permanent deadlock: no cyclic node ever settles,*)
(* no matter the interleaving TLC explores. Safety.                        *)
NoCyclicSettles == settled \cap Cyclic = {}

(* Monotone: settled only grows.                                           *)
Monotone == [][settled \subseteq settled']_settled

(* Every acyclic node eventually settles — the plan's schedulable work     *)
(* always completes. Liveness.                                             *)
Completes == <>[](settled = Acyclic)
=============================================================================
