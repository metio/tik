/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Sweep

/-!
# Theorem (c): more facts never remove a positively guarded stage

A stage is guarded only positively when neither its own guards nor those of
any stage it waits for, through `:after` or a `[:stage-reached …]`, contain a
`:not`. Adding facts (keeping every fact already present, with its value,
author and validity) never removes such a stage from the reached set.

The condition has to cover the stages it waits for: a stage without any guard
can still drop out when a fact negated upstream arrives
(`Tik.Counter.upstreamNegation`). Changing or retracting a fact is not adding
one, and can remove any stage (`Tik.Counter.overwrite`).
-/

namespace Tik

mutual
theorem Guard.refs_false_negFree : ∀ g : Guard, g.negFree = true → g.refs false = []
  | .fact _, _ => rfl
  | .factEq _ _, _ => rfl
  | .signedBy _ _, _ => rfl
  | .diffPerson _ _, _ => rfl
  | .const _, _ => rfl
  | .reached _, _ => rfl
  | .not _, h => by simp [Guard.negFree] at h
  | .all gs, h => by
    simp only [Guard.negFree] at h
    simp only [Guard.refs]; exact Guard.refsList_false_negFree gs h
  | .any gs, h => by
    simp only [Guard.negFree] at h
    simp only [Guard.refs]; exact Guard.refsList_false_negFree gs h

theorem Guard.refsList_false_negFree : ∀ gs : List Guard, Guard.negFreeList gs = true → Guard.refsList false gs = []
  | [], _ => rfl
  | g :: gs, h => by
    simp only [Guard.negFreeList, Bool.and_eq_true] at h
    simp only [Guard.refsList, Guard.refs_false_negFree g h.1, Guard.refsList_false_negFree gs h.2,
      List.append_nil]
end

/-- `C` holds a set of stages whose every definition is free of `:not` and
which is closed under the stages they wait for. -/
def PositiveCone (p : Process) (C : StageId → Prop) : Prop :=
  ∀ st ∈ p.stages, C st.id →
    Guard.negFreeList st.guards = true ∧ (∀ a ∈ st.after, C a) ∧ (∀ q ∈ st.refs true, C q)

/-- No guard of the process contains a `:not`. -/
def NegFree (p : Process) : Prop := ∀ st ∈ p.stages, Guard.negFreeList st.guards = true

section
variable (p : Process) (mem : Members)

theorem iter_mono_cone {C : StageId → Prop} (hC : PositiveCone p C) {F₁ F₂ : Facts} (hF : FactLe F₁ F₂) :
    ∀ k s, C s → s ∈ iter p F₁ mem k → s ∈ iter p F₂ mem k := by
  intro k
  induction k with
  | zero => intro s _ h; exact h
  | succ k ih =>
    intro s hs h
    let r₁ := iter p F₁ mem k
    let r₂ := iter p F₂ mem k
    show s ∈ stepE p F₂ mem r₂
    have h' : s ∈ stepE p F₁ mem r₁ := h
    rw [mem_stepE] at h' ⊢
    rcases h' with h' | ⟨st, hst, hid, hf⟩
    · exact Or.inl (ih s hs h')
    · by_cases hin : s ∈ r₂
      · exact Or.inl hin
      · right
        subst hid
        obtain ⟨_, hafter, hg⟩ := (fires_nil_iff F₁ mem r₁ st).mp hf
        obtain ⟨hneg, hCa, hCq⟩ := hC st hst hs
        refine ⟨st, hst, rfl, (fires_nil_iff F₂ mem r₂ st).mpr ⟨hin, ?_, ?_⟩⟩
        · intro a ha; exact ih a (hCa a ha) (hafter a ha)
        · have hg₂ := Guard.evalAll_mono_facts mem hF st.guards r₁.contains r₁.contains hneg
            (fun _ h => h) hg
          apply Guard.evalAll_mono F₂ mem st.guards r₁.contains r₂.contains _ _ hg₂
          · intro q hq hq₁
            exact (contains_iff r₂ q).mpr (ih q (hCq q hq) ((contains_iff r₁ q).mp hq₁))
          · intro q hq
            rw [Guard.refsList_false_negFree st.guards hneg] at hq
            simp at hq

/-- **Theorem (c).** Adding facts never removes a stage whose guards, and the
guards of every stage it waits for, are all positive. -/
theorem reachedSet_mono_cone {C : StageId → Prop} (hC : PositiveCone p C) {F₁ F₂ : Facts}
    (hF : FactLe F₁ F₂) (s : StageId) (hs : C s) (h : s ∈ reachedSet p F₁ mem []) :
    s ∈ reachedSet p F₂ mem [] := by
  obtain ⟨j₁, hj₁, _⟩ := reachedSet_nil_spec p F₁ mem
  obtain ⟨j₂, hj₂, hst₂⟩ := reachedSet_nil_spec p F₂ mem
  rw [hj₁] at h
  rw [hj₂]
  have h₁ := iter_mono p F₁ mem (Nat.le_max_left j₁ j₂) s h
  have h₂ := iter_mono_cone p mem hC hF _ s hs h₁
  exact (hst₂ _ (Nat.le_max_right j₁ j₂) s).mp h₂

/-- Theorem (c) for a process without negation: adding facts never removes a
stage. -/
theorem reachedSet_mono_negFree (h : NegFree p) {F₁ F₂ : Facts} (hF : FactLe F₁ F₂)
    (s : StageId) (hs : s ∈ reachedSet p F₁ mem []) : s ∈ reachedSet p F₂ mem [] :=
  reachedSet_mono_cone p mem (C := fun _ => True)
    (fun st hst _ => ⟨h st hst, fun _ _ => trivial, fun _ _ => trivial⟩) hF s trivial hs

end

end Tik
