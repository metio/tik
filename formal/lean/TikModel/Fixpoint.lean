/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Sweep

/-!
# Theorem (b): a stratified process has exactly one fixpoint

A set `M` of stages is a fixpoint when a stage is in it exactly when some
definition of that stage has every `:after` stage in `M` and its guards hold
reading `M`. For a stratified process there is exactly one, and the
synchronous sweep from nothing computes it.

The proof follows `spec/SweepFixpointInductive.tla`: the model is built
stratum by stratum (`approx`), and the sweep's snapshot is related to it by
three invariants, which together are inductive where "a terminal state is the
model" is not:

* (A) every stage in the snapshot is in the model;
* (B) a stage of the model whose entry bound is below the sweep count is in
  the snapshot;
* (C) a stage in the snapshot has a depth below the sweep count.
-/

namespace Tik

def IsFixpoint (p : Process) (F : Facts) (mem : Members) (M : StageId → Bool) : Prop :=
  ∀ s, M s = true ↔ ∃ st ∈ p.stages, st.id = s ∧ st.after.all M = true ∧
    Guard.evalAll F mem M st.guards = true

section
variable (p : Process) (F : Facts) (mem : Members) (c : Cert)

/-- Whether some definition of `s` is enabled reading `R`. -/
def enabledBy (R : StageId → Bool) (s : StageId) : Bool :=
  p.stages.any (fun st => st.id == s && st.after.all R && Guard.evalAll F mem R st.guards)

theorem enabledBy_iff (R : StageId → Bool) (s : StageId) :
    enabledBy p F mem R s = true ↔ ∃ st ∈ p.stages, st.id = s ∧ st.after.all R = true ∧
      Guard.evalAll F mem R st.guards = true := by
  simp only [enabledBy, List.any_eq_true, Bool.and_eq_true, beq_iff_eq]
  constructor
  · rintro ⟨st, hst, ⟨h1, h2⟩, h3⟩; exact ⟨st, hst, h1, h2, h3⟩
  · rintro ⟨st, hst, h1, h2, h3⟩; exact ⟨st, hst, ⟨h1, h2⟩, h3⟩

/-- The model below stratum `n`: a stage enters at the stratum of its entry
bound, judged against the strata before it. -/
def approx : Nat → StageId → Bool
  | 0, _ => false
  | n + 1, s => approx n s || (decide (c.L s = n) && enabledBy p F mem (approx n) s)

theorem approx_lt : ∀ n s, approx p F mem c n s = true → c.L s < n
  | 0, _, h => by simp [approx] at h
  | n + 1, s, h => by
    simp only [approx, Bool.or_eq_true, Bool.and_eq_true, decide_eq_true_eq] at h
    rcases h with h | ⟨h, _⟩
    · have := approx_lt n s h; omega
    · omega

theorem approx_stable (s : StageId) : ∀ n, c.L s < n → approx p F mem c n s = approx p F mem c (c.L s + 1) s := by
  intro n hn
  induction n with
  | zero => omega
  | succ n ih =>
    rcases Nat.lt_or_ge (c.L s) n with h | h
    · have hne : c.L s ≠ n := by omega
      have hstep : approx p F mem c (n + 1) s = approx p F mem c n s := by
        simp only [approx, hne, decide_false, Bool.false_and, Bool.or_false]
      rw [hstep]
      exact ih h
    · have : c.L s = n := by omega
      rw [this]

/-- The model. -/
def model (s : StageId) : Bool := approx p F mem c (c.L s + 1) s

theorem approx_eq_model (s : StageId) (n : Nat) (h : c.L s < n) : approx p F mem c n s = model p F mem c s :=
  approx_stable p F mem c s n h

theorem model_iff (s : StageId) : model p F mem c s = true ↔ enabledBy p F mem (approx p F mem c (c.L s)) s = true := by
  have h0 : approx p F mem c (c.L s) s = false := by
    cases h : approx p F mem c (c.L s) s
    · rfl
    · have := approx_lt p F mem c _ s h; omega
  simp [model, approx, h0]

