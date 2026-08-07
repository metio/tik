---
title: "renovate-migration"
description: "A tik process with 3 stages: move a repository from Dependabot to Renovate, with evidence"
tags: [process, library]
---

move a repository from Dependabot to Renovate, with evidence

## Identity

A definition is named by its content, so this address is what a
ticket pins and what a consumer checks against:

```text
sha256-fc1d1067f6e89f52537828296ef3e78e01ed0a552da105d8770aa399131bc0e1
```

## Shape

```text
● planned      ⊢ approach
│
▼ configured   ⊢ renovate.config.ref · removal.commit · dependabot.status = absent
│
▼ verified     ⊢ renovate.dashboard · renovate.first-pr · approval.maintainer = approved · ✎maintainer
```

## Roles to fill

Every role ships empty, so adopting a process never inherits
somebody else's org chart. Name yours with `tik roles add <role> <actor>`.

- `maintainer` — empty

## Facts it records

| Path | Shape |
| --- | --- |
| `approach` | `[:enum :org-wide-config :repo-local-config]` |
| `approval.maintainer` | `[:enum :approved :rejected]` |
| `dependabot.status` | `[:enum :present :absent]` |
| `removal.commit` | `[:string {:min 1}]` |
| `renovate.config.ref` | `[:string {:min 1}]` |
| `renovate.dashboard` | `[:string {:min 1}]` |
| `renovate.first-pr` | `[:string {:min 1}]` |

## Stages

### `planned`

Reached when:

- the fact `approach` stands

Runbook: `kb/runbooks/renovate-migration-planned.md`

### `configured`

Follows `planned`.

Reached when:

- the fact `renovate.config.ref` stands
- the fact `removal.commit` stands
- the fact `dependabot.status` equals `:absent`

Runbook: `kb/runbooks/renovate-migration-configured.md`

### `verified`

Follows `configured`.

Reached when:

- the fact `renovate.dashboard` stands
- the fact `renovate.first-pr` stands
- the fact `approval.maintainer` equals `:approved`
- `approval.maintainer` was asserted by a member of the `maintainer` role

Runbook: `kb/runbooks/renovate-migration-verified.md`

## Take it

```sh
tik adopt processes/renovate-migration.edn
```

The definition and its runbooks are copied into your store, and the
publisher's signature travels with them when a key in your `actors`
verifies it. Read the stages before you adopt: they say who has to
sign what, which is a decision about your organisation.
