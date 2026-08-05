---
title: Reading a store
description: The inbox, the board, explain, selectors, history, and counterfactuals.
tags: [cli, explain, next, board, selectors]
---

Every view here is computed when you ask, so none of them can be stale.

## What should I do?

```sh
tik next --actor alice
tik next --role :triager
tik explain 3184 --actor alice
```

`next` ranks by how much downstream work each step unlocks, with quiet
tickets rising. `explain` is the per-ticket answer: what is missing, whose
signature it needs, and which stages it blocks. See
[Explain](/concepts/explain/) for the model behind them.

## The board

```sh
tik ls
tik ls --all --long
tik ls --where 'stage=:blocked and fact:severity=:high and not disputed'
tik search firefox login
```

A selector is space-separated terms, all ANDed, each optionally negated:
`stage=:blocked`, `fact:severity`, `fact:severity=:high`, `actor=seb`,
`disputed`, `conflicted`, `unsigned`, `derived-from=<hash>`, `~text`. The
same grammar drives `search`, and `tik dupes` reports near-title
lookalikes.

## One ticket

```sh
tik status 3184
tik status 3184 --at 2026-03-01T00:00:00Z
tik log 3184
tik diff 3184 5
```

`status` reports the derived stage, the facts behind it, links, and what is
next. `--at` answers the same question about any past moment by evaluating
that moment. `diff` shows the evidence gained over the last few events.

## Why, and what if

```sh
tik causal 3184
tik whatif 3184 severity=:low +P2D retract:category
tik debug 3184
```

`causal` names the signed events that made each reached stage true —
including negations and time saying so honestly. `whatif` shows the stage
diff a change would produce and writes nothing. `debug` shows the fixpoint
with its working: every sweep, every guard verdict.

## The roadmap and the wider picture

```sh
tik plan
tik plan roadmap.html
tik roles
tik work week --actor alice
```

`plan` derives the dependency-link roadmap — ready, blocked, done, cyclic,
the critical path, and each item's unlock impact. `roles` shows who gates
what: every role on the open board, its effective members, and the stages
waiting on its signature.

## Sharing what you see

```sh
tik board board.html
tik serve --port 8080
tik bundle 3184 --out ticket-3184.tgz
```

`board` renders the whole store into one dependency-free HTML file you can
mail or archive. `serve` publishes it live, read-only, with `/tickets.edn`
and `/explain/<id>.edn` for tools. `bundle` packs one ticket — events,
signatures, witness marks, the pinned ruleset, and a `verify.sh` — into a
tarball a third party checks with coreutils and `ssh-keygen`, no tik
required.
