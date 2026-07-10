<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# employee-onboarding: hired

The paperwork is real: a start date, the contract file, and HR's
signed reference to it.

- `tik set <id> start-date=2026-09-01`
- `tik attach <id> contract/<name>.pdf`
- HR records `tik set <id> contract.ref=<reference>` with their key.
