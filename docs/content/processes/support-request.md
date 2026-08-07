---
title: "support-request"
description: "A tik process with 6 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-84c7f6140fc8cf90ee3a33efde68238cb3eba603b216dfb08aeafcbafc28aa20
```

## Shape

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

- `billing` — empty
- `triager` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `category` | `[:enum :billing :technical :account :abuse]` |
| `customer.ack` | `:boolean` |
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

Follows `received`.

Reached when:

- `PT48H` has passed since `create`
- it is NOT the case that the fact `category` stands

Runbook: `kb/runbooks/support-request-escalated.md`

### `reproducible`

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

Follows `resolved`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `customer.ack` stands

Runbook: `kb/runbooks/support-request-closed.md`

## Take it

```sh
tik adopt processes/support-request.edn
```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it. Read the stages before you adopt: they say who has to
sign what, which is a decision about your organisation.
