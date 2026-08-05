---
title: Decisions
description: The architecture decision record — what was chosen, why, and what it rules out.
---

Every load-bearing choice in tik has a record: the decision, the context
that forced it, and the consequences that follow. Several of them exist to
name something tik will *not* do, which is the part that keeps the model
small.

A few worth reading first:

- **Derived state is never authoritative** — the one law, written down as
  a constraint on every future feature.
- **Coordination-free horizontal scaling** — no leader, no lock, no
  consensus, and what that forbids.
- **Stored bytes are the hashed region** — why `sha256sum` alone audits a
  store, and why signatures are detached.
- **Stratified negation** — how negation inside a fixpoint stays
  deterministic.
- **Time semantics** — three clocks, never conflated, and which one a
  guard reads.

These files live in the repository's knowledge bundle under
`kb/decisions/`, so a checkout and this site carry the same record.
