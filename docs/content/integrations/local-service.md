---
title: Local service
description: Run the board, MCP, the bridges, backend and notify from the native binary, under systemd or by hand.
tags: [serve, bridge, backend, notify, systemd, install]
---

The native binary runs everything a long-running local install needs:
`tik serve` for the board and MCP, `tik bridge <kind>` sweeps, `tik
backend` for scheduled pipelines and `tik notify` for hints. It needs no
JVM, no babashka and no checkout. Every release checks this with
`dev/native-smoke.sh`, which drives the built binary through each of
these against loopback stand-ins for GitHub, GitLab, Redmine, Odoo and an
IMAP mailbox.

## The environment

| Variable | Read by | Meaning |
|---|---|---|
| `TIK_ROOT` | every verb | The store. Without it, tik uses the nearest ancestor holding `.tik/`, `tickets/` or `tik.db`. |
| `TIK_ACTOR` | every write | The actor events are signed as. `--actor` overrides it. |
| `TIK_KEY` | every write | The ed25519 private key that signs. `--key` overrides it. |
| `GITHUB_TOKEN` | `bridge github` | The token when `github.edn` names neither `:token` nor `:tokens`. |
| `GITHUB_WEBHOOK_SECRET` | `bridge github --webhook` | The HMAC secret when `github.edn` has no `:webhook {:secret …}`. |
| `GITLAB_TOKEN` | `bridge gitlab` | The token when `gitlab.edn` has no `:token`. |
| `REDMINE_API_KEY` | `bridge redmine` | The key when `redmine.edn` has no `:key`. |
| `ODOO_PASSWORD` | `bridge odoo` | The password when `odoo.edn` has no `:password`. |
| `CREDENTIALS_DIRECTORY` | any `{:credential …}` | Set by systemd for `LoadCredential=`. |
| `XDG_RUNTIME_DIR`, `XDG_STATE_HOME` | `notify` | The default socket directory and the cursor. |

Any secret in a bridge config can be a spec instead of a literal:
`{:command "pass show github"}` (the first line of the command's
output), `{:file "/run/secrets/x"}`, `{:env "NAME"}` or `{:credential
"NAME"}`. The credential form is the one to prefer under systemd: the
secret never sits in a file you commit, in the environment or in argv.
An unset variable, a missing file, a failing command and an empty or
blank value are refused rather than read as an empty secret. A command
has one minute to answer, its error output goes to tik's stderr instead
of into the refusal, and a file, credential or output longer than 64 KiB
is refused.

## The board and MCP

```sh
openssl rand -hex 32 > ~/.local/state/tik/mcp-token
TIK_ROOT=~/work/.tik TIK_ACTOR=claude TIK_KEY=~/.config/tik/claude_ed25519 \
  tik serve --host 127.0.0.1 --port 7777 \
    --mcp-token-file ~/.local/state/tik/mcp-token --surfaces http,mcp
```

`/` is the board, `/tickets.edn` and `/explain/<id>.edn` its data, and
`/mcp` the MCP endpoint. Clients send the token as `Authorization: Bearer
<token>`. `/mcp` answers both the `initialize` handshake and stateless
2026-07-28 requests; see [MCP server](/integrations/mcp-server/). Calls
on `/mcp` write as `TIK_ACTOR`, which is why the token is required and why
`serve` listens on 127.0.0.1 unless `--host` says otherwise.

## The bridges

Each sync bridge reads its config from the store root (`github.edn`,
`gitlab.edn`, `redmine.edn`, `odoo.edn`, `imap.edn`), or from `--config`,
sweeps once and exits 0, or 1 when any unit of the sweep failed:

```sh
export TIK_ROOT=~/work/.tik TIK_KEY=~/.config/tik/github_ed25519
tik bridge github                       # github.edn: :token or GITHUB_TOKEN
tik bridge gitlab                       # gitlab.edn: :token or GITLAB_TOKEN
tik bridge redmine                      # redmine.edn: :key or REDMINE_API_KEY
tik bridge odoo                         # odoo.edn: :password or ODOO_PASSWORD
tik bridge imap                         # imap.edn: {:imap {:host … :user … :password …}}
```

