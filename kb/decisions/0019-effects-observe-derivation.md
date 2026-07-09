---
type: decision
status: accepted
date: 2026-07-09
title: Effects observe derivation; transport is not a domain concept
supersedes: null
---

# ADR 0019: Effects observe derivation

## Decision

Derivation is pure; **effects observe derivation**. An effect planner
watches derived frontier transitions and fires outbound integrations —
webhooks, mail, chat — under these rules:

- **Delivery never touches truth.** Success or failure of an outbound
  call changes nothing in any ticket's log.
- **No transport event types, ever.** There is no `:webhook/sent`, no
  `:email/delivered`, no `:kafka/published` — transport is not a domain
  concept. When the *business outcome* matters ("customer was
  notified"), it re-enters as a fact or attestation asserted by the
  notifying actor, accountable like any claim.
- **Idempotency is structural, not stateful**: the effect key is the
  content hash of `(ticket, stage, effect-id, head)`, so replays and
  re-derivations dedupe without a delivery-state machine — and any
  delivery ledger an effect runner keeps is disposable porcelain
  (ADR 0013).
- **Inbound is symmetric and already covered**: an external system's
  webhook is just another actor whose bridge validates, authenticates,
  and appends signed events (ADR 0001, 0011).

## Context

The first integration author under deadline pressure will want to
record "the webhook succeeded" in the ticket — and each such record is
a transport detail promoted to domain truth, the exact accretion path
by which event vocabularies grow to `CommentEdited`/`EmailSent` size.
The rule that prevents it is cheap and total: effects are a lens with
side effects, downstream of truth, never upstream.

## Consequences

- Retry policy, dead-letter queues, and delivery dashboards are effect-
  runner concerns with no kernel surface.
- Notifications phrase themselves from the timeline ("resolution added
  by Alice — now eligible for QA"), because that is the only truth
  there is.
- An effect runner crashing and replaying produces the same effect keys
  — at-least-once delivery with structural dedup, no coordination.
