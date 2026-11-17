---
type: decision
status: accepted
date: 2026-11-17
title: A pinned issuer key is identified by its kid and its key material
supersedes: null
---

# ADR 0027: A pinned issuer key is its kid and its material

## Decision

**Two pinned issuer keys are the same key when they share a `kid` and an
RFC 7638 thumbprint.** Re-pinning an issuer with `tik bridge jwks` adds
every key whose pair is new and drops none. An issuer that publishes new
key material under a `kid` it used before leaves both keys pinned, and
the command warns on stderr that the material behind that `kid` changed.

Every verifier tries each pinned key under the token's `kid` and accepts
the token when one of them verifies it: `tik verify`, `tik rederive`, and
a bundle's `verify.sh`. A key this build cannot use is skipped while
another key under the same `kid` remains.

An evidence bundle names the first key under a `kid` `<kid>.pem`, as
before, and each further key under the same `kid` `<kid>~<thumbprint>.pem`.
That covers a reused `kid` and two issuers that happen to use the same
`kid`.

The pinned file stays a plain JWKS. Identity by thumbprint changes what
counts as a duplicate, not the format an operator commits.

## Context

ADR 0023 merged a re-pin by `kid`, the newer key winning. That kept a
rotated key whose `kid` changed, which is the common case, but an issuer
that reused a `kid` for new material silently replaced the old key, and
every binding the old key signed stopped verifying. The SPIFFE bundle
format and some IdPs do not promise unique `kid`s over time, so the case
is reachable without anyone misbehaving.

Refusing such a re-pin was the alternative. It keeps one key per `kid`,
but it leaves the operator unable to pin the key that signs new bindings
for as long as the issuer keeps the reused name.

Trying several keys widens no trust. Every key in the file is one the
operator pinned and committed; a token that verifies under any of them
was signed by a key the store already trusts for that issuer.

## Consequences

- A store that pinned a reused `kid` before this decision holds only the
  newer key. Bindings the older key signed stay unverifiable until that
  key is pinned again, with `tik bridge jwks --jwks <file>` from a copy
  of the issuer's earlier key set.
- A bundle from an older tik names keys `<kid>.pem` only and still reads.
  An older `tik rederive` reading a newer bundle sees only the first key
  under each `kid`, so a binding signed by a further key is a note there,
  which grants nothing rather than failing.
- The warning is the review point. Confirming that new material under an
  old `kid` came from the issuer is the operator's job, as confirming the
  first pin was.
