---
type: decision
status: accepted
date: 2026-08-05
title: No fold step reads the future — the claimed clock is clamped to the evaluated clock
supersedes: null
---

# ADR 0022: No fold step reads the future

## Decision

**Every step of `evolve` evaluates guards at the event's own claimed
`:event/at`, clamped to the read's evaluated `now`: `min(at, now)`.** No
step may act as though more time has passed than actually has at the
moment of the read.

Derivation stays exactly what it was — a pure function of
`(events, now)` — and reduction order is untouched: events still fold in
`(at, id)` order, and only the instant each step's guards are *evaluated
at* is clamped. Logs whose events are all dated at or before the read
derive byte-for-byte as they did before, so the clamp changes the answer
only for the case it exists to fix.

## Context

`:elapsed-since` reads the claimed clock (ADR 0012). Combined with a
per-event evaluation instant, a single postdated event was enough to
satisfy a time guard at its claimed instant — and on a **sticky** stage
the fold carried that reach forward permanently. The result was a
milestone reachable by writing a date, which no later evidence could
retract: retraction cannot help, because sticky is exactly the promise
that retraction does not.

Refusing postdated events at our own write boundary does not close this.
An event can arrive from another replica already postdated, and under
ADR 0021 a replica may not be trusted to have applied any such check —
so the property must hold in the derivation, which every replica runs,
rather than in one write path.

ADR 0012 remains intact: claimed time is still the default clock and
backdating is still detectable rather than preventable. The clamp is
about the *evaluated* clock, the third of that ADR's three: a read at
`now` may not consult a claimed instant later than `now`. Reading a
claimed future early was the one place the evaluated clock was not
actually governing evaluation.

## Consequences

- `tik.stage/evolve` and `stage-timeline` take `now`. Every caller had
  one already; only the two lens call sites that had been folding
  without one changed.
- The clamp lifts by itself. As `now` advances past a postdated event's
  claimed instant, that event is evaluated at the instant it claims, and
  the derivation converges on the unclamped answer. Nothing is
  discarded, censored, or rewritten — the event is simply not read early.
- `:at` in the timeline stays the event's own unclamped claim: the
  timeline records what the log says, not what a particular read
  believed.
- The reference kernel (`test/tik/reference.clj`) clamps identically,
  so the differential property test still compares two independent
  implementations of the same semantics rather than one of each.
- A postdated event is still evidence of something. Surfacing
  future-dated events in the lenses remains worth doing and is now a
  reporting nicety rather than the only defence.
