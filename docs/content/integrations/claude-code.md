---
title: Claude Code skill
description: Install the tik skill so Claude drives a store correctly — recording evidence and letting stages derive, instead of setting statuses.
tags: [claude, skill, plugin, agents]
---

Install the tik skill into [Claude Code](https://claude.com/claude-code)
and Claude gains working knowledge of tik: it recognises a store, records
facts with `tik set` rather than inventing a status field, reads
`tik explain` before asking a person anything, and authors process
definitions whose stages derive from evidence instead of listing
activities.

That last part is the reason the skill exists. The failure mode for any
model writing a tik process is a task list wearing a process costume —
stages named after activities, with nothing following from anything. The
skill carries the design law that rules it out, along with the closed
guard vocabulary and the authoring loop that checks the result.

The skill lives in this repository under `skills/tik/`, packaged as a
Claude Code plugin by the manifests in `.claude-plugin/`.

## Install

Add this repository as a plugin marketplace, then install the plugin:

```text
/plugin marketplace add metio/tik
/plugin install tik@tik
```

Claude activates the skill whenever a repository holds a `tickets/` or
`.tik/` directory, an `actors` registry, or `processes/*.edn` — or when
you mention tik, tickets, processes, or stages.

## From the MCP server

A tik MCP server also serves the skill, through the MCP Skills extension
(`io.modelcontextprotocol/skills`). With the server registered, Claude
Code v2.1.331 or later lists the skill as `<server>:tik`, for example
`tik:tik`, and loads the copy built into that server, so it matches the
server's version without a plugin install. Claude Code lists MCP skills
only over MCP revision 2026-07-28, which `tik mcp` and `tik serve` speak.
See [MCP server](/integrations/mcp-server/#agent-skill) for how the
manifest is signed and checked.

## What it grants Claude

The skill teaches the CLI surface and the model behind it:

- **The daily loop** — `tik ls`, `tik next`, `tik new`, `tik set`,
  `tik explain`, `tik status`, and the selector grammar for filtering a
  board.
- **Corrections** — retract, dispute, and the fact that a dispute is
  answered only by a *different* value, so Claude does not try to clear
  one by retyping the same fact.
- **Authoring** — `tik author`, the guard vocabulary, and the lint,
  simulate, and test loop that proves a definition before a real ticket
  depends on it.
- **The one design law** — a stage is defined by what must be TRUE to
  reach it, never by who moved what.

It also knows when *not* to act: an unreachable step in `explain` means
waiting is pointless and the definition or the role register needs
fixing, not patience.

## Hearing about changes

`tik notify` runs a local notifier that re-derives and verifies every
ticket whose log changed and pushes a hint of each stage change to the
sessions that asked for it, over a Unix socket only your user can reach:

```sh
tik notify --config ~/.config/tik/notify.edn
```

```clojure
{:stores {"metio" "~/git/github.com/metio/.tik"}
 :window "PT5S"
 :sinks [{:store "metio" :where "stage=:asked"
          :command ["notify-send" "tik" "{line}"]}]}
```

A Claude Code session subscribes by running `tik notify watch` under the
Monitor tool, which feeds each output line back to Claude:

```sh
tik notify watch --store metio --where "fact:request.from=infra.run"
```

Each line is one JSON hint naming the store, the ticket, its title, the
stages it left and entered, and the actor of the event that moved it. A
hint holds no fact values and is never evidence: Claude re-derives with
`tik status` before acting on it. `tik notify channel` delivers the same
hints as a Claude Code channel to a session started with
`--dangerously-load-development-channels server:<name>`. Hints sent while
nobody was subscribed are not replayed, so keep a slow fallback poll. The
decisions behind this are in [ADR 0040](/decisions/0040-a-notifier-pushes-hints-not-evidence/).

## For agents generally

The skill is one surface over a derivation every tool can read. The gated
agent commands enforce the same boundary without any prompt-side
cooperation:

```sh
tik agent actions 3184 --actor bot
tik agent set 3184 severity=:high --actor bot
```

An agent sees only what the frontier admits for its role, and anything
else is refused with the derived reason. Because the boundary is the
derivation rather than an instruction, it cannot be talked around.

This site also publishes `/llms.txt` and `/llms-full.txt`, so a model can
read the documentation directly.
