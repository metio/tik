---
type: decision
status: accepted
date: 2026-07-09
title: Events are never deleted; blobs may be; nothing is compacted into authority
supersedes: null
---

# ADR 0017: Deletion, retention, and compaction

## Decision

- **Events are never deleted.** History is the truth substrate; an
  event store that forgets events is corrupt (missing parents,
  ADR 0004), not compact.
- **Blobs may be deleted.** Deletion removes *availability*, never
  *history*: the attach event and hash remain, and verify L3 reports
  verifiable absence (ADR 0014). This is the designed answer to GDPR
  erasure, leaked secrets, and retention policy — personal data and
  sensitive payloads belong in blobs, precisely so they are deletable.
- **No cryptographic compaction of event history.** Any "verified
  reduction summary" is derived state: usable as an untrusted
  accelerator, never as a replacement for the events it summarizes
  (ADR 0013). A store that can no longer replay is a store that can no
  longer verify.

## Context

Long-lived append-only systems always eventually ask "can we delete old
events?", usually citing storage cost or privacy law. The two motives
have different correct answers, and blurring them is the danger: storage
cost is a payload problem (blobs, quotas, lazy transfer — events
themselves are tiny), while privacy law is about *content*, which is why
deletable content lives behind hashes rather than inside events. The
design keeps a sharp line: **verification reports absence; it never
rewrites truth.**

## Consequences

- Process design guidance: anything that may need erasure goes into an
  artifact, not a fact value. Fact values are forever.
- Retention policy is blob policy. Event retention policy does not
  exist.
- If event volume itself ever becomes a real cost (PLAN §18 fact spam),
  the levers are write authorization and quotas at the store seam —
  admission control, not amnesia.
