---
type: decision
status: accepted
date: 2026-07-09
title: Three clocks; claimed is the default; the clock is part of the guard
supersedes: null
---

# ADR 0012: Time semantics are part of the process contract

## Decision

Three clocks, never conflated:

1. **Claimed** — `:event/at`, asserted by the actor, inside the hashed
   region.
2. **Observed** — witness countersignature over a head: "this history
   existed no later than T".
3. **Evaluated** — the explicit `now` argument to derivation; never an
   implicit system clock.

**Claimed is the default clock for guards.** A guard opts into observed
time with `{:clock :witnessed}` where backdating matters. Evaluation
time is always explicit — the kernel has no ambient "now" (CLAUDE.md:
no kernel I/O includes no clock reads).

The permanent answers:

- **Backdating is detectable, not preventable**: a claimed time earlier
  than a countersigned observation of the event's absence is visible to
  any verifier. Pretending clocks can be trusted would be dishonest;
  every clock is just another evidence source, and stronger time means
  moving up the ladder (claimed → witnessed → externally anchored).
- **A witness arriving after a stage derived** changes nothing
  retroactively: derivation at `(events, now)` is a pure function, and
  a conclusion is indexed by its inputs. Under `{:clock :witnessed}` a
  stage may only become derivable once the witness exists — that is the
  guard working, not history changing.
- **Re-evaluation at a later `now` never changes history** because
  history is not a single state: it is `f(events, now)` for every now.
  "What was derivable on March 1" is answered by evaluating at March 1,
  reproducibly, forever.

## Context

Someone will eventually write a time-dependent guard without saying
which clock it reads, and every implicit answer is wrong for someone:
claimed time is gameable, witnessed time may not exist yet, system time
is unreproducible. Making the clock part of the guard's meaning keeps
audit questions answerable — and makes `verify` L2 possible at all,
since re-derivation years later must not depend on the wall clock of
the machine running it.

## Consequences

- `:elapsed-since` and any future temporal guard name their reference
  point and read the claimed clock unless the process opts into
  witnessed.
- The deferred recency guard (PLAN §19) defaults to the witnessed clock
  because staleness is exactly where claimed time cannot be trusted.
- Timestamp games (PLAN §18) are answered by this ladder, not by NTP.
