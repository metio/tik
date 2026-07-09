--------------------------- MODULE SweepFixpoint ---------------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* tik's derivation semantics, exactly: the reached set is computed by     *)
(* SYNCHRONOUS SWEEPS — each iteration adds ALL stages enabled against     *)
(* the sweep-start snapshot, repeated to closure (tik.stage/reached-set).  *)
(*                                                                          *)
(* The shape modeled is the sharpest stratified case: d negates c, and c   *)
(* lives in a strictly earlier stratum (depth 1 < depth 2), so ADR 0005's  *)
(* linter ACCEPTS this process:                                            *)
(*                                                                          *)
(*     a (root) ── b ── d   with d guarded by "not reached c"              *)
(*        └────── c                                                        *)
(*                                                                          *)
(* Under sweep semantics the result is deterministic: c enters at sweep 2, *)
(* d first becomes prerequisite-enabled at sweep 3 and finds c already     *)
(* decided — reached = {a, b, c}, always. TLC verifies both the unique     *)
(* fixpoint (invariant) and that it is actually attained (liveness).       *)
(*                                                                          *)
(* ChaoticFixpoint.tla is the same shape under fire-one-stage-at-a-time    *)
(* iteration, where TLC FINDS divergence — see that module for why this    *)
(* pair pins the conformance contract.                                     *)
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

Sweep == reached' = reached \cup {s \in Stages : Enabled(s)}

Spec == Init /\ [][Sweep]_reached /\ WF_reached(Sweep)

Terminal == \A s \in Stages : ~Enabled(s)

Expected == {"a", "b", "c"}

(* One fixpoint, no matter what: ADR 0005's determinism claim.            *)
UniqueFixpoint == Terminal => reached = Expected

(* ... and the sweeps actually get there.                                 *)
Convergence == <>[](reached = Expected)
=============================================================================
