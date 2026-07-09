# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
Feature: Disputes regress stage by derivation
  Rejecting a fact is a signed event; the stage that depended on it simply
  stops being derivable. No workflow engine, no transition, no state to
  repair (ADR 0003).

  Background:
    Given a ticket following the "support-request" process
    And "seb" asserts category = :technical
    And "seb" asserts severity = :high

  Rule: A disputed fact stops satisfying guards until superseded

    Example: disputing the category regresses :triaged
      Then the stage :triaged is reached
      When "billing" disputes category because "not technical after all"
      Then the stage :triaged is not reached
      And the current stages are :received

    Example: a corrected assertion supersedes the dispute
      When "billing" disputes category because "not technical after all"
      And "seb" asserts category = :billing
      Then the stage :triaged is reached

  Rule: A retracted fact is withdrawn without replacement

    Example: retracting severity regresses :triaged
      When "seb" retracts severity
      Then the stage :triaged is not reached
