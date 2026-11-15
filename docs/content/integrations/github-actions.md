---
title: GitHub Actions
description: Failed workflow runs as ci-failure tickets, one per failure streak.
tags: [github, ci, bridge, metrics]
---

```sh
tik bridge github --config github.edn             # poll once
tik bridge github --config github.edn --webhook   # serve workflow_run deliveries
```

`tik bridge github` turns failed GitHub Actions runs into tickets under the
`ci-failure` process. One ticket covers one failure streak: the consecutive
failed runs of one workflow on one branch, from the first failure after a
green run up to the next green run. Further failures attach to the open
ticket as `ci/run/<id>/<attempt>` artifacts, the next green run is
recorded as `forge.green-run`, and a failure after that opens a new ticket.
Cancelled, skipped and neutral runs neither extend nor end a streak.
[GitLab pipelines](/integrations/gitlab-pipelines/) feed the same process
with the same facts, so the metrics below cover both forges.

## Configuration

```clojure
{:repos   ["metio/tik" {:repo "metio/jaas" :branches ["main" "next"]}]
 :orgs    ["metio" "sebhoss"]       ; every non-archived repository, default branch
 :exclude ["metio/scratch"]
 :token   {:env "GITHUB_TOKEN"}     ; or {:command …} / {:file …} / {:credential …}
 :tokens  {"sebhoss" {:command ["pass" "show" "github/sebhoss"]}}
 :api     "https://api.github.com"  ; a GitHub Enterprise Server URL also works
 :actor   "github"
 :actions {:process   :ci-failure
           :lookback  "P14D"        ; streaks with no run newer than this are left alone
           :max-pages 5             ; history pages fetched to find a streak's first run
           :failing   #{"failure" "timed_out" "startup_failure"}
           :green     #{"success"}}
 :webhook {:secret {:env "GITHUB_WEBHOOK_SECRET"} :port 8787}}
```

Each source is enabled by its block; `:actions` is the only source so far.
A token needs read access to Actions and metadata on the repositories it
covers. `:tokens` maps a repository owner to its own token, because a
fine-grained GitHub token is scoped to one resource owner; owners without
an entry use `:token`, then `$GITHUB_TOKEN`. An `:orgs` entry may also name
a user account: its public repositories are listed, plus its private ones
when that owner's token belongs to the user. A failure listing one owner is
reported and skips that owner only.

The bridge actor (`github` by default) must be registered in `actors` with
the key in `TIK_KEY`. The `ci-failure` process's `:bridge` role holds only
the forge bridges (`github`, `gitlab`), so nobody else can open or recover
a streak.

Each ticket records `forge.host`, `forge.repo`, `forge.owner`,
`forge.workflow`, `forge.branch`, `forge.first-run` and `forge.head-sha`,
plus `repo`, the repository's name as a keyword (`:tik`): the value `tik
new` records for a ticket made inside the checkout. A store above many
checkouts therefore shows a repository's CI failures next to its other
tickets (`tik ls --all --where 'fact:repo=:tik'`).

## Polling and webhooks

Both drivers run the same sync and are idempotent, so they can run side by
side and on several replicas. A ticket id hashes the repository id,
workflow, branch and first failed run. A ticket is dated by the creation
time of its first failed run, and each later event by the start time of
the run attempt it records. GitHub never changes either timestamp, whereas
it does rewrite `updated_at` on old runs. Two replicas syncing the same
history therefore write identical events, and a re-poll writes nothing.
Time to `:recovered` runs from the first failed run's creation to the
start of the green run.

Run both from `tik backend` (`pipelines.edn`):

```clojure
{:pipelines
 [{:id :github-poll    :every "PT10M" :run ["bridge" "github"] :as "github"}
  {:id :github-webhook :watch true    :run ["bridge" "github" "--webhook"] :as "github"}]}
```

For the webhook, point a repository or organization webhook at the
listener with content type `application/json`, the shared secret, and the
*Workflow runs* event. Deliveries without a valid `X-Hub-Signature-256` are
refused with 401. A delivery only names the workflow; the bridge then reads
that workflow's history from the API, so a lost delivery is repaired by the
next poll.

A streak whose first run lies beyond `:max-pages` of history is skipped
with a message instead of being opened on a guessed start, because a
guessed start would give replicas different ticket ids.

## Triage

The cause is the one fact the forge cannot supply:

```sh
tik set <id> cause=dependency   # flaky | code | dependency | config | infra | upstream
```

A ticket reaches `:resolved` once it has both a cause and a bridge-signed
green run.

## Metrics

`tik stats` groups any tickets by a fact and derives the counts and the time
from creation to a stage:

```sh
tik stats --by forge.repo --split cause --until :resolved --where 'fact:forge.workflow'
tik stats --by forge.host --until :recovered
tik stats --by forge.repo --format json
```

The columns are the ticket count, how many have not reached the stage, and
the median, 90th-percentile and mean time to reach it. The
`fact:forge.workflow` term restricts the count to CI-failure tickets. Every number is recomputed
from the ticket logs on each run.
