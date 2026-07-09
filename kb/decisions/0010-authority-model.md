---
type: decision
status: accepted
date: 2026-07-09
title: Signatures establish authorship; authorization is derived
supersedes: null
---

# ADR 0010: The authority model

## Decision

**Signatures establish authorship. Authorization is derived** — from
identity attestations, role facts, and process guards. The kernel never
interprets a signature as permission by itself; there is no code path
where "validly signed" alone unlocks anything.

The permanent answers:

- **Revocation is prospective, not retroactive.** A key revocation is an
  attestation event. Signatures made before revocation remain valid
  *authorship claims* forever — the log is immutable and history does
  not get rewritten (ADR 0002's reproducibility applies to trust too).
  Whether the author was *authorized* is evaluated against role validity
  at the relevant time.
- **Role membership is time-dependent.** "Alice was a triager *when she
  triaged*" is the derivable question; the three clocks (PLAN §5) decide
  which "when" — claimed by default, witnessed where the process demands
  it.
- **Delegation is an attestation with scope and expiry** (`:valid-until`,
  capability), including human→agent delegation. An authority chain
  (Alice ← Bob ← Compliance) is derivation over delegation attestations.
- **Key compromise does not invalidate history**; it changes
  interpretation. The response is evidence: revoke (attestation),
  dispute facts the compromised key asserted, and let derivation regress
  what depended on them. A lens may flag "signed by a later-revoked
  key"; the kernel keeps the record.

## Context

Someone will eventually read `[:signed-by :manager]` and conclude the
signature system *is* the authorization system. It is not: that guard
expands to authorship (the signature) **plus** role derivation (is the
author in the role, at the right time, per the identity attestations).
Losing this distinction is how "it's signed" quietly becomes "it's
approved" — the sidecar-discovery footgun (ADR 0007) and role-decay
attack (PLAN §18) are both instances.

## Consequences

- Verify L1 checks authorship only. Authorization questions are L2
  questions — re-derivable, explainable, offline.
- Roles are security boundaries and must get process-grade discipline:
  attestation-backed grants with provenance, temporal validity, explicit
  migration (PLAN §19 identity concretions).
- "Was this allowed?" is always answerable from the log, never from a
  key server's current opinion.
