---
title: Derived beats declared
description: The one law — if something follows from the log, storing it as authoritative state is a bug.
tags: [law, derivation, design]
---

**If something can be derived, storing it as authoritative state is a
bug.** That is the whole law, and every design question in tik resolves
against it.

A ticket's stage is the obvious case. It is never written down anywhere. It
is `f(events, now)` — a pure function of the ticket's own event log, the
process definition that ticket pinned, and the instant you are asking
about. Ask twice with the same inputs and you get the same answer, forever.

## What the law buys

**Correctness by construction.** A stored status can disagree with the
facts; a derived one cannot. When somebody retracts the approval that a
ticket's `:approved` stage depended on, the stage regresses on the next
read, with no rollback step, no cleanup job, and no window during which the
board is lying.

**Every question about every moment.** Because `now` is an argument rather
than an ambient clock, "what was true on March 1" is answered by evaluating
March 1:

```sh
tik status 3184 --at 2026-03-01T00:00:00Z
```

**Replication without coordination.** There is no authoritative mutable
cell to serialize behind a lock, so replicas merge by unioning their event
sets and each derives the same answer independently. See
[Replication](/concepts/replication/).

**Auditability that survives the tool.** The conclusion is reproducible
from the signed bytes by anyone, including someone who does not run tik.
`tik bundle` packs one ticket into a tarball that verifies with coreutils
and `ssh-keygen` alone.

## What the law costs

Derivation is work done on every read, and the law forbids the obvious
shortcut of remembering the answer. tik pays that cost deliberately and
keeps it bounded: the fold is linear in events and polynomial in the size
of the process definition, which is authored and small. Fact lookups are
served from indexes the fold maintains, so history length does not turn
into a quadratic.

Performance problems get indexes, caches, or a different storage backend.
They never get new authoritative state. A cache that some lens keeps is
fine as long as nothing treats it as the truth — the truth is recomputed
from the events.

## The companion law

**Coordination-free by construction: no leader, no lock, no consensus.**
Every operation is correct on arbitrarily many replicas that share nothing
and reconcile only by eventually unioning their grow-only,
content-addressed event sets.

Reads consult one ticket's own log, so they shard without limit. Writes are
either content-addressed events that are a pure function of their intent —
two replicas forming the same intent emit byte-identical events, and the
union keeps one — or appends whose contention resolves by *derivation*:
two competing claims about a fact reduce to `:conflicted`, a derived state,
rather than a lock to be won.

A design that needs a replica to win an election, hold a lock, or agree
with a quorum before it can act breaks this law as surely as caching a
derived value breaks the first one.

## What the kernel refuses to answer

**The kernel answers "what follows from these signed facts?" It never
answers "what should happen next?"** There are no workflow transitions, no
scheduler, no policy engine, and no external queries in the core. A guard
consults one ticket's log and nothing else — never a service, never another
ticket, never a clock of its own.

Notifications, inboxes, webhooks, boards, and agent surfaces are all
porcelain over derivations. That boundary is what keeps evaluation offline,
reproducible, and true years from now.
