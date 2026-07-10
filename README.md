# tik

A process system, not a ticket system.

A "ticket" in tik is an append-only log of signed, content-addressed facts
and artifacts. Its position in a process is a **derived value** — a pure
function of the log, the process definition, and the current time. Nobody
moves a ticket: humans and agents contribute facts, guards verify them,
stages derive, and `tik explain` always knows exactly what is needed next.
git answers "what changed?"; tik answers "what is true, why is it true,
who proved it, and can anyone reproduce the conclusion?"

The elevator version lives in [docs/PITCH.md](docs/PITCH.md).
Part of the [metio](https://metio.wtf) family. Licensed 0BSD,
[REUSE](https://reuse.software)-compliant. Full design: [docs/PLAN.md](docs/PLAN.md).
Decisions and concepts live in the OKF bundle under [kb/](kb/index.md);
unjudged ideas under [docs/IDEAS.md](docs/IDEAS.md).

## Quickstart

```console
$ nix develop                     # clojure, babashka, clj-kondo, tlc, jdk, reuse

$ bb tik new support-request --title "login broken for tenant X" --actor seb
018f2f6e-...

$ bb tik status 018f
stage:   received
To reach :triaged:
  ✗ set fact [:category] ([:enum :billing :technical :account :abuse])
  ✗ set fact [:severity] ([:enum :low :normal :high :critical])

$ bb tik set 018f category=technical severity=high --actor seb
$ bb tik status 018f              # -> triaged; the technical branch now wants a repro

$ echo 'curl -v ...' > crash.sh
$ bb tik attach 018f crash.sh
$ bb tik set 018f resolution.ref=\"abc123def456\" --actor seb
$ bb tik status 018f              # -> resolved, derived — nothing was "moved"

$ bb tik dispute 018f category --reason "not technical after all" --actor billing
$ bb tik status 018f              # -> regressed to received, with the reason in explain

$ bb tik log 018f                 # the evidence timeline: events interleaved with
                                  # derived stage transitions (computed, never stored)
$ bb tik diff 018f 2              # what the last 2 events made derivable
$ bb tik next --actor seb         # the inbox: which facts unlock the most work,
                                  # filtered to what seb's roles can act on
$ ssh-keygen -q -t ed25519 -N '' -f me && export TIK_KEY=\$PWD/me
$ bb tik actor add seb me.pub     # identity rung 1: the actors registry
$ bb tik sign 018f                # sign your events (TIK_KEY signs new
                                  # writes automatically from here on)
$ bb tik verify 018f              # L0 integrity (sha256sum-checkable) +
                                  # L1 authenticity (ssh-keygen -Y) +
                                  # L2 reproducibility (re-derivation)
```

## Process definitions

Processes are plain EDN under [processes/](processes/) — reviewed,
versioned, and linted like code. Tickets pin the definition's content
hash at creation; the hash is the identity, the version number is a label.

```console
$ bb tik lint processes/support-request.edn
[warning] fact [:customer :ack] is a bare boolean — a checkbox with extra steps. ...
```

(That warning is intentional — the sample demonstrates the *facts over
flags* linter rule.)

Design a process live — a scratch ticket, re-derived after every command,
against a definition that reloads whenever the file changes:

```console
$ bb tik sim processes/support-request.edn
sim> set category=technical
sim> now +PT49H
sim> quit
```

And pin its behavior with scripted tests — steps in, expected stages out,
`explain` printed on failure so the process tells you *why* a stage did
not derive:

```console
$ bb tik test processes/support-request.tests.edn
  ok    facts alone do not triage — the triager role must sign
  ok    closed is sticky: surviving an ack retraction
test: PASS
```

## Development

```console
$ bb test          # JVM test suite (kaocha): golden canonical-form tests,
                   # reducer laws, property tests against a reference kernel,
                   # explain soundness/completeness, the conformance corpus
$ bb lint          # clj-kondo (0 errors, 0 warnings)
$ bb tla           # TLC over the TLA+ specs in spec/ — merge convergence,
                   # fixpoint semantics (one model is REQUIRED to fail:
                   # it documents a counterexample)
$ reuse lint       # license compliance
```

## Layout

```text
src/tik/            the kernel (pure, bb-compatible .cljc — this IS the SDK)
  canonical.cljc    byte-stable EDN + content addressing (the un-migratable layer)
  event.cljc        the closed event vocabulary (7 types) & minting
  reduce.cljc       events -> ticket state; fact-status, the one choke point
  guard.cljc        the closed guard basis (9 operators; :fact= is sugar)
  stage.cljc        stage fixpoint: derived, synchronous sweeps, sticky carry
  explain.cljc      unmet guards -> actionable todos (the product)
  process.cljc      process schema + linter
  store/            EventStore protocol + file/git backend
cli/tik/cli.clj     babashka CLI
processes/          process definitions + scripted process tests (EDN)
corpus/             conformance corpus: event files + expected derivations —
                    the corpus, not the Clojure, is the definition of tik
spec/               TLA+ models (TLC-checked via `bb tla`)
kb/                 OKF knowledge bundle: decisions (ADRs), concepts
docs/PLAN.md        the full design; §19 is the ledger of settled verdicts
docs/IDEAS.md       unjudged ideas, no commitments
```

## The one rule

From [ADR 0001](kb/decisions/0001-event-log-acceptance-test.md): every
feature must be new event/attestation types, new pure guard vocabulary, or a
lens over them. Nothing stores mutable state; nothing moves a ticket;
verification never leaves the log.
