---
title: Contributing
description: The toolchain, the gate a change passes, and where new code belongs.
---

```sh
git clone https://github.com/metio/tik.git
cd tik
nix develop
```

The flake carries the whole toolchain — JVM, Clojure, babashka, clj-kondo,
TLC, GraalVM, ssh-keygen, Hugo — so local and CI resolve identical
versions. Every command below assumes `nix develop --command`.

## The gate

```sh
bb test        # JVM test suite (kaocha + test.check)
bb lint        # clj-kondo: 0 errors, 0 warnings
bb analyze     # eastwood + splint
bb fmt         # cljfmt (bb fmt fix rewrites)
bb tla         # TLC model checks
bb tik test processes/support-request.tests.edn
reuse lint     # every file carries SPDX headers (0BSD)
```

All of it is green on main and expected to stay that way. `bb tla` asserts
that `ChaoticFixpoint` **fails** — a passing chaotic model means a
documented counterexample was lost.

Focus a single namespace or var while iterating:

```sh
clojure -M:test --focus tik.stage-test
clojure -M:test --focus tik.stage-test/sticky-milestone-survives-retraction
```

## Where code belongs

- **Kernel** (`src/tik/*.cljc`) — deterministic, pure, replayable forever.
  No I/O of any kind: no HTTP, no SQL, no environment variables, no
  implicit clock. Time enters as the explicit `now` argument. Everything
  external arrives as a signed event.
- **Store** (`src/tik/store/`) — the one I/O seam, behind the EventStore
  protocol.
- **Porcelain** (`cli/`) — may format, cache, and do I/O, and may evolve
  quickly, as long as nothing it caches is treated as authoritative.

Dependencies point one way: porcelain depends on the kernel, never the
reverse. The kernel speaks EDN; English prose belongs in lenses.

## Five test layers

Each has caught a real bug the others missed, so a change to kernel
semantics extends the layer that would have caught its bug:

1. **Golden byte tests** pin the canonical serialization. A change to
   `canonical.cljc` invalidates every event id and signature ever written.
2. **The conformance corpus** (`corpus/`) — event files plus expected
   derivations. The corpus, not the Clojure, is the definition of tik.
3. **Property tests against a reference kernel** — a deliberately slow
   prefix-replay implementation the optimized fold must agree with, with
   generators biased toward timestamp ties.
4. **TLA+ models** (`spec/`) for merge convergence and fixpoint semantics.
5. **Fuzzing** — the other layers feed valid input and check the answers;
   this one feeds garbage and checks the *manner* of failure. The contract
   is to fail well: structured rejection, never a raw exception, never a
   silent pass.

## Why things are the way they are

The [decision log](/decisions/) records every load-bearing choice with the
context that forced it and the consequences that follow. Several entries exist
to name something tik will not do, so it is the fastest way to find out whether
an idea has already been settled.

## Smells

A change carrying any of these is probably changing the model rather than
extending it, and wants a design discussion first: caching a derived value
as authoritative, a new event type or guard keyword, ordering derived from
parents, a guard that queries anything, a leader or lock or quorum on a
correctness path, a self-minted event with a non-deterministic field, or
kernel code doing I/O.

## Regenerating the process gallery

The pages under [/processes/](/processes/) are generated from the definitions
in [metio/tik-processes](https://github.com/metio/tik-processes), not written
by hand:

```sh
tik gallery ../tik-processes/processes ../tik-processes/templates \
  --out docs/content/processes \
  --assets docs/static/processes \
  --intro docs/processes-intro.md
```

One command writes all three parts, because they have to agree. `--out` is
the pages a person reads. `--assets` publishes what those pages cite — each
definition's archived bytes, its publication signature, and the `actors` file
that signature checks against — so a page naming an address and the file at
that address ship together. `--intro` splices in the library-specific prose,
kept in a file precisely so regenerating cannot discard it.

Each page carries the content address it was generated from, so a page that
has fallen behind the library names a hash the library no longer publishes.
Regenerate after the library changes; the site build does not do it, because
the definitions live in a different repository.

## Licensing

Every file carries `SPDX-FileCopyrightText` and `SPDX-License-Identifier`
(0BSD), inline where the format allows comments and through `REUSE.toml`
where it does not.
