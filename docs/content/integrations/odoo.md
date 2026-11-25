---
title: Odoo
description: Odoo project tasks mirrored as odoo-task tickets, with their history.
tags: [odoo, bridge, sync]
---

```sh
tik bridge odoo --config odoo.edn                               # poll once
tik bridge odoo describe --model project.task --config odoo.edn # read a model's fields
```

`tik bridge odoo` mirrors Odoo project tasks into the store as tickets
under the `odoo-task` process. Each ticket carries the task's title,
stage, assignee and project as facts signed by the bridge, its
conversation as artifacts, and its URL as `odoo.url`. The stage follows
the mirrored Odoo stage: `:closed` when the configuration names that stage
as closed, `:open` for every other stage. Moving the task back to an open
stage in Odoo brings the ticket back to `:open` on the next sync.

Odoo stays the place where the task is worked on. Facts you add locally
(a `link.*` to another ticket, notes for yourself) stay in tik and never
move the mirrored stages.

## Configuration

```clojure
{:profile       "project.task"             ; optional; the Odoo model to mirror
 :url           "https://odoo.example"
 :db            "odoo"                     ; the database name
 :user          "you@example.com"          ; a string or a secret spec
 :password      {:env "ODOO_PASSWORD"}     ; a secret spec
 :closed-stages ["Done" "Cancelled"]       ; stage names that count as closed
 :users         {45 "seb"}                 ; Odoo user id -> tik actor
 :domain        [["user_id" "=" 45]]       ; optional; default: tasks assigned to :user
 :actor         "odoo"
 :process       :odoo-task}
```

The bridge talks to Odoo's JSON-RPC endpoint, `/jsonrpc`. Odoo 12 has no
API keys, so it logs in with the user's password and sends it with every
call; a user with read access to the tasks is enough. Odoo often refuses to
list its databases, in which case the database name shows after logging in
through the web interface (`/web/session/get_session_info` reports it as
`db`).

Odoo 12 stages carry no closed flag. Folding a stage only hides its column,
and a folded backlog is still open work, so `:closed-stages` names the
closed stages explicitly.

Each sweep syncs every task the domain selects, plus every task the store
already mirrors, so a task that is reassigned or archived keeps its ticket
current. A task counts as mirrored when the store holds its own ticket, the
one whose id the host of `:url`, the model and `odoo.id` name; `odoo.url`
plays no part, so changing the scheme, port or path of `:url` on the same
host keeps every task, and a ticket of another host or model is never
taken for one. `:users` renders an Odoo user as a tik actor in `odoo.assignee`;
other users appear under the name Odoo shows.

## Profiles

A profile describes how one Odoo model becomes tickets: which tracked
fields become which facts, which field derives the ticket's state and from
which configuration lists, how the ticket title is formed, which chatter
messages become artifacts, the default domain and the process. `:profile`
names the profile by its model, as a string or a keyword, and defaults to
`"project.task"`. A configuration that names a model without a profile is
refused when it loads, with the list of profiles there are.

| Profile | Facts | State | Title | Configuration |
|---|---|---|---|---|
| `project.task` | `odoo.title`, `odoo.stage`, `odoo.assignee`, `odoo.project` | `odoo.state`: `:closed` for a stage in `:closed-stages`, else `:open` | `#<id> <title the task was created with>` | `:closed-stages` |

Every profile also asserts `odoo.id` and `odoo.url`, whose URL names the
model. The ticket id is a name-based UUID over the Odoo host, the model and
the record id, so records of two models with the same id become two
tickets.

## Describing a model

```sh
tik bridge odoo describe --model helpdesk.ticket --config odoo.edn
tik bridge odoo describe --model project.task --edn
tik bridge odoo describe --model project.task --format json
```

`tik bridge odoo describe` asks the server how it defines one model's
fields and prints, for each field, its type, label, whether it is required
or read-only, the model a relation points at, the values of a selection,
and whether Odoo tracks it. Only tracked fields can be mirrored with their
history, so this is the list to read before writing a profile for a new
model. The output also says whether the model inherits `mail.thread`.

The command writes nothing to Odoo or to the store. It logs in and calls
`version`, `login` and `fields_get`, and refuses every other call before it
leaves tik, so a write method cannot be reached from it. It reads `:url`,
`:db`, `:user` and `:password` from the same `odoo.edn` as the bridge;
`:password` has to be a secret spec such as `{:env "ODOO_PASSWORD"}` or
`{:command "pasejo secret show …"}`, and a literal password in the file is
refused. Neither the output nor an error message carries the password.

Odoo 12 reports the tracking flag as `track_visibility`, later versions as
`tracking`; the command asks for both and names the one the server
answered with. When the server reports neither, every field's tracked flag
reads `unknown`. Whether `message_post` accepts a `message_id` argument
also reads `unknown`: `fields_get` describes fields, and Odoo offers no
read-only call that reports a method's arguments.

When the model has a profile, the output ends with how the profile fits
the server: the fields it reads that the server lacks, fields whose type
differs from the one the profile declares, actor fields that do not point
at `res.users`, mirrored fields the server does not track, and, for a
state read from a selection field, the selection values no configuration
list names, which fall to the profile's default state. A stage field such
as `stage_id` is a relation, so its stage names are records that
`fields_get` cannot list.

## History

Every event is a function of the task and its chatter. Odoo records each
change of a tracked field as a tracking value in the chatter, with the old
and new value as Odoo displays them. The ticket is dated by the task's
creation and carries the values the task was created with, recovered by
undoing each tracked change from the current values. Each message then
adds its changes at its own time, and a comment or email as an
`odoo/message/<id>` artifact. A re-sync adds only the messages that are
new, and two machines syncing the same Odoo write identical events.

Only the title, stage, assignee and project are mirrored, because those
are the fields Odoo tracks. A tracking value names its field by the label
the field had at the time, in the language of whoever made the change; the
bridge reads every field's label in every installed language and leaves
out a label two fields share.

## Open work

```sh
tik ls --all --where 'stage=:open and fact:odoo.assignee=seb'
tik stats --by odoo.project --until :closed
```
