---
type: runbook
title: ci-failure / :failing
---

# ci-failure: failing

A CI workflow (a GitHub Actions workflow, or a GitLab pipeline source)
has been failing on this branch since the run in `forge.first-run`. The
forge bridge (`tik bridge github` or `tik bridge gitlab`) opened the ticket
and attaches every further failed run of the streak under `ci/run/`.

- Open the first run, find out why it failed, and fix it or hand it on.
- Record the cause as soon as it is known (see `:triaged`).
