/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Proofs

/-!
The axioms every theorem rests on. `check.sh` compiles this file and fails
unless each line names only `propext`, `Classical.choice` and `Quot.sound`,
so a `sorry` (`sorryAx`), an added axiom or `native_decide`
(`Lean.ofReduceBool`) anywhere in a proof's dependencies fails the check.
-/

#print axioms Tik.derive_order_independent
#print axioms Tik.derive_perm
#print axioms Tik.derive_dup
#print axioms Tik.reachedSet_perm
#print axioms Tik.stratified_unique_fixpoint
#print axioms Tik.stratCheck_unique_fixpoint
#print axioms Tik.reachedSet_mono_cone
#print axioms Tik.reachedSet_mono_negFree
#print axioms Tik.Counter.chaoticOrderDependent
#print axioms Tik.Counter.positiveCycle_two_fixpoints
#print axioms Tik.Counter.positiveCycle_not_stratified
#print axioms Tik.Counter.upstreamNegation_drops
#print axioms Tik.Counter.overwrite
