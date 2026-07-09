---
type: decision
status: accepted
date: 2026-07-09
title: Artifacts — the hash is in the trust domain, the blob is not
supersedes: null
---

# ADR 0014: Artifact semantics

## Decision

An `:artifact/attach` event binds a path label to a content hash. The
permanent answers:

- **The hash is inside the trust domain; the blob is not.** The event
  (signed, hashed, parented) proves *that* an actor attached *exactly
  these bytes* at a claimed time. The bytes themselves are payload:
  stored by hash, transferred lazily, verifiable on arrival.
- **Blobs are immutable** — a "changed" artifact is a new hash attached
  by a new event; the old attachment remains history.
- **Deletion removes availability, never history** (GDPR, leaked
  secrets). The event stands, the hash stands, and `verify` L3 reports
  **verifiable absence** — "blob absent" is a truthful verification
  outcome, not a failure to be papered over. This is a designed
  property; transcripts from real environments contain things that must
  be deletable (PLAN §13).
- **Metadata is descriptive, never authoritative.** The path label,
  media type, and any annotations are the attaching actor's *claims*
  about the bytes — disputable like any claim — not properties the
  kernel vouches for. A guard trusting `path = "repro/…"` trusts the
  attacher's labeling; when it matters, processes require an
  attestation about the content, not a filename shape (PLAN §18,
  evidence flooding).
- **Two hashes are two artifacts.** Logical identity across encodings
  ("the same report as PDF and HTML") is not a kernel concept; if a
  process needs it, it is asserted as a fact and carries its asserter's
  accountability.

## Context

Artifacts are where the trust domain touches arbitrary external bytes,
and every mistake here has the same shape: attributing to the blob a
guarantee that only the *event about the blob* carries. Keeping the
boundary explicit — evidence about bytes vs. the bytes — is what lets
blobs be deleted, lazily transferred, and quota-limited without any of
it touching the integrity story.

## Consequences

- Verify: L0/L1 cover the attach event; L3 covers blob presence and
  hash match; the two never blur.
- Store quotas and retention policies apply to blobs freely (PLAN §18
  storage exhaustion) — policy about payload, not about history.
- The `[:artifact prefix]` guard checks that a matching *claim* exists;
  richer content requirements compose with attestations, per the closed
  guard basis.
