---
title: "identity-registry"
description: "A tik process with 1 stage"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-e3d4a4cd66b79971817e05d25afa5a7bb3e0a2c1db673b4e56a72308a3af3bb0
```

## Shape

```text
● registry
```

## Stages

### `registry`

Reached immediately — it carries no guards.

Runbook: `kb/runbooks/identity-registry-registry.md`

## Take it

```sh
tik adopt processes/identity-registry.edn```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
