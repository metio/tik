/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.GuardLemmas

/-!
# The sweep without a sticky carry

With an empty carry a sweep is a function of the snapshot alone (`stepE`),
the snapshot only grows, and it grows only by stage ids, so it stops growing
within as many sweeps as the process has stages. `reachedSet` stops at the
first sweep that adds nothing, which is therefore some `iter j` from which
every later sweep stays put.
-/

namespace Tik

theorem contains_iff (r : List StageId) (s : StageId) : r.contains s = true ↔ s ∈ r := by
  simp

theorem contains_ext {r r' : List StageId} (h : ∀ s, s ∈ r ↔ s ∈ r') : r.contains = r'.contains := by
  funext s
  apply Bool.eq_iff_iff.mpr
  rw [contains_iff, contains_iff, h]

section
variable (p : Process) (F : Facts) (mem : Members)

/-- A sweep with an empty carry. -/
def stepE (r : List StageId) : List StageId :=
  r ++ (p.stages.filter (Stage.fires F mem r [])).map (·.id)

theorem step_nil (k : Nat) (r : List StageId) : step p F mem [] k r = stepE p F mem r := by
  simp [step, stepE]

theorem fires_nil_iff (r : List StageId) (st : Stage) :
    Stage.fires F mem r [] st = true ↔
      st.id ∉ r ∧ (∀ a ∈ st.after, a ∈ r) ∧ Guard.evalAll F mem r.contains st.guards = true := by
  have hR : (fun s => r.contains s || ([] : List StageId).contains s) = r.contains := by
    funext s; simp
  simp only [Stage.fires, hR, Bool.and_eq_true, Bool.not_eq_true', List.all_eq_true, contains_iff]
  constructor
  · rintro ⟨⟨h1, h2⟩, h3⟩
    refine ⟨?_, h2, h3⟩
    intro hm; rw [(contains_iff r st.id).mpr hm] at h1; exact absurd h1 (by simp)
  · rintro ⟨h1, h2, h3⟩
    refine ⟨⟨?_, h2⟩, h3⟩
    cases h : r.contains st.id
    · rfl
    · exact absurd ((contains_iff r st.id).mp h) h1

theorem mem_stepE (r : List StageId) (s : StageId) :
    s ∈ stepE p F mem r ↔ s ∈ r ∨ ∃ st ∈ p.stages, st.id = s ∧ Stage.fires F mem r [] st = true := by
  simp only [stepE, List.mem_append, List.mem_map, List.mem_filter]
  constructor
  · rintro (h | ⟨st, ⟨hst, hf⟩, hid⟩)
    · exact Or.inl h
    · exact Or.inr ⟨st, hst, hid, hf⟩
  · rintro (h | ⟨st, hst, hid, hf⟩)
    · exact Or.inl h
    · exact Or.inr ⟨st, ⟨hst, hf⟩, hid⟩

theorem sub_stepE (r : List StageId) : ∀ s ∈ r, s ∈ stepE p F mem r :=
  fun s h => (mem_stepE p F mem r s).mpr (Or.inl h)

theorem stepE_congr {r r' : List StageId} (h : ∀ s, s ∈ r ↔ s ∈ r') :
    ∀ s, s ∈ stepE p F mem r ↔ s ∈ stepE p F mem r' := by
  have hc := contains_ext h
  have hf : Stage.fires F mem r [] = Stage.fires F mem r' [] := by
    funext st; unfold Stage.fires; rw [hc]
  intro s
  rw [mem_stepE, mem_stepE, hf, h]

/-- `n` sweeps from the snapshot `r`. -/
def iterFrom (r : List StageId) : Nat → List StageId
  | 0 => r
  | n + 1 => stepE p F mem (iterFrom r n)

/-- The snapshot after `n` sweeps from nothing. -/
abbrev iter (n : Nat) : List StageId := iterFrom p F mem [] n

theorem iterFrom_shift (r : List StageId) : ∀ n, iterFrom p F mem (stepE p F mem r) n = iterFrom p F mem r (n + 1)
  | 0 => rfl
  | n + 1 => by
    show stepE p F mem (iterFrom p F mem (stepE p F mem r) n) = stepE p F mem (iterFrom p F mem r (n + 1))
    rw [iterFrom_shift r n]

theorem iterFrom_add (r : List StageId) (a : Nat) : ∀ b, iterFrom p F mem r (a + b) = iterFrom p F mem (iterFrom p F mem r a) b
  | 0 => rfl
  | b + 1 => by
    show stepE p F mem (iterFrom p F mem r (a + b)) = stepE p F mem (iterFrom p F mem (iterFrom p F mem r a) b)
    rw [iterFrom_add r a b]

theorem iter_mono {n m : Nat} (h : n ≤ m) : ∀ s ∈ iter p F mem n, s ∈ iter p F mem m := by
  induction m with
  | zero => have : n = 0 := by omega
            subst this; exact fun s hs => hs
  | succ m ih =>
    intro s hs
    rcases Nat.lt_or_ge n (m + 1) with hlt | hmore
    · exact sub_stepE p F mem _ s (ih (by omega) s hs)
    · have : n = m + 1 := by omega
      subst this; exact hs

/-- A snapshot that a sweep does not grow stays put forever. -/
theorem stable_forever {r : List StageId} (hst : ∀ s ∈ stepE p F mem r, s ∈ r) :
    ∀ n s, s ∈ iterFrom p F mem r n ↔ s ∈ r := by
  intro n
  induction n with
  | zero => intro s; exact Iff.rfl
  | succ n ih =>
    intro s
    show s ∈ stepE p F mem (iterFrom p F mem r n) ↔ s ∈ r
    rw [stepE_congr p F mem ih]
    exact ⟨hst s, sub_stepE p F mem r s⟩

/-! ## Termination -/

def ids : List StageId := p.stages.map (·.id)

theorem stepE_sub_ids (r : List StageId) (s : StageId) (h : s ∈ stepE p F mem r) : s ∈ r ∨ s ∈ ids p := by
  rcases (mem_stepE p F mem r s).mp h with h | ⟨st, hst, hid, _⟩
  · exact Or.inl h
  · exact Or.inr (by simp only [ids, List.mem_map]; exact ⟨st, hst, hid⟩)

/-- How many stage ids the snapshot still lacks. -/
def missing (L r : List StageId) : Nat := (L.filter (fun s => !r.contains s)).length

theorem len_filter_cons_true {y : StageId} {r : List StageId} (L : List StageId) (h : r.contains y = true) :
    ((y :: L).filter (fun s => !r.contains s)).length = (L.filter (fun s => !r.contains s)).length := by
  rw [List.filter_cons, h]; rfl

theorem len_filter_cons_false {y : StageId} {r : List StageId} (L : List StageId) (h : r.contains y = false) :
    ((y :: L).filter (fun s => !r.contains s)).length = (L.filter (fun s => !r.contains s)).length + 1 := by
  rw [List.filter_cons, h]; rfl

theorem missing_le (L : List StageId) {r r' : List StageId} (hsub : ∀ s ∈ r, s ∈ r') :
    missing L r' ≤ missing L r := by
  induction L with
  | nil => simp [missing]
  | cons y L ih =>
    unfold missing at *
    cases h1 : r.contains y
    · rw [len_filter_cons_false L h1]
      cases h2 : r'.contains y
      · rw [len_filter_cons_false L h2]; omega
      · rw [len_filter_cons_true L h2]; omega
    · have h2 : r'.contains y = true := (contains_iff r' y).mpr (hsub y ((contains_iff r y).mp h1))
      rw [len_filter_cons_true L h1, len_filter_cons_true L h2]; omega

