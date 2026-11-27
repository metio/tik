/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Model

/-!
# Event order

The derived stages are a function of the event set: two lists holding the
same events, in any order and with any duplicates, derive the same stages,
sticky carry included. The one assumption is the content-addressing promise:
two events with the same id are the same event.
-/

namespace Tik

set_option linter.deprecated false

theorem keyLt_irrefl (a : Event) : ¬ keyLt a a := by
  intro h
  rcases h with h | ⟨_, h⟩
  · exact Int.lt_irrefl _ h
  · exact String.lt_irrefl _ h

theorem keyLt_trans {a b c : Event} (h₁ : keyLt a b) (h₂ : keyLt b c) : keyLt a c := by
  unfold keyLt at *
  rcases h₁ with h₁ | ⟨e₁, h₁⟩ <;> rcases h₂ with h₂ | ⟨e₂, h₂⟩
  · exact Or.inl (Int.lt_trans h₁ h₂)
  · exact Or.inl (e₂ ▸ h₁)
  · exact Or.inl (e₁ ▸ h₂)
  · exact Or.inr ⟨e₁.trans e₂, String.lt_trans h₁ h₂⟩

theorem keyLt_asymm {a b : Event} (h : keyLt a b) : ¬ keyLt b a :=
  fun h' => keyLt_irrefl a (keyLt_trans h h')

theorem id_eq_of_not_keyLt {a b : Event} (h₁ : ¬ keyLt a b) (h₂ : ¬ keyLt b a) : a.id = b.id := by
  unfold keyLt at *
  have ht : a.time = b.time := by omega
  have hab : ¬ a.id < b.id := fun h => h₁ (Or.inr ⟨ht, h⟩)
  have hba : ¬ b.id < a.id := fun h => h₂ (Or.inr ⟨ht.symm, h⟩)
  exact String.le_antisymm (String.not_lt.mp hba) (String.not_lt.mp hab)

/-- Ordered strictly by `(at, id)`. -/
abbrev Sorted (l : List Event) : Prop := l.Pairwise keyLt

/-- The content-addressing promise over a list: an id names one event. -/
def IdInj (l : List Event) : Prop := ∀ a ∈ l, ∀ b ∈ l, a.id = b.id → a = b

theorem mem_ins_sub (e : Event) : ∀ (l : List Event) (y : Event), y ∈ ins e l → y = e ∨ y ∈ l
  | [], y, h => by simp [ins] at h; exact Or.inl h
  | x :: xs, y, h => by
    unfold ins at h
    split at h
    · simpa using h
    · split at h
      · simp only [List.mem_cons] at h
        rcases h with h | h
        · exact Or.inr (by simp [h])
        · rcases mem_ins_sub e xs y h with h | h
          · exact Or.inl h
          · exact Or.inr (by simp [h])
      · exact Or.inr h

theorem mem_ins (e : Event) : ∀ (l : List Event), (∀ x ∈ l, x.id = e.id → x = e) →
    ∀ y, y ∈ ins e l ↔ y = e ∨ y ∈ l
  | [], _, y => by simp [ins]
  | x :: xs, hinj, y => by
    unfold ins
    split
    · simp
    · split
      · have ih := mem_ins e xs (fun z hz => hinj z (by simp [hz])) y
        simp only [List.mem_cons, ih]
        constructor
        · rintro (h | h | h)
          · exact Or.inr (Or.inl h)
          · exact Or.inl h
          · exact Or.inr (Or.inr h)
        · rintro (h | h | h)
          · exact Or.inr (Or.inl h)
          · exact Or.inl h
          · exact Or.inr (Or.inr h)
      · rename_i h₁ h₂
        have hx : x = e := hinj x (by simp) (id_eq_of_not_keyLt h₂ h₁)
        subst hx
        simp

theorem sorted_ins (e : Event) : ∀ (l : List Event), Sorted l → Sorted (ins e l)
  | [], _ => by simp [ins]
  | x :: xs, hs => by
    have ⟨hx, hxs⟩ := List.pairwise_cons.mp hs
    unfold ins
    split
    · rename_i h
      refine List.Pairwise.cons ?_ hs
      intro y hy
      simp only [List.mem_cons] at hy
      rcases hy with hy | hy
      · exact hy ▸ h
      · exact keyLt_trans h (hx y hy)
    · split
      · rename_i _ h
        refine List.Pairwise.cons ?_ (sorted_ins e xs hxs)
        intro y hy
        rcases mem_ins_sub e xs y hy with hy | hy
        · exact hy ▸ h
        · exact hx y hy
      · exact hs

theorem sorted_canon : ∀ l : List Event, Sorted (canon l)
  | [] => by simp [canon]
  | a :: t => by
    show Sorted (ins a (canon t))
    exact sorted_ins a _ (sorted_canon t)

