---
type: decision
status: accepted
date: 2026-08-06
title: Key bindings are evidence — rung 2 grants signing authority, pinned keys anchor it
supersedes: null
---

# ADR 0023: Key bindings are evidence

## Decision

**A key binding on an identity-registry ticket grants signing authority
for the actor it names, once three things hold.** Verification consults
rung 1 (the curated `actors` allowed-signers registry) AND rung 2 (keys a
verified binding grants); a binding that fails any rule grants nothing.

1. **The id-token's signature is the issuer's**, checked against keys
   PINNED in the store at `<root>/jwks/<issuer>.json`.
2. **The token is about the subject the attestation names** — its `sub`
   and `iss` must equal the binding's, or a valid token from one subject
   would endorse a body naming another.
3. **The token was live when the binding was written** — the
   attestation's own `:event/at` falls inside the token's `nbf`…`exp`
   window (60s leeway), so a binding means "this actor presented a
   working token at that moment" rather than "this actor once held a
   token". A token carrying neither `exp` nor `iat` is refused: nothing
   ties it to a moment.

**The registry is derived, not configured**: any ticket pinned to the
`identity-registry` process is one. A trust base must not be something a
config file can forget to mention.

**Evidence of forgery FAILS; inability to judge is a NOTE.** A bad
signature, a mismatched subject or issuer, a token that was not live —
each means somebody wrote a binding the IdP never supported, and `verify`
fails. An issuer nobody pinned is reported and passes, because such a
binding grants nothing (only verified bindings reach the signer set) and
because events are never deleted (ADR 0017): failing on it would leave a
store that once recorded a binding for an unreachable IdP permanently
unverifiable, with no exit. This is the distinction L1 already draws when
it calls an unsigned event "authenticity unclaimed, not failed".

## Context

Rung 2 existed on paper: `tik bridge oidc` wrote `:identity` attestations
to a registry ticket and nothing ever read them. A key bound through OIDC
could sign nothing that would verify, so the ladder's second rung was a
record rather than a rung.

The forcing case is a pipeline. A release process whose guards demand
`:signed-by :ci` needs CI to hold a key, and the two available answers
were both bad: a long-lived private key in a repository secret, or no
signatures at all. The third answer is the one Sigstore made familiar —
an ephemeral keypair per run, bound to the workload identity the platform
already issues, discarded when the job ends. Nothing long-lived exists to
leak. That only works if a binding can grant authority, which is what
this decision settles.

Pinning is what keeps it offline. Verification never calls the IdP, so a
binding stays checkable on a machine with no network, years later, after
the issuer is decommissioned — the promise every other rung makes. An
issuer that rotates publishes the new key beside the old, so re-pinning
MERGES by `kid`: dropping a retired key would stop verifying every
binding it signed.

Rule 3 is judged on the claimed clock (ADR 0012), which makes a backdated
binding detectable rather than prevented. A witness countersignature over
the head is what turns that into evidence, exactly as it does for every
other claimed timestamp.

## Consequences

- `tik bridge jwks --issuer <url>` pins an issuer's keys; the file is
  committed like `actors`, because it is trust base, not cache.
- `tik bridge workload` binds a key to a workload identity from a
  pre-issued token (`--github`, `--token-file`, `--token-env`). The token
  is verified BEFORE the binding is written: minting one that `verify`
  would refuse helps nobody and cannot be undone in a log that never
  deletes.
- `--key` in the bridges names the key being BOUND. It is no longer
  passed through as the key that SIGNS the attestation, which had the
  bridges attempting to sign with a public key; `--public-key` is the
  unambiguous spelling.
- The binding attestation is signed by the very key it binds, and that is
  sound rather than circular: the binding's trust comes from the IdP's
  signature over the token, not from the event's own signature.
- Whoever controls a workload identity controls the actor it binds. The
  subject is on the log, so an auditor sees which repository, ref, and
  workflow minted it — the guard `:signed-by :ci` is exactly as strong as
  the platform's token issuance and no stronger, which is a property to
  state plainly rather than bury.
- The registry scan is computed once per audit. The verify ladder runs
  per ticket, so a store-level lookup done per ticket is quadratic — it
  took a 131-ticket audit from 30 seconds to over five minutes.
