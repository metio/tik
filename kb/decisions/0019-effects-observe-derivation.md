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
  content hash of `(ticket, stage, sink identity)`, so replays and
  re-derivations dedupe without a delivery-state machine — and any
  delivery ledger an effect runner keeps is disposable porcelain
  (ADR 0013). The key deliberately excludes the head: a head moves with
  every appended event, so keying on it would re-notify the same stage
  on every subsequent write — the opposite of dedup.
- **Structural dedup is scoped to one runner's ledger, not to the
  estate.** The key is stable across replicas, but the ledger recording
  which keys were sent is local, so N runners observing the same
  transition deliver N times. Under the horizontal scaling ADR 0021
  calls preferred, that is the narrow per-pipeline delivery lease
  ADR 0021 admits as its one exception — the only place coordination is
  allowed, and it serializes DELIVERY, never the log. Until a runner
  takes such a lease, run exactly one effect runner per estate.
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
  — at-least-once delivery, deduped against its own ledger, no
  coordination. Across runners the guarantee is at-least-once per
  runner; exactly-once across an estate is what the ADR 0021 delivery
  lease buys, and nothing more.
