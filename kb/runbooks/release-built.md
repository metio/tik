---
type: runbook
title: release / :built
---

<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# release: built

The artifacts for this version exist and the pipeline has said which commit
produced them.

```sh
tik set <id> version=2026.8.5204821 commit=<sha> --actor ci
tik attach <id> checksums/SHA256SUMS
```

`commit` must be signed by a member of `:ci`: the pipeline is the only party
that can honestly say which source produced these bytes, and a human asserting
it is a claim about something they did not observe.

The checksums file is attached rather than pasted so it is addressed by its
hash. The hash is in the trust domain; the blob is not (ADR 0014). Anyone can
re-fetch the artifacts and check them against it without trusting this store.
