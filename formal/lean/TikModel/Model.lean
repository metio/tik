/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/

/-!
# The derivation function

An executable model of `tik.stage/reached-set`, `tik.stage/evolve` and
`tik.stage/effective-reached` over the part of the guard vocabulary whose
answer depends on facts, roles and stages: `:fact`, `:fact=`, `:signed-by`,
`:different-person`, `:stage-reached`, `:and`, `:or` and `:not`. Every other
guard is a `const` leaf whose truth the caller supplies, since it is fixed for
one evaluation of the fixpoint.

Stage ids, fact paths, values and actors are the canonical EDN strings of the
kernel's values, so equality here is equality of canonical bytes.
-/

namespace Tik

abbrev StageId := String
abbrev Path := String
abbrev Val := String
abbrev Actor := String
abbrev Role := String

/-- A present fact: its value, who asserted it, and whether the value satisfies
the schema the definition declares for its path (`true` when it declares
none). An absent, retracted, disputed or conflicted fact has no entry. -/
structure Fact where
  value : Val
  by_ : Actor
  valid : Bool
  deriving DecidableEq, Repr, Inhabited

abbrev Facts := Path → Option Fact
abbrev Members := Role → List Actor

inductive Guard where
  | fact (p : Path)
  | factEq (p : Path) (v : Val)
  | signedBy (role : Role) (p : Path)
  | diffPerson (p q : Path)
  | reached (s : StageId)
  | const (b : Bool)
  | not (g : Guard)
  | all (gs : List Guard)
  | any (gs : List Guard)
  deriving Repr, Inhabited

structure Stage where
  id : StageId
  after : List StageId
  guards : List Guard
  sticky : Bool
  deriving Repr, Inhabited

structure Process where
  stages : List Stage
  deriving Repr, Inhabited

/-! ## Guards -/

mutual
/-- `tik.guard/eval-guard`'s verdict. `R` answers whether a stage counts as
reached for this evaluation. -/
def Guard.eval (F : Facts) (mem : Members) (R : StageId → Bool) : Guard → Bool
  | .fact p => match F p with
    | some f => f.valid
    | none => false
  | .factEq p v => match F p with
    | some f => f.value == v
    | none => false
  | .signedBy r p => match F p with
    | some f => f.valid && (mem r).contains f.by_
    | none => false
  | .diffPerson p q => match F p, F q with
    | some a, some b => a.by_ != b.by_
    | _, _ => false
  | .reached s => R s
  | .const b => b
  | .not g => !(Guard.eval F mem R g)
  | .all gs => Guard.evalAll F mem R gs
  | .any gs => Guard.evalAny F mem R gs

def Guard.evalAll (F : Facts) (mem : Members) (R : StageId → Bool) : List Guard → Bool
  | [] => true
  | g :: gs => Guard.eval F mem R g && Guard.evalAll F mem R gs

def Guard.evalAny (F : Facts) (mem : Members) (R : StageId → Bool) : List Guard → Bool
  | [] => false
  | g :: gs => Guard.eval F mem R g || Guard.evalAny F mem R gs
end

/-! ## Strata -/

/-- The `:after` list of the stage named `s`; the last definition of an id
wins, as in `(into {} …)`. -/
def Process.afterOf (p : Process) (s : StageId) : Option (List StageId) :=
  (p.stages.reverse.find? (fun st => st.id == s)).map (·.after)

/-- Depth along `:after` with `fuel` steps; `none` for a stage on a cycle, a
stage below one, a stage naming an unknown id, or an unknown id. -/
def Process.depthAux (p : Process) : Nat → StageId → Option Nat
  | 0, _ => none
  | n + 1, s => match p.afterOf s with
    | none => none
    | some [] => some 0
    | some as => (as.mapM (p.depthAux n)).map (fun ds => ds.foldl max 0 + 1)

/-- `tik.stage/strata` as the sweep reads it: a stage without a depth counts
as depth 0. -/
def Process.depthOf (p : Process) (s : StageId) : Nat :=
  (p.depthAux p.stages.length s).getD 0

/-! ## The synchronous sweep -/

