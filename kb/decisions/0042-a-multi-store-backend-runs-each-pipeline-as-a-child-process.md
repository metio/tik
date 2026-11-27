---
type: decision
status: proposed
date: 2026-11-27
title: A multi-store backend runs each pipeline as a child process of the same binary, scheduled on a grid with jitter, never overlapping, bounded and reported, and watches mailboxes under strict rules
supersedes: null
---

# ADR 0042: A multi-store backend runs each pipeline as a child process

## Decision

Proposed on 2026-11-27. Not accepted. It is implemented in `tik backend`
under ticket 7eb48e71 of the metio store, and builds on ADR 0041. Each
choice names the alternatives and why it was taken, ranked by security
first, then extensibility and re-use, then performance.

**A multi-store backend (a config with `:stores`) runs every run of every
pipeline as a child process of the same tik binary, started with its
store's variables only. The pipelines replace the systemd timers that ran
one bridge each: they fire on a grid with jitter, collapse missed fires
into one, never overlap a pipeline's own runs, are killed when they
outlive a timeout, write labelled lines to the journal and send a failure
report.** A single-store backend (`pipelines.edn` without `:stores`) is
unchanged and still runs its verbs in process.

```clojure
{:stores {:metio {:root "~/git/github.com/metio/.tik" :org "metio" :actor "claude"
                  :key {:command ["pasejo" "secret" "show" "--store" "personal-ls" "tik/metio/claude"]}}}
 :on-failure {:run ["notify-send" "--urgency=critical" "tik {store}/{pipeline}" "{reason}"]}
 :pipelines [{:id :github-metio :store :metio :every "PT10M" :as "github"
              :key {:credential "github-metio-key"} :run ["bridge" "github"]}
             {:id :mail-metio :store :metio :watch true :as "mail"
              :key {:credential "mail-metio-key"} :config "imap.edn"
              :run ["bridge" "imap" "--watch"]}]}
```

### 1. A run is a child process, not a call

Each run is `<this tik> <verb> --actor <as> [--config <file>]` with the
environment of `tik.context/child-env` for its store: `TIK_ROOT`,
`TIK_KEY` and `TIK_ACTOR` of that store and pipeline, nothing of any
other store's.

- **Security.** A child shares no memory with another store's runs. The
  derived-row cache leak found under ADR 0041 shows the class of bug a
  shared process invites: any process-global cache, rate limiter or
  `alter-var-root` is a channel between stores. A process boundary closes
  every such channel at once, where a context binding closes only the ones
  somebody remembered to bind. The boundary is against mistakes: every
  child runs as the same Unix user, so a compromised child could read any
  file the backend can.
- **A bounded run needs a kill.** A bridge blocked in a socket read ignores
  `Thread.interrupt`, so an in-process run cannot be stopped when it hangs.
  A child is stopped with SIGTERM, then SIGKILL after five seconds, with
  the processes it started (a password manager, an ssh tunnel) where the
  runtime can list them (the JVM and the native image; babashka cannot).
- **Labelled output.** A child's stdout and stderr are read line by line
  and written as `[store/pipeline] <line>`, from every thread the verb
  starts. An in-process run captures only the calling thread's `*out*`, and
  only once the run ends.
- **Cost.** One process start per run: milliseconds for the native binary,
  about a second for babashka. A ten-minute bridge interval does not
  notice either.

Rejected: in-process runs bound to the store context (ADR 0041's first
shape), for the reasons above.

### 2. How the backend names itself

`:exec` in the config wins. Without it, a native image runs its own
executable (`/proc/self/exe`) and babashka runs `bb --config <its bb.edn>
tik`. Any other runtime, such as a JVM REPL, cannot name its own command
line, and the backend refuses to start pipelines rather than guess. The
`javax.net.ssl.trustStore*` properties the backend was started with are
passed on as `-D` arguments, so a private CA reaches every run.

### 3. The scheduler is pure

`tik.schedule` takes the clock and the random draw as arguments and
returns data; `tik.pipeline-run` executes its decisions. The properties are
property-tested without a clock or a process.

- **Grid and jitter.** A pipeline keeps a grid point (start plus whole
  intervals) and fires at the grid point plus a draw from `[0, :jitter)`.
  The default jitter is one minute, the timers' `RandomizedDelaySec=60`,
  and never more than half the interval. A grid does not drift by the run
  time, as an `OnCalendar` timer does not.
- **Catch-up.** Every fire missed while the backend was stopped or the
  machine slept collapses into one run (`Persistent=true`). A sleep does
  not advance during a suspend, so the scheduler re-reads the wall clock at
  least every 30 seconds. A fire found more than 35 seconds late is
  re-drawn into its jitter window once, so the pipelines that all missed
  do not start in the same instant after a resume.
