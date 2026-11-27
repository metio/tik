/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-/
import TikModel

/-!
# The differential oracle

`tik-lean run [--mutant NAME] FILE` reads one case per line (see
`TikModel.Interchange`) and prints, per case, the stages the model derives:
`{"id": ..., "reached": [...], "stratified": bool}`. `tik-lean echo FILE`
prints every case as parsed, for the round trip of the format.
-/

open Lean (Json)
open Tik Tik.Interchange

def sortedIds (xs : List StageId) : List StageId :=
  (xs.eraseDups).mergeSort (fun a b => decide (a ≤ b))

def evalCase (mutant : Option Mutant.Variant) (c : Case) : List StageId :=
  let mem := c.members
  match mutant, c.mode with
  | none, .fixpoint => reachedSet c.process c.factView mem c.base
  | none, .derive => derive c.process mem c.events
  | some v, .fixpoint => Mutant.reachedSet v c.process c.factView mem c.base
  | some v, .derive => Mutant.derive v c.process mem c.events

def runLine (mutant : Option Mutant.Variant) (echo : Bool) (line : String) : Except String String := do
  let j ← Json.parse line
  let c ← parseCase j
  if echo then
    return (caseJson c).compress
  let reached := sortedIds (evalCase mutant c)
  return (Json.mkObj [("id", c.id),
    ("reached", Json.arr (reached.map Json.str).toArray),
    ("stratified", stratCheck c.process)]).compress

def usage : String :=
  "usage: tik-lean run [--mutant chaotic|seed-carry|no-after|keep-retracted] FILE\n       tik-lean echo FILE"

def main (args : List String) : IO UInt32 := do
  let (echo, mutant, file) ← match args with
    | ["run", f] => pure (false, none, f)
    | ["run", "--mutant", m, f] => match Mutant.Variant.parse m with
      | some v => pure (false, some v, f)
      | none => do IO.eprintln s!"unknown mutant {m}\n{usage}"; return 2
    | ["echo", f] => pure (true, none, f)
    | _ => do IO.eprintln usage; return 2
  let contents ← IO.FS.readFile file
  let out ← IO.getStdout
  let mut failed := false
  for line in contents.splitOn "\n" do
    if line.all Char.isWhitespace then continue
    match runLine mutant echo line with
    | .ok s => out.putStrLn s
    | .error e =>
      IO.eprintln s!"tik-lean: {e}"
      failed := true
  return if failed then 1 else 0
