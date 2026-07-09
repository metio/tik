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
