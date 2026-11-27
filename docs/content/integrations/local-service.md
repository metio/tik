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

## Hints

```sh
tik notify --config ~/.config/tik/notify.edn          # the notifier
tik notify watch --store work --where "stage=:asked"  # one JSON line per hint
```

See [Claude Code](/integrations/claude-code/) for the notifier's config
and what a hint holds.
