---
title: "okf-publish"
description: "A tik process with 4 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-be995ad80e6eb4f0a3873b17c3f3a984d18a9671ba4e408fa31c75a1580e991c
```

## Shape

```text
● drafted     ⊢ bundle · ⧉okf/
│
▼ reviewed    ⊢ ✎reviewer
│
▼ approved    ⊢ ✎approver
│
▼ published   ⊢ published.url
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `approver` — empty
- `author` — empty
- `reviewer` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `bundle` | `[:string {:min 16}]` |
| `published.url` | `[:string {:min 8}]` |

## Stages

### `drafted`

Reached when:

- the fact `bundle` stands
- an artifact is attached whose path starts with `okf/`

Runbook: `kb/runbooks/okf-publish-drafted.md`

### `reviewed`

Follows `drafted`.

Reached when:

- `bundle` was asserted by a member of the `reviewer` role

Runbook: `kb/runbooks/okf-publish-reviewed.md`

### `approved`

Follows `reviewed`.

Reached when:

- `bundle` was asserted by a member of the `approver` role

Runbook: `kb/runbooks/okf-publish-approved.md`

### `published`

Follows `approved`.

Reached when:

- the fact `published.url` stands

Runbook: `kb/runbooks/okf-publish-published.md`

## Take it

```sh
tik adopt processes/okf-publish.edn```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
