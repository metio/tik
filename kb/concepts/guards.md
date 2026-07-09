---
type: concept
title: Guards
---

# Guards

Deterministic pure functions of `(events, now)` from a closed, versioned
vocabulary (`tik.guard`). Never effectful: `verify` must re-evaluate them
identically, offline, years later. Effectful validation lives only in edge
admission checks (CLI porcelain, server ingest), before events are minted.

Design law: **facts over flags.** A bare boolean guard is a checkbox with
extra steps; `tik lint` warns. Prefer facts useful downstream — categories
that route branches, artifacts, signed approvals.
