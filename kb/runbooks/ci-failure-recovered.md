---
type: runbook
title: ci-failure / :recovered
---

# ci-failure: recovered

The workflow passed again on this branch. The forge bridge records the
first green run after the streak as `forge.green-run`; nobody else can
satisfy this stage, because the guard requires the bridge's signature.
