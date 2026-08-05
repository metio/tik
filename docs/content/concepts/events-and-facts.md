---
title: Events and facts
description: Seven event types, five fact statuses, and the content addressing that makes a store auditable with coreutils.
tags: [events, facts, disputes, storage]
---

A ticket is an append-only set of events. Everything else about it is
computed.

## Seven event types

The vocabulary is closed and versioned, because the semantics of a
verifiable kernel have to be enumerable:

| Event | Meaning |
|---|---|
| `:ticket/create` | the ticket exists, pinned to a process definition by hash |
| `:fact/assert` | a claim about a path: `[:severity] = :high` |
| `:fact/retract` | withdraw a claim, with no replacement |
| `:fact/dispute` | reject a claim, with a reason |
| `:artifact/attach` | a file, stored and addressed by its hash |
| `:attestation/add` | a signed claim the kernel does not interpret |
| `:process/migrate` | re-pin this ticket to a newer definition |

Things that look like they need their own type turn out not to. Comments
are artifacts — text blobs attached by hash. Links are facts under a
`[:link …]` path. Work records are `:work` attestation claims. Witness
countersignatures are detached sidecars over a head rather than events,
because an event would move the very head it witnesses.

## Content addressing

An event's id is the SHA-256 of its canonical bytes, and the stored file is
named for that id. The bytes on disk are exactly the hashed region, so a
store audits with nothing but coreutils:

```sh
sha256sum tickets/*/events/*.edn   # filename must equal the digest
```

Signatures never live inside the hashed region. They are detached sidecars
alongside the event, which is what lets a second person add their signature
to an event without changing its identity.

Every event names its parents, forming a Merkle DAG. Parents carry
integrity and causality — they are how a replica knows whether two writes
saw each other. They never carry ordering: the fold orders by `(at, id)`
over the event *set*, which is what makes the reducer total, commutative,
and idempotent.

## Fact status

One function decides why a fact does or does not satisfy a guard, and
guards consult nothing else:

| Status | Meaning |
|---|---|
| `:present` | a value stands |
| `:absent` | nothing has been claimed |
| `:retracted` | withdrawn, no replacement offered |
| `:disputed` | rejected with a reason, and unusable until corrected |
| `:conflicted` | causally concurrent claims disagree |

## What a dispute means

A dispute rejects **the value that stood when it was raised**, so the
assertion that answers it has to claim something else. Re-asserting the
rejected value verbatim leaves the path `:disputed` — otherwise the party a
dispute holds accountable could clear it in one command by retyping the
same fact.

A retraction clears disputes too, because the rejected claim is gone. A
dispute raised on a path holding nothing rejects no particular value, so
the first assertion answers it, which is what keeps a dispute from making a
path permanently unusable.

Disputes accumulate: several people may reject the same claim, and each
withdraws only their own.

```sh
tik dispute 3184 category --reason "this is billing, not technical"
tik dispute 3184 category --withdraw     # takes back your own objection
```

## What a conflict means

When two replicas write the same path without having seen each other, and
they disagree, the fact reads `:conflicted` and every guard that depends on
it fails with a reason saying so. Nobody has to win. A later write that
observed both supersedes them, and the conflict resolves by derivation.

Concurrent writes that happen to *agree* are not a conflict — there is no
disagreement to surface.
