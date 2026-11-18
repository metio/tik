---
title: Integrations
description: Agent surfaces and machine-readable views of a tik store.
---

A tik store is meant to be read by tools as readily as by people. Every
lens takes `--edn` or `--format json`, `tik serve` publishes
`/tickets.edn` and `/explain/<id>.edn`, and the frontier doubles as an
agent's authorization boundary.

- [Claude Code skill](/integrations/claude-code/) — install the plugin and
  Claude drives a store correctly, recording evidence rather than setting
  statuses.
- [MCP server](/integrations/mcp-server/) — the frontier as a gated tool
  surface over stdio.
- [GitHub Actions](/integrations/github-actions/) — failed workflow runs as
  tickets, one per failure streak, with `tik stats` for per-repository
  metrics.
- [GitLab pipelines](/integrations/gitlab-pipelines/) — failed pipelines as
  the same tickets, through an SSH port forward when the API only listens
  on its own host.
- [Redmine](/integrations/redmine/) — Redmine issues mirrored as tickets,
  with their history, so local work and remote tickets sit in one store.
- [Odoo](/integrations/odoo/) — Odoo project tasks mirrored as tickets,
  with their history from the chatter.
