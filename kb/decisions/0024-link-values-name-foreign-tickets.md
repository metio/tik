---
type: decision
status: accepted
date: 2026-08-06
title: A link's value names its referent; a foreign ticket is a map carrying the head observed
supersedes: null
---

# ADR 0024: Link values name foreign tickets

## Decision

**A link is an ordinary fact under a `[:link …]` path, and the referent
lives in the VALUE, never in the path.** A value is either a bare uuid —
a ticket in this store — or a map:

```clojure
{:ticket #uuid "…"      ; identity
 :head   "sha256-…"     ; the head observed when the claim was made
 :store  "…"}           ; advisory: where to fetch it
```

**A ticket uuid is the identity on its own.** Uuids are globally unique
by construction, so `(store, ticket)` over-specifies: naming a store
tells a reader where to *find* the ticket, never which ticket it is.
`:store` therefore carries no authority and no comparison depends on it.

**`:head` is the reason the map exists.** One head commits to the entire
ancestry (ADR 0004), so a link that pins one says which version of the
other ticket the claim was made against, and a reader can tell a stale
link from a current one. Without it, a cross-store link is a claim about
a moving target.

**`[:link …]` is reserved.** A missing fact under it means "waiting on
something elsewhere", which a lens can tell apart from "nobody here did
the work yet" — the same shape, two very different answers.

## Context

The cross-store gate design (docs/IDEAS.md) named this as the decision
everything else waits behind, and warned that the choice is inherited by
every later integration — bundles, the registry, the external tracker
adapters all want to name a foreign ticket. It offered two shapes: a
scoped fact path, or a map-valued fact.

The path is not available. `:process/facts` keys are vectors of KEYWORDS
(`tik.process/ProcessDef`), so a uuid or a store name cannot go in one
without changing what a path is everywhere — in the linter, the schemas,
`parse-key`, and every existing definition. The value has no such
constraint: it is arbitrary EDN, and `tik set <id> link.depends-on=<uuid>`
already put the referent there. Extending that value from a bare uuid to
a map is backward compatible and needs no kernel change, no new event
type, and no guard vocabulary.

The trigger to revisit fired from the direction the entry predicted. A
release now records itself in a store CI cannot push to and ships as an
evidence bundle, so "this bug is fixed in the release recorded there"
requires naming a ticket that lives somewhere else.

## Consequences

- Links keep every property of a fact for free: dispute, supersession,
  retraction, and `:conflicted` when two bridges disagree. That is why
  the banked gate design has guards read the FACT rather than an
  attestation, whose mandatory freshness window would close a milestone
  gate again after it opened.
- A guard on a link reads the local fact and stops. Nothing reads another
  ticket, so evaluation stays offline and correct after the other store
  is gone. Whoever minted the link is the one who looked.
- A bare uuid remains valid and remains the short form for a local link,
  so nothing already written changes meaning.
- `tik.link` is the one place that interprets a value, and it is total:
  a link's value is whatever somebody asserted, so a lens asking what it
  points at must decline a number or a wrong-shaped map rather than
  raise.
- The CLI spelling is `<uuid>`, `<uuid>@<store>`, or
  `<uuid>@<store>#<head>` — a person types the short form and gets the
  short form back.
- Cross-store CYCLE detection is still missing: `tik lint` reasons over
  one definition, so A-gates-on-B-gates-on-A deadlocks with no diagnostic
  anywhere. Recorded, not solved.
