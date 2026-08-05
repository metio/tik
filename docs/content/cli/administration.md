---
title: Store administration
description: Identity, roles, verification, migration, storage backends, and alerts.
tags: [cli, verify, roles, migration, effects]
---

## Identity

```sh
tik actor add alice ~/.config/tik/id_ed25519.pub
tik sign 3184
tik witness 3184
```

`actor add` registers a signer in the store's allowed-signers registry.
Exporting `TIK_KEY` signs every write as it happens; `sign` catches up on
your own earlier events, and only your own — signing somebody else's would
assert something false. `witness` countersigns a ticket's head, and one
signature timestamps the entire ancestry beneath it.

## Roles

```sh
tik roles
tik roles add triager alice
tik roles remove triager bob
```

A process definition declares which roles exist and who starts in them; the
store's register decides who is in one today, for every ticket at once. Use
it for a hire or a departure — it takes effect on in-flight tickets
immediately, with no definition version bump and no per-ticket migration.
See [Roles and authority](/authoring/roles-and-authority/).

## Verification

```sh
tik verify
tik verify --changed
tik root --witness
```

`verify` runs the ladder: content addressing, schema, signatures, and
re-derivation. `--changed` skips unchanged heads for a fast drift check
rather than the full audit. `root` prints one hash committing to the entire
store, optionally countersigned, optionally anchored to a third-party
timestamp.

## Moving tickets to newer rules

```sh
tik reprocess 3184 processes/support-request.edn
tik reprocess 3184 processes/support-request.edn --apply
```

A ticket is judged by the rules it was minted under, so a definition that
grows leaves existing tickets deriving under the old one until you move
them — deliberately, because an implicit upgrade would re-judge a whole
store on an edit. The dry run prints the pinned-versus-proposed hashes,
which stages would be gained or would regress, and the new blockers.
`--apply` records the re-pin as a signed event, so the log keeps which
rules judged the ticket when.

## Storage

```sh
tik init --sqlite
tik store migrate --to file
tik export ./audit-copy
tik pack
tik gc --apply
```

The file store is the signed interchange format; SQLite keeps the same
events in one file. Migration is lossless in both directions — events and
their detached signatures travel together. `export` materializes any store
as the file format. `pack` consolidates settled tickets into one
content-addressed pack each, and `gc` removes archived definitions no
ticket pins any more.

## Alerts out, mail in

```sh
tik effects run --config effects.edn --dry-run
tik bridge email < message.eml
```

`effects run` pushes derived stage transitions to Slack, Discord, Matrix,
Teams, ntfy, PagerDuty, plain webhooks, email, or any program through the
command sink. Any sink field may be a secret resolved at send time — from
an environment variable, a file, a command, or a systemd credential — so
no secret sits in the config file.

The email bridge turns messages into tickets and comments, and replies
carrying `tik> key=value` lines become facts, which closes the loop for
people who never leave their inbox.

## The gated agent surface

```sh
tik agent actions 3184 --actor bot
tik agent set 3184 severity=:high --actor bot
```

An agent sees only what the frontier admits for its role, and anything else
is refused with the derived reason. The boundary is the same derivation
everyone else reads, so it cannot be talked around.
