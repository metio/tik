---
title: "employee-onboarding"
description: "A tik process with 4 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-95c7eadc7481ddec6ed5db85ac555e9302d6d7e2c9bc82505e767c8628de0f23
```

## Shape

```text
● hired             ⊢ start-date · ⧉contract/ · ✎hr
│
├─▶ equipped        ⊢ ✎it
│   │
│   ┈▶ ready
└─▶ accounts-live   ⊢ ✎it
    │
    ▼ ready ★       ⊢ ⋈ after equipped, accounts-live   buddy
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `hr` — empty
- `it` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `accounts.email` | `[:string {:min 5}]` |
| `buddy` | `[:string {:min 2}]` |
| `contract.ref` | `[:string {:min 4}]` |
| `equipment.ref` | `[:string {:min 4}]` |
| `start-date` | `[:string {:min 10}]` |

## Stages

### `hired`

Reached when:

- the fact `start-date` stands
- an artifact is attached whose path starts with `contract/`
- `contract.ref` was asserted by a member of the `hr` role

Runbook: `kb/runbooks/onboarding-hired.md`

### `equipped`

Follows `hired`.

Reached when:

- `equipment.ref` was asserted by a member of the `it` role

Runbook: `kb/runbooks/onboarding-equipped.md`

### `accounts-live`

Follows `hired`.

Reached when:

- `accounts.email` was asserted by a member of the `it` role

Runbook: `kb/runbooks/onboarding-accounts.md`

### `ready` · sticky

Follows `equipped`, `accounts-live`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `buddy` stands

Runbook: `kb/runbooks/onboarding-ready.md`

## Take it

```sh
tik adopt processes/employee-onboarding.edn
```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it. Read the stages before you adopt: they say who has to
sign what, which is a decision about your organisation.
