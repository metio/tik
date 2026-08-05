---
title: Runbooks
description: One page per stage — what reaching it means and what evidence gets you there.
---

A process definition names a runbook per stage through its `:hint` field,
and `tik explain` prints that link with the missing evidence:

```console
To reach :triaged:
  ✗ set fact [:category] ([:enum :billing :technical :account :abuse])
  (see: kb/runbooks/support-request-triaged.md)
```

The pages below are those files. They live in the repository's knowledge
bundle under `kb/runbooks/`, so the same document serves a checkout, an
agent, and this site.
