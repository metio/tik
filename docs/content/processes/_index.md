---
title: "Processes"
description: "A library of 17 tik process definitions, each derived from the definition itself."
tags: [process, library]
---

Process definitions to read, adapt and adopt. Each page is generated from the
definition it describes, so what you read is what a ticket would derive under.

A process is worth reading before it is taken: it says who must sign what,
and the roles ship empty so adoption never inherits somebody else's org
chart.

<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD

Library-specific prose for the generated /processes/ index. `tik gallery
--intro` splices it in, so regenerating the catalog never discards it.
-->
These pages are generated from the definitions themselves with `tik gallery`,
so what you read is what a ticket derives under. Each carries the content
address it was generated from — the value a ticket pins, and the one a
consumer checks against.

Some of these run tik's own work; the rest are here to be taken. Every role
ships empty either way: who is in one is organisation state, and it lives in a
store's register rather than in the definition a ticket pinned.

Rows marked *(template)* ask a few questions instead of arriving finished:
`tik adopt` prompts for each, then expands and lints your answers into a plain
definition. Their pages show the shape with every option on, and say which
stages depend on which answer.

## Taking one without cloning anything

Every definition is served here by its own address. Fetch it, hash it, compare
— you either have the definition the page describes or you have nothing, and
no trust in this site is required for that to hold:

```sh
HASH=sha256-…                                    # the address on its page
curl -fsSLO https://tik.projects.metio.wtf/processes/by-hash/${HASH}.edn
[ "sha256-$(sha256sum "${HASH}.edn" | cut -d' ' -f1)" = "${HASH}" ] || exit 1
tik adopt "${HASH}.edn"
```

The publication signature is served beside it, and
[`actors`](/processes/actors) carries the key it checks against, so you can
confirm who stands behind a definition with OpenSSH alone:

```sh
curl -fsSLO https://tik.projects.metio.wtf/processes/by-hash/${HASH}.sig.<fpr>
curl -fsSLO https://tik.projects.metio.wtf/processes/actors
ssh-keygen -Y verify -f actors -I seb -n tik-process \
  -s "${HASH}.sig.<fpr>" < "${HASH}.edn"
```

Adopting this way brings the definition alone. `tik adopt` from a clone of the
library brings its runbooks too — the how-to each stage's `:hint` points at,
which is what makes a process legible to somebody who did not write it.

## Adopting one

One command copies a bundle into your store:

```text
tik adopt processes/<name>.edn
```

It brings the definition and its `:hint` runbooks along, so the how-to travels
with the what. Then fill in your people — every role ships with `:members []`,
a template, not a hardcoded org chart:

```text
tik actor add alice alice.pub           # register a signer
# then edit the role's :members, or keep roles open and gate by signature
```

Check it in your context with `tik lint processes/<name>.edn`, and try it live
with `tik sim processes/<name>.edn`.

Nothing here runs until you mint a ticket against it. A definition is
hash-pinned at that moment, so your tickets are bound to the exact rules you
adopted, and later library revisions never change work already in flight.

## Templates

A `templates/*.tmpl.edn` is a definition with **holes** and a `:tik/params`
spec declaring what fills them. `tik adopt templates/<name>.tmpl.edn` reads
that spec and prompts you for each typed input, then expands and lints the
result — no EDN hand-written. A template is inert data (two markers,
`[:tik/param k]` and `[:tik/when flag elem]`), never code, so it is as safe to
share and verify as a plain definition.

The three most general bundles ship as templates, so adapting one is answering
questions rather than editing EDN:

| Template | What it asks |
| --- | --- |
| `support-request` | the escalation window, and whether you flag untriaged requests, require a reproduction, and close only on customer confirmation |
| `incident-response` | how long before a missing postmortem is flagged, whether the ticket flags it itself, and whether a second person must accept it |
| `employee-onboarding` | which branches exist — equipment, accounts — and whether a named buddy is required |
| `expense-approval` | who approves, and whether legal must sign off |

