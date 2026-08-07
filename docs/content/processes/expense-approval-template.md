---
title: "expense-approval"
description: "A tik process template"
tags: [process, library]
---

## What you choose

`tik adopt` reads these from the template itself and asks for each one,
typed and validated — no EDN to hand-write.

| Question | Answer | |
| --- | --- | --- |
| `approvers` | `:vector` | who can approve? |
| `with-legal` | `:boolean` | require legal sign-off? *(optional)* |

## Identity

Your answers decide this one, and this template ships no reference
answers — so there is a shape to see only once you have chosen:

```text
(your answers decide it)
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `approver` — declared members: `:tik/param`, `:approvers`
- `legal` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `amount` | `[:int]` |

## Stages

## Take it

```sh
tik adopt templates/expense-approval.tmpl.edn
```

`tik adopt` asks each question above at the prompt, then expands and
lints the answers into a plain definition — the template never runs as
code, and the expanded EDN is what your tickets pin.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
