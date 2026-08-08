---
type: runbook
title: okf-publish / :published
---

# okf-publish · published

The bundle is public.

- Record where it went live: `tik set <id> published.url=<url>`.
- This stage is NOT sticky: if the bundle is later revised, approval
  regresses and the ticket drops back out of `published` until the new
  version is re-reviewed and re-approved. Stale public knowledge
  un-publishes itself by derivation.