The actor of a sweep is the config's `:actor` (or `github`, `gitlab`,
`redmine`, `odoo`), so `TIK_KEY` must be that actor's key. A server that
cannot be reached, or whose certificate does not chain to a CA the binary
trusts, fails the sweep with a line naming the API, the address and the
cause, and nothing is written. For a server behind a private CA, name a
trust store holding that CA; the binary reads `-D` system properties
before its verb:

```sh
tik -Djavax.net.ssl.trustStore=/etc/pki/internal.p12 \
    -Djavax.net.ssl.trustStorePassword=changeit bridge odoo
```

The detail of each bridge is on its own page:
[GitHub Actions](/integrations/github-actions/),
[GitLab pipelines](/integrations/gitlab-pipelines/),
[Redmine](/integrations/redmine/) and [Odoo](/integrations/odoo/).

## Under systemd

A timer per bridge, with the signing key and the remote's secret handed
over as credentials. The key file is readable only by the unit while it
runs:

```ini
# ~/.config/systemd/user/tik-bridge@.service
[Unit]
Description=Sweep the %i tik bridge
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
EnvironmentFile=%h/.config/tik/bridge-%i.env
LoadCredential=tik-key:%h/.config/tik/%i_ed25519
Environment=TIK_KEY=%d/tik-key
ExecStart=%h/.local/bin/tik bridge ${TIK_BRIDGE}
```

```ini
# ~/.config/systemd/user/tik-bridge@.timer
[Unit]
Description=Sweep the %i tik bridge every 10 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=10min

[Install]
WantedBy=timers.target
```

```sh
# ~/.config/tik/bridge-github-work.env
TIK_BRIDGE=github
TIK_ROOT=/home/me/work/.tik
```

`%d` is the unit's credentials directory. A secret the config fetches
with `{:credential "github-token"}` arrives the same way, through another
`LoadCredential=github-token:<file>` line. Enable one instance per bridge
with `systemctl --user enable --now tik-bridge@github-work.timer`.

The board runs as a plain service:

```ini
# ~/.config/systemd/user/tik.service
[Unit]
Description=tik board and MCP on 127.0.0.1:7777

[Service]
Environment=TIK_ROOT=%h/work/.tik TIK_ACTOR=claude
LoadCredential=tik-key:%h/.config/tik/claude_ed25519
LoadCredential=mcp-token:%h/.local/state/tik/mcp-token
Environment=TIK_KEY=%d/tik-key
ExecStart=%h/.local/bin/tik serve --host 127.0.0.1 --port 7777 \
  --mcp-token-file %d/mcp-token --surfaces http,mcp
# tik exits with 143 on the SIGTERM of a stop, which is no failure.
SuccessExitStatus=143
Restart=on-failure

[Install]
WantedBy=default.target
```

## Pipelines instead of timers

`tik backend` runs the same sweeps from one process on an interval. Each
pipeline runs as the actor its `:as` names, passed as `--actor`, which
takes precedence over a bridge config's `:actor`. `TIK_KEY` is that
actor's key, so one backend process signs as one actor, and the bridge
role of each process (`roles.edn`) has to include it. Put
`pipelines.edn` at the store root:

```clojure
{:pipelines
 [{:id :github  :every "PT10M" :as "tik-backend" :run ["bridge" "github"]}
  {:id :redmine :every "PT10M" :as "tik-backend" :run ["bridge" "redmine"]}
  {:id :board   :watch true                      :run ["serve" "--port" "7777"]}]}
```

```sh
TIK_ROOT=~/work/.tik TIK_KEY=~/.config/tik/backend_ed25519 tik backend
```

A failing pipeline logs and the others carry on; a `:watch` pipeline is
restarted with backoff when it exits.

## Several stores in one backend

A config with `:stores` serves several stores from one process. Each
store signs with its own key as its own actor, and its board and MCP
answer below `/s/<store>/` with that store's own token:

```clojure
;; ~/.config/tik/backend.edn
{:stores {:work {:root "~/work/.tik" :org "work" :actor "claude"
                 :key {:credential "tik-work-key"}
                 :mcp-token-file "~/.local/state/tik/mcp-token-work"}
          :home {:root "~/home/.tik" :org "home" :actor "claude"
                 :key {:command ["pass" "show" "tik/home"]}}}
 :serve {:host "127.0.0.1" :port 7777}
 :notify {}
 :pipelines [{:id :github-work :store :work :every "PT10M" :run ["bridge" "github"]}]}
```

