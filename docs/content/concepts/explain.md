---
title: Explain
description: The product surface — what evidence is missing, who may supply it, and when waiting is pointless.
tags: [explain, next, inbox, reasons]
---

A traditional tracker exposes state. tik exposes **justification**: what is
true, why it is true, and what evidence is missing next. `tik explain` is
where that surfaces, and every other view is a rendering of it.

```console
$ tik explain 3184
To reach :resolved:
  ✓ [:fact [:resolution :ref]]
  ✗ attach an artifact whose path starts with "repro/"
  blocks: :closed
  (see: kb/runbooks/support-request-resolved.md)
```

Nothing in that block is speculation. The checkmarks are guards that
already hold, the crosses are structured reasons produced by guard
evaluation, `blocks` is the downstream closure, and the hint is the
[runbook](/runbooks/) the definition declares for that stage.

## Reasons are data

The prose lives in this lens only. Underneath, each missing step is a
structured reason carrying the path, the schema, the role, the actor whose
signature already counted — whatever the guard knows:

```sh
tik explain 3184 --edn
```

That data contract is stable plumbing; the English is not. It renders as
CLI text, as web forms built from the schemas in the reasons, and as agent
task specifications whose acceptance criteria *are* the guards.

Reasons are sorted by **who can act on them right now**: values anyone can
supply first, then corrections, artifacts, specific people, attestations,
other stages, and finally time, which is nobody's to act on.

## Who can act

`--actor` filters a block to what one person can do, and counts the rest
rather than hiding it:

```console
$ tik explain 3184 --actor alice
To reach :resolved:
  ✗ attach an artifact whose path starts with "repro/"
  … 1 step(s) waiting on others or time
```

`tik next` rotates the same derivation into an inbox — for a person, or for
a whole role:

```sh
tik next --actor alice
tik next --role :triager
```

The inbox ranks by unlock impact, so the step that frees the most
downstream work comes first, and it holds back tickets whose dependency
links point at unsettled upstream work.

## Waiting versus impossible

Some blocked tickets are waiting for a colleague. Others can never move at
all — a role with no members, a negation over a sticky stage already
reached, a prerequisite that is itself dead. Those look identical until you
say so, and telling somebody to wait for something that will never arrive
is the failure that matters most for a tool whose whole claim is answering
what is blocking.

```console
$ tik explain 9c21
To reach :approved (unreachable):
  ⊘ fact [:sign-off] must be asserted by a member of role :auditor
      (currently by "seb") — nobody can ever do this
```

The derivation stays conservative: a step is only called impossible when it
is provably undischargeable from the definition and the log. A choice dies
only when every one of its branches does. Anything merely waiting keeps its
`✗`.

## Proving it

`tik causal` answers the auditor's question — which signed events made each
reached stage true, with negations and time saying so honestly:

```sh
tik causal 3184
```

And `tik whatif` asks the counterfactual without writing anything:

```sh
tik whatif 3184 severity=:low +P2D retract:category
```