theorem mem_canon : ∀ l : List Event, IdInj l → ∀ y, y ∈ canon l ↔ y ∈ l
  | [], _, y => by simp [canon]
  | a :: t, hinj, y => by
    have hinj' : IdInj t := fun x hx z hz h => hinj x (by simp [hx]) z (by simp [hz]) h
    have ih := mem_canon t hinj'
    show y ∈ ins a (canon t) ↔ y ∈ a :: t
    rw [mem_ins a (canon t) (fun x hx h => hinj x (by simp [(ih x).mp hx]) a (by simp) h)]
    simp [ih]

theorem eq_of_sorted_of_mem_iff : ∀ (a b : List Event), Sorted a → Sorted b →
    (∀ y, y ∈ a ↔ y ∈ b) → a = b
  | [], [], _, _, _ => rfl
  | [], y :: _, _, _, h => absurd ((h y).mpr (by simp)) (by simp)
  | x :: _, [], _, _, h => absurd ((h x).mp (by simp)) (by simp)
  | x :: xs, y :: ys, ha, hb, h => by
    have ⟨hx, hxs⟩ := List.pairwise_cons.mp ha
    have ⟨hy, hys⟩ := List.pairwise_cons.mp hb
    have hxy : x = y := by
      have h1 := (h x).mp (by simp)
      have h2 := (h y).mpr (by simp)
      simp only [List.mem_cons] at h1 h2
      rcases h1 with h1 | h1
      · exact h1
      · rcases h2 with h2 | h2
        · exact h2.symm
        · exact absurd (hx y h2) (keyLt_asymm (hy x h1))
    subst hxy
    have hrest : ∀ z, z ∈ xs ↔ z ∈ ys := by
      intro z
      constructor
      · intro hz
        have := (h z).mp (by simp [hz])
        simp only [List.mem_cons] at this
        rcases this with h' | h'
        · subst h'; exact absurd (hx z hz) (keyLt_irrefl z)
        · exact h'
      · intro hz
        have := (h z).mpr (by simp [hz])
        simp only [List.mem_cons] at this
        rcases this with h' | h'
        · subst h'; exact absurd (hy z hz) (keyLt_irrefl z)
        · exact h'
    rw [eq_of_sorted_of_mem_iff xs ys hxs hys hrest]

/-- Two lists holding the same events order to the same history. -/
theorem canon_eq {l₁ l₂ : List Event} (hmem : ∀ e, e ∈ l₁ ↔ e ∈ l₂) (hinj : IdInj (l₁ ++ l₂)) :
    canon l₁ = canon l₂ := by
  have h₁ : IdInj l₁ := fun a ha b hb => hinj a (by simp [ha]) b (by simp [hb])
  have h₂ : IdInj l₂ := fun a ha b hb => hinj a (by simp [ha]) b (by simp [hb])
  apply eq_of_sorted_of_mem_iff _ _ (sorted_canon l₁) (sorted_canon l₂)
  intro y
  rw [mem_canon l₁ h₁, mem_canon l₂ h₂, hmem]

/-- **Theorem (a), events.** The derived stages do not depend on the order
events arrive in, nor on duplicates: two lists holding the same events derive
the same stages, sticky carry included. -/
theorem derive_order_independent (p : Process) (mem : Members) {l₁ l₂ : List Event}
    (hmem : ∀ e, e ∈ l₁ ↔ e ∈ l₂) (hinj : IdInj (l₁ ++ l₂)) :
    derive p mem l₁ = derive p mem l₂ := by
  unfold derive
  rw [canon_eq hmem hinj]

/-- A permutation of the same events derives the same stages. -/
theorem derive_perm (p : Process) (mem : Members) {l₁ l₂ : List Event} (hp : l₁.Perm l₂)
    (hinj : IdInj l₁) : derive p mem l₁ = derive p mem l₂ := by
  apply derive_order_independent p mem (fun e => hp.mem_iff)
  intro a ha b hb
  simp only [List.mem_append] at ha hb
  have ha' : a ∈ l₁ := ha.elim id hp.mem_iff.mpr
  have hb' : b ∈ l₁ := hb.elim id hp.mem_iff.mpr
  exact hinj a ha' b hb'

/-- Replaying an event twice derives what replaying it once does. -/
theorem derive_dup (p : Process) (mem : Members) (l : List Event) (e : Event) (he : e ∈ l)
    (hinj : IdInj l) : derive p mem (e :: l) = derive p mem l := by
  apply derive_order_independent p mem
  · intro x; simp only [List.mem_cons]; constructor
    · rintro (h | h); exact h ▸ he; exact h
    · exact Or.inr
  · intro a ha b hb
    simp only [List.mem_append, List.mem_cons] at ha hb
    have ha' : a ∈ l := by rcases ha with (h | h) | h <;> first | exact h ▸ he | exact h
    have hb' : b ∈ l := by rcases hb with (h | h) | h <;> first | exact h ▸ he | exact h
    exact hinj a ha' b hb'

end Tik
