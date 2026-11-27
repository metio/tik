/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel.Model

/-!
# Deliberately wrong derivations

Each variant breaks the model in one way the differential oracle must notice.
`bb lean` runs the oracle against every one of them and fails when a variant
agrees with the Clojure kernel on every case. None of them is used by a
theorem.
-/

namespace Tik.Mutant

inductive Variant where
  /-- Fire one enabled stage per sweep, the evaluator `spec/ChaoticFixpoint.tla`
  refutes. -/
  | chaotic
  /-- Seed the sticky carry into the first snapshot, the defect ADR 0032
  records. -/
  | seedCarry
  /-- Ignore `:after`. -/
  | noAfter
  /-- Ignore retractions. -/
  | keepRetracted
  deriving Repr, DecidableEq

def Variant.parse : String → Option Variant
  | "chaotic" => some .chaotic
  | "seed-carry" => some .seedCarry
  | "no-after" => some .noAfter
  | "keep-retracted" => some .keepRetracted
  | _ => none

def fires (v : Variant) (F : Facts) (mem : Members) (reached base : List StageId)
    (st : Stage) : Bool :=
  let prereq := if v == .seedCarry then reached ++ base else reached
  !reached.contains st.id &&
    (v == .noAfter || st.after.all prereq.contains) &&
    Guard.evalAll F mem (fun s => reached.contains s || base.contains s) st.guards

def step (v : Variant) (p : Process) (F : Facts) (mem : Members) (base : List StageId)
    (k : Nat) (reached : List StageId) : List StageId :=
  let fired := (p.stages.filter (fires v F mem reached base)).map (·.id)
  reached ++ base.filter (fun s => decide (p.depthOf s ≤ k) && !reached.contains s) ++
    (if v == .chaotic then fired.take 1 else fired)

def loop (v : Variant) (p : Process) (F : Facts) (mem : Members) (base : List StageId) :
    Nat → Nat → List StageId → List StageId
  | 0, _, r => r
  | fuel + 1, k, r =>
    let r' := step v p F mem base k r
    if r'.all r.contains && base.all r.contains then r else loop v p F mem base fuel (k + 1) r'

def reachedSet (v : Variant) (p : Process) (F : Facts) (mem : Members)
    (base : List StageId) : List StageId :=
  loop v p F mem base (fuelFor p base) 0 []

def factsOf (v : Variant) (es : List Event) : Facts := fun path =>
  es.foldl (fun acc e => match e.kind with
    | .assert p f => if p == path then some f else acc
    | .retract p => if p == path && v != .keepRetracted then none else acc
    | .other => acc) none

def evolveFrom (v : Variant) (p : Process) (mem : Members) (done : List Event) :
    List Event → List StageId → List StageId
  | [], carry => carry
  | e :: rest, carry =>
    let prefix_ := done ++ [e]
    let r := reachedSet v p (factsOf v prefix_) mem carry
    let carry' := carry ++ (r.filter (fun s => p.isSticky s && !carry.contains s)).eraseDups
    evolveFrom v p mem prefix_ rest carry'

def derive (v : Variant) (p : Process) (mem : Members) (es : List Event) : List StageId :=
  let es' := canon es
  reachedSet v p (factsOf v es') mem (evolveFrom v p mem [] es' [])

end Tik.Mutant
