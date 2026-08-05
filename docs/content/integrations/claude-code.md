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
