---
type: decision
status: accepted
date: 2026-07-09
title: Conformance is defined by the corpus, the laws, and normative sweep semantics
supersedes: null
---

# ADR 0018: Second-implementation conformance

## Decision

The corpus, not the Clojure, is the definition of tik. A conforming
implementation must agree on all five layers:

1. **Canonical bytes** (ADR 0008): identical serialization for every
   supported value, byte for byte.
2. **Event validity** (ADR 0004, 0009): minting rules (mandatory
   parents, root uniqueness), totality over unknown types.
3. **Reduction**: identical ticket state from identical event sets —
   ordered by `(at, id)`, deduplicated by id, handler semantics per the
   closed vocabulary.
4. **Fixpoint semantics**: the **synchronous sweep is normative**
   (ADR 0005) — all enabled stages added per sweep against the
   sweep-start snapshot. Fire-one-stage-at-a-time iteration is
   nonconformant even on stratification-clean processes;
   `spec/ChaoticFixpoint.tla` exhibits the divergence and the corpus
   case `sweep-order-negation` pins the correct result.
5. **Explain laws**: soundness (every block re-derivable, nothing
   speculative) and completeness (every unreached-with-prereqs-reached
   stage appears) — the data contract of ADR 0016.

Conformance is demonstrated by passing the corpus and the property laws,
not by code review of the implementation.

## Context

The offline-verification story only survives multiple implementations
(tik-rust, tik-go, a browser verifier) if "agrees with tik" is testable
without reading Clojure. The corpus provides exact expectations; the
reference kernel (`test/tik/reference.clj`) provides an executable
oracle; the generators provide adversarial inputs; the TLA+ models
document the semantics that are easy to get subtly wrong. The sweep
requirement exists because it is the one place where a plausible
independent implementation of "iterate to closure" silently diverges.

## Consequences

- Growing the corpus is a conformance act: new semantics land with
  corpus cases or they are not fully specified.
- Format-version bumps (ADR 0006/0008) version the conformance target
  with them.
- A federation partner claiming "we verified this" is claiming corpus
  conformance — which is checkable, which is the point.
