<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What tik is

A process system, not a ticket system: a ticket is an append-only log of
signed, content-addressed events, and its stage is **never stored** — it is
a pure function `f(events, now)` derived on read. The system's one law is
**derived beats declared**: if something can be derived, storing it as
authoritative state is a bug. Every design question in this repo eventually
resolves against that law — a PR that caches a derived value isn't declined
as a preference, it violates the law.

The one law has a companion, equally absolute: **coordination-free by
construction — no leader, no lock, no consensus** (ADR 0021). Every
operation must be correct on arbitrarily many replicas that share nothing
and reconcile only by eventually unioning their grow-only,
content-addressed event sets. Reads read one ticket's own log and shard
without limit; writes are either content-addressed events that are a pure
function of their intent (identical intent → byte-identical event → union
keeps one) or CRDT-safe appends whose contention resolves by *derivation*
(`:conflicted`), never a lock; any event a replica mints on its own (e.g.
`recur`) derives its id and every byte — `:at` included — deterministically.
A design that needs a replica to win an election, hold a lock, or agree
with a quorum before it can act violates this law as surely as caching a
derived value violates the first. A tik replica is stateless by
construction; horizontal scaling is the preferred deployment.

**The kernel answers "what follows from these signed facts?" It never
answers "what should happen next?"** That is why there are no workflow
transitions, schedulers, policy engines, or external queries in the core;
notifications, inboxes, webhooks, UIs, and agents are porcelain over
derivations.

`docs/PLAN.md` is the canonical design document. Its §19 is a ledger of
already-litigated ideas (deferred vs rejected-on-principle, each with the
law it failed against) — **read §19 before proposing a new feature or
event type**; most "obvious" extensions are already there with a verdict.
ADRs live in `kb/decisions/`. Unjudged ideas go to `docs/IDEAS.md`, not
into the plan.

## Commands

All tooling runs through the nix dev shell (the host has no toolchains):

```text
nix develop --command <cmd>       # every command below assumes this
```

- `bb tik <cmd> …` — the CLI (babashka). `bb tik` alone prints usage.
- `bb test` / `clojure -M:test` — JVM test suite (kaocha + test.check).
- Single test namespace: `clojure -M:test --focus tik.stage-test`
- Single test var: `clojure -M:test --focus tik.stage-test/sticky-milestone-survives-retraction`
- `bb lint` / `clj-kondo --lint src cli test` — must be 0 errors, 0 warnings.
- `bb tla` / `bash spec/check.sh` — TLC model checks. Merge and
  SweepFixpoint must pass; **ChaoticFixpoint must FAIL** (the script
  asserts the violation — a "passing" chaotic model means a documented
  counterexample was lost).
- `reuse lint` — every file needs SPDX headers (0BSD); non-commentable
  formats go through `REUSE.toml`.
- `bb tik test processes/support-request.tests.edn` — scripted process
  tests (steps in, expected stages out, explain printed on failure).
- `bb tik sim processes/support-request.edn` — live process design REPL
  (scratch ticket, auto-reloading definition).

The full gate before calling work done: kaocha + kondo + eastwood +
splint + cljfmt + tla + reuse + typos + markdown + `tik test`. All
green on main; keep them green.

## Dogfood store (H1)

tik's own work runs in tik: the repo root is a live store (`tickets/`,
`actors`, process `processes/tik-dev.edn`). `bb tik next --actor seb`
is the project's actual inbox; when you land a feature, `bb tik set
<id> commit=<sha> gate=:green` on its ticket. Sign writes by exporting
`TIK_KEY=~/.config/tik/id_ed25519` (key lives outside the repo; only
the `actors` registry is committed). Never hand-edit `tickets/` —
events are content-addressed, `tik verify` will catch it.

H3 instrumentation (ticket f446679f, running): drive every work
session from `tik next`/`explain`. If you ever need `tik log` or raw
event files to figure out WHAT TO DO NEXT (demos don't count), that is
a kill-criterion datapoint — record it immediately:
`bb tik comment f446679f leg-1 spelunking datapoint <date>: <what the
lenses failed to answer>`.

## Architecture

Derivation pipeline (all in `src/tik/`, pure `.cljc`, babashka + JVM):

```text
events (canonical.cljc, event.cljc)
  → ticket state (reduce.cljc)      fold ordered by (at, id) over the SET
  → guard evaluation (guard.cljc)   pure fn of (state, now, reached)
  → stage fixpoint (stage.cljc)     synchronous sweeps to closure
  → explain (explain.cljc)          structured reasons → the product surface
