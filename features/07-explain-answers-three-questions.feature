# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
Feature: Explain answers what is missing, structurally
  Every surface answers three questions: what is true, what could become
  true next, and exactly what evidence is missing — with who-can-act as
  data, never prose (PLAN §12).

  Background:
    Given a ticket following the "support-request" process

  Rule: Missing evidence is named per frontier stage

    Example: a fresh ticket knows exactly what triage needs
      Then explain for :triaged lists category as missing
      And explain for :triaged lists severity as missing

    Example: partially satisfied stages report only the remainder
      When "seb" asserts category = :technical
      Then explain for :triaged lists severity as missing

  Rule: Who-can-act is part of the reason

    Example: the wrong actor is named, with the role that could act
      When "rando" asserts category = :technical
      And "rando" asserts severity = :high
      Then explain for :triaged says only "triager" may provide category
