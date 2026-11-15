---
type: runbook
title: ci-failure / :triaged
---

# ci-failure: triaged

The cause of the streak is on record. Whoever investigated it records one
of the declared causes:

- `tik set <id> cause=flaky` — the failure does not reproduce.
- `tik set <id> cause=code` — a change in this repository broke it.
- `tik set <id> cause=dependency` — an updated dependency broke it.
- `tik set <id> cause=config` — CI or build configuration broke it.
- `tik set <id> cause=infra` — runners, registries or the network failed.
- `tik set <id> cause=upstream` — a service outside our control failed.
