/-
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD

Machine-checked proofs of the algebraic laws tik's convergence rests on.

A tik ticket is an append-only, content-addressed event set. Two replicas
sync by a git merge, which is a UNION of event files — conflict-free by
construction (ADR 0004/0007). And derivation is a pure function of that
SET: reduction orders by (at, id) over the set, so it is independent of the
order or multiplicity in which events arrive (the reduce property tests
sample this; here it is PROVED).

We model the event set as a grow-only set (a membership predicate) and
prove: union is commutative, associative, and idempotent — the G-Set CRDT
laws — and therefore any derivation that is a function of the set converges
regardless of merge order (commutativity) and is invariant to duplicate
events (idempotence).

Self-contained: only `funext` and `propext` from Lean 4 core, no mathlib.
Check with:  lean proofs/GSet.lean   (exit 0, no errors = verified)

Attached to the code: this proof holds for any `derive` that is a
function of the set. `test/tik/proof_conformance_test.clj` discharges
that hypothesis for the REAL kernel (its derive is invariant to event
order and multiplicity) and restates `derive_converges` and
`derive_dup_invariant` as equations the real `ticket-state` must
satisfy — so the implementation inherits these theorems rather than
merely resembling them.
-/

/-- A grow-only set over `α`, as a membership predicate. -/
def GSet (α : Type) := α → Prop

namespace GSet

/-- Merge two replicas: an element is present if either replica has it. -/
def union {α} (a b : GSet α) : GSet α := fun x => a x ∨ b x

/-- Merge is commutative: replicas converge regardless of merge direction. -/
theorem union_comm {α} (a b : GSet α) : union a b = union b a := by
  funext x
  exact propext ⟨Or.symm, Or.symm⟩

/-- Merge is associative: multi-way merges converge regardless of grouping. -/
theorem union_assoc {α} (a b c : GSet α) :
    union (union a b) c = union a (union b c) := by
  funext x
  apply propext
  constructor
  · intro h
    exact h.elim
      (fun ab => ab.elim Or.inl (fun hb => Or.inr (Or.inl hb)))
      (fun hc => Or.inr (Or.inr hc))
  · intro h
    exact h.elim
      (fun ha => Or.inl (Or.inl ha))
      (fun bc => bc.elim (fun hb => Or.inl (Or.inr hb)) Or.inr)

/-- Merge is idempotent: re-merging a replica with itself changes nothing —
    the content-addressed union of duplicate events is the same set. -/
theorem union_idem {α} (a : GSet α) : union a a = a := by
  funext x
  exact propext ⟨fun h => h.elim id id, Or.inl⟩

/-- Convergence: any derivation that is a function of the merged set gives
    the same answer no matter which replica is merged "first". This is why
    tik never needs conflict resolution — the derived stage is identical on
    every replica once they have seen the same events. -/
theorem derive_converges {α β} (derive : GSet α → β) (a b : GSet α) :
    derive (union a b) = derive (union b a) := by
  rw [union_comm]

/-- Duplicate-invariance: a derivation is unaffected by receiving the same
    replica's events twice — the fold is idempotent because the union is. -/
theorem derive_dup_invariant {α β} (derive : GSet α → β) (a : GSet α) :
    derive (union a a) = derive a := by
  rw [union_idem]

end GSet
