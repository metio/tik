---
type: decision
status: accepted
date: 2026-11-22
title: What a kajero notebook knows about a ticket enters it through a probe, as facts its guards read like any other
supersedes: null
---

# ADR 0030: A notebook enters a ticket through a probe

## Decision

**What a kajero notebook knows about a ticket reaches the ticket only as
facts a probe asserts.** No guard operator names a notebook, and
derivation never reads one. A process that wants knowledge as evidence
declares facts for it and composes the store's `probes/kajero.sh` into its
probe:

```sh
sh "$(dirname "$0")/pull-request.sh"
sh "$(dirname "$0")/kajero.sh"
```

**The probe reports three facts**, read from the notebook's
`/view.edn?selector=ticket=<id>`, which selects every entry whose records
cite the ticket as `tik:<id>` evidence:

| Fact | Value |
| --- | --- |
| `[:kajero :entries]` | the number of entries citing the ticket |
| `[:kajero :held]` | of those, the entries that held at least once and are not disputed |
| `[:kajero :revisions]` | their current revision hashes, sorted and space-separated |

A guard reads them with the operators that exist, for example
`[:malli [:map [[:kajero :held] [:int {:min 1}]]]]` for "at least one
lesson that somebody confirmed". Because the facts are re-derived from the world on
every probe, a stage guarded by them regresses on its own when the entry
is disputed or retracted, unless the process makes it sticky.

**An unreachable notebook fails the probe.** It never reports zero
entries, so a stopped service cannot read as forgotten knowledge and
regress a ticket.

**Nothing the notebook sends is printed unless it is well formed.** The
counts are computed by the probe, and every revision must match
`sha256-<64 hex>`. A probe line becomes a signed fact, so a value
carrying a newline would otherwise assert facts of its own.

## Context

kajero cites tik tickets as evidence and shows their current stages; the
other direction asks whether a ticket can depend on what was learned
under it, for example that a change records its gotchas before it
merges. Three shapes were possible.

A guard operator that queries the notebook was rejected. Derivation is a
pure function of the log (ADR 0001), and `verify`, `rederive` and an
evidence bundle (ADR 0025) recompute stages offline, years later, without
the notebook. A stage that read a service would derive differently on a
machine without it.

A bridge that pushes attestations from the notebook into tickets was
deferred. A bridge validates and appends the events an external system
sends (ADR 0019), so it acts when that system speaks. A notebook's
knowledge changes without sending anything: an entry held yesterday and
is disputed today once somebody observes it. A probe re-reads the
current state whenever it runs, which is the shape of the question.

A probe asserts ordinary facts, signed by whoever ran it, which is what
every other piece of the outside world already does: pull requests,
checks and merge commits enter tickets the same way.

## Consequences

- The facts are claims of the actor that probed, not of the notebook.
  `[:kajero :revisions]` names exactly what was counted, so a reviewer can
  read those revisions in the notebook, whose records carry their own
  signatures.
- The notebook's EDN reads are unauthenticated on its loopback address.
  A notebook that restricts reads needs the probe to authenticate before
  this works for it.
- No definition uses the facts yet. A process that wants them declares
  them in `:process/facts` and composes the probe, and its version bump
  goes through `tik reprocess` per ticket (ADR 0002).
- `probes/tests/kajero.test.sh` in the store pins the probe's behaviour
  against a fixture notebook, including the refusals.
