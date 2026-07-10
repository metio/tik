<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# Getting started

A working ticket system in under five minutes: no server, no database,
no account, no YAML. Tickets are plain files in a directory you own;
everything else — stages, boards, inboxes — is computed from them when
you ask.

Every command below is executed verbatim by the test suite
(`test/tik/guide_test.clj`), so if this page and the software ever
disagree, the build breaks before you can notice.

## Install

Either run the single binary (Linux x86-64, no dependencies):

```sh
./tik --help
```

or run from source with [babashka](https://babashka.org/):

```sh
bb tik --help
```

The pages below write `tik`; substitute `bb tik` if you run from
source.

## Minute one: track a thing

Make an empty directory and create a ticket. That is the entire setup:

```sh
mkdir my-tickets && cd my-tickets
tik new track --title "replace the office router"
```

The command prints the ticket's id. Two commands tell you everything
about it (a unique prefix of the id is enough, like git):

```sh
tik status <id>     # where it stands, and what would move it
tik ls              # every open ticket, with its derived stage
```

The built-in `track` process has two stages: the ticket exists
(`open`), and it ended with an outcome on record (`done`). Record the
outcome and the ticket settles itself:

```sh
tik set <id> outcome=ordered the UniFi one, arrives Tuesday
```

Notice what you did NOT do: no status dropdown, no "move to Done".
You recorded a fact; the stage followed from it. That is the whole
model — **stages are never set, they are derived from facts**, and
that stays true from this two-stage starter to the largest process
you will ever define.

## Minute three: a real process from a template

`track` is deliberately minimal. For a bug tracker, start from the
built-in template:

```sh
tik author --template bug
```

This writes three things you own and can edit freely:

- `processes/bug.edn` — the process definition: four stages
  (`reported`, `confirmed`, `fixed`, `verified`), each defined by
  WHAT MUST BE TRUE to reach it, never by who dragged a card where.
- `kb/runbooks/bug-*.md` — one short runbook per stage.
- `processes/bug.tests.edn` — scripted tests for the process itself.

Open `processes/bug.edn` and put real usernames in the two roles
(they ship as `change-me`), then file the first bug:

```sh
tik new bug --title "login fails on Firefox"
tik set <id> severity=:high repro.steps=open the login page, click sign in, watch it 500
```

Now ask the question every ticket system exists to answer:

```sh
tik explain <id>
```

```text
To reach :confirmed:
  ✗ fact [:approval :triager] = :approved — a member of role :triager must sign
```

explain never speculates: every line is derived from the definition
and the recorded facts, ordered by what you can act on right now.
When several people share the board, each gets a personal answer:

```sh
tik next --actor alice     # what can alice do that unlocks the most?
tik next --role :triager   # what is the triager role being waited on for?
```

## Minute five: your own process

Describe your workflow in plain words and tik writes the definition —
no EDN knowledge needed:

```sh
tik author
```

The interview asks for stages ("what does reaching this stage mean?")
and, per stage, what must be true: a piece of information, a choice
from fixed options, a signature by a role, a file, or waiting time.
It compiles your answers to a linted definition plus runbooks and a
test skeleton.

Prefer to draft with an LLM? `tik author prompt` prints a prompt that
makes any model emit the answers file, and
`tik author --from answers.edn` builds from it.

Try a definition live before using it — a scratch ticket, reloading
on every save:

```sh
tik sim processes/bug.edn
```

And pin its behavior with scripted tests (steps in, expected stages
out; failures print explain so the process tells you why):

```sh
tik test processes/bug.tests.edn
```

## What you now have that a tracker does not give you

**An audit trail nobody can quietly edit.** A ticket is an
append-only log of content-addressed events: the filename of every
event IS the sha256 of its bytes, and each event names its parents.
`tik verify` re-checks the whole store with nothing but hashes — and
with a signing key (`tik actor add`, `TIK_KEY`), every write is
signed and verification extends to WHO said everything:

```sh
tik verify
```

**Time travel and what-ifs, for free.** Because stages are computed
from facts, any question about any moment is answerable:

```sh
tik status <id> --at 2026-07-01T00:00:00Z   # the state back then
tik whatif <id> severity=:low               # what would change? (nothing is written)
tik causal <id>                             # which events made each stage true
```

**A board you can mail.** `tik board` renders the whole store into
one dependency-free HTML file; `tik serve` serves it live; `tik
bundle <id>` packs one ticket into a tarball a third party can verify
with nothing but coreutils and ssh-keygen.

**Email in, alerts out.** `tik bridge email` turns mail into tickets
and comments (replies with `tik> key=value` lines become facts);
`tik effects run` pushes derived transitions to Slack, Discord,
Matrix, Teams, ntfy, PagerDuty, plain webhooks, email, or any program
via the command sink — see `tik --help` for the full list.

## Where your data lives

In the directory you made: `tickets/` holds the events, `processes/`
your definitions, `actors` the signer registry. Plain files — put
them in git and you have replication, history, and backup; two
machines that both changed a ticket merge by file union, and the
derived stage converges because derivation is a pure function of the
event set.

Nothing is ever stored about a ticket's stage. If that sentence
bothers you, you now understand tik; if it delights you, read
[PLAN.md](PLAN.md) — the design in full.