theorem model_isFixpoint (hc : ∀ st ∈ p.stages, c.Ok st) : IsFixpoint p F mem (model p F mem c) := by
  intro s
  rw [model_iff, enabledBy_iff]
  constructor
  · rintro ⟨st, hst, hid, ha, hg⟩
    obtain ⟨_, _, hDL, hL, hpos, hneg⟩ := hc st hst
    subst hid
    refine ⟨st, hst, rfl, ?_, ?_⟩
    · rw [List.all_eq_true] at ha ⊢
      intro a hamem
      rw [← approx_eq_model p F mem c a _ (hL a hamem)]; exact ha a hamem
    · rw [← hg]
      apply Guard.evalAll_congr
      · intro q hq; exact (approx_eq_model p F mem c q (c.L st.id) (hpos q hq)).symm
      · intro q hq; exact (approx_eq_model p F mem c q (c.L st.id) (by have := hneg q hq; omega)).symm
  · rintro ⟨st, hst, hid, ha, hg⟩
    obtain ⟨_, _, hDL, hL, hpos, hneg⟩ := hc st hst
    subst hid
    refine ⟨st, hst, rfl, ?_, ?_⟩
    · rw [List.all_eq_true] at ha ⊢
      intro a hamem
      rw [approx_eq_model p F mem c a _ (hL a hamem)]; exact ha a hamem
    · rw [← hg]
      apply Guard.evalAll_congr
      · intro q hq; exact (approx_eq_model p F mem c q (c.L st.id) (hpos q hq))
      · intro q hq; exact (approx_eq_model p F mem c q (c.L st.id) (by have := hneg q hq; omega))

/-- Every fixpoint is the model. -/
theorem fixpoint_eq_model (hc : ∀ st ∈ p.stages, c.Ok st) (M : StageId → Bool)
    (hM : IsFixpoint p F mem M) : M = model p F mem c := by
  have hmodel := model_isFixpoint p F mem c hc
  funext s
  suffices h : ∀ n, ∀ s, c.L s = n → M s = model p F mem c s from h _ s rfl
  intro n
  induction n using Nat.strongRecOn with
  | _ n ih =>
    intro s hs
    apply Bool.eq_iff_iff.mpr
    rw [hM s, hmodel s]
    have key : ∀ st ∈ p.stages, st.id = s →
        (st.after.all M = true ∧ Guard.evalAll F mem M st.guards = true ↔
         st.after.all (model p F mem c) = true ∧ Guard.evalAll F mem (model p F mem c) st.guards = true) := by
      intro st hst hid
      obtain ⟨_, _, hDL, hL, hpos, hneg⟩ := hc st hst
      subst hid
      have hall : st.after.all M = st.after.all (model p F mem c) := by
        have hM' : ∀ a ∈ st.after, M a = model p F mem c a :=
          fun a ha => ih _ (by rw [← hs]; exact hL a ha) a rfl
        apply Bool.eq_iff_iff.mpr
        simp only [List.all_eq_true]
        exact ⟨fun h a ha => (hM' a ha) ▸ h a ha, fun h a ha => (hM' a ha).symm ▸ h a ha⟩
      have hg : Guard.evalAll F mem M st.guards = Guard.evalAll F mem (model p F mem c) st.guards := by
        apply Guard.evalAll_congr
        · intro q hq; exact ih _ (by rw [← hs]; exact hpos q hq) q rfl
        · intro q hq; exact ih _ (by have := hneg q hq; omega) q rfl
      rw [hall, hg]
    constructor
    · rintro ⟨st, hst, hid, h⟩; exact ⟨st, hst, hid, (key st hst hid).mp h⟩
    · rintro ⟨st, hst, hid, h⟩; exact ⟨st, hst, hid, (key st hst hid).mpr h⟩

/-! ## The sweep computes the model -/

