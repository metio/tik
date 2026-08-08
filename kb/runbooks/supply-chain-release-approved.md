---
type: runbook
title: supply-chain-release / :approved
---

# supply-chain-release · approved

Somebody who is not the pipeline looked at the evidence and decided to
ship.

- A member of `release-manager` records the decision:
  `tik set <id> approval=:ship`, signed with their own key.
- Four eyes is derived, not promised: `[:different-person [:commit]
  [:approval]]` compares who asserted each fact, so the actor that
  signed the commit cannot also sign the approval. A single-person
  project never reaches this stage, and the honest reading of that is
  "nobody independent looked", which is worth knowing.
- The stage is sticky. An advisory published next week does not
  un-approve a version that already shipped; it starts a new ticket
  against the next one.

Approve after `:attested` and `:scanned` hold, since both are the
material the decision is supposed to rest on. If you find yourself
approving first, the process is telling you something true about how
releases actually happen here.
