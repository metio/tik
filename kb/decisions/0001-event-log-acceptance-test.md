---
type: decision
status: accepted
date: 2026-07-08
title: The event-log acceptance test for all features
---

# ADR 0001: The event-log acceptance test

## Decision

A feature proposal must be expressible as one or more of:

(a) new **event or attestation types** over the existing append-only log,
(b) new **guard vocabulary** evaluated purely from `(events, now)`,
(c) **porcelain or lens** behavior deriving from (a) and (b).

Proposals requiring stored mutable state, imperative stage transitions, or
verification paths that leave the log are **rejected or redesigned**.

One carve-out, so the test cannot be misread: features necessary to
preserve the **integrity, authenticity, or reproducibility of the log
itself** — canonical serialization, signature algorithms, hash migration,
witness sidecars, store verification — are trust substrate, governed by
their own ADRs (0004–0007), not by this test. The substrate may never
introduce domain truth outside the log; it exists to make (a)–(c)
trustworthy. "A new signature algorithm is neither an event nor a lens,
therefore rejected" is a misreading, not an application, of this ADR.

## Context

The kernel's value is that stage is derived, merges are union, and
verification is offline-forever. Every mechanism designed so far — disputes,
OIDC key/role enrollment, server countersigning, cross-instance federation,
replay-based notifications, MCP/agent actors — passed this test, several by
*shrinking* rather than growing. The test exists to defend the kernel from
its own maintainers under deadline pressure.

## Consequences

- "Reject and move back to triage" became a signed `:fact/dispute` event;
  regression is derived, never performed.
- Identity, roles, revocation, timestamps, and federation all enter as
  attestation events — the trust model rides the same rails as the tickets.
- Any proposal that fails the test is a design smell worth a new ADR, not a
  quick exception.
- This ADR is the head of a chain — each successor constrains the next
  step of a claim's life: 0001 says where truth may enter; 0004 how
  history is connected; 0005 how derivation stays deterministic; 0006 how
  identities remain stable; 0007 what exactly is signed. Together:
  a claim enters → is immutable → merges safely → derives conclusions →
  and the conclusions are reproducible. Local exceptions ("just store the
  current status", "just add a transition endpoint", "just call this
  service during verification") each look harmless; together they
  recreate the workflow engine this system exists to replace — which is
  why the test binds maintainers, not users.
