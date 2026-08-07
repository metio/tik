---
title: "supply-chain-release"
description: "A tik process with 5 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-f2aa808ac68e7f6a7e79b347d3006b8bfdadd6b01d279547b5646603f6212b27
```

## Shape

```text
● built            ⊢ version · commit · ✎ci · ⧉checksums/
│
├─▶ attested       ⊢ artifact.digest · ✎ci · ⊙sbom · ⊙provenance
│   │
│   ┈▶ approved
├─▶ scanned        ⊢ scan.result = clean · ✎ci · ⊙vulnerability-scan
│   │
│   ▼ approved ★   ⊢ ⋈ after attested, scanned   approval = ship · ✎release-manager · ⚖ commit ≠ approval
└─▶ withheld       ⊢ approval = hold · ✎release-manager
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `ci` — empty
- `release-manager` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `approval` | `[:enum :ship :hold]` |
| `artifact.digest` | `[:string {:min 8}]` |
| `commit` | `[:string {:min 7}]` |
| `scan.result` | `[:enum :clean :findings]` |
| `version` | `[:string {:min 1}]` |

## Stages

### `built`

Reached when:

- the fact `version` stands
- the fact `commit` stands
- `commit` was asserted by a member of the `ci` role
- an artifact is attached whose path starts with `checksums/`

Runbook: `kb/runbooks/supply-chain-release-built.md`

### `attested`

Follows `built`.

Reached when:

- the fact `artifact.digest` stands
- `artifact.digest` was asserted by a member of the `ci` role
- an attestation of `:sbom` exists, no older than `P1D`
- an attestation of `:provenance` exists, no older than `P1D`

Runbook: `kb/runbooks/supply-chain-release-attested.md`

### `scanned`

Follows `built`.

Reached when:

- the fact `scan.result` equals `:clean`
- `scan.result` was asserted by a member of the `ci` role
- an attestation of `:vulnerability-scan` exists, no older than `P1D`

Runbook: `kb/runbooks/supply-chain-release-scanned.md`

### `approved` · sticky

Follows `attested`, `scanned`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `approval` equals `:ship`
- `approval` was asserted by a member of the `release-manager` role
- the facts `commit` and `approval` came from different people

Runbook: `kb/runbooks/supply-chain-release-approved.md`

### `withheld`

Follows `built`.

Reached when:

- the fact `approval` equals `:hold`
- `approval` was asserted by a member of the `release-manager` role

Runbook: `kb/runbooks/supply-chain-release-withheld.md`

## Take it

```sh
tik adopt processes/supply-chain-release.edn```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
