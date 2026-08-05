---
type: runbook
title: release / :published
---

<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# release: published

A maintainer decided to ship, and that maintainer is not the party that built
it.

```sh
tik set <id> approval=:ship --actor seb
```

The `:different-person` guard compares who asserted `commit` with who asserted
`approval`. The pipeline cannot approve its own release — not because a rule
forbids it, but because the two facts would carry the same signature and the
guard would not hold.

Sticky milestone: shipping is a historical fact. A vulnerability disclosed next
week does not un-publish this version. It starts a new ticket, and this one
keeps saying what was true on the day.
