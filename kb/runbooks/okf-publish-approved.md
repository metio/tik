---
type: runbook
title: okf-publish / :approved
---

# okf-publish · approved

An owner authorizes public release.

- A member of the `approver` role signs over `[:bundle]` — ideally a
  different person than the reviewer (separation of duties).
- Approval binds to the exact hash; revise the bundle and approval
  regresses, forcing re-approval before it can be published again.
