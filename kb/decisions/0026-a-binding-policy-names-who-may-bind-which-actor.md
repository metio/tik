---
type: decision
status: accepted
date: 2026-11-15
title: A binding policy names which subject may bind which actor, and a bound key speaks only while its token was live
supersedes: null
---

# ADR 0026: A binding policy names who may bind which actor

## Decision

**A store states which subjects may bind which actor, and a binding
outside that statement grants nothing.** `oidc.edn` carries the policy
under `:bindings`, per actor, as the issuers and subjects that may bind
it:

```clojure
{:audience "tik"
 :bindings {"ci" [{:issuer "https://token.actions.githubusercontent.com"
                   :subject "repo:metio/tik:ref:refs/heads/main"}]}}
```

This adds a fourth rule to the three in ADR 0023: **the token's subject,
from its issuer, must be one the policy lets bind the actor the binding
names.** A subject is matched exactly, or as a prefix when the pattern
ends in `*`; the pattern spells out its own separator, because a
subject's structure differs per issuer.

- A binding the policy does not permit FAILS `verify`, with the actor and
  subject named. Like an audience mismatch, it is a genuine token used
  where it does not belong.
- A policy that does not parse permits nothing, and fails every binding
  it judges. A typo must not reopen what the policy closes.
- A store that declares no policy keeps the behaviour of ADR 0023 and is
  told so by `verify` and by `tik bridge workload`.
- The rule is judged after the cryptographic ones, so a forged token is
  reported as forged rather than as unauthorized.

**A bound key speaks for its actor only for events claimed inside the
window of the token that bound it**, with the same 60s leeway rule 3
allows. A signature by that key on an event claimed outside the window
does not verify as the actor.

## Context

ADR 0023 checks that a token is genuine, about the subject the binding
names, live when the binding was written, and minted for this store's
audience. None of that says which actor the subject may speak for: the
actor name is whatever the binding claims. A platform issuer serves every
tenant at once. Every GitHub repository can obtain a token from the same
issuer with any audience it asks for, so under ADR 0023 alone a workflow
in any repository could bind `ci`, and `verify` would credit its key. The
binding is signed only by the key it binds, so getting that event into
the store is the only barrier — a write, a merged change, or a `tik
import`, which accepts foreign bindings by design.

The same gap blocks any issuer whose subjects are structured names, such
as a SPIFFE trust domain: `spiffe://example.org/ns/release/sa/tik` only
means something once the store says which actor it may be.

The second rule follows from what the token vouches for. The IdP signed
a window, typically minutes long. Crediting the key without limit let one
leaked ephemeral key sign as the actor for as long as the store exists.
GitHub's tokens are valid for five minutes either side of issue; tik's
own release job signs everything within a second of binding.

## Consequences

- The policy is trust base, committed like `actors` and `jwks/`, and
  declared rather than derived for the same reason those are: it is the
  human statement a derivation is checked against.
- A job that signs for longer than its token lives binds again with a
  fresh token. Bindings accumulate, so this needs nothing new.
- Event times are claimed (ADR 0012), so a backdated event signed by an
  expired key is detectable rather than prevented, exactly as for rule 3.
- An evidence bundle carries only bindings that verified under the
  exporting store's policy. A recipient applies their own with
  `tik rederive --expect-bindings <oidc.edn>`, which fails unless every
  key earned through a binding was bound by an issuer and subject that
  file permits for the actor, with a token signed by an issuer key in
  the `jwks/` beside the file. The bundle ships the issuer keys its
  bindings verified with, so a policy checked against those alone would
  pass a supplier who signed their own tokens. The bundle's `verify.sh` applies no policy:
  it proves the bytes with coreutils and `ssh-keygen`, and deciding which
  subjects a reader trusts is a judgment, like which stage they expect,
  that belongs with the other expectations in `rederive`.
