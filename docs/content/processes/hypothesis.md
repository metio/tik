---
title: "hypothesis"
description: "A tik process with 5 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-2c85c9e54182541ec2243ac82b2d466fd286b511f4f113e4dd139328bea44e47
```

## Shape

```text
● captured
│
▼ stated          ⊢ statement · kill · ✎maintainer
│
▼ running         ⊢ experiment
│
├─▶ validated ★   ⊢ verdict = validated · evidence
└─▶ killed ★      ⊢ verdict = killed · evidence
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `maintainer` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `evidence` | `[:string {:min 8}]` |
| `experiment` | `[:string {:min 12}]` |
| `kill` | `[:string {:min 12}]` |
| `statement` | `[:string {:min 12}]` |
| `verdict` | `[:enum :validated :killed]` |

## Stages

### `captured`

Reached immediately — it carries no guards.

Runbook: `kb/runbooks/hypothesis-captured.md`

### `stated`

Follows `captured`.

Reached when:

- the fact `statement` stands
- the fact `kill` stands
- `statement` was asserted by a member of the `maintainer` role

Runbook: `kb/runbooks/hypothesis-stated.md`

### `running`

Follows `stated`.

Reached when:

- the fact `experiment` stands

Runbook: `kb/runbooks/hypothesis-running.md`

### `validated` · sticky

Follows `running`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `verdict` equals `:validated`
- the fact `evidence` stands

Runbook: `kb/runbooks/hypothesis-validated.md`

### `killed` · sticky

Follows `running`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `verdict` equals `:killed`
- the fact `evidence` stands

Runbook: `kb/runbooks/hypothesis-killed.md`

## Take it

```sh
tik adopt processes/hypothesis.edn
```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it. Read the stages before you adopt: they say who has to
sign what, which is a decision about your organisation.
