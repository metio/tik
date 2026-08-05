---
title: tik
---

# tik

**tik is a process system, not a ticket system.** A ticket is an append-only
log of signed, content-addressed facts and artifacts. Where that ticket
stands in its process is a **derived value** — a pure function of the log,
the process definition, and the current time.

Nobody moves a ticket. People and agents contribute evidence, guards check
it, stages derive, and `tik explain` says exactly what is missing and who
can supply it.

```console
$ tik set 3184 severity=:high resolution.ref=abc123def
ok
$ tik explain 3184
To reach :resolved:
  ✓ [:fact [:resolution :ref]]
  ✗ attach an artifact whose path starts with "repro/"
  blocks: :closed
  (see: kb/runbooks/support-request-resolved.md)
```

## The one law

**Derived beats declared.** If something follows from the log, storing it as
authoritative state is a bug. Stage, readiness, "who is blocked", the board,
the inbox, the roadmap — every one of them is computed on read, so every one
of them is correct by construction. A cache that goes stale is a cache; the
answer lives in the events.

The law has a companion: **coordination-free by construction**. Every
operation is correct on arbitrarily many replicas that share nothing and
reconcile by unioning their event sets. No leader, no lock, no consensus —
contention resolves by derivation.

[Derived beats declared →](/concepts/derived-beats-declared/)

## What you get

- **An answer to "what now?"** — `tik explain` names the missing evidence,
  its schema, and who is allowed to sign it. `tik next` turns that into a
  per-person inbox.
- **Offline forever.** A store is files in a directory. Signatures are
  detached sidecars from `ssh-keygen -Y`; the filename of an event is its
  own SHA-256, so `sha256sum` alone audits the store.
- **Replication by `git pull`.** Two clones append independently and merge
  by set union, with no file-level conflict to resolve by hand.
- **Time travel that costs nothing.** Derivation takes `now` as an
  argument, so `tik status --at 2026-03-01` answers what was true in March
  by evaluating March, reproducibly.
- **Processes as data.** A definition is plain EDN, hash-pinned at ticket
  creation, linted for a closed guard vocabulary and stratified negation,
  and testable with scripted cases.

## Start here

- [Install](/get-started/install/) — one binary, or babashka from source.
- [Your first ticket](/get-started/first-ticket/) — a working store in five
  minutes, no server and no database.
- [Concepts](/concepts/) — the model behind the CLI.
- [Authoring processes](/authoring/) — designing one worth deriving.

Part of the [metio](https://metio.wtf) family. Licensed 0BSD and
[REUSE](https://reuse.software)-compliant.
