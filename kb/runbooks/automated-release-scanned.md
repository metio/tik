---
type: runbook
title: automated-release / :scanned
---

# automated-release · scanned

A vulnerability scan ran against this build and came back clean.

- Attest the scan, naming the tool and the advisory database it read:
  `tik attest <id> :vulnerability-scan --body '{:tool "…" :db "…"}'`.
- Record the outcome as a fact `ci` signs:
  `tik set <id> scan.result=:clean` — or `:findings`.

`:findings` is a legitimate outcome and belongs on the record. Let the
scanner write what it found, let the ticket carry it, and let the release
ship anyway if that is the call — the stage stays unreached and the badge
says so, which is the whole reason this stage is worth deriving. A stage
nothing could ever fail would be decoration.

Run the scan inside the release job rather than reusing an earlier
result. The one-day window exists because a scan is a claim about the
advisory database at a moment; a result from last week is
cryptographically valid, honestly stale, and silent about everything
published since.

The stage is sticky, so it asserts that the scan was clean when this
version shipped. That does not claim the version is clean today, and a
release that has aged is worth re-scanning on a ticket of its own.
