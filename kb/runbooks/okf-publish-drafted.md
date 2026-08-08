---
type: runbook
title: okf-publish / :drafted
---

# okf-publish · drafted

The bundle exists and is identified.

- Attach the OKF bundle files under `okf/` (`tik attach <id> okf/...`).
- Record the bundle's content hash: `tik set <id> bundle=<sha256>`.
  This hash is what review and approval will sign over, so a later
  revision (new hash) automatically un-reviews the bundle.
