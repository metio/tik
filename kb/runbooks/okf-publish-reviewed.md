---
type: runbook
title: okf-publish / :reviewed
---

# okf-publish · reviewed

A reviewer vouches the bundle is accurate.

- A member of the `reviewer` role signs over the `[:bundle]` fact
  (`tik sign <id> --over bundle`, or the review attestation flow).
- Reviewing a *different* hash than the current one does not count —
  the signature must cover the bundle as it stands now.
