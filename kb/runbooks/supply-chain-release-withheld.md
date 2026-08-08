---
type: runbook
title: supply-chain-release / :withheld
---

# supply-chain-release · withheld

A version that was built and then deliberately not shipped, with the
decision and its author on the record.

- A member of `release-manager` records the hold:
  `tik set <id> approval=:hold`, signed.
- Say why in the same breath: `tik comment <id> "held: the scan found
  CVE-… in a transitive dependency"`. The comment is an artifact
  addressed by hash, so the reason travels in the evidence bundle
  alongside the decision.

This stage earns its keep months later. A version that quietly never
shipped looks identical to one nobody got around to, and the difference
matters when somebody asks what happened to 1.4.0. Reaching `:withheld`
answers that from the log rather than from memory.

A held version stays held. If the problem is fixed, that is a new build,
a new commit and a new ticket — not an edit to this one.
