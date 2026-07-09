---
type: decision
status: accepted
date: 2026-07-08
title: Stored bytes are exactly the hashed region; signatures are detached
supersedes: null
---

# ADR 0007: Stored bytes = hashed region; detached signatures

## Decision

An event file contains exactly the canonical bytes of the hashed region —
the event map WITHOUT `:event/id` (the filename is the id; storing it
inside would be redundant and, decisively, breaks `sha256sum(file) =
filename`). Signatures never live inside the event either:
`:event/key`/`:event/sig` are removed from the schema entirely; signatures
are detached sidecar files (`<event-id>.sig.<key-fingerprint>`) verifying
against the exact stored bytes, allowing multiple signatures per event
without touching the hashed region.

## Context

Found by the discipline this ADR family exists to protect: internal
`verify` passed while an independent `sha256sum` check failed, because the
file embedded the very id that its hash was supposed to equal. The claim
"an auditor can check store integrity with coreutils" was almost true —
and "almost verifiable" is a bug class of its own: **internal consistency
without external verifiability**, a verifier validating a circular claim
(`hash(bytes-with-id) == embedded id`). The fix is the standard
content-addressing move stated as a rule: *the address names the object;
it is never part of the object.*

What the decision actually buys is a moved trust boundary — from "trust
tik's verifier" to "trust mathematics + coreutils + the canonical
encoding". The implementation becomes a convenience layer over a smaller
primitive contract: `file bytes → hash → filename → signatures →
derivation`, each step independently inspectable. The tempting
alternative (`:event/id` and `:event/sig` fields, "self-contained
events") fails the deeper property: a verifier must never have to trust
the representation's self-description.

The separation also yields a clean algebra with no privileged mutation
path: the *object* is `bytes → hash → identity`; *endorsements*
(`.sig.<fingerprint>`, `.witness.<fingerprint>`, `.ots`) accumulate
around it without ever touching it — so Alice's signature still covers
the exact bytes after Bob signs, which embedded signature fields cannot
offer. Authority never lives inside the record (`approved_by: [alice]`
is the anti-pattern); people make claims *about* the record, the same
shape as every other claim in the system.

## Consequences

- `sha256sum` over any event file yields its filename. Verify L0 is
  literally coreutils.
- Clean separation of concerns: the .edn file is the *claim*; sidecars
  are *endorsements of the claim*. The v6 subtraction generalized this to
  witness countersignatures (`<head>.witness.<fingerprint>`) and OTS
  anchors (`.ots`) — one endorsement pattern for authorship, observation,
  and anchoring.
- The verify ladder's layers become mechanically independent, each adding
  trust without assuming the next: L0 "do I have the object?" (coreutils,
  no keys, no network, no tik code); L1 "who signed these bytes?"; L2
  "does it mean what it claims?" (re-derivation); L3 "do artifacts and
  witnesses check out?".
- **Sidecar discovery is the residual footgun**: "this object has
  signatures" and "this signature is authoritative" are different
  statements. A UI that renders any valid sidecar as "signed ✓" invites
  the attack of attaching a valid signature from an irrelevant key.
  Signatures prove *identity*, never *authorization* — authorization is
  always signature + identity facts + role derivation + process guard,
  and lenses must render the distinction.
- Canonicalization is the remaining sharp edge: everything downstream
  trusts the canonical serializer. Its defenses are the golden byte
  tests, independent `sha256sum` checks, the corpus, and property tests
  (which caught the `pr-str` map-ordering instability — exactly the
  failure class this layer must catch).
- Readers attach `:event/id` from the filename; `verify` recomputes it from
  the bytes.
- The Event schema shrinks. Simplification found by verification — the
  best kind.