**What is deliberately not a knob.** Role MEMBERS belong in the store's
register (`tik roles add triager alice`), where a hire or a departure takes
effect on in-flight tickets with no new definition and no re-pin — baking
people into a template would put every staffing change into the hash a ticket
pinned. And the enums stay fixed, because the two markers cannot build one
from an answer and a template that asked for a taxonomy would have to accept
raw EDN, which is the thing templates exist to avoid. Edit the expanded
definition when your categories differ; that is a one-line change and the
template is honest about being one.

Each template ships a `<name>.params.edn` with every option on. Expanding with
it reproduces the definition under `processes/` — the same content address the
library publishes and signs — which is how a template is checked for having
drifted from its worked example:

```sh
tik adopt templates/incident-response.tmpl.edn \
  --params templates/incident-response.params.edn
tik lint processes/incident-response.edn
# identity sha256-79744dfcb0177c97ecb00d676f64a8e7cc9f8e630f9e76988e805a5258e4f533
```

(`support-request` is the exception, and says so in its params file: the
sample keeps a bare boolean on purpose to demonstrate the facts-over-flags
warning, and the template does not ship that to adopters.)

## Probes — keeping a stage honest

Some bundles ship a `probes/*.sh`. A probe re-derives live evidence from the
world (a repo's contents, a service's state) into signed facts every time you
run `tik probe`, so a stage that was reached can *regress* when reality changes
— it is not a one-time checkbox. `renovate-migration` uses one to re-check that
Dependabot is still gone.

## Why these hold up

Every definition passes `tik lint` (the `support-request` boolean warning is a
deliberate, documented design tension, not an oversight). Roles carry no
members, so adoption never inherits someone else's org chart. And because a
tik process is derived, not declared, an adopted process cannot silently drift:
its stage is always a pure function of the signed evidence you record.

| Process | Stages | Roles |
| --- | --- | --- |
| [`automated-release`](/processes/automated-release/) | `built`, `attested`, `scanned` | `ci` |
| [`employee-onboarding`](/processes/employee-onboarding/) | `hired`, `equipped`, `accounts-live`, `ready` | `hr`, `it` |
| [`employee-onboarding`](/processes/employee-onboarding-template/) *(template)* | `hired`, `equipped`, `accounts-live`, `ready` | `hr`, `it` |
| [`expense-approval`](/processes/expense-approval-template/) *(template)* |  | `approver`, `legal` |
| [`hypothesis`](/processes/hypothesis/) | `captured`, `stated`, `running`, `validated`, `killed` | `maintainer` |
| [`identity-registry`](/processes/identity-registry/) | `registry` | — |
| [`incident-response`](/processes/incident-response/) | `declared`, `mitigated`, `postmortem-due`, `analyzed`, `reviewed` | `commander`, `responder` |
| [`incident-response`](/processes/incident-response-template/) *(template)* | `declared`, `mitigated`, `postmortem-due`, `analyzed`, `reviewed` | `commander`, `responder` |
| [`okf-publish`](/processes/okf-publish/) | `drafted`, `reviewed`, `approved`, `published` | `approver`, `author`, `reviewer` |
| [`release`](/processes/release/) | `built`, `scanned`, `attested`, `published`, `withheld` | `ci`, `maintainer` |
| [`renovate-migration`](/processes/renovate-migration/) | `planned`, `configured`, `verified` | `maintainer` |
| [`supply-chain-release`](/processes/supply-chain-release/) | `built`, `attested`, `scanned`, `approved`, `withheld` | `ci`, `release-manager` |
| [`support-request`](/processes/support-request/) | `received`, `triaged`, `escalated`, `reproducible`, `resolved`, `closed` | `billing`, `triager` |
| [`support-request`](/processes/support-request-template/) *(template)* | `received`, `triaged`, `escalated`, `reproducible`, `resolved`, `closed` | `triager` |
| [`sweep-order`](/processes/sweep-order/) | `a`, `b`, `c`, `d` | — |
| [`tik-dev`](/processes/tik-dev/) | `captured`, `triaged`, `implemented`, `landed`, `parked` | `maintainer` |
| [`track`](/processes/track/) | `open`, `done` | — |