theorem missing_lt (L : List StageId) {r r' : List StageId} (hsub : ∀ s ∈ r, s ∈ r')
    {x : StageId} (hx : x ∈ L) (hx' : x ∈ r') (hxn : x ∉ r) : missing L r' < missing L r := by
  induction L with
  | nil => simp at hx
  | cons y L ih =>
    have hle := missing_le L hsub
    unfold missing at *
    by_cases hy : x = y
    · subst hy
      have hxr : r.contains x = false := by
        cases h : r.contains x
        · rfl
        · exact absurd ((contains_iff r x).mp h) hxn
      rw [len_filter_cons_false L hxr, len_filter_cons_true L ((contains_iff r' x).mpr hx')]; omega
    · have hxL : x ∈ L := by simpa [hy] using hx
      have ih' := ih hxL
      cases h1 : r.contains y
      · rw [len_filter_cons_false L h1]
        cases h2 : r'.contains y
        · rw [len_filter_cons_false L h2]; omega
        · rw [len_filter_cons_true L h2]; omega
      · have h2 : r'.contains y = true := (contains_iff r' y).mpr (hsub y ((contains_iff r y).mp h1))
        rw [len_filter_cons_true L h1, len_filter_cons_true L h2]; omega

theorem missing_le_length (L r : List StageId) : missing L r ≤ L.length :=
  List.length_filter_le _ _