```

Every other UI is a rendering of explain (plus the timeline the evolve
fold already carries). `cli/tik/cli.clj` is porcelain over this;
`src/tik/store/` is the EventStore seam (file store: `sha256sum(file) =
filename = event id`).

### Where code belongs

- **Kernel** (`src/tik/*.cljc`): deterministic, pure, replayable forever.
  **No kernel function performs I/O** — no HTTP, no SQL, no env vars, no
  implicit clock (time enters only as the explicit `now` argument or
  `:event/at`). Everything external enters as a signed event.
- **Store** (`src/tik/store/`): the one I/O seam, behind the EventStore
  protocol.
- **Porcelain** (`cli/`, future web/MCP): may format, cache, index, do
  I/O, and evolve quickly — as long as nothing it caches is treated as
  authoritative. Dependencies point one way: porcelain → kernel, never
  kernel → porcelain.

Do not optimize by storing anything the kernel derives. Performance
problems get indexes, caches, or alternative stores — never new
authoritative state. (Someone will eventually propose `:ticket/current-stage`
"because it's faster." The answer is in PLAN §19.)

### Closed vocabularies (versioned, enumerable — never extend casually)

- **7 event types** (`event.cljc`): create, fact assert/retract/dispute,
  artifact/attach, attestation/add, process/migrate.
  Why only seven: comments are artifacts (text blobs by hash); links are
  facts (`[:link …]` paths); work records are `:work` attestation claims;
  witness countersignatures are detached sidecars over a head, not events
  (an event would move the head it witnesses).
- **12 guard operators** (`guard.cljc`, vocabulary v2): `:fact :fact=
  :artifact :signed-by :stage-reached :elapsed-since :attested-within
  :different-person :and :or :not :malli`.
  (`:fact=` was briefly v6 sugar; restored as an operator by dogfood
  evidence — reasons are the API, ADR 0016. `:attested-within` closes the
  stale-evidence gap — a fresh-enough attestation; `:different-person` is
  separation of duties — two facts signed by distinct actors. Both read the
  log yet stay pure functions of `(state, now, reached)`.) New keywords
  require a version bump and a PLAN §19 verdict.
- **fact-status** (`reduce.cljc`) is the single choke point for why a fact
  does/doesn't satisfy guards: `:present :absent :retracted :disputed
  :conflicted`. Guards consult nothing else about facts.

### Load-bearing invariants

- **`canonical.cljc` is the un-migratable layer.** Any change to its
  output invalidates every event id and signature ever written; golden
  tests in `canonical_test.clj` pin bytes. If they break, you changed the
  format — bump `format-version` and think very hard. Map keys sort by
  their canonical form (never `pr-str` — unstable for Instants).
- **Stored bytes are the hashed region (ADR 0007):** event files contain
  canonical bytes *without* `:event/id`; the filename is the id, so
  `sha256sum` alone verifies the store. Signatures/countersignatures are
  detached sidecars.
- **Synchronous sweep is normative** (ADR 0005 + `spec/ChaoticFixpoint.tla`):
  the fixpoint adds all enabled stages per sweep against the sweep-start
  snapshot. Fire-one-stage-at-a-time is order-dependent even on
  linter-clean processes; the corpus case `sweep-order-negation` pins this.
- **Guards never query.** Anything external enters as a signed attestation
  event; evaluation scope is one ticket's log, offline, forever.
- **Reduction order is `(at, id)` over the event set** — parents (Merkle
  DAG, mandatory per ADR 0004) are for integrity/causality/sync, never
  ordering. The reducer is total, commutative, idempotent (property-tested).
- **Coordination-free by construction (ADR 0021).** No operation may need
  leader election, a distributed lock, or consensus to be correct across
  replicas. Any event a replica mints on its own must be a pure function of
  its inputs — id and every byte, `:at` included — so concurrent replicas
  emit byte-identical events that union-merge to one (the standard evidence
  is a property test that two independent stores produce an identical
  event-id set, as `recur` has). Contention resolves by derivation
  (`:conflicted`), never a lock. The one admissible exception is outbound
  side-effect *delivery* that cannot be made idempotent — a narrow
  per-pipeline lease may serialize delivery there, never the log.

### The test stack (five independent layers)

- Golden byte tests + coreutils checks (`canonical_test.clj`, `verify` L0).
- The **conformance corpus** (`corpus/`) — event files + expected
  derivations; the corpus, not the Clojure, is the definition of tik.
  Regenerating it (process hash changes cascade through all event ids) is
  done with a script driving the kernel itself.
- Property tests against a **reference kernel** (`test/tik/reference.clj`,
  deliberately slow O(n²) prefix replay; `test/tik/gen_events.clj` holds
  generators biased toward timestamp ties). The optimized single-fold
  `evolve` must agree with it. explain has property-tested soundness and
  completeness laws.
- **TLA+ models** (`spec/`) for merge convergence and fixpoint semantics.
- **Fuzzing** (`test/tik/fuzz_test.clj`) — the other layers feed VALID
  inputs and check the answers; this one feeds garbage and checks the
  manner of failure. The contract is *fail well*: ex-info or schema
  rejection, never a raw JVM exception, never a silent pass. Includes
  an exhaustive single-byte-flip sweep over stored event files.
  `TIK_FUZZ_N` scales iterations for soak runs.

Each layer has caught at least one real bug the others missed; when
touching kernel semantics, extend the layer that would have caught your
bug.

### Smells

If a change introduces any of these, it is probably changing the model
rather than extending it — read PLAN §19 before writing code:

- mutable state, or caching a derived value as authoritative
- a new event type or guard keyword (both vocabularies are closed and
  versioned; additions need a §19 verdict and a version bump)
- ordering behavior derived from parents (parents are integrity/causality;
  order is `(at, id)`)
- a guard that queries anything — another ticket, a service, a clock
- a leader, a distributed lock, a quorum/consensus round, or a
  "designated" replica anywhere on a correctness path — or a
  self-minted event with a non-deterministic field (`random-uuid`,
  wall-clock `:at`) that would duplicate under concurrent replicas
  (violates coordination-free-by-construction, ADR 0021)
- kernel code importing from `cli/` or doing I/O
- English strings in kernel output (the kernel speaks EDN; prose lives in
  lenses)

### Process definitions

Plain EDN in `processes/`, hash-pinned by tickets at creation (the hash is
the identity; the version number is a label). `tik lint` enforces the
closed guard basis, graph sanity, stratified negation, and facts-over-flags.
Changing a sample process definition changes its hash and requires
regenerating the corpus case that pins it.
