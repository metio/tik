---
type: runbook
title: release / :withheld
---

<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# release: withheld

A maintainer decided this version does not ship.

```sh
tik set <id> approval=:hold --actor seb
tik comment <id> "held: the scan is clean but the advisory for the transitive dep is unresolved upstream"
```

Recorded with the same weight as shipping, because the reason a version never
shipped is worth as much a year later as the reason one did — and it is the
question nobody can answer from a green pipeline.

Withholding is not terminal. A later `approval=:ship` supersedes it, and the
log keeps both decisions with their timestamps and signatures.
