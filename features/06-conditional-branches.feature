# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
Feature: Conditional prerequisites are guards, not edges
  "Technical implies reproduced" is material implication inside a guard,
  so parallel branch tips can both be current — maximality is computed
  against the :after graph only.

  Background:
    Given a ticket following the "support-request" process
    And "seb" asserts category = :technical
    And "seb" asserts severity = :high

  Rule: Technical tickets need a reproduction before resolution counts

    Example: a resolution without a repro stays unresolved
      When "seb" asserts resolution.ref = "abcdef123456"
      Then the stage :resolved is not reached

    Example: attaching the repro unlocks both branch tips
      When "seb" asserts resolution.ref = "abcdef123456"
      And "seb" attaches "repro/crash.sh"
      Then the stage :reproducible is reached
      And the stage :resolved is reached
      And the current stages are :reproducible, :resolved
