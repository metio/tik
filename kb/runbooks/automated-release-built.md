---
type: runbook
title: automated-release / :built
---

# automated-release · built

The pipeline says which commit produced these bytes, and hands over the
checksums that name them.

- Record the version and the commit from the pipeline's own context:
  `tik set <id> version="\"1.4.0\"" commit="\"$GITHUB_SHA\""`.
- The `commit` fact must be signed by a member of the `ci` role. Name
  yours first with `tik roles add ci <actor>` — the definition ships with
  the role empty so adoption inherits nobody else's org chart.
- Attach the checksums under a `checksums/` prefix:
  `tik attach <id> dist/SHA256SUMS --as checksums/SHA256SUMS`. The hash
  goes on the log and the blob is stored beside it, so a reader compares
  their own download against a value the signature covers.

Give `ci` a keypair generated per run and bound to the pipeline's own
workload identity — `tik bridge workload --github` binds it to the token
the platform already issues — so nothing long-lived exists to leak and
the binding is what makes the signatures checkable afterwards.

The stage is sticky: a published version is never rebuilt, so "this
commit produced these bytes" is a fact about a moment that stays true.
