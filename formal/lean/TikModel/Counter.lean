/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Fixpoint
import TikModel.Monotone

/-!
# Counterexamples

Each of these refutes a stronger statement than the one proved, and says why
the proved statement carries its condition.
-/

namespace Tik.Counter

open Tik

/-! ## Firing one stage at a time depends on the order -/

/-- `processes/sweep-order.edn`: `:d` negates `:c` from a later stratum. -/
def sweepOrder : Process :=
  { stages := [
      { id := ":a", after := [], guards := [], sticky := false },
      { id := ":b", after := [":a"], guards := [], sticky := false },
      { id := ":c", after := [":a"], guards := [], sticky := false },
      { id := ":d", after := [":b"], guards := [.not (.reached ":c")], sticky := false }] }

def noFacts : Facts := fun _ => none
def noMembers : Members := fun _ => []

/-- Fire the named stages one at a time, each only when enabled against the
stages fired so far. -/
def chaoticRun (p : Process) (F : Facts) (mem : Members) : List StageId → List StageId → Option (List StageId)
  | [], r => some r
  | s :: rest, r =>
    if p.stages.any (fun st => st.id == s && Stage.fires F mem r [] st) then
      chaoticRun p F mem rest (r ++ [s])
    else none

def terminal (p : Process) (F : Facts) (mem : Members) (r : List StageId) : Bool :=
  !p.stages.any (Stage.fires F mem r [])

/-- Two orders of firing one stage at a time both end where nothing is
enabled, at different sets; the synchronous sweep derives `{:a, :b, :c}`. -/
theorem chaoticOrderDependent :
    chaoticRun sweepOrder noFacts noMembers [":a", ":b", ":c"] [] = some [":a", ":b", ":c"] ∧
    terminal sweepOrder noFacts noMembers [":a", ":b", ":c"] = true ∧
    chaoticRun sweepOrder noFacts noMembers [":a", ":b", ":d", ":c"] [] = some [":a", ":b", ":d", ":c"] ∧
    terminal sweepOrder noFacts noMembers [":a", ":b", ":d", ":c"] = true ∧
    reachedSet sweepOrder noFacts noMembers [] = [":a", ":b", ":c"] := by
  decide

/-! ## A positive cycle has two fixpoints -/

/-- `:a` waits for `:b` and `:b` for `:a`; the lint accepts it, since nothing
negates either. -/
def positiveCycle : Process :=
  { stages := [
      { id := ":a", after := [], guards := [.reached ":b"], sticky := false },
      { id := ":b", after := [], guards := [.reached ":a"], sticky := false }] }

theorem positiveCycle_two_fixpoints :
    IsFixpoint positiveCycle noFacts noMembers (fun _ => false) ∧
    IsFixpoint positiveCycle noFacts noMembers (fun s => s == ":a" || s == ":b") ∧
    (fun _ => false) ≠ (fun s : StageId => s == ":a" || s == ":b") ∧
    reachedSet positiveCycle noFacts noMembers [] = [] := by
  refine ⟨?_, ?_, ?_, by decide⟩
  · intro s
    simp [positiveCycle, Guard.evalAll, Guard.eval]
  · intro s
    simp only [positiveCycle, List.mem_cons, List.not_mem_nil, or_false]
    constructor
    · intro h
      simp only [Bool.or_eq_true, beq_iff_eq] at h
      rcases h with h | h
      · exact ⟨_, Or.inl rfl, h.symm, by decide, by decide⟩
      · exact ⟨_, Or.inr rfl, h.symm, by decide, by decide⟩
    · rintro ⟨st, hst | hst, hid, _, _⟩ <;> subst hst <;> subst hid <;> decide
  · intro h
    have := congrFun h ":a"
    simp at this

theorem positiveCycle_not_stratified : ¬ Stratified positiveCycle := by
  rintro ⟨c, hc⟩
  have ha := (hc _ (List.mem_cons_self ..)).2.2.2.2.1 ":b" (by decide)
  have hb := (hc _ (List.mem_cons_of_mem _ (List.mem_cons_self ..))).2.2.2.2.1 ":a" (by decide)
  simp only at ha hb
  omega

/-! ## A positively guarded stage can drop out when upstream negation flips -/

/-- `:b` has no guard at all, but waits for `:a`, which negates the fact
`[:x]`. -/
def upstreamNegation : Process :=
  { stages := [
      { id := ":a", after := [], guards := [.not (.fact "[:x]")], sticky := false },
      { id := ":b", after := [":a"], guards := [], sticky := false }] }

def withX : Facts := fun p => if p == "[:x]" then some { value := "true", by_ := "\"seb\"", valid := true } else none

theorem upstreamNegation_drops :
    FactLe noFacts withX ∧
    ":b" ∈ reachedSet upstreamNegation noFacts noMembers [] ∧
    ":b" ∉ reachedSet upstreamNegation withX noMembers [] := by
  refine ⟨fun _ _ h => by simp [noFacts] at h, by decide, by decide⟩

/-! ## A new event can change a fact, which is not adding one -/

def overwriteProcess : Process :=
  { stages := [{ id := ":s", after := [], guards := [.factEq "[:x]" ":a"], sticky := false }] }

def setA : Event := { time := 1, id := "e1", kind := .assert "[:x]" { value := ":a", by_ := "\"seb\"", valid := true } }
def setB : Event := { time := 2, id := "e2", kind := .assert "[:x]" { value := ":b", by_ := "\"seb\"", valid := true } }

theorem overwrite :
    derive overwriteProcess noMembers [setA] = [":s"] ∧
    derive overwriteProcess noMembers [setA, setB] = [] := by
  decide

end Tik.Counter
