---
title: Runbooks
description: One page per stage — what reaching it means and what evidence gets you there.
tags: [runbooks, processes, stages]
---

A process definition names a runbook per stage through its `:hint` field, and
`tik explain` prints that link beside the missing evidence:

```text
To reach :triaged:
  ✗ set fact [:category] ([:enum :billing :technical :account :abuse])
  (see: kb/runbooks/support-request-triaged.md)
```

So these pages are read at the moment somebody is stuck, and each answers a
single question: what has to become true here, and who can make it so. They
live in the repository's knowledge bundle under `kb/runbooks/`, so a checkout,
an agent, and this site read the same document.

Each process below is listed in stage order rather than alphabetically — a
runbook makes most sense beside the stages it sits between.

## support-request

The sample process the conformance corpus pins: a customer report from arrival
to acknowledgement.

| Stage | What it means |
| --- | --- |
| [`:received`](/runbooks/support-request-received/) | A new report exists and nothing is known yet. |
| [`:triaged`](/runbooks/support-request-triaged/) | A triager has signed off a category and a severity. |
| [`:reproducible`](/runbooks/support-request-reproducible/) | A reproduction is attached under `repro/`. |
| [`:resolved`](/runbooks/support-request-resolved/) | A resolution reference points at the fix. |
| [`:escalated`](/runbooks/support-request-escalated/) | Derives on its own: 48 hours with no category. |
| [`:closed`](/runbooks/support-request-closed/) | Sticky milestone — only the customer's acknowledgement closes it. |

## release

A version's supply chain as evidence: one ticket per release, each stage
saying what must be true of the artifacts rather than which job ran.

| Stage | What it means |
| --- | --- |
| [`:built`](/runbooks/release-built/) | Artifacts exist and CI has signed which commit produced them. |
| [`:scanned`](/runbooks/release-scanned/) | A vulnerability scan came back clean, attested within the last day. |
| [`:attested`](/runbooks/release-attested/) | An SBOM, build provenance, and a signature are on record. |
| [`:published`](/runbooks/release-published/) | Sticky — a maintainer shipped it, and not the one who built it. |
| [`:withheld`](/runbooks/release-withheld/) | A maintainer decided it does not ship, with the reason. |

## tik-dev

The process tik's own development runs in; this repository is a live store.

| Stage | What it means |
| --- | --- |
| [`:captured`](/runbooks/tik-dev-captured/) | A thought exists and the ticket preserves it. |
| [`:triaged`](/runbooks/tik-dev-triaged/) | A summary and a kind are on record. |
| [`:implemented`](/runbooks/tik-dev-implemented/) | A commit is named. |
| [`:landed`](/runbooks/tik-dev-landed/) | The full local gate came back green. |
| [`:parked`](/runbooks/tik-dev-parked/) | Deliberately not now — with the reason, which is the deliverable. |

## hypothesis

Falsifiable claims carrying a kill criterion, so a plan can be wrong on
purpose rather than by accident.

| Stage | What it means |
| --- | --- |
| [`:captured`](/runbooks/hypothesis-captured/) | A belief worth testing exists, even half-formed. |
| [`:stated`](/runbooks/hypothesis-stated/) | The claim and what would kill it are both written down. |
| [`:running`](/runbooks/hypothesis-running/) | The experiment is named: what is run, on what, measured how. |
| [`:validated`](/runbooks/hypothesis-validated/) | Evidence a stranger could check. |
| [`:killed`](/runbooks/hypothesis-killed/) | Which criterion fired, and the evidence it fired on. |

## track

Two stages, for something that needs recording rather than a workflow.

| Stage | What it means |
| --- | --- |
| [`:open`](/runbooks/track-open/) | The ticket exists; record what is true as it happens. |
| [`:done`](/runbooks/track-done/) | The thing ended — say how. |

## identity-registry

| Stage | What it means |
| --- | --- |
| [`:registry`](/runbooks/identity-registry-registry/) | Always this stage: key bindings are evidence, not workflow. |