```sh
tik backend --config ~/.config/tik/backend.edn
```

- `:key` is a path, `{:file …}`, `{:credential …}` or `{:command …}`.
  A command's whole output is written to an owner-only file below
  `XDG_RUNTIME_DIR` for as long as the backend runs. `{:env …}` is
  refused.
- At start, each key must sign as the store's `:actor` according to that
  store's own `actors` file. Two stores with different `:org` may not
  resolve to the same key unless `:allow-shared-keys [[:work :home]]`
  says so.
- An MCP token file must hold at least 32 characters and be readable by
  you only, and each store needs its own. A token sent to another store's
  path is refused with 401.
- A pipeline names its `:store`. It runs as that store's actor, or as
  `:as`, which that store's `actors` file must grant the key to. It may
  not pass `--key`, `--actor` or `--config`, and may not run `serve`,
  `notify`, `mcp` or `backend`, which the backend provides for every
  store itself.
- `:notify` takes the settings of a `notify.edn` and watches every store
  of the backend.
- `TIK_ROOT`, `TIK_KEY`, `TIK_PQ_KEY` and `TIK_ACTOR` must not be set in
  the backend's environment, and neither may `GITHUB_TOKEN`,
  `GITHUB_WEBHOOK_SECRET`, `GITLAB_TOKEN`, `REDMINE_API_KEY` or
  `ODOO_PASSWORD`: every pipeline inherits the environment, so a token
  meant for one store's bridge would reach all of them. Name the secret
  in the bridge's config instead.

Any problem with any store refuses the whole backend and lists every
problem, so a running backend always serves every store it names.

## Bridges and mailboxes as pipelines

A multi-store backend replaces one systemd timer per bridge. Each run of
a pipeline is a child process of the same binary that sees only its own
store's root, key and actor, so a bridge signs as its own delegate in its
own store, and nothing one run keeps in memory reaches another store's.

```clojure
{:stores {...}
 :on-failure {:run ["notify-send" "--urgency=critical" "tik {store}/{pipeline}" "{reason}"]
              :repeat "PT1H"}
 :pipelines
 [{:id :github-work :store :work :every "PT10M" :as "github"
   :key {:credential "github-work-key"} :config "github.edn" :run ["bridge" "github"]}
  {:id :redmine-work :store :work :every "PT10M" :jitter "PT2M" :timeout "PT5M" :as "redmine"
   :key {:credential "redmine-work-key"} :run ["bridge" "redmine"]}
  {:id :support-mail :store :work :watch true :as "mail"
   :key {:credential "mail-work-key"} :config "imap.edn" :run ["bridge" "imap" "--watch"]}]}
```

| Key | Meaning | Default |
|---|---|---|
| `:every` | The interval of a scheduled pipeline, as an ISO-8601 duration. Fires sit on a grid from the backend's start, so they do not drift by the run time. | |
| `:jitter` | Each fire lands at a random point within this window after its grid point. | `PT1M`, at most half of `:every` |
| `:timeout` | A run still going after this is stopped (SIGTERM, then SIGKILL five seconds later) and counts as failed. | `:every` |
| `:watch` | A continuous pipeline, restarted after 1 s, doubling to 60 s while it keeps failing fast, and back to 1 s once a run lasted a minute. | |
| `:config` | The verb's config file, relative to the store's root, absolute, or `~/…`; it must exist when the backend starts. | the verb's default file at the store's root |
| `:as`, `:key` | The delegate the run signs as, and its key, which the store's `actors` file must grant to it. | the store's actor and key |

The backend's own keys:

- **No overlap.** A pipeline due while its previous run still goes is not
  started again; the skip is logged and the next fire is its next grid
  point. Different pipelines run at the same time.
- **Catch-up.** Fires missed while the backend was stopped or the machine
  slept collapse into one run, which lands within the jitter window of the
  moment the backend noticed, at most 30 seconds after a resume.
- **`:on-failure`** runs a command, without a shell, when a run fails or
  times out, with `{store}`, `{pipeline}`, `{exit}` and `{reason}` filled
  in. A pipeline is reported when it starts failing and again every
  `:repeat` while it keeps failing; `"PT0S"` reports every failure, as a
  unit's `OnFailure=` does.
