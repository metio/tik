------------------------- MODULE MonotonePositive -------------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* The monotone-fixpoint theorem, model-checked: over a NEGATION-FREE       *)
(* process (guards built only from fact presence and reached prereqs —      *)
(* the monotone operators), evidence only accumulates, so the reached set   *)
(* only grows and its final value does not depend on the ORDER evidence     *)
(* arrives. tik.metamorphic-test proves the time-monotonicity direction as  *)
(* a property test; this pins the order-independence direction by having    *)
(* TLC explore every interleaving of fact arrival and sweeps.               *)
(*                                                                          *)
(*     a (root) ── b   b needs fact x                                       *)
(*        └────── c ── d   d needs fact y                                   *)
(*                                                                          *)
(* Facts x and y arrive in any order at any time; sweeps fire under the     *)
(* same synchronous semantics as SweepFixpoint. No matter the interleaving  *)
(* the terminal reached set is {a,b,c,d}, and reached never shrinks.        *)
EXTENDS FiniteSets

Stages == {"a", "b", "c", "d"}
Facts  == {"x", "y"}

After(s) == CASE s = "a" -> {}
              [] s = "b" -> {"a"}
              [] s = "c" -> {"a"}
              [] s = "d" -> {"c"}

(* Positive fact requirement — no negation anywhere.                        *)
Needs(s) == CASE s = "b" -> {"x"}
              [] s = "d" -> {"y"}
              [] OTHER   -> {}

VARIABLES reached, available

Enabled(s) == /\ s \notin reached
              /\ After(s) \subseteq reached
              /\ Needs(s) \subseteq available

Init == /\ reached = {}
        /\ available = {}

(* A fact arrives (monotone: available only grows).                         *)
ArriveFact == /\ \E f \in Facts \ available : available' = available \cup {f}
              /\ UNCHANGED reached

(* A synchronous sweep against the sweep-start snapshot.                     *)
Sweep == /\ reached' = reached \cup {s \in Stages : Enabled(s)}
         /\ UNCHANGED available

Next == ArriveFact \/ Sweep

Fairness == /\ WF_available(ArriveFact)
            /\ WF_reached(Sweep)

Spec == Init /\ [][Next]_<<reached, available>> /\ Fairness

Full == {"a", "b", "c", "d"}

(* reached never shrinks: the monotonicity invariant, on every step.        *)
Monotone == [][reached \subseteq reached']_<<reached, available>>

(* When no more evidence can arrive and no stage is enabled, the reached    *)
(* set is Full — regardless of the arrival interleaving TLC took here.      *)
Terminal == /\ available = Facts
            /\ \A s \in Stages : ~Enabled(s)
UniqueFixpoint == Terminal => reached = Full

(* ... and every run actually converges there.                             *)
Convergence == <>[](reached = Full)
=============================================================================
