# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
Feature: Time is an input to derivation, not a mutation
  Stage is f(events, now): the escalation stage becomes derivable purely
  because evaluation time moved, and fact-level negation keeps categorized
  tickets from ever escalating (ADR 0005's monotone-safe spelling).

  Background:
    Given a ticket following the "support-request" process

  Rule: Uncategorized tickets escalate after 48 hours

    Example: before the window nothing escalates
      When 47 hours pass
      Then the stage :escalated is not reached

    Example: after the window escalation derives with no event at all
      When 49 hours pass
      Then the stage :escalated is reached

  Rule: Categorization is fact-level negation — categorized tickets never escalate

    Example: a category asserted before the window blocks escalation forever
      When "seb" asserts category = :technical
      And 49 hours pass
      Then the stage :escalated is not reached