- **The journal.** Every line is labelled `[store/pipeline]`, and under
  systemd it carries a priority, so `journalctl --user -u tik-backend -p
  warning` shows the pipelines' error output and the failure reports.
- **`:exec`** names the command that runs this tik. The native binary and
  babashka find themselves; set it when neither applies.

Two pipelines of stores with different `:org` may not sign with the same
key unless `:allow-shared-keys` names the pair of stores: one
organisation's delegate never signs for another's.

### Mailboxes

`bridge imap` reads one mailbox under `:imap`, or several under
`:mailboxes`. Every other top-level key is a default for each mailbox, so
adding a support address is one more list entry:

```clojure
;; imap.edn at the store's root
{:dkim {:require true :authserv-id "mx.example.org"}
 :default-actor "mail"
 :mailboxes
 [{:id "support-example-org" :process :support
   :imap {:host "imap.example.org" :user "support@example.org"
          :password {:command ["pass" "show" "mail/support@example.org"]}}}
  {:id "support-app" :process :app-support
   :imap {:host "imap.example.org" :user "support@app.example"
          :password {:credential "support-app-imap"}}}]}
```

Each mailbox has its own connection, its own backoff and its own
`:process`, and its lines are labelled with its `:id`. `bridge imap
--mailbox <id>` reads only that one. A `:mailboxes` list, and every
mailbox a backend watches, must:

- require DKIM at a pinned verifier, `:dkim {:require true :authserv-id
  …}`, the id your receiving MX stamps on `Authentication-Results`, so a
  forged From is never credited to anybody (see below);
- take its password from `{:command …}`, `{:credential …}` or `{:file
  …}`, never a literal and never `{:env …}`;
- under a backend, be signed by a key the store grants to every actor a
  mailbox records mail as: `:default-actor` and each `:from->actor` value.

#### The DKIM verdict

tik reads only the topmost `Authentication-Results` header of a message,
the first one in its header block. The MTA that delivered the message
prepended that header above every header the sender wrote, so a sender
cannot place a header above it. When the topmost header names a verifier
other than `:authserv-id`, the message is refused, even if a header
further down claims the trusted id and `dkim=pass`. The From domain must
then be the `header.d` of a `dkim=pass` in that header, or a subdomain
of it.

This holds only when your receiving MTA stamps every message it
delivers. A message it delivers without a header of its own keeps the
sender's headers on top, and a forged header there is read as the
verdict. Check how your provider treats mail between two of its own
customers before you rely on the gate.

`:authserv-id` takes one of:

- a string, compared with the whole id, ignoring case:
  `"mx.example.org"`;
- a pattern, `{:pattern "mx[0-9]+\\.example\\.org"}`, matched against
  the whole id, ignoring case;
- a list of both: `["mx.example.org" {:pattern "…"}]`.

A pattern always matches the whole id, so `mx[0-9]+\.example\.org`
never accepts `evil.mx1.example.org` or `mx1.example.org.attacker.test`.
It may use letters, digits and `-`, `\.` for a dot, `\d`, character
classes such as `[0-9]`, groups with alternatives such as `(mx|smtp)`,
and the quantifiers `?`, `*`, `+` and `{n,m}`. It must end in a literal
domain of at least two labels, such as `\.example\.org`. An unescaped
`.`, `^`, `$`, a top-level `|`, inline flags and any other construct are
refused when the config loads, and so is a pattern that matches an empty
id.

Some providers stamp the name of the receiving MX host rather than one
id for the domain. Migadu stamps `mx13.migadu.com` or a sibling host,
whichever received the message, so pin it with a pattern:

```clojure
{:dkim {:require true :authserv-id {:pattern "mx[0-9]+\\.migadu\\.com"}}}
```

Migadu was seen adding no `Authentication-Results` header to mail sent
from one Migadu mailbox to another, so such mail is refused. If Migadu
also keeps an `Authentication-Results` header that a Migadu-hosted
sender wrote into such a mail, that forged header is the topmost one;
whether it does has not been checked.

Every IMAP and POP3 connection checks that the server's certificate is
trusted and names the host, and plaintext (`:tls false`) is refused for
any host that is not written as a loopback address (`localhost`,
`127.x.x.x`, `::1`).

