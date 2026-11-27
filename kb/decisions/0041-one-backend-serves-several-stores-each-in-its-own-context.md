---
type: decision
status: proposed
date: 2026-11-27
title: One tik backend serves several stores, each in its own context with its own key, actor, actors file, board and MCP token
supersedes: null
---

# ADR 0041: One backend serves several stores, each in its own context

## Decision

Proposed on 2026-11-27. Not accepted. It is implemented in `tik
backend` under ticket 03ec81f1 of the metio store. Each choice below names
the alternatives and why it was taken, ranked by security first, then
extensibility and re-use, then performance.

**A `tik backend` whose config has `:stores` serves several stores from
one process. Every request, pipeline run and subprocess for a store runs
inside that store's context, and the context alone decides the root, the
signing key and the actor.** A single-store invocation (`pipelines.edn`
without `:stores`, `tik serve`, `tik mcp`, every other verb) is
unchanged: it reads `TIK_ROOT`, `TIK_KEY`, `TIK_PQ_KEY` and `TIK_ACTOR`
from the environment as before.

```clojure
{:stores {:metio {:root "~/git/github.com/metio/.tik" :org "metio" :actor "claude"
                  :key {:command ["pasejo" "secret" "show" "tik/metio/claude"]}
                  :mcp-token-file "~/.config/tik/mcp-token-metio"}
          :personal {:root "~/git/.tik" :org "default" :actor "claude"
                     :key {:credential "tik-default-key"}}}
 :serve {:host "127.0.0.1" :port 7777}
 :notify {:poll "PT2S"}
 :pipelines [{:id :github-metio :store :metio :every "PT10M"
              :config "github.edn" :run ["bridge" "github"]}]}
```

1. **A thread-bound store context.** `tik.context/*store*` holds `{:name
   :root :key :actor}` and is bound around everything done for one store.
   `tik.cli-core/root`, `signing-key`, `pq-signing-key`, `tik.args/actor`
   and the MCP server's actor and signer read it through
   `tik.context/env` before the environment, and inside a context they
   never read the environment at all. The binding is per thread, so two
   requests for two stores running at once each see their own; futures
   carry it along. `tik.cli-core/*root*`, which the notifier binds, was
   not enough on its own, because it names only the root, and the key
   and actor would still have come from a process-wide variable.
   Rejected: one process per store behind a proxy (the maintainer asked
   for one process, and it would only move the routing); passing the
   store as an argument through every verb (hundreds of call sites, each
   a place to forget it).

2. **No guessing in a multi-store process.** Once the stores have opened,
   `tik.context/strict!` makes a read of a store variable outside any
   context throw, so a code path that forgot to bind a store fails
   instead of discovering a store from the working directory or signing
   with an inherited key. The backend also refuses to start while any of
   `TIK_ROOT`, `TIK_KEY`, `TIK_PQ_KEY` or `TIK_ACTOR` is set in its
   environment. Inside a context, `--key` naming any file but the store's
   own is refused, and a multi-store pipeline may not pass `--key` or
   `--actor` at all.

3. **Keys resolved once, at start, into a private runtime directory.** A
   store's `:key` is a path, `{:file …}`, `{:credential …}` (systemd
   `LoadCredential=`) or `{:command …}`, whose whole output is written to
   a `rw-------` file in a fresh `rwx------` directory below
   `XDG_RUNTIME_DIR`, removed on exit (`tik.secret/materialize-file`).
   `{:env …}` is refused, because a key in a variable is inherited by
   every child. No message carries a key: a failing command is reported
   with its stderr, a file that is not a key as "not an OpenSSH private
   key". Holding the key only in memory was declined because signing
   shells out to `ssh-keygen -Y sign -f`, which needs a file, and the
   in-process reader verifies but does not sign.

4. **Each key is proven against its own store before anything runs.**
   The backend signs a challenge with each store's key in the event
   namespace and verifies it against that store's `actors` file for that
   store's actor, exactly as an event would be verified. A key the store
   does not grant to its actor, or a store without an `actors` file,
   refuses the backend. Two stores whose keys are the same but whose
   `:org` differs are refused unless `:allow-shared-keys` names the pair,
   so one organisation's key never signs another's store by accident.
   The same holds for a pipeline's `:as` and its optional own `:key`: the
   signer must be granted to `:as` in the pipeline's store.

5. **Routing: one path per store, and a token that opens one store.**
   The board answers at `/s/<store>/`, `/s/<store>/tickets.edn` and
   `/s/<store>/explain/<id>.edn`; MCP at `/s/<store>/mcp`. Each store has
   its own token file, refused when shorter than 32 characters or
   readable by group or others, and two stores may not share one. The
   request is checked against the token of the store its path names, so
   a token sent to another store's path is a 401. Both were taken, rather
   than either alone: a token that maps to a store but shares one
   endpoint would make the store depend on which token arrived, and paths
   with one shared token would let anyone holding it reach every store.
   `/` and `/mcp` answer 404. The rebinding guard (421 for a foreign
   Host) runs before routing.

6. **Fail closed at start, isolate at run time.** A config problem, a
   store that will not open, a key that does not prove, or a token that
   does not pass refuses the whole backend, and every problem is listed
   at once. Starting the healthy stores and skipping the broken one was
   declined: the service would show as running while one store's
   bridges never sweep and its MCP answers 404, and an agent or operator
   reading "active" would take that store's silence for an empty inbox.
   A start-time failure is deterministic and fixed by editing the
   config, so refusing costs one restart. Once running, failures stay
   isolated as before: a failing pipeline logs and the others carry on,
   and a request that fails answers 500 for that request only.

7. **The notifier is reused.** `:notify` takes the settings of a
   `notify.edn` without `:stores`, and the backend runs
   `tik.notifier/notify-forever!` over its own stores' roots
   (`config-from`). The notifier binds `*root*` per store as it did and
   signs nothing, so it runs outside every store context.

8. **Per-root caches.** The derived-row cache (`tik.cli-core/cache-state`)
   is keyed by store root. It used to be one process-wide map, so a
   process deriving two stores in turn, which the notifier already did,
   flushed one store's rows, titles included, into the other store's
   `.derived-cache.json`.

## Consequences

- Probes and rollout scopes started for a store get that store's
  `TIK_ROOT`, `TIK_KEY` and `TIK_ACTOR` (`tik.context/child-env`), never
  the process's.
- MCP calls on all stores still run one at a time, behind the lock the
  single-store server already had. A slow call on one store delays the
  others.
- A post-quantum key (`TIK_PQ_KEY`) has no per-store setting yet, so a
  store whose `signatures.edn` requires ML-DSA refuses writes from a
  multi-store backend.
- The tests pin each isolation: a concurrent mix of CLI and MCP writes to
  two stores, every resulting signature verified against its own store's
  actors and checked for the other store's fingerprint; a config matrix;
  each start-time refusal; and `dev/native-smoke.sh` serving two stores
  with different keys from one native binary, with a cross-store write
  refused.
