---
type: decision
status: accepted
date: 2026-07-09
title: Explain's structured reasons are the stable API; renderings are not
supersedes: null
---

# ADR 0016: Explain stability contract

## Decision

**Explain output is structured data with stable semantics; rendering is
not stable.** Clients — web UIs, MCP agents, chat surfaces, dashboards —
bind to the reason data (`:reason` keywords like `:fact/missing`,
`:role/unsatisfied`, plus their payload keys), never to English strings.

The compatibility rules:

- Reason keywords and their payload keys are **versioned with the guard
  vocabulary**: a guard-vocab version enumerates exactly which reasons
  can occur (a closed vocabulary implies a closed reason set).
- **New reasons appear only additively under a version bump**; existing
  reason keywords never change meaning or payload shape within a
  version.
- The block structure (`:stage`, `:satisfied`, `:missing`, `:blocks`,
  `:hint`) is part of the same contract, backed by the property-tested
  soundness/completeness laws (PLAN §8).
- **Renderings may change freely** — wording, ordering, localization,
  ranking, capability-based redaction (IDEAS) are all lens behavior. A
  client that greps CLI text has no compatibility claim.

## Context

Explain is the product surface, which makes it the API everyone will
integrate against. Without this contract, MCP and UI clients would
inevitably couple to English strings, and improving a message would
become a breaking change — freezing exactly the layer that must stay
free to improve. The kernel-speaks-data rule (PLAN §5) already provides
the mechanism; this ADR adds the promise.

## Consequences

- Localization and the explain-as-chatbot surface are renderings —
  automatically within contract.
- Corpus expectations and property tests target the data layer, so the
  contract is enforced by the same suites that enforce derivation.
- A new guard operator (version bump) documents its reasons as part of
  its admission (ADR 0001, PLAN §19 gate).
