---
title: Lint, simulate, test
description: Proving a definition does what you meant — before a real ticket depends on it.
tags: [authoring, lint, testing, simulation]
---

Three checks, in the order you reach for them.

## Lint

```sh
tik lint processes/support-request.edn
```

The linter enforces what the kernel cannot:

- **The closed guard basis** — only operators the declared
  `:process/guard-vocab` admits.
- **Graph sanity** — every `:after` names a stage that exists, and no
  cycles.
- **Stratified negation** — `[:not [:stage-reached …]]` may only name a
  stage in a strictly earlier stratum, which is what makes the fixpoint
  provably deterministic.
- **Facts over flags** — a warning where a bare boolean stands in for
  information worth recording.
- **Prefix boundaries** — an `:artifact` prefix that does not end at a path
  boundary matches more than its author expects.

With no argument it lints the *store* instead: open tickets missing
descriptions, titles, or signatures.

## Simulate

```sh
tik sim processes/support-request.edn
```

A scratch ticket against a definition that reloads on every save. Assert
facts, watch stages derive, and edit the definition in another window —
the fastest loop for finding out that a guard means something other than
what you read into it.

## Test

```sh
tik test processes/support-request.tests.edn
```

Scripted cases: evidence in, expected stages out. Deterministic — a fixed
epoch, pure derivation, no store — and a failing case prints `explain`, so
the process itself says why a stage did not derive.

```clojure
{:test/process "support-request.edn"
 :test/cases
 [{:case/name "facts alone do not triage — the triager role must sign"
   :case/steps [[:actor "rando"]
                [:set [:category] :technical]
                [:set [:severity] :high]]
   :case/expect {:excludes #{:triaged}}}

  {:case/name "uncategorized tickets escalate after 48h"
   :case/steps [[:now "+PT49H"]]
   :case/expect {:includes #{:escalated}}}]}
```

`[:now "+PT49H"]` moves the evaluation clock, so time-gated stages are
testable without waiting and without a mock. `[:actor "rando"]` switches
who is signing, which is how a `:signed-by` guard gets tested from both
sides.

Write the negative cases. "A triager categorizing reaches `:triaged`" is
the easy half; "facts alone do not triage" is the half that catches a
`:signed-by` you dropped.

## Publish

```sh
tik process sign support-request
```

Signing archives the definition by content hash and signs those canonical
bytes. The hash stays the identity; the signature is the authority behind
it.
