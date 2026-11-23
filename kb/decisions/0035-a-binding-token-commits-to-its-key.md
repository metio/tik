---
type: decision
status: accepted
date: 2026-11-23
title: A binding counts only when its token commits to the key it binds
supersedes: null
---

# ADR 0035: A binding token commits to its key

## Decision

**A key binding counts only when its id-token names the key it binds.**
The token commits to a key through `key-commitment`, the string `tik-key-`
followed by the hex SHA-256 of the key's type and base64 blob, carried in
one of two places:

1. **The `nonce` of an OIDC login.** `tik bridge oidc` sends the commitment
   as the nonce of the device or password grant, and the IdP copies it into
   the id-token.
2. **An `aud` entry ending in `#<commitment>`.** GitHub Actions issues one
   audience per token, chosen by the job, so `tik bridge workload --github`
   requests the audience `<audience>#<commitment>`. The audience check
   compares the part before `#`, so a store expecting `tik` accepts
   `tik#tik-key-…`.

A store whose issuer can carry neither sets `:key-commitment :optional` in
`oidc.edn`. Its bindings then count without a commitment, and `verify`
names every one that does. An evidence bundle has no store configuration,
so `tik rederive` and the bundled `verify.sh` always require the
commitment.

## Context

A binding stores its id-token verbatim, so it can be re-checked offline.
That makes the token readable by anyone who can read the store. Before
this decision a binding counted when the issuer signed the token, the
subject and issuer matched, and the binding's claimed instant fell inside
the token's window. None of those involved the bound key. A writer who
could append to the registry could copy a genuine token into a second
binding naming their own key, claim an instant inside the window, and
sign as the token's actor for that window. The claimed instant is not
trusted time (ADR 0012), so the window does not prevent this after the
fact.

The commitment closes it, because the token can name only the key that
was in hand when it was requested.

## Consequences

- Bindings written before this decision carry no commitment and stop
  counting. `verify` reports each as a note rather than a failure, since
  events are never deleted and a failing store could never recover. A
  workload binds again on its next run; a store that must keep old
  bindings sets `:key-commitment :optional`.
- A token from a file or an environment variable must have been requested
  with the commitment already; `bridge workload` names the audience to
  request when it refuses one.
- An IdP that ignores the nonce produces bindings that count only in a
  store that opted out. `bridge oidc` warns when this happens.
