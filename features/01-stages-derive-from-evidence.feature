# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
Feature: Stages derive from evidence
  Nobody moves a ticket. People assert facts; guards verify them; stages
  emerge as a pure function of the log, the process definition, and time.

  Background:
    Given a ticket following the "support-request" process

  Rule: A fresh ticket sits at its root stage

    Example: creation alone reaches only :received
      Then the stage :received is reached
      And the stage :triaged is not reached
      And the current stages are :received

  Rule: Satisfying a stage's guards derives the stage — no transition exists

    Example: category and severity from a triager derive :triaged
      When "seb" asserts category = :technical
      And "seb" asserts severity = :high
      Then the stage :triaged is reached
      And the current stages are :triaged

    Example: half the evidence derives nothing
      When "seb" asserts category = :technical
      Then the stage :triaged is not reached