/-- Whether stage `st` fires against the snapshot `reached`, with the sticky
carry `base` visible to guards. -/
def Stage.fires (F : Facts) (mem : Members) (reached base : List StageId) (st : Stage) : Bool :=
  !reached.contains st.id && st.after.all reached.contains &&
    Guard.evalAll F mem (fun s => reached.contains s || base.contains s) st.guards

/-- One sweep, number `k`: every stage enabled against the sweep-start
snapshot enters at once, and a carried stage of depth at most `k` joins. -/
def step (p : Process) (F : Facts) (mem : Members) (base : List StageId) (k : Nat)
    (reached : List StageId) : List StageId :=
  reached ++ base.filter (fun s => decide (p.depthOf s ≤ k) && !reached.contains s) ++
    (p.stages.filter (Stage.fires F mem reached base)).map (·.id)

/-- Sweeps until a sweep adds nothing and the whole carry has joined, or the
fuel runs out. -/
def loop (p : Process) (F : Facts) (mem : Members) (base : List StageId) :
    Nat → Nat → List StageId → List StageId
  | 0, _, r => r
  | fuel + 1, k, r =>
    let r' := step p F mem base k r
    if r'.all r.contains && base.all r.contains then r else loop p F mem base fuel (k + 1) r'

def fuelFor (p : Process) (base : List StageId) : Nat :=
  2 * p.stages.length + base.length + 2

/-- `tik.stage/reached-set`. -/
def reachedSet (p : Process) (F : Facts) (mem : Members) (base : List StageId) : List StageId :=
  loop p F mem base (fuelFor p base) 0 []

/-! ## Events, the fold and the read -/

inductive Kind where
  | assert (p : Path) (f : Fact)
  | retract (p : Path)
  | other
  deriving DecidableEq, Repr, Inhabited

/-- An event: its claimed instant (nanoseconds since the epoch), its id and
what it does to the facts. -/
structure Event where
  time : Int
  id : String
  kind : Kind
  deriving DecidableEq, Repr, Inhabited

/-- The `(at, id)` order the reducer folds in. -/
def keyLt (a b : Event) : Prop := a.time < b.time ∨ (a.time = b.time ∧ a.id < b.id)

instance (a b : Event) : Decidable (keyLt a b) := by
  unfold keyLt; exact inferInstance

/-- Insertion into a list ordered by `(at, id)`; an event whose key is
already present is dropped, as the reducer drops a duplicate id. -/
def ins (e : Event) : List Event → List Event
  | [] => [e]
  | x :: xs => if keyLt e x then e :: x :: xs else if keyLt x e then x :: ins e xs else x :: xs

/-- `tik.reduce/ordered`: deduplicated and ordered by `(at, id)`. -/
def canon (l : List Event) : List Event := l.foldr ins []

/-- The effective facts after a fold over `es`: the last write on a path
wins. -/
def factsOf (es : List Event) : Facts := fun path =>
  es.foldl (fun acc e => match e.kind with
    | .assert p f => if p == path then some f else acc
    | .retract p => if p == path then none else acc
    | .other => acc) none

def Process.isSticky (p : Process) (s : StageId) : Bool :=
  p.stages.any (fun st => st.id == s && st.sticky)

/-- The sticky carry after the fold over every prefix of `es`, which must
already be ordered. -/
def evolveFrom (p : Process) (mem : Members) (done : List Event) :
    List Event → List StageId → List StageId
  | [], carry => carry
  | e :: rest, carry =>
    let prefix_ := done ++ [e]
    let r := reachedSet p (factsOf prefix_) mem carry
    let carry' := carry ++ (r.filter (fun s => p.isSticky s && !carry.contains s)).eraseDups
    evolveFrom p mem prefix_ rest carry'

def evolve (p : Process) (mem : Members) (es : List Event) : List StageId :=
  evolveFrom p mem [] es []

/-- `tik.stage/effective-reached`. -/
def derive (p : Process) (mem : Members) (es : List Event) : List StageId :=
  let es' := canon es
  reachedSet p (factsOf es') mem (evolve p mem es')

end Tik
