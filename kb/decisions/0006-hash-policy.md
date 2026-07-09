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

## Context

SHA-256 is where the relevant world converges: git's own hash transition
targets SHA-256 (SHA-512, SHA-512/256, BLAKE2 and K12 were considered and
rejected), OCI digests, Sigstore/in-toto/DSSE, Nix SRI, FIPS 180-4 — and
`sha256sum` in coreutils, which is what keeps verify level 0 checkable with
tools from 1995. SHA-256's length-extension weakness is irrelevant to
content addressing (it affects secret-prefix MACs, which tik never
constructs); performance is irrelevant at event sizes. BLAKE3's speed does
not buy anything tik needs at the price of ecosystem and compliance
alignment.

Unlike git — whose uniform object-id namespace makes its transition
agonizing — tik's ids are strings referenced verbatim by parents,
attestations, and links, which is what makes the additive migration path
possible. That property is retained on purpose.

## Consequences

- If witnessed.dev ever sells into CNSA 2.0 territory, the answer is a new
  prefix (`sha384-`/`sha512-`) under a format-version bump — a policy
  change, not a redesign.
