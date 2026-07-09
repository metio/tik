-------------------------- MODULE ChaoticFixpoint --------------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* The SAME stratified process as SweepFixpoint.tla, under a plausible-    *)
(* but-wrong reading of "iterate to closure": fire ONE enabled stage at a  *)
(* time, in any order. TLC is EXPECTED to violate UniqueFixpoint here      *)
(* (spec/check.sh asserts the violation): the order a,b,d fires d while c  *)
(* is not yet reached, then c fires — terminal {a,b,c,d}; the order a,c,…  *)
(* blocks d — terminal {a,b,c}.                                            *)
(*                                                                          *)
(* The moral, and the reason this module exists: stratification of the    *)
(* PROCESS (ADR 0005) is necessary but not sufficient — determinism also   *)
(* requires the EVALUATOR to use synchronous sweeps (or stratum-ordered    *)
(* evaluation). A second implementation that fires stages one at a time    *)
(* is not conformant even on linter-clean processes. The conformance      *)
(* corpus is the executable form of this contract; this model is the      *)
(* explanation of why the contract says what it says.                     *)
EXTENDS FiniteSets

Stages == {"a", "b", "c", "d"}

After(s) == CASE s = "a" -> {}
              [] s = "b" -> {"a"}
              [] s = "c" -> {"a"}
              [] s = "d" -> {"b"}

Neg(s) == IF s = "d" THEN {"c"} ELSE {}

VARIABLE reached

Enabled(s) == /\ s \notin reached
              /\ After(s) \subseteq reached
              /\ Neg(s) \cap reached = {}

Init == reached = {}

Fire == \E s \in Stages : Enabled(s) /\ reached' = reached \cup {s}

Terminal == \A s \in Stages : ~Enabled(s)

Next == Fire \/ (Terminal /\ UNCHANGED reached)

Spec == Init /\ [][Next]_reached

Expected == {"a", "b", "c"}

(* Violated on purpose — chaotic iteration is order-dependent.            *)
UniqueFixpoint == Terminal => reached = Expected
=============================================================================