theorem sweep_invariant (hc : ∀ st ∈ p.stages, c.Ok st) : ∀ k,
    (∀ s ∈ iter p F mem k, model p F mem c s = true) ∧
    (∀ s, model p F mem c s = true → c.L s < k → s ∈ iter p F mem k) ∧
    (∀ s ∈ iter p F mem k, c.D s < k) := by
  have hfix := model_isFixpoint p F mem c hc
  intro k
  induction k with
  | zero =>
    refine ⟨fun s h => by simp [iterFrom] at h, fun s _ h => absurd h (Nat.not_lt_zero _),
      fun s h => by simp [iterFrom] at h⟩
  | succ k ih =>
    obtain ⟨hA, hB, hC⟩ := ih
    let r := iter p F mem k
    have hr : iter p F mem (k + 1) = stepE p F mem r := rfl
    -- (C) for a stage that fires in sweep k + 1
    have hC' : ∀ st ∈ p.stages, Stage.fires F mem r [] st = true → c.D st.id ≤ k := by
      intro st hst hf
      obtain ⟨_, hafter, _⟩ := (fires_nil_iff F mem r st).mp hf
      obtain ⟨h0, h1, _⟩ := hc st hst
      by_cases he : st.after = []
      · rw [h0 he]; omega
      · obtain ⟨a, ha, hle⟩ := h1 he
        have := hC a (hafter a ha); omega
    refine ⟨?_, ?_, ?_⟩
    · -- (A)
      intro s hs
      rw [hr, mem_stepE] at hs
      rcases hs with hs | ⟨st, hst, hid, hf⟩
      · exact hA s hs
      · have hD := hC' st hst hf
        obtain ⟨_, hafter, hg⟩ := (fires_nil_iff F mem r st).mp hf
        obtain ⟨_, _, _, _, _, hneg⟩ := hc st hst
        apply (hfix s).mpr
        refine ⟨st, hst, hid, ?_, ?_⟩
        · exact List.all_eq_true.mpr (fun a ha => hA a (hafter a ha))
        · apply Guard.evalAll_mono F mem st.guards r.contains (model p F mem c) _ _ hg
          · intro q _ hq; exact hA q ((contains_iff r q).mp hq)
          · intro q hq hm
            exact (contains_iff r q).mpr (hB q hm (by have := hneg q hq; omega))
    · -- (B)
      intro s hm hlt
      rw [hr, mem_stepE]
      by_cases hin : s ∈ r
      · exact Or.inl hin
      · right
        obtain ⟨st, hst, hid, ha, hg⟩ := (hfix s).mp hm
        obtain ⟨_, _, hDL, hL, hpos, hneg⟩ := hc st hst
        subst hid
        refine ⟨st, hst, rfl, (fires_nil_iff F mem r st).mpr ⟨hin, ?_, ?_⟩⟩
        · intro a hamem
          exact hB a ((List.all_eq_true.mp ha) a hamem) (by have := hL a hamem; omega)
        · apply Guard.evalAll_mono F mem st.guards (model p F mem c) r.contains _ _ hg
          · intro q hq hmq
            exact (contains_iff r q).mpr (hB q hmq (by have := hpos q hq; omega))
          · intro q _ hq; exact hA q ((contains_iff r q).mp hq)
    · -- (C)
      intro s hs
      rw [hr, mem_stepE] at hs
      rcases hs with hs | ⟨st, hst, hid, hf⟩
      · have := hC s hs; omega
      · have := hC' st hst hf; rw [← hid]; omega

/-- With an empty carry, the reached set of a stratified process is its
model. -/
theorem reachedSet_eq_model (hc : ∀ st ∈ p.stages, c.Ok st) (s : StageId) :
    s ∈ reachedSet p F mem [] ↔ model p F mem c s = true := by
  obtain ⟨j, hj, hstable⟩ := reachedSet_nil_spec p F mem
  rw [hj]
  constructor
  · intro h; exact (sweep_invariant p F mem c hc j).1 s h
  · intro h
    have hin := (sweep_invariant p F mem c hc (max j (c.L s + 1))).2.1 s h (by omega)
    exact (hstable _ (by omega) s).mp hin

end

/-- **Theorem (b).** A stratified process has exactly one fixpoint, and with an
empty sticky carry `reachedSet` is it. -/
theorem stratified_unique_fixpoint (p : Process) (F : Facts) (mem : Members) (h : Stratified p) :
    (∀ M₁ M₂, IsFixpoint p F mem M₁ → IsFixpoint p F mem M₂ → M₁ = M₂) ∧
    IsFixpoint p F mem (reachedSet p F mem []).contains := by
  obtain ⟨c, hc⟩ := h
  refine ⟨fun M₁ M₂ h₁ h₂ => ?_, ?_⟩
  · rw [fixpoint_eq_model p F mem c hc M₁ h₁, fixpoint_eq_model p F mem c hc M₂ h₂]
  · have : (reachedSet p F mem []).contains = model p F mem c := by
      funext s
      apply Bool.eq_iff_iff.mpr
      rw [contains_iff]
      exact reachedSet_eq_model p F mem c hc s
    rw [this]
    exact model_isFixpoint p F mem c hc

/-- Theorem (b) for every process the model's own check judges stratified. -/
theorem stratCheck_unique_fixpoint (p : Process) (F : Facts) (mem : Members) (h : stratCheck p = true) :
    (∀ M₁ M₂, IsFixpoint p F mem M₁ → IsFixpoint p F mem M₂ → M₁ = M₂) ∧
    IsFixpoint p F mem (reachedSet p F mem []).contains :=
  stratified_unique_fixpoint p F mem (stratCheck_sound p h)

end Tik
