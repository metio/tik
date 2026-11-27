/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Model

/-!
# Stratification

ADR 0033's rule as a certificate: an entry bound `L` (the latest sweep a stage
can enter in) and a depth `D` (the earliest sweep it is judged in). A stage
waits through `:after` and through every `[:stage-reached X]` read at even
negation parity, so each of those has a smaller entry bound; a stage it reads
at odd parity, which only ever blocks it, must have entered for good before
the stage is first judged.

The certificate is stricter than the lint in one place: the lint lets a
positive wait run in a cycle as long as nothing negates a stage on it, and
such a process can have more than one fixpoint (`Tik.Counter.positiveCycle`).
-/

namespace Tik

mutual
/-- The stages `g` reads at parity `pol`: `true` for an even number of
enclosing `:not`, `false` for an odd number. -/
def Guard.refs (pol : Bool) : Guard → List StageId
  | .reached s => if pol then [s] else []
  | .not g => Guard.refs (!pol) g
  | .all gs => Guard.refsList pol gs
  | .any gs => Guard.refsList pol gs
  | .fact _ | .factEq _ _ | .signedBy _ _ | .diffPerson _ _ | .const _ => []

def Guard.refsList (pol : Bool) : List Guard → List StageId
  | [] => []
  | g :: gs => Guard.refs pol g ++ Guard.refsList pol gs
end

def Stage.refs (pol : Bool) (st : Stage) : List StageId := Guard.refsList pol st.guards

structure Cert where
  L : StageId → Nat
  D : StageId → Nat

/-- What the certificate demands of one stage. -/
def Cert.Ok (c : Cert) (st : Stage) : Prop :=
  (st.after = [] → c.D st.id = 0) ∧
  (st.after ≠ [] → ∃ a ∈ st.after, c.D st.id ≤ c.D a + 1) ∧
  c.D st.id ≤ c.L st.id ∧
  (∀ a ∈ st.after, c.L a < c.L st.id) ∧
  (∀ q ∈ st.refs true, c.L q < c.L st.id) ∧
  (∀ q ∈ st.refs false, c.L q < c.D st.id)

def Stratified (p : Process) : Prop := ∃ c : Cert, ∀ st ∈ p.stages, c.Ok st

/-- The certificate's check as a function, for a concrete one. -/
def Cert.okB (c : Cert) (st : Stage) : Bool :=
  (!st.after.isEmpty || c.D st.id == 0) &&
  (st.after.isEmpty || st.after.any (fun a => decide (c.D st.id ≤ c.D a + 1))) &&
  decide (c.D st.id ≤ c.L st.id) &&
  st.after.all (fun a => decide (c.L a < c.L st.id)) &&
  (st.refs true).all (fun q => decide (c.L q < c.L st.id)) &&
  (st.refs false).all (fun q => decide (c.L q < c.D st.id))

theorem Cert.okB_sound (c : Cert) (st : Stage) (h : c.okB st = true) : c.Ok st := by
  simp only [Cert.okB, Bool.and_eq_true, Bool.or_eq_true, Bool.not_eq_true',
    List.isEmpty_eq_false_iff, List.isEmpty_iff, beq_iff_eq, decide_eq_true_eq,
    List.any_eq_true, List.all_eq_true] at h
  obtain ⟨⟨⟨⟨⟨h1, h2⟩, h3⟩, h4⟩, h5⟩, h6⟩ := h
  refine ⟨?_, ?_, h3, h4, h5, h6⟩
  · intro he
    rcases h1 with h1 | h1
    · exact absurd he h1
    · exact h1
  · intro hne
    rcases h2 with h2 | h2
    · exact absurd h2 hne
    · exact h2

/-- The latest sweep a stage can enter in, computed with `fuel` steps. -/
def Process.entryAux (p : Process) : Nat → StageId → Nat
  | 0, _ => 0
  | n + 1, s =>
    p.stages.foldl (fun m st =>
      if st.id == s then
        (st.after ++ st.refs true).foldl (fun m' x => max m' (p.entryAux n x + 1)) m
      else m) (p.depthOf s)

/-- The certificate built from the kernel's depth and the entry bound. -/
def Process.cert (p : Process) : Cert :=
  { L := p.entryAux (p.stages.length + 1), D := p.depthOf }

/-- Whether the process is stratified, judged by the certificate the model
builds for it. -/
def stratCheck (p : Process) : Bool := p.stages.all (p.cert.okB)

theorem stratCheck_sound (p : Process) (h : stratCheck p = true) : Stratified p := by
  refine ⟨p.cert, fun st hst => Cert.okB_sound _ _ ?_⟩
  exact (List.all_eq_true.mp h) st hst

end Tik
