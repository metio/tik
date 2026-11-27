/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import Lean.Data.Json
import TikModel.Model

/-!
# The interchange format

One JSON object per line, written by `test/tik/lean_oracle.clj`:

```
{"id": "...", "mode": "fixpoint" | "derive",
 "process": {"stages": [{"id": ":a", "after": [":b"], "sticky": false,
                         "guards": [<guard>, ...]}]},
 "roles": {":r": ["\"seb\""]},
 "facts": [{"path": "[:f0]", "value": ":a", "by": "\"seb\"", "valid": true}],
 "base": [":c"],
 "events": [{"at": 1700000000000000000, "id": "sha256-...", "kind": "assert",
             "path": "[:f0]", "value": ":a", "by": "\"seb\"", "valid": true},
            {"at": ..., "id": ..., "kind": "retract", "path": "[:f0]"},
            {"at": ..., "id": ..., "kind": "other"}]}
```

A guard is an array: `["fact", p]`, `["fact=", p, v]`, `["signed-by", r, p]`,
`["different-person", p, q]`, `["stage-reached", s]`, `["const", b]`,
`["not", g]`, `["and", g, ...]`, `["or", g, ...]`. Every string is the
canonical EDN of the kernel's value. `facts` and `base` are read in fixpoint
mode, `events` in derive mode.
-/

namespace Tik.Interchange

open Lean (Json toJson)

inductive Mode where
  | fixpoint
  | derive
  deriving Repr, DecidableEq

structure Case where
  id : String
  mode : Mode
  process : Process
  roles : List (Role × List Actor)
  facts : List (Path × Fact)
  base : List StageId
  events : List Event

def Case.members (c : Case) : Members := fun r =>
  match c.roles.find? (·.1 == r) with
  | some (_, ms) => ms
  | none => []

def Case.factView (c : Case) : Facts := fun p =>
  (c.facts.find? (·.1 == p)).map (·.2)

private def field (j : Json) (k : String) : Except String Json := j.getObjVal? k

private def strs (j : Json) : Except String (List String) := do
  let a ← j.getArr?
  a.toList.mapM (·.getStr?)

private def optField (j : Json) (k : String) : Json :=
  (j.getObjVal? k).toOption.getD Json.null

partial def parseGuard (j : Json) : Except String Guard := do
  let a := (← j.getArr?).toList
  match a with
  | [] => throw "empty guard"
  | op :: args =>
    let op ← op.getStr?
    match op, args with
    | "fact", [p] => return .fact (← p.getStr?)
    | "fact=", [p, v] => return .factEq (← p.getStr?) (← v.getStr?)
    | "signed-by", [r, p] => return .signedBy (← r.getStr?) (← p.getStr?)
    | "different-person", [p, q] => return .diffPerson (← p.getStr?) (← q.getStr?)
    | "stage-reached", [s] => return .reached (← s.getStr?)
    | "const", [b] => return .const (← b.getBool?)
    | "not", [g] => return .not (← parseGuard g)
    | "and", gs => return .all (← gs.mapM parseGuard)
    | "or", gs => return .any (← gs.mapM parseGuard)
    | _, _ => throw s!"unknown guard {j.compress}"

def parseStage (j : Json) : Except String Stage := do
  let guards ← (← (← field j "guards").getArr?).toList.mapM parseGuard
  return { id := ← (← field j "id").getStr?
           after := ← strs (← field j "after")
           guards := guards
           sticky := ← (← field j "sticky").getBool? }

def parseFact (j : Json) : Except String Fact := do
  return { value := ← (← field j "value").getStr?
           by_ := ← (← field j "by").getStr?
           valid := ← (← field j "valid").getBool? }

def parseEvent (j : Json) : Except String Event := do
  let kind ← match ← (← field j "kind").getStr? with
    | "assert" => pure (Kind.assert (← (← field j "path").getStr?) (← parseFact j))
    | "retract" => pure (Kind.retract (← (← field j "path").getStr?))
    | "other" => pure Kind.other
    | k => throw s!"unknown event kind {k}"
  return { time := ← (← field j "at").getInt?, id := ← (← field j "id").getStr?, kind := kind }

def parseCase (j : Json) : Except String Case := do
  let mode ← match ← (← field j "mode").getStr? with
    | "fixpoint" => pure Mode.fixpoint
    | "derive" => pure Mode.derive
    | m => throw s!"unknown mode {m}"
  let proc ← field j "process"
  let stages ← (← (← field proc "stages").getArr?).toList.mapM parseStage
  let roles ← match optField j "roles" with
    | Json.null => pure []
    | r => do
      let o ← r.getObj?
      o.toList.mapM (fun (k, v) => do return (k, ← strs v))
  let facts ← match optField j "facts" with
    | Json.null => pure []
    | fs => do
      (← fs.getArr?).toList.mapM (fun f => do return (← (← field f "path").getStr?, ← parseFact f))
  let base ← match optField j "base" with
    | Json.null => pure []
    | b => strs b
  let events ← match optField j "events" with
    | Json.null => pure []
    | es => do (← es.getArr?).toList.mapM parseEvent
  return { id := ← (← field j "id").getStr?, mode, process := { stages }, roles, facts, base, events }

/-! ## Writing, for the round trip -/

private def strArr (xs : List String) : Json := Json.arr (xs.map Json.str).toArray

partial def guardJson : Guard → Json
  | .fact p => strArr ["fact", p]
  | .factEq p v => strArr ["fact=", p, v]
  | .signedBy r p => strArr ["signed-by", r, p]
  | .diffPerson p q => strArr ["different-person", p, q]
  | .reached s => strArr ["stage-reached", s]
  | .const b => Json.arr #[Json.str "const", Json.bool b]
  | .not g => Json.arr #[Json.str "not", guardJson g]
  | .all gs => Json.arr ((Json.str "and") :: gs.map guardJson).toArray
  | .any gs => Json.arr ((Json.str "or") :: gs.map guardJson).toArray

def stageJson (st : Stage) : Json :=
  Json.mkObj [("id", st.id), ("after", strArr st.after),
    ("guards", Json.arr (st.guards.map guardJson).toArray), ("sticky", st.sticky)]

def factFields (f : Fact) : List (String × Json) :=
  [("value", Json.str f.value), ("by", Json.str f.by_), ("valid", Json.bool f.valid)]

def eventJson (e : Event) : Json :=
  let common : List (String × Json) := [("at", toJson e.time), ("id", Json.str e.id)]
  match e.kind with
  | .assert p f => Json.mkObj (common ++ [("kind", Json.str "assert"), ("path", Json.str p)] ++ factFields f)
  | .retract p => Json.mkObj (common ++ [("kind", Json.str "retract"), ("path", Json.str p)])
  | .other => Json.mkObj (common ++ [("kind", Json.str "other")])

def caseJson (c : Case) : Json :=
  Json.mkObj [
    ("id", c.id),
    ("mode", match c.mode with | .fixpoint => "fixpoint" | .derive => "derive"),
    ("process", Json.mkObj [("stages", Json.arr (c.process.stages.map stageJson).toArray)]),
    ("roles", Json.mkObj (c.roles.map (fun (r, ms) => (r, strArr ms)))),
    ("facts", Json.arr (c.facts.map (fun (p, f) => Json.mkObj ([("path", Json.str p)] ++ factFields f))).toArray),
    ("base", strArr c.base),
    ("events", Json.arr (c.events.map eventJson).toArray)]

end Tik.Interchange
