# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
Feature: Sticky milestones survive later evidence changes
  A sticky stage (GSM milestone) is reached-once-stays-reached: the log
  shows both truths — "was closed" and "the ack was withdrawn" — without
  the derived present lying about either.

  Background:
    Given a ticket following the "support-request" process
    And "seb" asserts category = :technical
    And "seb" asserts severity = :high
    And "seb" attaches "repro/crash.sh"
    And "seb" asserts resolution.ref = "abcdef123456"

  Rule: :closed is sticky

    Example: the customer ack closes the ticket
      When "customer" asserts customer.ack = true
      Then the stage :closed is reached

    Example: retracting the ack does not un-close
      When "customer" asserts customer.ack = true
      And "customer" retracts customer.ack
      Then the stage :closed is reached

  Rule: Non-sticky stages do regress

    Example: retracting the resolution reopens :resolved
      Then the stage :resolved is reached
      When "seb" retracts resolution.ref
      Then the stage :resolved is not reached
