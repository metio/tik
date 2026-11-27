/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Sweep

/-!
# Theorem (a), sweeps: the order of the stage list does not matter

The synchronous sweep judges every stage against the snapshot its sweep
started from, so the order a definition lists its stages in, which is the
order a sweep visits them in, does not change the reached set, with or
without a sticky carry. Stage ids are assumed distinct, as the lint demands.
Firing one stage at a time does not have this property
(`Tik.Counter.chaoticOrderDependent`).
-/

namespace Tik

theorem id_inj_of_nodup : ∀ (l : List Stage), (l.map (·.id)).Nodup →
    ∀ x ∈ l, ∀ y ∈ l, x.id = y.id → x = y
  | [], _, x, hx, _, _, _ => by simp at hx
  | z :: l, hnd, x, hx, y, hy, h => by
    rw [List.map_cons, List.nodup_cons] at hnd
    have hz : ∀ w ∈ l, w.id ≠ z.id := fun w hw he => hnd.1 (by rw [← he]; exact List.mem_map_of_mem hw)
    simp only [List.mem_cons] at hx hy
    rcases hx with hx | hx <;> rcases hy with hy | hy
    · rw [hx, hy]
    · subst hx; exact absurd h.symm (hz y hy)
    · subst hy; exact absurd h (hz x hx)
    · exact id_inj_of_nodup l hnd.2 x hx y hy h

theorem find_id_congr {a b : List Stage} (hmem : ∀ x, x ∈ a ↔ x ∈ b)
    (hinj : ∀ x ∈ a, ∀ y ∈ a, x.id = y.id → x = y) (s : StageId) :
    a.find? (fun st => st.id == s) = b.find? (fun st => st.id == s) := by
  cases ha : a.find? (fun st => st.id == s) with
  | none =>
    symm
    rw [List.find?_eq_none] at ha ⊢
    intro x hx; exact ha x ((hmem x).mpr hx)
  | some x =>
    have hxa := List.mem_of_find?_eq_some ha
    have hpx := List.find?_some ha
    cases hb : b.find? (fun st => st.id == s) with
    | none =>
      rw [List.find?_eq_none] at hb
      exact absurd hpx (hb x ((hmem x).mp hxa))
    | some y =>
      have hyb := List.mem_of_find?_eq_some hb
      have hpy := List.find?_some hb
      simp only [beq_iff_eq] at hpx hpy
      rw [hinj x hxa y ((hmem y).mpr hyb) (hpx.trans hpy.symm)]

theorem all_congr_mem {a b : List StageId} (h : ∀ x, x ∈ a ↔ x ∈ b) (f : StageId → Bool) :
    a.all f = b.all f := by
  apply Bool.eq_iff_iff.mpr
  simp only [List.all_eq_true, h]

section
variable {p q : Process} (hp : p.stages.Perm q.stages) (hnd : (p.stages.map (·.id)).Nodup)
include hp hnd

theorem afterOf_perm : p.afterOf = q.afterOf := by
  funext s
  unfold Process.afterOf
  rw [find_id_congr (a := p.stages.reverse) (b := q.stages.reverse)]
  · intro x; rw [List.mem_reverse, List.mem_reverse]; exact hp.mem_iff
  · intro x hx y hy
    exact id_inj_of_nodup p.stages hnd x (List.mem_reverse.mp hx) y (List.mem_reverse.mp hy)

theorem depthAux_perm : ∀ n, p.depthAux n = q.depthAux n := by
  intro n
  induction n with
  | zero => funext s; rfl
  | succ n ih =>
    funext s
    simp only [Process.depthAux, afterOf_perm hp hnd, ih]

theorem depthOf_perm : p.depthOf = q.depthOf := by
  funext s
  simp only [Process.depthOf, hp.length_eq, depthAux_perm hp hnd]

theorem step_perm (F : Facts) (mem : Members) (base : List StageId) (k : Nat)
    {r r' : List StageId} (h : ∀ s, s ∈ r ↔ s ∈ r') :
    ∀ s, s ∈ step p F mem base k r ↔ s ∈ step q F mem base k r' := by
  have hc := contains_ext h
  have hf : Stage.fires F mem r base = Stage.fires F mem r' base := by
    funext st; unfold Stage.fires; rw [hc]
  intro s
  unfold step
  rw [hc, hf, depthOf_perm hp hnd]
  simp only [List.mem_append, List.mem_filter, List.mem_map, h, hp.mem_iff]

theorem loop_perm (F : Facts) (mem : Members) (base : List StageId) : ∀ (fuel k : Nat)
    (r r' : List StageId), (∀ s, s ∈ r ↔ s ∈ r') →
    ∀ s, s ∈ loop p F mem base fuel k r ↔ s ∈ loop q F mem base fuel k r'
  | 0, _, _, _, h => h
  | fuel + 1, k, r, r', h => by
    have hs := step_perm hp hnd F mem base k h
    have hc := contains_ext h
    simp only [loop]
    rw [all_congr_mem hs, hc]
    split
    · exact h
    · exact loop_perm F mem base fuel (k + 1) _ _ hs

/-- **Theorem (a), sweeps.** Listing the stages in another order derives the
same reached set, for any facts and any sticky carry. -/
theorem reachedSet_perm (F : Facts) (mem : Members) (base : List StageId) :
    ∀ s, s ∈ reachedSet p F mem base ↔ s ∈ reachedSet q F mem base := by
  unfold reachedSet fuelFor
  rw [hp.length_eq]
  exact loop_perm hp hnd F mem base _ 0 [] [] (fun _ => Iff.rfl)

end

end Tik
