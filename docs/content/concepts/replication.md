---
title: Replication
description: Merging by set union — no leader, no lock, no consensus, and no file-level conflicts to resolve by hand.
tags: [replication, git, crdt, conflicts, scaling]
---

A tik store replicates by copying files. Two clones append independently,
merge by set union, and derive the same answer — because derivation is a
pure function of the event set and events are addressed by their content.

```sh
git pull && git push     # this is the replication protocol
```

## Why the merge is trivial

Every event lives in its own file whose name is the SHA-256 of its bytes.
Two people adding evidence to the same ticket create different files, so
the merge is a union of directories and git never sees a conflicting hunk.
Two people making the *identical* claim produce byte-identical files with
the same name, and the union keeps one — deduplication for free.

That is why merging never requires a human to resolve a text conflict
inside a ticket. What it can produce is a *derived* disagreement, and that
is the point.

## Disagreement resolves by derivation

When two replicas wrote the same fact path without having seen each other,
and their claims differ, the fact reads `:conflicted`. Every guard reading
it fails with a reason naming the competing claims, and `tik explain` asks
for a value that supersedes them:

```console
$ tik explain 3184
To reach :triaged:
  ✗ fact [:category] has conflicting concurrent assertions — one must
    supersede (ADR 0003)
```

Nobody wins an election. A later write that observed both competitors
settles it, and the conflict disappears from the derivation. Parents are
what make "observed" a checkable claim rather than a guess.

## No leader, no lock, no consensus

No operation needs a leader, a distributed lock, or a quorum to be correct:

- **Reads shard without limit.** A derivation reads one ticket's own log
  and nothing else. Guards never query across tickets, so N stateless
  replicas across M shards scale reads with zero coordination.
- **Writes never resolve by lock.** A write is either a content-addressed
  event that is a pure function of its intent, or an append whose
  contention resolves by derivation.
- **Self-minted events are deterministic.** Anything a replica mints on its
  own — a recurring ticket, a scheduled probe — derives its id and every
  byte, `:at` included, from its inputs. Two replicas firing the same
  schedule concurrently mint the same event, and the union keeps one.

A tik replica is stateless by construction, so horizontal scaling is the
preferred deployment.

## Partial logs say so

Between syncs, a replica's copy of a ticket is incomplete by definition,
and a derivation over a partial log is not merely incomplete — it is
confidently wrong in a specific way. Ancestry the replica cannot see reads
as concurrency, so writes that supersede each other in a linear history
surface as `:conflicted`, and explain asks somebody to resolve a conflict
that does not exist.

Every lens therefore checks whether referenced ancestors are missing and
says so, rather than presenting a mid-sync view with the confidence of a
complete one.

## Backends

The file store is the signed interchange format: a `tickets/` tree that
`sha256sum` audits and git replicates. The SQLite backend keeps the same
events in a single file when that suits operations better. Convert in place
with `tik store migrate --to sqlite|file` — events and their detached
signatures both travel.
