---
title: Recording work
description: Creating tickets, asserting facts, attaching evidence, and correcting the record.
tags: [cli, facts, artifacts, corrections]
---

You record evidence. Stages follow.

## Create a ticket

```sh
tik new support-request --title "login fails on Firefox"
```

The ticket pins the process definition's content hash at creation, so it is
judged by the rules it was minted under until somebody deliberately moves
it with `tik reprocess`.

Created beneath a store, a ticket inherits its context as signed facts:
`repo=<name>` from the enclosing git repository, plus any `.tik-facts.edn`
maps on the way down, nearest winning, with anything explicit beating both.

## Assert facts

```sh
tik set 3184 severity=:high resolution.ref=abc123def456
```

Dotted keys nest, so `parked.reason="waiting on legal"` writes the path
`[:parked :reason]`. Values parse as EDN, and a bare word becomes a
keyword — `severity=high` and `severity=:high` mean the same thing, which
keeps facts out of the swamp of free-form strings.

Links are facts too:

```sh
tik set 3184 link.depends-on=9c21f0a4
```

`tik next` then holds 3184 back while 9c21 is unsettled, and `tik status`
names the blocker.

## Attach evidence

```sh
tik attach 3184 ./crash-repro.sh
tik comment 3184 reproduced on a clean profile, video attached
```

Artifacts are stored by hash. A comment is an artifact too — a text blob
attached by its digest — which is why comments need no event type of their
own.

## Correct the record

Nothing is edited or deleted. Corrections are new events:

```sh
tik set 3184 severity=:critical              # supersedes; history retained
tik retract 3184 resolution.ref --reason "wrong commit"
tik dispute 3184 category --reason "this is billing"
tik dispute 3184 category --withdraw         # take back your own objection
```

A later assertion supersedes an earlier one. A retraction says the claim
should not exist and offers no replacement. A dispute rejects the value
that stood when it was raised, so only a *different* value answers it. In
every case the stage regresses by derivation — there is no rollback step.

## Signed claims and attestations

```sh
tik attest 3184 {:ci :green}
```

An attestation is a signed claim whose meaning the kernel does not
interpret. Lenses read it, and the `:attested-within` guard checks that a
fresh-enough one exists — a replayed "CI green" from last month is
cryptographically valid and fails that guard honestly.

## Derive facts from the world

```sh
tik probe 3184
```

A probe is any executable that prints `key=value` lines. It runs with its
working directory in the ticket's `[:repo]` repository, and changed values
land as ordinary signed facts, so a ticket regresses on its own when
reality does. The environment carries `TIK_TICKET`, `TIK_REPO`, and every
present fact as `TIK_FACT_<PATH>`, which is what lets one repository hold
many subjects — a package, tenant, or workload per ticket.

## Recurring and bulk work

```sh
tik recur weekly-review --period 2026-W32
tik rollout dependency-audit --parent-title "Q3 audit"
```

`recur` mints this period's ticket exactly once: it creates only when no
ticket already carries that period label. The schedule lives outside the
log — run it from cron or a timer — and tik derives whether the period
exists yet. `rollout` creates one ticket per git repository under the
store, wired to a parent by link facts, so the parent is a checklist whose
checkmarks derive from each child's evidence. Re-runs create tickets only for
repositories without one, so scheduling `rollout` keeps the checklist current
as repositories appear. A scheduled run takes `--period` (the labels `recur`
reads) or `--at`: the ticket ids and every claimed instant then follow from
the process, the repository, the actor, and the period, and two replicas
running the same rollout write identical events.

## Standing rules

A rule that every repository of a kind must satisfy, such as "every Go
module has a README", is a process run per repository. The process's
`:scope` selects the population and its `:probe` observes the evidence:

```clojure
{:process/id :readme-present
 :scope "probes/go-module.sh"     ; test -f go.mod
 :probe "probes/readme.sh"        ; prints readme=:present or readme=:absent
 :process/facts {[:readme] [:enum :present :absent]}
 :process/stages
 [{:stage/id :tracked :guards []}
  {:stage/id :holds :after [:tracked]
   :guards [[:fact= [:readme] :present]]}]
 ...}
```

`rollout` asks the scope once per repository, with the working directory in
that repository and `TIK_REPO` set: exit 0 includes it, exit 1 leaves it out,
and any other exit is reported and leaves that repository out of the run with
exit code 1. `--scope` replaces the definition's scope for one run. Scheduling
`rollout --period …` and `probe` keeps the rule current: a repository that
joins the population gets its own ticket and starts short of `:holds`, and a
compliant repository falls back from `:holds` when its probe observes the
README gone, since `:holds` is not sticky. `tik status <parent>` counts the
children per stage, and `--links holds` lists the compliant ones.