- **No overlap.** A pipeline due while its run still goes is not started a
  second time; the fire is skipped, logged, and the next one is the next
  grid point. Different pipelines run concurrently. This is a per-process
  guard on delivery timing, not a lock on any correctness path: two
  backends running the same pipeline still converge, because the verbs
  mint content-addressed events (ADR 0021).
- **Bounded.** A run is killed after `:timeout`, by default its interval,
  so a stuck run never outlives the period it belongs to.
- **Restarts.** A `:watch` pipeline restarts after 1 s, doubling to 60 s
  while it keeps failing fast, and starts over at 1 s once a run lasted a
  minute.

### 4. Failure reports

`:on-failure {:run [...] :repeat "PT1H"}` runs a command without a shell
when a run fails or times out, with `{store}`, `{pipeline}`, `{exit}` and
`{reason}` filled in separate arguments. Every value comes from the
backend (names from its config, a number, a sentence it wrote), never from
a remote, and no output of the run is passed on. A pipeline is reported
when it goes from working to failing, and again after `:repeat` while it
keeps failing; `"PT0S"` reports every failure as systemd's `OnFailure=`
does. The default of an hour exists because a crash-looping watcher
restarts every minute. Every failure is also written as `FAILED: <reason>`
at priority err.

### 5. The journal

Every line is labelled `[store/pipeline]`. When systemd sets
`JOURNAL_STREAM`, each line also carries an sd-daemon priority prefix
(`<6>` progress, `<5>` a skip, `<4>` a run's stderr, `<3>` failures), so
`journalctl -p warning` shows what needs attention. Line breaks inside a
child's line are flattened, so a line cannot forge another pipeline's
label. A child never sees `JOURNAL_STREAM`, since it writes to a pipe.

### 6. Config files per pipeline

`:config` names the verb's config file, relative to the store's root,
absolute, or `~/…`; the backend passes it as `--config <absolute path>`
and refuses to start when it does not exist. `--config` inside `:run` is
refused, since it would resolve against the backend's working directory.
Without `:config` the verb reads its default file at the store's root, as
before.

### 7. Delegate keys

A pipeline's `:key` must sign as its `:as` in its own store's `actors`
file (ADR 0041). Two pipelines of stores with different organisations may
not resolve to the same key unless `:allow-shared-keys` names the pair,
which extends ADR 0041's rule from store keys to delegate keys: one
organisation's delegate never signs for another's.

The backend also refuses `GITHUB_TOKEN`, `GITHUB_WEBHOOK_SECRET`,
`GITLAB_TOKEN`, `REDMINE_API_KEY` and `ODOO_PASSWORD` in its environment.
Every child inherits the backend's environment, so a token meant for one
organisation's bridge would reach every store's runs. The configs name
their secrets with `{:command …}` or `{:credential …}` instead.

### 8. Mailboxes

`bridge imap` reads one mailbox under `:imap` or a list under
`:mailboxes`, each with an `:id`, its own `:imap` settings and its own
`:process`; the other top-level keys are defaults for every mailbox. A
pipeline belongs to one store, so a store's mailboxes share one pipeline
and one process, each mailbox on its own connection and thread with its
own backoff. Adding `support@klimpi.app` is a list entry.

A `:mailboxes` list, and any IMAP pipeline of a multi-store backend
(checked before the backend starts), holds to strict rules:

- `:dkim {:require true :authserv-id …}` with a non-blank id, so a From is
  credited only when the pinned verifier stamped `dkim=pass` for its
  domain;
- the password is a `{:command …}`, `{:credential …}` or `{:file …}`
  spec, never a literal and never `{:env …}`, which every child inherits;
- the pipeline's key must sign as every actor a mailbox records mail as
  (`:default-actor` and each `:from->actor` value), or what it ingests
  would not verify.

For every IMAP and POP3 connection, strict or not: a TLS socket checks
that the certificate names the host (a raw `SSLSocket` checks only that
the chain is trusted, so any certificate a trusted CA issued for any name
was accepted), and plaintext (`:tls false`) is refused for any host not
written as a loopback address, judged on the text without DNS. A watch
that drops says why on stderr, without the password, and a plaintext
refusal ends it instead of retrying.

## Consequences

- The systemd timer per bridge and the board service become one unit
  running `tik backend --config backend.edn`; see the local-service guide.
- The board and MCP move below `/s/<store>/` (ADR 0041), so MCP clients
  registered on `/mcp` need the new path.
- `tik.process-tree` exists only for the JVM and the native image;
  babashka stops a timed-out run without its descendants.
- Not done: a per-mailbox fact on new tickets (such as `product=klimpi`);
  each mailbox chooses its process instead.
