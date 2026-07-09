---
type: decision
status: accepted
date: 2026-07-09
title: The log admits all well-formed claims; trust is evaluated, not filtered
supersedes: null
---

# ADR 0011: Event existence vs. participation in truth

## Decision

**The log contains all received well-formed claims. Derivation consumes
the whole set.** Trust is never a silent reducer filter — it is
expressed where it is visible: in guards (`:signed-by`, and any future
trust-conditions) and in the verify ladder (L1 authenticity). A fact
asserted by an actor lacking the required role *exists*, *is derived
over*, and *fails the guard with an explainable reason*
(`:role/unsatisfied`, naming the actor) — it is never treated as
nonexistent.

## Context

"Event exists" and "event participates in a given conclusion" are
different statements, and conflating them creates ambiguity in both
directions. If the reducer silently dropped untrusted events, then two
verifiers with different key knowledge would derive different states
from the same log — derivation would no longer be a pure function of
the event set, and explain could not say *why* a claim didn't count
(it would have vanished). Keeping admission total and trust explicit
means every exclusion has a structured reason a human can read.

Signature-invalid sidecars are an L1 finding about an *endorsement*,
not grounds to un-exist the claim: the `.edn` file is the claim, and a
claim whose endorsement fails is a claim with a visible problem
(ADR 0007).

## Consequences

- Reducer totality (ADR 0009, property-tested) extends to
  trust-questionable events: reduction never asks "do I trust this?".
- explain can render "fact exists but its author lacks the role" —
  strictly more useful than "fact missing", and only possible because
  the fact was admitted.
- Write-side gatekeeping (who may append at all) is store/transport
  policy — quotas, authenticated endpoints — and lives outside
  derivation entirely (PLAN §18, fact spam).
