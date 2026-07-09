# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
Feature: Roles gate who can provide evidence
  A signature proves who asserted a fact; authorization is derived from
  role facts and process guards (ADR 0010). The same fact from the wrong
  actor exists, is visible, and satisfies nothing.

  Background:
    Given a ticket following the "support-request" process

  Rule: Facts from actors outside the required role do not satisfy signed-by guards

    Example: a random actor cannot triage
      When "rando" asserts category = :technical
      And "rando" asserts severity = :high
      Then the stage :triaged is not reached
      And explain for :triaged says only "triager" may provide category

    Example: the triager role unlocks the same evidence
      When "seb" asserts category = :technical
      And "seb" asserts severity = :high
      Then the stage :triaged is reached
