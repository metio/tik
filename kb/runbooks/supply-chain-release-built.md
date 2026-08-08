---
type: runbook
title: supply-chain-release / :built
---

# supply-chain-release · built

The pipeline says which commit produced these bytes, and hands over the
checksums that name them.

- Record the version and the commit from the pipeline's own context:
  `tik set <id> version="\"1.4.0\"" commit="\"$GITHUB_SHA\""`.
- The `commit` fact must be signed by a member of the `ci` role. A build
  is the one party that can honestly say which source produced which
  artifact, so a human asserting it proves nothing.
- Attach the checksums file under a `checksums/` prefix:
  `tik attach <id> dist/SHA256SUMS --as checksums/SHA256SUMS`. The
  artifact's hash is on the log; the blob is stored beside it, so a
  reader compares their download against a value the signature covers.

Give `ci` an ephemeral key bound to the pipeline's own workload identity
rather than a long-lived secret — `tik bridge workload --github` binds a
per-run keypair to the token the platform already issues, and nothing
long-lived exists to leak.
