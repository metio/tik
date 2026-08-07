---
title: "track"
description: "A tik process with 2 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-5812cdba533c92d934af16c73fe4027fc37bd034e6005f56237358680037d3c3
```

## Shape

```text
● open
│
▼ done ★   ⊢ outcome
```

## Facts it records

| Path | Shape |
| --- | --- |
| `outcome` | `[:string {:min 4}]` |

## Stages

### `open`

Reached immediately — it carries no guards.

Runbook: `kb/runbooks/track-open.md`

### `done` · sticky

Follows `open`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `outcome` stands

Runbook: `kb/runbooks/track-done.md`

## Take it

```sh
tik adopt processes/track.edn
```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it. Read the stages before you adopt: they say who has to
sign what, which is a decision about your organisation.
