---
title: "employee-onboarding"
description: "A tik process template with 4 stages"
tags: [process, library]
---

## What you choose

`tik adopt` reads these from the template itself and asks for each one,
typed and validated — no EDN to hand-write.

| Question | Answer | |
| --- | --- | --- |
| `with-equipment` | `:boolean` | does someone hand over equipment? *(optional)* |
| `with-accounts` | `:boolean` | does someone provision accounts? *(optional)* |
| `with-buddy` | `:boolean` | is a named buddy required before day one counts? *(optional)* |

## Identity

Your answers decide this one: turn a stage off and the process is a
different process with a different address. The shape below is what
`employee-onboarding.params.edn` produces, with every option on:

```text
sha256-95c7eadc7481ddec6ed5db85ac555e9302d6d7e2c9bc82505e767c8628de0f23
```

## Shape

Every option on. Stages that depend on an answer say so below.

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

Included when you answer yes to `with-equipment`.

Follows `hired`.

Reached when:

- `equipment.ref` was asserted by a member of the `it` role

Runbook: `kb/runbooks/onboarding-equipped.md`

### `accounts-live`

Included when you answer yes to `with-accounts`.

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
tik adopt templates/employee-onboarding.tmpl.edn
```

`tik adopt` asks each question above at the prompt, then expands and
lints the answers into a plain definition — the template never runs as
code, and the expanded EDN is what your tickets pin.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
