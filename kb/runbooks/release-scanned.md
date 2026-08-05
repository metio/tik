---
type: runbook
title: release / :scanned
---

<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# release: scanned

A vulnerability scan ran against this version's dependencies, found nothing,
and said so recently enough to be about today's advisories.

```sh
tik attest <id> :vulnerability-scan --body '{:tool "clj-watson" :db "github-advisory"}' --actor ci
tik set <id> scan.result=:clean --actor ci
```

Both halves are needed and they say different things. The fact records the
verdict; the attestation records that a scan happened and when. The one-day
freshness window is what makes replay useless — a scan from last month is
cryptographically valid and fails this guard honestly, because the advisory
database it consulted no longer exists.

A scan that finds something is `scan.result=:findings`, which is not a failure
to record but a reason this version does not reach `:scanned`. Fix the finding
and cut a new version; do not re-assert the same value on this one.
