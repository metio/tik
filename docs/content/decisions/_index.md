---
title: Decisions
description: The architecture decision record — what was chosen, why, and what it rules out.
tags: [decisions, adr, design]
---

Every load-bearing choice in tik has a record: the decision, the context that
forced it, and the consequences that follow. Several exist to name something
tik will *not* do, which is the part that keeps the model small.

New here, read these four: [derived state is never
authoritative](/decisions/0013-derived-state-never-authoritative/) is the one
law written as a constraint on every future feature;
[coordination-free](/decisions/0021-coordination-free-horizontal-scaling/) is
its companion; [stored bytes are the hashed
region](/decisions/0007-stored-bytes-are-the-hashed-region/) is why
`sha256sum` alone audits a store; and [three
clocks](/decisions/0012-time-semantics/) is why a guard's meaning includes
which clock it reads.

These files live in the repository's knowledge bundle under `kb/decisions/`,
so a checkout and this site carry the same record.

## The laws

| Decision | What it settles |
| --- | --- |
| [0013](/decisions/0013-derived-state-never-authoritative/) | Derived material may be cached only if disposable and untrusted. |
| [0021](/decisions/0021-coordination-free-horizontal-scaling/) | No leader, no lock, no consensus — on any correctness path. |
| [0001](/decisions/0001-event-log-acceptance-test/) | The event-log acceptance test every feature must pass. |

## Derivation

| Decision | What it settles |
| --- | --- |
| [0005](/decisions/0005-stratified-negation/) | Negation inside the fixpoint must be stratified to stay deterministic. |
| [0003](/decisions/0003-conflicts-block/) | Concurrent conflicting assertions block guards; people resolve them. |
| [0016](/decisions/0016-explain-stability-contract/) | Explain's structured reasons are the stable API; the prose is not. |
| [0018](/decisions/0018-conformance/) | Conformance is the corpus, the laws, and the normative sweep semantics. |
| [0019](/decisions/0019-effects-observe-derivation/) | Effects observe derivation; transport is not a domain concept. |

## Time

| Decision | What it settles |
| --- | --- |
| [0012](/decisions/0012-time-semantics/) | Three clocks, never conflated; the clock is part of the guard. |
| [0022](/decisions/0022-no-step-reads-the-future/) | The claimed clock is clamped to the evaluated clock. |

## The log and its bytes

| Decision | What it settles |
| --- | --- |
| [0004](/decisions/0004-mandatory-parents/) | Parents are mandatory; the log is a Merkle DAG. |
| [0006](/decisions/0006-hash-policy/) | SHA-256, self-describing ids, one algorithm per store. |
| [0007](/decisions/0007-stored-bytes-are-the-hashed-region/) | Stored bytes are exactly the hashed region; signatures are detached. |
| [0008](/decisions/0008-canonical-serialization-protocol/) | Canonical serialization is a versioned wire protocol. |
| [0017](/decisions/0017-deletion-and-compaction/) | Events are never deleted; blobs may be; nothing compacts into authority. |
| [0020](/decisions/0020-eventstore-contract/) | The contract every storage backend must honour. |

## Trust and authority

| Decision | What it settles |
| --- | --- |
| [0010](/decisions/0010-authority-model/) | Signatures establish authorship; authorization is derived. |
| [0011](/decisions/0011-log-admission-vs-trust/) | The log admits all well-formed claims; trust is evaluated, not filtered. |
| [0009](/decisions/0009-unknown-data-policy/) | Unknown data is handled differently per layer, on purpose. |
| [0014](/decisions/0014-artifact-semantics/) | The artifact hash is in the trust domain; the blob is not. |
| [0015](/decisions/0015-process-definition-trust/) | Definition hash is identity; publication signatures are authority. |
| [0002](/decisions/0002-pinned-process-versions/) | Tickets pin their definition hash; migration is an event. |
| [0023](/decisions/0023-key-bindings-are-evidence/) | A verified key binding grants signing authority; pinned keys anchor it. |
| [0024](/decisions/0024-link-values-name-foreign-tickets/) | A link's value names its referent; a foreign ticket carries the head observed. |
| [0025](/decisions/0025-the-evidence-bundle-is-a-versioned-format/) | The evidence bundle is a named, versioned format; a badge names the derivation. |
