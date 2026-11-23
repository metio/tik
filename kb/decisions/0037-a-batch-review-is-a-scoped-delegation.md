---
type: decision
status: proposed
date: 2026-11-23
title: A reviewed batch is approved by one signature, through a scoped and short-lived delegation
supersedes: null
---

# ADR 0037: A batch review is a scoped delegation

## Decision

Proposed, not yet accepted: nothing described here is implemented.

**A reviewer may approve a batch of tickets with one signature, by
delegating to a throwaway key exactly the review events they were
shown.** The per-ticket review events stay as they are, so no guard and
no process definition changes, and a ticket's stage still derives from its
own events alone. Signing ticket by ticket keeps working unchanged; the
batch is an option.

1. **The delegation is an attestation on the identity registry.**
   Claim `:delegation`, authored by the delegating actor and signed by a
   key `actors` grants that actor directly (rung 1). A key that is itself
   delegated or bound through rung 2 cannot delegate, so there are no
   chains. The body names:
   - `:delegation/public-key`, the throwaway key;
   - `:delegation/not-after`, at most one hour after the attestation's
     `:event/at`;
   - `:delegation/scope`, a set of `[ticket-id head-event-id]` pairs, the
     heads the reviewer was shown;
   - `:delegation/facts`, the fact paths the delegated key may assert, for
     a review `#{[:review :verdict]}`.
2. **A delegated signature counts as the delegator's only inside its
   scope.** An event signed by the throwaway key is credited to the
   delegating actor when all of these hold: the delegation verifies as
   that actor; the event's `:event/actor` is that actor; its claimed
   instant lies between the delegation's instant and `:delegation/not-after`;
   its ticket and one of its parents form a pair in `:delegation/scope`;
   and every fact it asserts has a path in `:delegation/facts`. Anything
   else signed by that key counts for nobody.
3. **The reviewer's tool holds the throwaway key, and only for the batch.**
   `tik-review --batch` shows the list, generates the key in a private
   runtime directory, asks for the one signature over the delegation,
   signs each review event with the throwaway key, and deletes it. With a
   security key (`sk-ssh-ed25519`), the one signature is one touch, and
   with `verify-required` one PIN entry, for the whole list.
4. **A store can require the delegation to come from a security key.**
   `{:delegation {:require-touch true}}` in `oidc.edn` credits a delegation
   only when its signature carries the user-presence flag. Without the
   setting any key `actors` grants the delegator may sign one.
5. **Bundles carry the delegation.** It travels in `identity/` beside key
   bindings, and the bundle's `verify.sh` checks the same conditions with
   `ssh-keygen` and coreutils.

## Context

Each review verdict is a signed event on its own ticket. A batch of 163
tickets is 163 signatures, which with a FIDO2 security key means 163
touches; FIDO2 has no touch caching. A touch per event defeats the point
of a touch, because after the tenth the reviewer stops reading.

A single batch event read by many tickets would make one ticket's stage
depend on another ticket's log. Guards read a ticket's own events, links
included, since a link is a fact on the ticket that holds it (ADR 0024),
and ADR 0029 keeps even relations out of derivation. ADR 0010 already decides that delegation is an
attestation with scope and expiry; this applies it to the reviewer's own
key.

The scope is what makes the one signature an informed one. It lists the
exact heads the reviewer was shown, as `tik-merge` pins a pull request
head, so a push or a new event after the list was drawn falls outside
it. The fact-path restriction stops the throwaway key from asserting
anything except the verdict.

## Consequences

- The claimed instant of a delegated event is not trusted time (ADR 0012).
  Whoever obtains the throwaway key before it is deleted can sign further
  verdicts on the listed heads, backdated into the window. The scope bounds
  that to the listed tickets, heads and fact paths, and keeping the key in
  memory or a private runtime directory for seconds keeps the exposure
  small. A witness countersignature over the heads after the batch removes
  the backdating window.
- `verify` reports, per credited event, that it rests on a delegation, so
  a reader can tell a batch approval from a ticket-by-ticket one.
- The throwaway key is Ed25519. Under ADR 0036 it would carry an ML-DSA
  half as well, generated the same way.

## Alternatives considered

- **A batch event read by each ticket.** Fewer events, but each ticket's
  stage would then derive from a log other than its own, which nothing in
  derivation does today.
- **PIV with a cached touch policy.** A YubiKey PIV slot can cache a touch
  for 15 seconds, but OpenSSH reaches PIV only through PKCS#11, with
  ECDSA, and a cache approves whatever is signed during it, not a list.
- **No batch.** Keeps the model as simple as it is now, at the cost of
  per-event touches, or of a software key that needs no touch at all.
