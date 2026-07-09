---
type: decision
status: accepted
date: 2026-07-08
title: SHA-256 content addressing, self-describing ids, one algorithm per store
supersedes: null
---

# ADR 0006: Hash policy

## Decision

1. Content addresses are SHA-256 over canonical bytes, written as
   self-describing ids: `sha256-<hex>`.
2. **Exactly one algorithm per store per format-version.** Verifiers reject
   stores mixing algorithms within a format version — hash agility in the
   *format*, discipline in the *policy*, because "verifier accepts
   whatever" is a downgrade-attack surface.
3. Migration, if ever needed, is **additive**: a format-version bump after
   which new events use the new prefix while old events remain exactly as
   signed. Mixed-prefix DAGs verify as long as the verifier implements both
   prefixes. No rewrite of history, ever.
4. **The format version defines the trust contract, not the identifier
   syntax.** A verifier implementing a format version must support exactly
   the hash algorithms that version requires; support for other algorithms
   is never inferred from a self-describing prefix alone. A generic
   hash-registry verifier ("dispatch on whatever the prefix says") would
   recreate the downgrade surface rule 2 exists to close.

## Context

The argument for SHA-256 is ecosystem weight, not a prediction about
cryptography: it is sufficiently deployed that the verification ecosystem
around it currently outweighs the benefits of any alternative. git's own
hash transition targets SHA-256 (SHA-512, SHA-512/256, BLAKE2 and K12 were
considered and rejected), OCI digests, Sigstore/in-toto/DSSE, Nix SRI,
FIPS 180-4 — and `sha256sum` in coreutils, which is what keeps verify
level 0 checkable with tools from 1995. Should that balance ever shift,
rule 3 is the exit.

SHA-256's length-extension weakness is irrelevant here, and for a reason
stronger than "it only affects secret-prefix MACs": tik hashes complete
canonical byte sequences and never uses the hash as an authentication
primitive. The division of labor is strict — **hashes answer "is this
byte sequence unchanged?"; detached signatures answer "who authorized
this byte sequence?"** (ADR 0007) — so the hash carries integrity only,
never authenticity. Performance is irrelevant at event sizes; BLAKE3's
speed does not buy anything tik needs at the price of ecosystem and
compliance alignment.

Unlike git — whose uniform object-id namespace makes its transition
agonizing — tik's ids are strings referenced verbatim by parents,
attestations, and links, which is what makes the additive migration path
possible. That property is retained on purpose.

## Consequences

- If witnessed.dev ever sells into CNSA 2.0 territory, the answer is a new
  prefix (`sha384-`/`sha512-`) under a format-version bump — a policy
  change, not a redesign.
- The invariant future requirements must respect, in one line:
  **identities are stable; migrations are additive; verification rules
  are explicit.** This ADR does not predict cryptographic trends — it
  constrains how any trend gets absorbed.
