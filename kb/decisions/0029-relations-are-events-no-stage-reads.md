---
type: decision
status: accepted
date: 2026-11-21
title: A relation between tickets is its own event kind, with a closed vocabulary that no stage reads
supersedes: 0024-link-values-name-foreign-tickets
---

# ADR 0029: Relations are events no stage reads

## Decision

**A relation between two tickets is recorded by its own event types,
`:relation/assert` and `:relation/withdraw`, and stage derivation never
reads one.** No guard operator names a relation, so `status`, `whatif`,
`debug`, `rederive` and `verify` derive the same stages from a log with
or without its relations. A relation is navigation and scheduling. It is
never evidence.

**The body holds exactly two keys:**

```clojure
{:relation/kind   :depends-on            ; from the closed vocabulary
 :relation/target #uuid "…"}             ; or {:ticket #uuid "…" :head "sha256-…" :store "…"}
```

A target is a local ticket uuid or the foreign reference of ADR 0024,
whose shape and meaning carry over unchanged: the uuid is the identity,
`:head` is the version observed when the relation was made, and `:store`
is advisory. A body has no other keys, no free text and no value field.

**The vocabulary is closed and versioned by tik**, as the guard
vocabulary is:

| Kind | What the lenses do with it |
| --- | --- |
| `:depends-on` | `next` holds the ticket back until the target settles; `plan` draws the edge, the critical path and the unlock impact |
| `:supersedes` | the board hides the superseded target; the target's `status` names its successor |
| `:follows-up` | `status` lists a ticket's follow-ups, such as the change a `follow-up` review verdict opens |
| `:duplicates` | the board hides the duplicate and names the original |

A definition cannot add a kind. A new kind is a tik release with an ADR,
the same bar as a new guard operator, so no relation can carry data or
meaning that tik does not define.

**A definition opts in per kind** with `:process/relations`, a set of
kinds. A ticket whose definition omits the key accepts no relations. The
key is part of the pinned bytes, so widening it is a new definition
version and moves tickets only through `reprocess`.

**A relation is written on its source ticket by an actor who may act on
that ticket now.** That actor is a member of a role the definition names,
or one for whom the frontier admits at least one action. The
agent surface gains `tik agent relate <id> <kind> <target>`, refused with
exit 3 unless the definition opts into the kind and the frontier admits
the actor. The target ticket needs no such standing, because nothing is
written to its log.

**A relation is validated when it is written**, so the log never holds a
malformed one from tik itself:

- the kind is in the vocabulary and in the definition's `:process/relations`;
- a local target exists in the store, and a foreign target is a
  well-formed reference that names a head;
- a ticket does not relate to itself;
- a `:depends-on` that would close a cycle among local tickets is refused;
- asserting a relation that stands is a no-op, and withdrawing one that
  does not stand is refused.

**The relations standing on a ticket** are, for each `(kind, target)`
pair, the latest assert or withdraw in the causal order of the event
DAG. Two concurrent events on one pair are shown as conflicted in
`status`; a lens treats a conflicted `:depends-on` as standing, which
holds a ticket back rather than releasing it.

**Cross-ticket gates are evidence facts.** `tik bridge gate` mints the
upstream's milestone into the downstream ticket as a fact under
`[:gate <name>]`, whose value is the ADR 0024 reference with the head
observed, beside the signed `:gate` attestation it writes today. A guard
reads `[:fact [:gate <name>]]`. The fact records that an actor looked at
the upstream and saw a sticky stage reached at a named head, which is
evidence; a relation records only that one ticket points at another.
The reverse `[:link :gates]` fact is no longer written, since a lens
derives which tickets a release gated from the `[:gate …]` facts that
name it.

## Context

ADR 0024 put links into facts under `[:link …]`, and PLAN §2 chose that
shape because it bought dispute, retraction and supersession for free.
The same choice made a link readable by guards and subject to the
frontier. Both turned out to work against what a link is for.

- **A guard on a pointer advances a stage on no evidence.** A
  definition in production use guards a `:candidate` stage on
  `[:fact [:link :depends-on]]`, so recording which ticket this one waits
  on is part of what makes it a candidate. A pointer proves nothing
  about the work; `tik.link` says itself that nothing here reads another
  ticket.
- **The frontier withholds links from the actors who know them.** An
  agent may set only what the frontier admits for the next stages. A
  `change` ticket has no `[:link …]` fact in any stage, so an agent
  working on two dependent changes cannot record the dependency at all,
  and a definition that admitted one would have to make it evidence of
  some stage to do so.
- **Facts invite a general metadata channel.** Any definition may
  declare any `[:link <anything>]` path with any schema, so the reserved
  prefix constrains nothing about what a link means or holds.

Relations as events keep what made links-as-facts attractive. Each
relation is signed, content-addressed, has parents, is checked by
`tik verify` and travels in an evidence bundle, so its audit trail is the
log's. Retraction becomes `:relation/withdraw`, and supersession is the
latest event per pair. Dispute does not carry over, deliberately: the
dispute machinery exists to contest evidence, and a relation that is
wrong is withdrawn by whoever may act on the ticket.

The events are also safe under ADR 0009. A reader that predates them
preserves, hashes and ignores them, and because no stage reads a
relation, that reader derives exactly the stages a current one does.
Adding them is the kind of change ADR 0001 and PLAN §2 reserve for new
capability: new event types under a version bump, never pluggable code.

Alternatives considered:

- **A free metadata map on the ticket.** Unbounded keys and values would
  become a second state channel that definitions cannot see and lenses
  interpret ad hoc, which is the abuse this decision exists to prevent.
- **Exempting `[:link …]` facts from the frontier.** It fixes the agent
  problem and keeps the guard problem, and it makes one fact prefix
  behave unlike every other fact.
- **Letting definitions guard on relations behind an opt-in.** A single
  definition that does so turns relations back into state for every
  reader of its tickets, and the maintainer decided that no guard ever
  reads a relation.

## Consequences

- The event vocabulary grows from seven types to nine. PLAN §2, the
  `event-types` docstring and the events-and-facts concept page change
  with the release that adds them.
- `tik.link` keeps parsing and rendering references for relation targets
  and `[:gate …]` values, and stays total, because a target arriving
  from a replica or a bundle is not guaranteed to be well formed.
- `plan`, `query` and `inspect` read relations. `plan` stays total over
  cycles: write-time refusal covers one store, while two replicas can
  each accept one half of a cycle, and a foreign cycle is still not
  detected anywhere, as ADR 0024 recorded.
- A target may be missing from a replica that has not received it yet,
  so every lens tolerates a relation whose target does not resolve.
- For one release the lenses read both `[:link …]` facts and relations.
  A `tik store` upgrade step then turns each standing link fact into a
  relation event signed by the actor who runs it, with the original
  fact's event among its parents, and turns each `[:link <gate-name>]`
  minted by `bridge gate` into a `[:gate <gate-name>]` fact. After that
  release, `tik lint` reports a `[:link …]` path in a definition as an
  error.
- A definition that guards on a `[:link …]` fact drops that guard and
  opts into the kind instead, keeping whatever other facts already name
  its subject. Its tickets move only through `reprocess`, and the dry
  run shows any stage they would lose.
- `tik set <id> link.<kind>=<id>` stops being the way to relate tickets;
  `tik relate <id> <kind> <target>` and `tik unrelate` replace it, and
  the CLI keeps the short reference spellings of ADR 0024.
