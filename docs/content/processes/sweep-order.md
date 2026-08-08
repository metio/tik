---
title: "sweep-order"
description: "A tik process with 4 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-6fed34008ad5e2d4d44b057ec365a8075e591f1de6c85c613e3c53a6dec8bcae
```

## Shape

```text
● a
│
├─▶ b
│   │
│   ▼ d   ⊢ ¬⤳c
└─▶ c
```

## Stages

### `a`

Reached immediately — it carries no guards.

### `b`

Follows `a`.

Reached immediately — it carries no guards.

### `c`

Follows `a`.

Reached immediately — it carries no guards.

### `d`

Follows `b`.

Reached when:

- it is NOT the case that stage `c` is reached

## Take it

```sh
tik adopt processes/sweep-order.edn```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