### Under systemd

One unit replaces the board's service and every bridge timer. The keys
of the delegates arrive as credentials; the store keys come from a
password manager here, and `{:credential …}` works for them as well:

```ini
# ~/.config/systemd/user/tik-backend.service
[Unit]
Description=tik: the boards, MCP, bridges, mailboxes and notifier of every local store
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=%h/.local/bin/tik backend --config %h/.config/tik/backend.edn
LoadCredential=github-work-key:%h/.config/tik/github-work_ed25519
LoadCredential=redmine-work-key:%h/.config/tik/redmine-work_ed25519
LoadCredential=mail-work-key:%h/.config/tik/mail-work_ed25519
LoadCredential=support-app-imap:%h/.config/tik/support-app-imap
# the MCP tokens live here; the backend refuses a token others can read
StateDirectory=tik-backend
StateDirectoryMode=0700
# tik exits with 143 on the SIGTERM of a stop, which is no failure.
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
# a stop also ends every run the backend started
KillMode=control-group

[Install]
WantedBy=default.target
```

The backend refuses `TIK_ROOT` and the bridges' token variables, so the
unit sets none of them. Disable the bridge timers and the old board
service when it starts, and point MCP clients at `/s/<store>/mcp`.

### Reporter pseudonyms and block lists

A mailbox can record who sent each report without writing the address
into the store, and refuse senders who only send garbage. Both keys go
on a mailbox or at the top level as a default for all of them:

```clojure
{:reporter {:key {:credential "reporter-key"}
            :table "~/.local/state/tik/reporters"
            :fact [:reporter :id]}
 :block {:addresses ["noise@example.net"]
         :domains ["spam.example"]
         :pseudonyms ["hmac-sha256:<64 hex digits>"]}}
```

With `:reporter`, a new ticket from a sender the DKIM gate authenticated
carries the fact `[:reporter :id]` (or the `:fact` you name) holding
`hmac-sha256:` and the hex HMAC-SHA256 of the lower-cased address under
your key. Without the key nobody can reverse it; with it, a guessed
address can be checked, so guard the key like a signing key. It comes
from `{:command …}`, `{:credential …}` or `{:file …}`, holds at least 32
bytes (`openssl rand -hex 32` prints 64), and is resolved once when the
bridge starts. The bridge refuses to start when `:reporter` lacks a key,
lacks `:dkim {:require true …}`, puts its table inside the store, or
names a process that does not declare the fact with a schema a
pseudonym satisfies:

```clojure
:process/facts {[:reporter :id] [:re "^hmac-sha256:[0-9a-f]{64}$"]}
```

A comment on an existing ticket records no pseudonym, and a `tik>` line
in a mail can never set the reporter fact.

The table maps each pseudonym to its address and the time it was first
seen. It is a directory only you can enter (0700), one file per
pseudonym (0600), and it stays on this machine, outside the store, so it
is never committed or pushed:

- `tik reporter show <pseudonym>` resolves one;
- `tik reporter forget <address|pseudonym>` deletes its rows for an
  erasure request, after which the pseudonym on the tickets resolves to
  nothing (backups of the table keep what they hold until they expire);
- `tik reporter stats [--format json]` counts tickets per pseudonym,
  showing the address where a row exists and `(forgotten)` where none
  does.

Each takes `--table <dir>`, or reads the table from `--config <imap.edn>`
(by default `imap.edn`, else `bridge.edn`, at the store's root) with
`--mailbox <id>` when the config names several.

`:block` refuses a sender after the DKIM gate and before anything is
written; the poll logs the rule that matched, never the address or the
body, and goes on with the next message. A domain matches itself and
every subdomain on a label boundary, so `spam.example` also blocks
`mail.spam.example` and not `nospam.example`. Listing `:pseudonyms`
keeps plaintext out of the config and needs `:reporter`, whose key
computes a sender's pseudonym; a block by pseudonym still holds after
the sender's row was forgotten.

## Hints

```sh
tik notify --config ~/.config/tik/notify.edn          # the notifier
tik notify watch --store work --where "stage=:asked"  # one JSON line per hint
```

See [Claude Code](/integrations/claude-code/) for the notifier's config
and what a hint holds.
