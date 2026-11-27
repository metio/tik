/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Strat

/-!
# How a guard's verdict depends on the stages it reads

A guard is monotone in the stages it reads at even negation parity and
antitone in those it reads at odd parity, and it reads nothing else. Without
`:not` it is also monotone in the facts.
-/

namespace Tik

mutual
theorem Guard.eval_mono (F : Facts) (mem : Members) : ∀ (g : Guard) (R₁ R₂ : StageId → Bool),
    (∀ q ∈ g.refs true, R₁ q = true → R₂ q = true) →
    (∀ q ∈ g.refs false, R₂ q = true → R₁ q = true) →
    g.eval F mem R₁ = true → g.eval F mem R₂ = true
  | .fact _, _, _, _, _, h => by simpa [Guard.eval] using h
  | .factEq _ _, _, _, _, _, h => by simpa [Guard.eval] using h
  | .signedBy _ _, _, _, _, _, h => by simpa [Guard.eval] using h
  | .diffPerson _ _, _, _, _, _, h => by simpa [Guard.eval] using h
  | .const _, _, _, _, _, h => by simpa [Guard.eval] using h
  | .reached s, _, _, hp, _, h => by
    simp only [Guard.eval] at h ⊢
    exact hp s (by simp [Guard.refs]) h
  | .not g, R₁, R₂, hp, hn, h => by
    simp only [Guard.eval, Bool.not_eq_true'] at h ⊢
    cases h2 : g.eval F mem R₂
    · rfl
    · have := Guard.eval_mono F mem g R₂ R₁
        (fun q hq => hn q (by simpa [Guard.refs] using hq))
        (fun q hq => hp q (by simpa [Guard.refs] using hq)) h2
      rw [this] at h; exact absurd h (by simp)
  | .all gs, R₁, R₂, hp, hn, h => by
    simp only [Guard.eval] at h ⊢
    exact Guard.evalAll_mono F mem gs R₁ R₂
      (fun q hq => hp q (by simpa [Guard.refs] using hq))
      (fun q hq => hn q (by simpa [Guard.refs] using hq)) h
  | .any gs, R₁, R₂, hp, hn, h => by
    simp only [Guard.eval] at h ⊢
    exact Guard.evalAny_mono F mem gs R₁ R₂
      (fun q hq => hp q (by simpa [Guard.refs] using hq))
      (fun q hq => hn q (by simpa [Guard.refs] using hq)) h

theorem Guard.evalAll_mono (F : Facts) (mem : Members) : ∀ (gs : List Guard) (R₁ R₂ : StageId → Bool),
    (∀ q ∈ Guard.refsList true gs, R₁ q = true → R₂ q = true) →
    (∀ q ∈ Guard.refsList false gs, R₂ q = true → R₁ q = true) →
    Guard.evalAll F mem R₁ gs = true → Guard.evalAll F mem R₂ gs = true
  | [], _, _, _, _, _ => rfl
  | g :: gs, R₁, R₂, hp, hn, h => by
    simp only [Guard.evalAll, Bool.and_eq_true] at h ⊢
    exact ⟨Guard.eval_mono F mem g R₁ R₂
              (fun q hq => hp q (by simp [Guard.refsList, hq]))
              (fun q hq => hn q (by simp [Guard.refsList, hq])) h.1,
           Guard.evalAll_mono F mem gs R₁ R₂
              (fun q hq => hp q (by simp [Guard.refsList, hq]))
              (fun q hq => hn q (by simp [Guard.refsList, hq])) h.2⟩

theorem Guard.evalAny_mono (F : Facts) (mem : Members) : ∀ (gs : List Guard) (R₁ R₂ : StageId → Bool),
    (∀ q ∈ Guard.refsList true gs, R₁ q = true → R₂ q = true) →
    (∀ q ∈ Guard.refsList false gs, R₂ q = true → R₁ q = true) →
    Guard.evalAny F mem R₁ gs = true → Guard.evalAny F mem R₂ gs = true
  | [], _, _, _, _, h => h
  | g :: gs, R₁, R₂, hp, hn, h => by
    simp only [Guard.evalAny, Bool.or_eq_true] at h ⊢
    rcases h with h | h
    · exact Or.inl (Guard.eval_mono F mem g R₁ R₂
              (fun q hq => hp q (by simp [Guard.refsList, hq]))
              (fun q hq => hn q (by simp [Guard.refsList, hq])) h)
    · exact Or.inr (Guard.evalAny_mono F mem gs R₁ R₂
              (fun q hq => hp q (by simp [Guard.refsList, hq]))
              (fun q hq => hn q (by simp [Guard.refsList, hq])) h)
end

/-- Guards that agree on every stage they read give the same verdict. -/
theorem Guard.evalAll_congr (F : Facts) (mem : Members) (gs : List Guard) (R₁ R₂ : StageId → Bool)
    (hp : ∀ q ∈ Guard.refsList true gs, R₁ q = R₂ q)
    (hn : ∀ q ∈ Guard.refsList false gs, R₁ q = R₂ q) :
    Guard.evalAll F mem R₁ gs = Guard.evalAll F mem R₂ gs := by
  cases h₁ : Guard.evalAll F mem R₁ gs <;> cases h₂ : Guard.evalAll F mem R₂ gs
  · rfl
  · have := Guard.evalAll_mono F mem gs R₂ R₁
      (fun q hq h => by rw [hp q hq]; exact h) (fun q hq h => by rw [← hn q hq]; exact h) h₂
    rw [h₁] at this; exact absurd this (by simp)
  · have := Guard.evalAll_mono F mem gs R₁ R₂
      (fun q hq h => by rw [← hp q hq]; exact h) (fun q hq h => by rw [hn q hq]; exact h) h₁
    rw [h₂] at this; exact absurd this (by simp)
  · rfl

/-! ## Negation-free guards and more facts -/

mutual
def Guard.negFree : Guard → Bool
  | .not _ => false
  | .all gs => Guard.negFreeList gs
  | .any gs => Guard.negFreeList gs
  | .fact _ | .factEq _ _ | .signedBy _ _ | .diffPerson _ _ | .reached _ | .const _ => true

def Guard.negFreeList : List Guard → Bool
  | [] => true
  | g :: gs => Guard.negFree g && Guard.negFreeList gs
end

/-- `F₂` holds every fact `F₁` holds, with the same value, author and
validity. -/
def FactLe (F₁ F₂ : Facts) : Prop := ∀ p f, F₁ p = some f → F₂ p = some f

mutual
theorem Guard.eval_mono_facts (mem : Members) {F₁ F₂ : Facts} (hF : FactLe F₁ F₂) :
    ∀ (g : Guard) (R₁ R₂ : StageId → Bool), g.negFree = true →
    (∀ q, R₁ q = true → R₂ q = true) →
    g.eval F₁ mem R₁ = true → g.eval F₂ mem R₂ = true
  | .fact p, _, _, _, _, h => by
    simp only [Guard.eval] at h ⊢
    split at h
    · rename_i f hf; rw [hF p f hf]; exact h
    · exact absurd h (by simp)
  | .factEq p v, _, _, _, _, h => by
    simp only [Guard.eval] at h ⊢
    split at h
    · rename_i f hf; rw [hF p f hf]; exact h
    · exact absurd h (by simp)
  | .signedBy r p, _, _, _, _, h => by
    simp only [Guard.eval] at h ⊢
    split at h
    · rename_i f hf; rw [hF p f hf]; exact h
    · exact absurd h (by simp)
  | .diffPerson p q, _, _, _, _, h => by
    simp only [Guard.eval] at h ⊢
    split at h
    · rename_i a b ha hb; rw [hF p a ha, hF q b hb]; exact h
    · exact absurd h (by simp)
  | .const _, _, _, _, _, h => by simpa [Guard.eval] using h
  | .reached s, _, _, _, hR, h => by
    simp only [Guard.eval] at h ⊢
    exact hR s h
  | .not _, _, _, hn, _, _ => by simp [Guard.negFree] at hn
  | .all gs, R₁, R₂, hn, hR, h => by
    simp only [Guard.eval, Guard.negFree] at h hn ⊢
    exact Guard.evalAll_mono_facts mem hF gs R₁ R₂ hn hR h
  | .any gs, R₁, R₂, hn, hR, h => by
    simp only [Guard.eval, Guard.negFree] at h hn ⊢
    exact Guard.evalAny_mono_facts mem hF gs R₁ R₂ hn hR h

theorem Guard.evalAll_mono_facts (mem : Members) {F₁ F₂ : Facts} (hF : FactLe F₁ F₂) :
    ∀ (gs : List Guard) (R₁ R₂ : StageId → Bool), Guard.negFreeList gs = true →
    (∀ q, R₁ q = true → R₂ q = true) →
    Guard.evalAll F₁ mem R₁ gs = true → Guard.evalAll F₂ mem R₂ gs = true
  | [], _, _, _, _, _ => rfl
  | g :: gs, R₁, R₂, hn, hR, h => by
    simp only [Guard.evalAll, Guard.negFreeList, Bool.and_eq_true] at h hn ⊢
    exact ⟨Guard.eval_mono_facts mem hF g R₁ R₂ hn.1 hR h.1,
           Guard.evalAll_mono_facts mem hF gs R₁ R₂ hn.2 hR h.2⟩

theorem Guard.evalAny_mono_facts (mem : Members) {F₁ F₂ : Facts} (hF : FactLe F₁ F₂) :
    ∀ (gs : List Guard) (R₁ R₂ : StageId → Bool), Guard.negFreeList gs = true →
    (∀ q, R₁ q = true → R₂ q = true) →
    Guard.evalAny F₁ mem R₁ gs = true → Guard.evalAny F₂ mem R₂ gs = true
  | [], _, _, _, _, h => h
  | g :: gs, R₁, R₂, hn, hR, h => by
    simp only [Guard.evalAny, Guard.negFreeList, Bool.and_eq_true, Bool.or_eq_true] at h hn ⊢
    rcases h with h | h
    · exact Or.inl (Guard.eval_mono_facts mem hF g R₁ R₂ hn.1 hR h)
    · exact Or.inr (Guard.evalAny_mono_facts mem hF gs R₁ R₂ hn.2 hR h)
end

end Tik
