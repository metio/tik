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
