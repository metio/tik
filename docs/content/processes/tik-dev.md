---
title: "tik-dev"
description: "A tik process with 5 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-93474ce2b4a3090fc946e356abcd54177c7ed6598706ec44f47cf3131fa08813
```

## Shape

```text
● captured
│
▼ triaged         ⊢ summary · kind · ✎maintainer
│
├─▶ implemented   ⊢ commit
│   │
│   ▼ landed ★    ⊢ gate = green
└─▶ parked        ⊢ parked.reason
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `maintainer` — declared members: `seb`

## Facts it records

| Path | Shape |
| --- | --- |
| `commit` | `[:string {:min 7}]` |
| `gate` | `[:enum :green :red]` |
| `kind` | `[:enum :feature :bug :docs :spike]` |
| `parked.reason` | `[:string {:min 4}]` |
| `summary` | `[:string {:min 8}]` |

## Stages

### `captured`

Reached immediately — it carries no guards.

Runbook: `kb/runbooks/tik-dev-captured.md`

### `triaged`

Follows `captured`.

Reached when:

- the fact `summary` stands
- the fact `kind` stands
- `kind` was asserted by a member of the `maintainer` role

Runbook: `kb/runbooks/tik-dev-triaged.md`

### `implemented`

Follows `triaged`.

Reached when:

- the fact `commit` stands

Runbook: `kb/runbooks/tik-dev-implemented.md`

### `landed` · sticky

Follows `implemented`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `gate` equals `:green`

Runbook: `kb/runbooks/tik-dev-landed.md`

### `parked`

Follows `triaged`.

Reached when:

- the fact `parked.reason` stands

Runbook: `kb/runbooks/tik-dev-parked.md`

## Take it

```sh
tik adopt processes/tik-dev.edn```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
