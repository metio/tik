---
title: "support-request"
description: "A tik process template with 6 stages"
tags: [process, library]
---

## What you choose

`tik adopt` reads these from the template itself and asks for each one,
typed and validated — no EDN to hand-write.

| Question | Answer | |
| --- | --- | --- |
| `escalate-after` | `:string` | escalate an untriaged request after (ISO-8601, e.g. PT48H) |
| `with-escalation` | `:boolean` | flag requests nobody triaged in that window? *(optional)* |
| `with-repro` | `:boolean` | require a reproduction before a technical fix counts? *(optional)* |
| `with-ack` | `:boolean` | close only once the customer confirms? *(optional)* |

## Identity

Your answers decide this one: turn a stage off and the process is a
different process with a different address. The shape below is what
`support-request.params.edn` produces, with every option on:

```text
sha256-92c2e66b1706e37f5036fd5c3365a8c773bb1fdadb6c39286cf37aae78741bfb
```

## Shape

Every option on. Stages that depend on an answer say so below.

```text
● received
│
├─▶ triaged            ⊢ category · severity · ✎triager
│   │
│   ├─▶ reproducible   ⊢ category = technical · ⧉repro/
│   └─▶ resolved       ⊢ resolution.ref · (¬category = technical | ⤳reproducible)
│       │
│       ▼ closed ★     ⊢ customer.ack
└─▶ escalated          ⊢ ⏱PT48H · ¬category
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `triager` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `category` | `[:enum :billing :technical :account :abuse]` |
| `customer.ack` | `[:string {:min 2}]` |
| `resolution.ref` | `[:string {:min 8}]` |
| `severity` | `[:enum :low :normal :high :critical]` |

## Stages

### `received`

Reached immediately — it carries no guards.

Runbook: `kb/runbooks/support-request-received.md`

### `triaged`

Follows `received`.

Reached when:

- the fact `category` stands
- the fact `severity` stands
- `category` was asserted by a member of the `triager` role

Runbook: `kb/runbooks/support-request-triaged.md`

### `escalated`

Included when you answer yes to `with-escalation`.

Follows `received`.

Reached when:

- `PT48H` has passed since `create`
- it is NOT the case that the fact `category` stands

Runbook: `kb/runbooks/support-request-escalated.md`

### `reproducible`

Included when you answer yes to `with-repro`.

Follows `triaged`.

Reached when:

- the fact `category` equals `:technical`
- an artifact is attached whose path starts with `repro/`

Runbook: `kb/runbooks/support-request-reproducible.md`

### `resolved`

Follows `triaged`.

Reached when:

- the fact `resolution.ref` stands
- either it is NOT the case that the fact `category` equals `:technical`, or stage `reproducible` is reached

Runbook: `kb/runbooks/support-request-resolved.md`

### `closed` · sticky

Included when you answer yes to `with-ack`.

Follows `resolved`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `customer.ack` stands

Runbook: `kb/runbooks/support-request-closed.md`

## Take it

```sh
tik adopt templates/support-request.tmpl.edn
```

`tik adopt` asks each question above at the prompt, then expands and
lints the answers into a plain definition — the template never runs as
code, and the expanded EDN is what your tickets pin.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
