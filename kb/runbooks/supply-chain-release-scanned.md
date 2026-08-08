---
type: runbook
title: supply-chain-release / :scanned
---

# supply-chain-release · scanned

A vulnerability scan ran against this build and came back clean.

- Attest the scan, naming the tool and the advisory database it read:
  `tik attest <id> :vulnerability-scan --body '{:tool "…" :db "…"}'`.
- Record the outcome as a fact `ci` signs:
  `tik set <id> scan.result=:clean` — or `:findings`.
- `:findings` is a legitimate answer and belongs on the record. Let the
  scan write what it found and let the ticket carry it; the stage stays
  unreached, and the release is a decision somebody makes with that in
  front of them.

Run the scan inside the release job rather than reusing an earlier
result. The one-day window exists because a scan is a claim about the
advisory database at a moment: last week's clean result is
cryptographically valid, honestly stale, and says nothing about the
advisories published since.
