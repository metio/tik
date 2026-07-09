---
type: decision
status: accepted
date: 2026-07-09
title: Definition hash is identity; publication signatures are authority
supersedes: null
---

# ADR 0015: Process definition trust

## Decision

A process definition's **hash is its identity** (ADR 0002/0006); a
**detached signature sidecar over its canonical bytes is publication
authority** — who vouches that this definition is an approved rule set.
The two are independent: an unsigned definition is still a definite,
pinnable identity; a signature adds "and Compliance published it".

The permanent answers:

- **Signatures are over canonical definition bytes** (the ADR 0007
  pattern), never over the hash string alone — signing a name instead
  of content is how substitution bugs are born.
- **Multiple signatures accumulate** as sidecars (author, security
  review, compliance) without touching identity.
- **Who may publish is deployment policy**, expressed as which
  publisher keys a deployment accepts — checked at ticket creation and
  by lint/CI, *not* by the kernel at derivation time: a ticket pinned
  to a definition derives under it regardless, because reproducibility
  of past conclusions must not depend on today's trust list.
- **Revocation is prospective**: revoking a definition (an attestation
  by its publisher) means "create no new tickets under this; migrate
  existing ones". Existing tickets keep deriving under their pin —
  ADR 0002's reproducibility — while lint, `next`, and migration sweeps
  surface them as work. Retroactive invalidation would rewrite what
  past conclusions meant, which is the exact audit hole pinning closes.

## Context

A process definition is executable governance: publishing a bad one is
the definition-poisoning attack (PLAN §18), and the kernel is correctly
neutral about it — the verifier proves correct evaluation, not good
policy. What the kernel *can* do is make authorship and endorsement of
definitions first-class evidence, so "who allowed these rules" is a log
question with the same answerability as "who asserted this fact".

## Consequences

- The compliance-library product (IDEAS) and starter templates ship as
  signed definitions; the signature is the product's warranty label.
- "Pinned to a revoked definition" is a governance lens finding and a
  `next` item, never a derivation change.
- Definition provenance rides the identity ladder (SSH keys → OIDC
  attestations → Sigstore), same as actors — no parallel trust system.
