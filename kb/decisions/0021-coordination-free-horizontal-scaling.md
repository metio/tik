---
type: decision
status: accepted
date: 2026-07-14
title: Coordination-free horizontal scaling — no leader, no lock, no consensus
supersedes: null
---

# ADR 0021: Coordination-free horizontal scaling

## Decision

**tik's second law, standing beside derived-beats-declared: every
operation must be correct on arbitrarily many replicas that share nothing
and reconcile only by eventually unioning their grow-only,
content-addressed event sets. No feature may require leader election, a
distributed lock, a quorum/consensus round, or any synchronous
cross-replica coordination to be correct.**

This is not an aspiration to bolt on later; it is a constraint every
feature is checked against now, exactly like the first law. Three
consequences are load-bearing and must stay true:

1. **Reads shard without limit.** A derivation reads one ticket's own log
   and nothing else — guards never query across tickets (ADR 0004 scope).
   So N stateless replicas × M `hash(ticket-id)` shards scale reads with
   zero coordination; cross-ticket views are scatter-gather or a
   disposable index (ADR 0013), never a shared authority.

2. **Writes never resolve by lock.** Every write is either (a) a
   content-addressed event that is a **pure function of its intent** — two
   replicas forming the same intent emit byte-identical events that
   union-merge to one (append is idempotent by id, ADR 0020) — or (b) a
   CRDT-safe append whose contention resolves by **derivation**: two
   competing facts reduce to `:conflicted` (reduce.cljc fact-status), a
   derived state, not a lock to be won.

3. **Generated events are deterministic.** Any event a replica MINTS on
   its own — `recur`, `probe`, any scheduled or automated create — must
   derive its id and every byte-bearing field, `:at` included, as a pure
   function of its inputs. `recur` is the worked example: ticket id =
   `nameUUIDFromBytes(process, period)` and `:at` = the period-start the
   porcelain parses from the label (or `--at`), so two backends firing the
   same schedule concurrently mint the same event, and the union keeps one.

## Context

The first law (derived-beats-declared, ADR 0013) is what makes the second
law reachable: with no authoritative mutable state, there is nothing that
must be serialized behind a lock or agreed by a quorum. A "current stage"
column, a uniqueness table, a first-come assignment claim would each
reintroduce a linearization point — and a linearization point is what
forces leader election or consensus and caps horizontal scale. tik has
none, and this ADR forbids adding one.

The pressure point is automation. The moment a replica acts on its own
(the delegated-agent backend in IDEAS: scheduled `recur`, standing-ticket
`probe`, outbound `effects`), the naive design reaches for "elect one
replica to fire the timer" — a leader. The second law rejects that: make
the minted event a pure function of its intent instead, and every replica
may fire the same timer because the duplicates collapse by content
address. Coordination is designed out, not coordinated away.

"No sync" is precise: no *synchronous coordination on the correctness
path*. Replicas still exchange events, but asynchronously — git-style
have/want set reconciliation over event ids (ADR 0020), which is
lock-free and can only ever leave a replica temporarily *incomplete*
(self-healing on the next sync), never *wrong*. The sole admissible
exception is outbound side-effect **delivery** that genuinely cannot be
made idempotent; a narrow per-pipeline lease may serialize *delivery*
there, never the log, and never a read or a derivation.

## Consequences

- **A tik replica is stateless by construction.** No StatefulSet ordering
  is needed for correctness, no leader sidecar, no quorum
  PodDisruptionBudget. Scale a Deployment to N, evict any pod at any
  moment, and every replica still answers correctly — which is why
  horizontal scaling (Kubernetes) is the preferred deployment.
- **The violation test is a first-class smell.** A design that needs a
  replica to win an election, hold a lock, or agree with a quorum before
  it can act violates this law as surely as caching a derived value
  violates the first. The fix is always the same shape: turn the
  operation into a pure-function content-addressed event (dedup by hash)
  or a derivation of conflict (`:conflicted`), never a lock.
- **Determinism is now a review gate for any generated event.** A new
  automated mint must be shown to be a pure function of its inputs — a
  property test that two independent stores produce byte-identical events
  is the standard evidence (`cli_test.clj`
  `recur_mints_byte_identical_events_on_independent_stores`).
- **The TLA+ Merge model is the formal backstop.** Convergence under
  arbitrary replica interleavings is already the property it checks; a
  feature that needs coordination would be a feature the merge model
  cannot express as pure set union.
