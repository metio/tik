---
title: GitLab pipelines
description: Failed GitLab pipelines as ci-failure tickets, one per failure streak, optionally through an SSH port forward.
tags: [gitlab, ci, bridge, metrics, ssh]
---

```sh
tik bridge gitlab --config gitlab.edn   # poll once
```

`tik bridge gitlab` turns failed GitLab pipelines into `ci-failure`
tickets, with the same streak rules, facts and metrics as
[GitHub Actions](/integrations/github-actions/). A pipeline has no
workflow id; its source (`push`, `schedule`, `web`, …) takes that place,
so a failing nightly schedule and green push pipelines on the same branch
are separate streaks, titled `push pipeline`, `schedule pipeline` and so
on. Pipelines with any status other than `failed` and `success` (canceled,
skipped, manual) neither extend nor end a streak.

## Configuration

```clojure
{:url        "https://gitlab.example"   ; the web URL; the API is <url>/api/v4
 :membership true                       ; every project the token's account is a member of
 :groups     ["team/platform"]          ; every project of a group and its subgroups
 :projects   ["team/app" {:project "team/lib" :branches ["main" "next"]}]
 :exclude    ["team/platform/scratch"]
 :token      {:env "GITLAB_TOKEN"}      ; or {:command …} / {:file …} / {:credential …}
 :tunnel     {:ssh "admin@gitlab.example" :target "127.0.0.1:8080"}
 :actor      "gitlab"
 :pipelines  {:process   :ci-failure
              :lookback  "P14D"
              :max-pages 5
              :failing   #{"failed"}
              :green     #{"success"}}}
```

The token needs read access to the API (`read_api`). Archived projects
are left out, and a project without `:branches` watches its default branch.
A failure listing one group or project is reported and skips it only.

## SSH port forward

Some GitLab instances only serve their API on the host itself. With
`:tunnel`, the bridge runs `ssh -N -L 127.0.0.1:<free port>:<target>
<ssh>` for the duration of the sweep and sends the API requests to the
local end. The forwarded target speaks plain HTTP; the connection that
leaves the machine is the SSH one. Outside a tunnel, the bridge refuses a
non-HTTPS API URL. The SSH destination must be an account allowed to
forward ports, and ssh runs with `BatchMode=yes`, so its key must be
usable without a prompt (an agent or an unencrypted key).

## Retried pipelines

A retried pipeline keeps its id and shows only its latest outcome, so the
bridge checks every green pipeline for a ticket opened on it and records
the recovery there. GitLab's pipeline list carries no start time, so such
a recovery is dated by the pipeline's creation and its time to
`:recovered` reads as zero.
