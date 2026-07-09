# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
Feature: Synchronous sweep evaluation is normative
  The sweep-order process (spec/ChaoticFixpoint.tla) is linter-clean yet
  order-dependent under fire-one-stage-at-a-time evaluation. Correct
  derivation decides :c before :d ever becomes prerequisite-enabled, so
  :d — guarded by "not reached :c" — never derives (ADR 0005, 0018).

  Rule: The later stratum sees the earlier one fully decided

    Example: :d is blocked by :c on a fresh ticket, with no events at all
      Given a ticket following the "sweep-order" process
      Then the stage :a is reached
      And the stage :b is reached
      And the stage :c is reached
      And the stage :d is not reached
      And the current stages are :b, :c
