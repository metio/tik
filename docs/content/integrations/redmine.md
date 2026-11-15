---
title: Redmine
description: Redmine issues mirrored as redmine-issue tickets, with their history.
tags: [redmine, bridge, sync]
---

```sh
tik bridge redmine --config redmine.edn   # poll once
```

`tik bridge redmine` mirrors Redmine issues into the store as tickets
under the `redmine-issue` process. Each ticket carries the issue's
status, assignee, priority, tracker, project, subject and due date as
facts signed by the bridge, its description and notes as artifacts, and
its URL as `redmine.url`. The stage follows the mirrored status: `:open`,
or `:closed` when Redmine counts the status as closed. Reopening the issue
in Redmine brings the ticket back to `:open` on the next sync.

Redmine stays the place where the issue is worked on. Facts you add
locally (a `link.*` to another ticket, notes for yourself) stay in tik and
never move the mirrored stages.

## Configuration

```clojure
{:url     "https://redmine.example"
 :key     {:env "REDMINE_API_KEY"}         ; your API key (My account → API access key)
 :query   {:assigned_to_id "me"}           ; issues.json filters; all statuses are included
 :users   {11 "seb"}                       ; Redmine user id -> tik actor
 :actor   "redmine"
 :process :redmine-issue}
```

Each sweep syncs every issue the query selects, plus every issue the store
already mirrors, so an issue that is reassigned or moved keeps its ticket
current. `:users` renders a Redmine user as a tik actor in
`redmine.assignee`; other users appear under the name Redmine shows.

## History

Every event is a function of the issue and its journal. The ticket is
dated by the issue's creation and carries the values the issue was opened
with, recovered by undoing each journal entry from the current values.
Each journal entry then adds its changes at its own time, and its note as
a `redmine/journal/<id>` artifact. A re-sync adds only the entries that
are new, and two machines syncing the same Redmine write identical events.

Statuses, priorities and trackers are rendered by name from Redmine's
current configuration. Renaming one in Redmine adds assertions under the
new name on the next sync; the latest journal entry still decides the
current value.

## Open work

```sh
tik ls --all --where 'stage=:open and fact:redmine.assignee=seb'
tik stats --by redmine.project --until :closed
```
