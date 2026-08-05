---
title: Roles and authority
description: Who may sign what, and keeping membership current without re-pinning every ticket.
tags: [roles, authority, signatures, separation-of-duties]
---

A role is a name a definition uses in its guards. Membership is store
state that decides who is in that role today.

## Declaring a role

```clojure
:process/roles
{:triager {:members ["seb"]}
 :approver {:members ["alice" "bob"]}}
```

Guards refer to the name:

```clojure
:guards [[:signed-by :triager [:category]]]
```

That guard holds when the fact at `[:category]` was asserted by a member of
`:triager`. It is a statement about evidence — who put this claim on the
record — rather than a permission check performed somewhere else.

## Membership lives in the register

A definition declares which roles exist and who starts in them. The store's
register decides who is in one now:

```sh
tik roles                          # who gates what, with effective members
tik roles add approver carol
tik roles remove approver bob
```

The register overrides a definition role by role, and takes effect on
in-flight tickets immediately — no version bump, no `reprocess`. That is
what makes a departure actually remove authority and a hire actually confer
it, rather than leaving a departed member able to sign every ticket minted
before they left.

Overriding is whole-role: the register's entry replaces the definition's
members for that role rather than merging with them, because a departure
has to be expressible. The first `tik roles add` on a role says so.

A store with no register derives exactly as before, so a definition's
declared members are a working default rather than something to restate.

Resolution takes no `now`. Time-aware validity — "was this actor a member
last March" — is a separate concern that wants signed bindings rather than
a mutable file, so a re-derivation at a past instant reads today's
membership.

## Separation of duties

Two guards express the common controls without a policy engine.

**Four eyes.** `:different-person` holds when two facts are present and
were asserted by distinct actors:

```clojure
[:different-person [:proposal] [:approval]]
```

Nobody approves their own proposal, and `explain` says so by name when
they try — the reason carries the actor whose signature already counted,
so anyone else re-asserting one path breaks the tie.

**Fresh evidence.** `:attested-within` holds when an attestation of a claim
exists and is recent enough:

```clojure
[:attested-within {:claim :ci-green} "P7D"]
```

A replayed attestation from last month is cryptographically valid and
fails this guard honestly, which is exactly the distinction a stale-evidence
control needs.

## An empty role is a dead end

A role with no members can never sign, so every stage behind it is
unreachable. `explain` says that rather than leaving somebody waiting:

```console
To reach :approved (unreachable):
  ⊘ fact [:sign-off] must be asserted by a member of role :auditor
      — nobody can ever do this
```

The fix is `tik roles add`, or a definition that does not demand a
signature nobody can give.

## Signing

Authority only means something once writes are signed. Register a signer
once, then let every write carry authorship:

```sh
tik actor add alice ~/.config/tik/id_ed25519.pub
export TIK_KEY=~/.config/tik/id_ed25519
```

Signatures are detached sidecars from `ssh-keygen -Y`, checked by
`tik verify` with stock OpenSSH. A signature is an authorship claim, which
is why `tik sign` refuses to sign somebody else's events.
