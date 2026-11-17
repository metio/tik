---
type: decision
status: accepted
date: 2026-11-17
title: A SPIFFE JWT-SVID binds like an OIDC token, and its trust domain is its issuer
supersedes: null
---

# ADR 0028: A SPIFFE JWT-SVID binds like an OIDC token

## Decision

**A JWT-SVID is an identity token for rung 2, judged by the four rules
of ADRs 0023 and 0026.** `tik bridge workload --token-file` binds one the
way it binds a GitHub Actions token. Four differences in the format are
handled where they arise:

- **No `iss`.** The JWT-SVID format makes `iss` optional. When a token
  has none and its `sub` is a SPIFFE ID, the issuer is the trust domain
  that ID names: `spiffe://example.org` for
  `spiffe://example.org/ns/ci/sa/release`. An `iss` that is present
  wins. Keys are pinned under that issuer, with
  `tik bridge jwks --issuer spiffe://example.org --jwks bundle.json`.
- **ECDSA.** ES256 and ES384 verify, on P-256 and P-384 keys. An ECDSA
  alg must match its key's curve. A bundle's `verify.sh` rebuilds the
  raw `r||s` signature as DER with `openssl asn1parse -genconf`, so it
  still needs only coreutils, `ssh-keygen` and `openssl`.
- **No `kid`.** A token whose header names no `kid` is tried against
  every pinned signing key of its issuer. Each is a key the operator
  pinned, so this widens nothing (ADR 0027 argues the same for a reused
  `kid`).
- **Keys for another use.** A SPIFFE bundle also lists the CA keys that
  sign X509-SVIDs, with `"use": "x509-svid"`. A key whose `use` is
  anything other than absent, `sig` or `jwt-svid` never verifies a
  token, and `tik bridge jwks` skips it at pin time and says how many it
  skipped.

## Context

SPIFFE gives a workload a short-lived identity with no long-lived
secret, which is the property rung 2 for machines exists for. SPIRE's
server configuration reference gives `jwt_key_type` a default of
`ec-p256`, and a P-256 key signs a JWS as ES256 (RFC 7518). tik verified
EdDSA and RS256 only, and required both `iss` and, with several keys
pinned, `kid`.

X509-SVIDs are out of scope. Checking one means RFC 5280 certificate
path validation, and `CertPathValidator` cannot be resolved under
babashka, which every tik runtime must support. PS256 is out for the
same kind of reason: RSASSA-PSS needs `PSSParameterSpec`, which babashka
does not expose. The JWT-SVID format allows RS, ES and PS algorithms, so
an SVID signed with PS256 is reported as an unsupported alg.

## Consequences

- A binding policy names SPIFFE subjects with a trailing `*` where a
  namespace or service account prefix is meant (ADR 0026), and its
  `:issuer` is the trust domain.
- A SPIFFE bundle rotates by dropping keys. Re-pinning merges and never
  drops (ADR 0027), so a binding the dropped key signed keeps verifying.
- Liveness needs `exp` or `iat`; a JWT-SVID always carries `exp`.
