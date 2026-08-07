---
title: "automated-release"
description: "A tik process with 3 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-c1cffcb2e879ff82ad36bf8e67c993aa8ddb0c5caacbdd484ec8440cb549c8bb
```

## Shape

```text
● built ★        ⊢ version · commit · ✎ci · ⧉checksums/
│
├─▶ attested ★   ⊢ artifact.digest · ✎ci · ⊙sbom · ⊙provenance
└─▶ scanned ★    ⊢ scan.result = clean · ✎ci · ⊙vulnerability-scan
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `ci` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `artifact.digest` | `[:string {:min 8}]` |
| `commit` | `[:string {:min 7}]` |
| `scan.result` | `[:enum :clean :findings]` |
| `version` | `[:string {:min 1}]` |

## Stages

### `built` · sticky

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `version` stands
- the fact `commit` stands
- `commit` was asserted by a member of the `ci` role
- an artifact is attached whose path starts with `checksums/`

Runbook: `kb/runbooks/automated-release-built.md`

### `attested` · sticky

Follows `built`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `artifact.digest` stands
- `artifact.digest` was asserted by a member of the `ci` role
- an attestation of `:sbom` exists, no older than `P1D`
- an attestation of `:provenance` exists, no older than `P1D`

Runbook: `kb/runbooks/automated-release-attested.md`

### `scanned` · sticky

Follows `built`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `scan.result` equals `:clean`
- `scan.result` was asserted by a member of the `ci` role
- an attestation of `:vulnerability-scan` exists, no older than `P1D`

Runbook: `kb/runbooks/automated-release-scanned.md`

## Take it

```sh
tik adopt processes/automated-release.edn```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
