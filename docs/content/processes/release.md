---
title: "release"
description: "A tik process with 5 stages"
tags: [process, library]
---

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-3c577f361a46ec2166b7f4d6fb875d2de4e317f478d425eb4ae39f77fdcb9aca
```

## Shape

```text
● built             ⊢ version · commit · ✎ci · ⧉checksums/
│
├─▶ scanned         ⊢ scan.result = clean · ✎ci · ⊙vulnerability-scan
│   │
│   ┈▶ published
├─▶ attested        ⊢ image · signature.bundle · ✎ci · ⊙sbom · ⊙provenance
│   │
│   ▼ published ★   ⊢ ⋈ after scanned, attested   approval = ship · ✎maintainer · ⚖ commit ≠ approval
└─▶ withheld        ⊢ approval = hold · ✎maintainer
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `ci` — declared members: `ci`
- `maintainer` — declared members: `seb`

## Facts it records

| Path | Shape |
| --- | --- |
| `approval` | `[:enum :ship :hold]` |
| `commit` | `[:string {:min 7}]` |
| `image` | `[:string {:min 8}]` |
| `scan.result` | `[:enum :clean :findings]` |
| `signature.bundle` | `[:string {:min 8}]` |
| `version` | `[:string {:min 6}]` |

## Stages

### `built`

Reached when:

- the fact `version` stands
- the fact `commit` stands
- `commit` was asserted by a member of the `ci` role
- an artifact is attached whose path starts with `checksums/`

Runbook: `kb/runbooks/release-built.md`

### `scanned`

Follows `built`.

Reached when:

- the fact `scan.result` equals `:clean`
- `scan.result` was asserted by a member of the `ci` role
- an attestation of `:vulnerability-scan` exists, no older than `P1D`

Runbook: `kb/runbooks/release-scanned.md`

### `attested`

Follows `built`.

Reached when:

- the fact `image` stands
- the fact `signature.bundle` stands
- `signature.bundle` was asserted by a member of the `ci` role
- an attestation of `:sbom` exists, no older than `P1D`
- an attestation of `:provenance` exists, no older than `P1D`

Runbook: `kb/runbooks/release-attested.md`

### `published` · sticky

Follows `scanned`, `attested`.

Once reached it stays reached: the fold carries it forward, so later
evidence cannot take it away.

Reached when:

- the fact `approval` equals `:ship`
- `approval` was asserted by a member of the `maintainer` role
- the facts `commit` and `approval` came from different people

Runbook: `kb/runbooks/release-published.md`

### `withheld`

Follows `built`.

Reached when:

- the fact `approval` equals `:hold`
- `approval` was asserted by a member of the `maintainer` role

Runbook: `kb/runbooks/release-withheld.md`

## Take it

```sh
tik adopt processes/release.edn```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it.

Read the stages before you adopt: they say who has to sign what,
which is a decision about your organisation.
