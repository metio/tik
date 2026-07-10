---
type: runbook
title: support-request / :triaged
---

# Runbook: support-request / :triaged

Assert `category` and `severity` — both must come from a triager
(the `:signed-by` guard checks the role, not the values). Category is
a claim about what KIND of problem this is; when genuinely unsure,
pick the closest and say why in a comment — a later dispute is cheap
and leaves better history than stalling. Severity reflects impact on
the customer, not effort to fix.
