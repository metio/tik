---
title: "Processes"
description: "A library of 13 tik process definitions, each derived from the definition itself."
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
The definitions live in
[metio/tik-processes](https://github.com/metio/tik-processes), which publishes
each one content-addressed and publication-signed. These pages are generated
from that library with `tik gallery`, and each carries the address it was
generated from — so a page describing a definition the library no longer
publishes says so, by naming a hash you will not find there.

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

| Process | Stages | Roles |
| --- | --- | --- |
| [`automated-release`](/processes/automated-release/) | `built`, `attested`, `scanned` | `ci` |
| [`employee-onboarding`](/processes/employee-onboarding/) | `hired`, `equipped`, `accounts-live`, `ready` | `hr`, `it` |
| [`employee-onboarding`](/processes/employee-onboarding-template/) *(template)* | `hired`, `equipped`, `accounts-live`, `ready` | `hr`, `it` |
| [`expense-approval`](/processes/expense-approval-template/) *(template)* |  | `approver`, `legal` |
| [`hypothesis`](/processes/hypothesis/) | `captured`, `stated`, `running`, `validated`, `killed` | `maintainer` |
| [`incident-response`](/processes/incident-response/) | `declared`, `mitigated`, `postmortem-due`, `analyzed`, `reviewed` | `commander`, `responder` |
| [`incident-response`](/processes/incident-response-template/) *(template)* | `declared`, `mitigated`, `postmortem-due`, `analyzed`, `reviewed` | `commander`, `responder` |
| [`okf-publish`](/processes/okf-publish/) | `drafted`, `reviewed`, `approved`, `published` | `approver`, `author`, `reviewer` |
| [`renovate-migration`](/processes/renovate-migration/) | `planned`, `configured`, `verified` | `maintainer` |
| [`supply-chain-release`](/processes/supply-chain-release/) | `built`, `attested`, `scanned`, `approved`, `withheld` | `ci`, `release-manager` |
| [`support-request`](/processes/support-request/) | `received`, `triaged`, `escalated`, `reproducible`, `resolved`, `closed` | `billing`, `triager` |
| [`support-request`](/processes/support-request-template/) *(template)* | `received`, `triaged`, `escalated`, `reproducible`, `resolved`, `closed` | `triager` |
| [`track`](/processes/track/) | `open`, `done` | — |
