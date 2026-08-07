---
title: "incident-response"
description: "A tik process template with 5 stages"
tags: [process, library]
---

## What you choose

`tik adopt` reads these from the template itself and asks for each one,
typed and validated — no EDN to hand-write.

| Question | Answer | |
| --- | --- | --- |
| `postmortem-after` | `:string` | flag a missing postmortem after (ISO-8601, e.g. P5D) |
| `with-deadline` | `:boolean` | let the ticket flag its own overdue postmortem? *(optional)* |
| `with-review` | `:boolean` | require a second person to accept the postmortem? *(optional)* |

## Identity

Your answers decide this one: turn a stage off and the process is a
different process with a different address. The shape below is what
`incident-response.params.edn` produces, with every option on:

```text
sha256-79744dfcb0177c97ecb00d676f64a8e7cc9f8e630f9e76988e805a5258e4f533
```

## Shape

Every option on. Stages that depend on an answer say so below.

```text
● declared           ⊢ severity · impact
│
▼ mitigated          ⊢ ✎commander · mitigation.note
│
├─▶ postmortem-due   ⊢ ⏱P5D · ¬postmortem.summary
└─▶ analyzed         ⊢ root-cause · postmortem.summary
    │
    ▼ reviewed ★     ⊢ review.verdict = accepted · ✎commander · ⚖ postmortem.summary ≠ review.verdict
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `commander` — empty
- `responder` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `impact` | `[:string {:min 10}]` |
| `mitigation.note` | `[:string {:min 10}]` |
| `postmortem.summary` | `[:string {:min 40}]` |
| `review.verdict` | `[:enum :accepted :needs-work]` |
| `root-cause` | `[:string {:min 10}]` |
| `severity` | `[:enum :sev1 :sev2 :sev3]` |

## Stages

### `declared`

Reached when:

- the fact `severity` stands
- the fact `impact` stands

Runbook: `kb/runbooks/incident-declared.md`

### `mitigated`

Follows `declared`.

Reached when:

- `severity` was asserted by a member of the `commander` role
- the fact `mitigation.note` stands

Runbook: `kb/runbooks/incident-mitigated.md`

### `postmortem-due`

Included when you answer yes to `with-deadline`.

Follows `mitigated`.

Reached when:

- `P5D` has passed since `create`
- it is NOT the case that the fact `postmortem.summary` stands

Runbook: `kb/runbooks/incident-postmortem-due.md`

### `analyzed`

Follows `mitigated`.

Reached when:

- the fact `root-cause` stands
- the fact `postmortem.summary` stands

Runbook: `kb/runbooks/incident-analyzed.md`

### `reviewed` · sticky

Included when you answer yes to `with-review`.

Follows `analyzed`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `review.verdict` equals `:accepted`
- `review.verdict` was asserted by a member of the `commander` role
- the facts `postmortem.summary` and `review.verdict` came from different people

Runbook: `kb/runbooks/incident-reviewed.md`

## Take it

```sh
tik adopt templates/incident-response.tmpl.edn
```

`tik adopt` asks each question above at the prompt, then expands and
lints the answers into a plain definition — the template never runs as
code, and the expanded EDN is what your tickets pin.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