theorem loop_nil (fuel k : Nat) (r : List StageId) :
    loop p F mem [] (fuel + 1) k r =
      if (stepE p F mem r).all r.contains then r else loop p F mem [] fuel (k + 1) (stepE p F mem r) := by
  simp [loop, step_nil]

theorem loop_spec : ∀ (fuel k : Nat) (r : List StageId), (∀ s ∈ r, s ∈ ids p) →
    missing (ids p) r < fuel →
    ∃ j, loop p F mem [] fuel k r = iterFrom p F mem r j ∧
      ∀ s ∈ stepE p F mem (iterFrom p F mem r j), s ∈ iterFrom p F mem r j
  | 0, _, _, _, h => absurd h (Nat.not_lt_zero _)
  | fuel + 1, k, r, hids, hfuel => by
    rw [loop_nil]
    split
    · rename_i hall
      refine ⟨0, rfl, ?_⟩
      intro s hs
      exact (contains_iff r s).mp ((List.all_eq_true.mp hall) s hs)
    · rename_i hall
      have : ∃ x ∈ stepE p F mem r, x ∉ r := by
        apply Classical.byContradiction
        intro hno
        apply hall
        apply List.all_eq_true.mpr
        intro x hx
        apply (contains_iff r x).mpr
        apply Classical.byContradiction
        intro hxn
        exact hno ⟨x, hx, hxn⟩
      obtain ⟨x, hx, hxn⟩ := this
      have hxids : x ∈ ids p := (stepE_sub_ids p F mem r x hx).resolve_left hxn
      have hlt := missing_lt (ids p) (sub_stepE p F mem r) hxids hx hxn
      have hids' : ∀ s ∈ stepE p F mem r, s ∈ ids p := by
        intro s hs
        rcases stepE_sub_ids p F mem r s hs with h | h
        · exact hids s h
        · exact h
      obtain ⟨j, hj, hstable⟩ := loop_spec fuel (k + 1) (stepE p F mem r) hids' (by omega)
      refine ⟨j + 1, ?_, ?_⟩
      · rw [hj, iterFrom_shift]
      · rw [← iterFrom_shift]; exact hstable

/-- With an empty carry, `reachedSet` is the snapshot of some sweep `j`
after which nothing changes. -/
theorem reachedSet_nil_spec :
    ∃ j, reachedSet p F mem [] = iter p F mem j ∧
      ∀ n, j ≤ n → ∀ s, s ∈ iter p F mem n ↔ s ∈ iter p F mem j := by
  have h := loop_spec p F mem (fuelFor p []) 0 [] (by simp)
    (by have := missing_le_length (ids p) []; simp [fuelFor, ids] at this ⊢; omega)
  obtain ⟨j, hj, hst⟩ := h
  refine ⟨j, hj, ?_⟩
  intro n hn s
  have : n = j + (n - j) := by omega
  rw [this]; show s ∈ iterFrom p F mem [] (j + (n - j)) ↔ s ∈ iterFrom p F mem [] j
  rw [iterFrom_add]
  exact stable_forever p F mem hst (n - j) s

end

end Tik
