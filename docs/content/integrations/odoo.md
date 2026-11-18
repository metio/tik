---
title: Odoo
description: Odoo project tasks mirrored as odoo-task tickets, with their history.
tags: [odoo, bridge, sync]
---

```sh
tik bridge odoo --config odoo.edn   # poll once
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
{:url           "https://odoo.example"
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
current. `:users` renders an Odoo user as a tik actor in `odoo.assignee`;
other users appear under the name Odoo shows.

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
