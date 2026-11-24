---
type: decision
status: proposed
date: 2026-11-24
title: One shared tik server per organisation holds no signing keys; agents sign, prove who they are per connection, and receive work through roles
supersedes: null
---

# ADR 0038: One shared server holds no keys

## Decision

Proposed on 2026-11-24. The direction of points 1 to 7 follows choices
the maintainer made that day, and the maintainer answered the questions
under "Resolved questions" on 2026-11-25. The text stays proposed until
the maintainer accepts it. Nothing here is implemented.

**An organisation runs one shared tik server, every employee's agent
connects to it, and the server holds no signing key.** Agents sign their
own events with keys the server never sees, prove which actor they are
with a short-lived signed assertion on each connection, and find their
work through `tik next` filtered by the roles the store's register gives
them.

1. **One server per organisation.** An entire company uses a single tik
   server (`tik serve` with its `/mcp` endpoint, or replicas of it under
   ADR 0021), and each employee connects their agent to it to read,
   receive and work on tickets. Replicas share nothing beyond the event
   set, so "one server" names one store and one trust base, however many
   processes serve it.

2. **The server holds no signing keys.** A server that signs on behalf of
   its callers can sign as any actor whose key it holds, so whoever
   compromises it, or operates it, can forge events for every one of
   them, and a single compromise forges the store. Signatures would then
   prove only that the server agreed, which is the custody problem
   ADR 0010 separates authorship from authorization to avoid. Each agent
   keeps its own key in `ssh-agent` or on a security key, and no key
   bytes leave the agent's machine.

3. **Writes are signed by the client and verified by the server.** The
   server composes the event for an intent and the client signs it:
   - `prepare` takes an intent (the arguments `tik_assert` and
     `tik_attest` take today), refuses it unless the frontier admits it
     for the connection's actor (`agent-admissible`,
     `cli/tik/agent.clj:19`), and returns the canonical stored bytes of
     the event, with `:event/actor` set to that actor and the ticket's
     current heads as parents.
   - The client signs those bytes with `ssh-keygen -Y sign -n tik-event`,
     the call `tik.sign` already makes (`src/tik/sign.clj:108`).
     OpenSSH's `-Y sign` accepts a public key file whose private half is
     held by `ssh-agent` (ssh-keygen(1), OpenSSH 10.2p1).
   - `submit` takes the bytes and the detached signature, recomputes the
     event id from the bytes, verifies the signature against `actors` or
     a binding that holds (ADR 0023, ADR 0026) as the event's actor,
     checks that actor equals the connection's actor and that the
     frontier still admits the event, and only then appends the event and
     its sidecar.

   Events stay content-addressed and their parents are the heads the
   signer saw. Two agents preparing against the same heads produce two
   events that both append, leaving two heads that union like any
   concurrent writes; contention between their facts resolves by
   derivation (`:conflicted`). Nothing in either path needs a lock or a
   designated replica (ADR 0021). A client that holds its own replica of
   the store may build and sign the event itself and call `submit`
   directly, since `submit` checks the same things whoever composed the
   bytes.

4. **Reads that depend on identity answer only for an actor proven by a
   signature.** The inbox (`tik next --actor <self>`), `tik_explain` and
   `tik_actions` answer for the actor of the connection's assertion,
   never for an actor named in a tool argument. The assertion:
   - is an SSHSIG signature in the namespace `tik-mcp-auth`, which the
     `namespaces="tik-*"` pattern of an `actors` line already admits and
     which no event, definition or witness signature uses;
   - signs the canonical bytes (`tik.canonical`) of a map with
     `:mcp-auth/actor`, `:mcp-auth/origin` (the server's configured
     origin, so an assertion made for one server is refused by any
     other, kajero included), `:mcp-auth/nonce` (random, at least 128
     bits), `:mcp-auth/issued-at` and `:mcp-auth/not-after`;
   - is valid for at most 15 minutes after `:mcp-auth/issued-at`, with a
     tolerance of 60 seconds for clock skew. Within that window it is a
     bearer credential reused on every request of the connection, so the
     replay window equals its validity; the origin binding and the short
     expiry bound a replay. The nonce makes each assertion distinct in
     logs and adds no replay protection of its own;
   - is verified against `actors` or a binding that holds, the same way
     an event signature is, by every replica on its own;
   - is porcelain authentication. It is never stored in the log, is not
     an event, and credits nothing: whether a fact counts still follows
     from the signature on the event and the guards (ADR 0011). Deciding
     who may connect and write at all is the store and transport policy
     ADR 0011 places outside derivation.

   A store that wants freshness proven by the server can add a challenge
   endpoint whose nonce the assertion must carry. It is left out of the
   first version because the helper would make one more request per
   connection and replicas would have to agree on what they issued.

5. **Work is routed by roles.** Definitions name
   functional roles (for example `:change-implementer`) in
   `[:signed-by :<role> path]` guards on the facts an agent provides. The
   store's role register maps each role to agent actors: one actor routes
   exactly, several form a pool. `tik next --actor X` already shows X only
   the actions the roles it holds admit, and the gated write surface uses
   the same predicate, so an agent's inbox and what it may write cannot
   disagree. There is no per-ticket assignment mechanism.

6. **The register gains an authority check before agents get their own
   identities.** `roles.edn` is an unsigned file, and `tik roles add`
   checks no authority, so an agent able to write the store could add
   itself to `:maintainer`, after which every in-flight ticket would
   accept its review verdicts. Closing this needs its own ADR, because it
   changes what `tik verify` treats as trust input. The direction
   proposed here:
   - register edits become signed: either events on a well-known register
     log, like the identity registry of PLAN §9, or a `roles.edn` with a
     detached signature that `tik verify` checks against a set of
     register authorities named in the store;
   - a role named by a guard on a maintainer fact refuses agent actors,
     which `tik verify` and `tik roles add` both enforce, so even a signed
     edit cannot hand an agent the maintainer's authority.

   Until that ADR lands, the register stays maintainer-edited, and the
   metio instructions say so.

7. **Claude Code integrates through what it already offers, with no new
   resident process.**
   - Connection identity comes from the server entry's `headersHelper`, a
     command Claude Code runs on each connection. A small script signs a
     fresh assertion through `ssh-agent` and prints it as the
     `Authorization` header. When an assertion expires, the server
     answers `401`, Claude Code re-runs the helper, reconnects and retries
     the call once.
   - Writes go through one Bash call to the local tik CLI in a remote
     mode that calls `prepare`, signs, and calls `submit`, so event bytes
     never pass through the model.
   - The inbox available now is a `Monitor` loop polling
     `tik next --actor <self> --format json` against the server and
     printing one line per new item. The target is a server-sent-events
     `/inbox` on `tik serve`, which the same Monitor subscribes to, and a
     dispatcher that starts one `claude -p` worker per ticket.

   Subagents share their parent's connection, so they act as the
   session's actor.

8. **kajero adopts the same mechanism**: client-signed writes and a
   signed connection assertion in its own namespace. That is kajero's
   decision and gets its own ADR there.

## Context

### The servers today

The tik MCP server writes as one actor. `tik serve --mcp-token-file`
answers `/mcp` behind one bearer token (`http-response`,
`cli/tik/mcp.clj:178`), the actor is the process environment variable
`TIK_ACTOR` (`cli/tik/mcp.clj:78`), and the key is `TIK_KEY`
(`signing-key`, `cli/tik/cli_core.clj:359`). Calls run one at a time
(`locking handle`, `cli/tik/mcp.clj:204`). The tools are `tik_board`,
`tik_explain`, `tik_actions`, `tik_new`, `tik_assert` and `tik_attest`;
none of them is the inbox. Everything else `tik serve` answers is
read-only (`cli/tik/serve.clj`). kajero's MCP writer is likewise one
`KAJERO_MCP_ACTOR`/`KAJERO_MCP_KEY` pair (kajero `src/kajero/web.clj`,
`main` a6333fb), and kajero signs with `tik.sign` in the namespace
`kajero`. Every Claude session and subagent on the maintainer's machine
therefore signs as one actor, `claude`, whether it writes through MCP or
through the local CLI.

A shared server for a whole organisation cannot keep that shape. One
token and one key make every employee's agent the same actor, and
giving the server one key per agent makes it custodian of all of them.

### Identity on the ladder

Event signatures are SSHSIG sidecars checked against `actors` (PLAN §9,
rung 1), or against keys bound through issuer-signed tokens (rung 2,
ADR 0023, ADR 0026, ADR 0035). Both verify offline from what travels
with the store. PLAN §19 rejects mTLS as a source of trust for events
because a connection leaves nothing in the log; the connection assertion
of point 4 keeps to that verdict, since it selects whose reads are
answered and who may write at all, and never makes an event count.

`tik import` is the existing ingest for foreign events. It merges by
content address and checks on the way in only that each event hashes to
its name, leaving signatures to a later `tik verify` (`cmd-import`,
`cli/tik/audit.clj:139`). A shared server must not append an unverified
event on request, so `submit` reuses import's append-and-sidecar half
and adds the signature and frontier checks before it. Composing an
event needs the ticket's heads and pinned definition, and the frontier
gate reads the store (`agent-admissible`); an employee's agent holds no
replica of the organisation's store, which is why the server composes
and the client signs.

### Routing

`cmd-next` (`cli/tik/inspect.clj:218`) derives each ticket with
`ticket-ctx` (`cli/tik/cli_core.clj:478`), whose roles are
`resolve-roles` over the register (`src/tik/process.cljc:134`), and the
inbox keeps an action only when `admissible?` (`src/tik/next.cljc:162`)
allows the actor; `agent-admissible` projects the same predicate for
writes. The register (`roles.edn`, `cli/tik/cli_core.clj:268`) overrides
a definition role by role, takes effect on in-flight tickets, and
replaces a role wholly once it names it, so the first
`tik roles add maintainer X` drops the definition's members unless they
are added too (`cmd-role`, `cli/tik/admin.clj:148`). A role in the
register gates nothing until a pinned definition's guard names it, and
a new guard reaches tickets pinned to an older definition only through
`tik reprocess`. In the metio store on 2026-11-24, `change` guards only
maintainer facts and there is no `roles.edn`, so every agent's inbox
would still list the same actions.

PLAN §19 defers time-aware role validity and delegation with scope and
`:valid-until`, including human-to-agent delegation for MCP; it records
no verdict on who may edit the register.

### What Claude Code offers

From the Claude Code documentation fetched on 2026-11-24, with Claude
Code 2.1.331 installed on the maintainer's machine:

- `headersHelper` (<https://code.claude.com/docs/en/mcp>, "Use dynamic
  headers for custom authentication") runs "fresh on each connection, at
  session start and on reconnect", does not cache its output, and has a
  10-second timeout. When a tool call returns `401` or `403`, Claude Code
  re-runs the helper, reconnects with the fresh headers and retries the
  call once; a rejection while connecting is reported as a failed
  connection, to be retried from `/mcp`. Helpers declared by a project or
  plugin run without credential-named environment variables, and project
  and local-scope helpers run only once the folder is trusted.
- Subagents (<https://code.claude.com/docs/en/sub-agents>): "String
  references share the parent session's connection"; only an inline
  `mcpServers` entry in an agent file gets a connection of its own, per
  agent type, while the subagent runs.
- Channels (<https://code.claude.com/docs/en/channels-reference>), the
  only mechanism that pushes into an idle session, are a research
  preview, stdio only, need `--dangerously-load-development-channels` for
  a custom server, and are ignored under `claude -p`. A standard MCP
  server pushes nothing into a session.
- `Monitor` turns each output line of a long-running command into a
  notification in the session and expires after at most 30 minutes, so a
  long wait re-arms it.

## Consequences

- **New MCP tools**, named in the style of the existing ones: `tik_next`
  (the inbox for the connection's actor, no actor argument),
  `tik_prepare` and `tik_submit`. `tik_explain` and `tik_actions` answer
  for the connection's actor. The HTTP transport accepts the signed
  assertion in place of the shared bearer token; `TIK_ACTOR` and
  `TIK_KEY` remain for the stdio server, which runs as its caller.
- **The tik CLI gains a remote mode and a public-key signing path.**
  The remote mode runs `prepare`, sign, `submit` against a server URL.
  `tik.sign/pubkey` derives the public key with `ssh-keygen -y` from a
  private key file (`src/tik/sign.clj:55`), so signing with a key held
  by `ssh-agent` needs the CLI to accept a public key file instead.
- **`~/.local/scripts/tik-claude` changes role.** Today it reads the
  organisation's key from pasejo into a private runtime directory and
  runs `tik` with `TIK_ACTOR` and `TIK_KEY` against a local store. Under
  this ADR it loads the agent's key into `ssh-agent`, or names a
  security key, serves as the `headersHelper` that signs the connection
  assertion, and invokes the CLI's remote mode for writes.
- **Post-quantum stores need more.** Under ADR 0036 a store with
  `signatures.edn` requires an ML-DSA-65 sidecar per event, which
  `ssh-agent` cannot produce; such a store needs the client to hold the
  ML-DSA half as well, outside the agent.
- **A security key costs a touch per assertion.** With a touch-required
  key each helper run, at most every 15 minutes per connection, asks for
  a touch within the helper's 10-second timeout, which is workable for
  an interactive session and not for unattended workers.
- **Serialised calls become the bottleneck** once many agents poll.
  `tik serve` runs one MCP call at a time, so the `/inbox` stream should
  derive once per store change and fan out to subscribers instead of
  answering N polls. The stream is a lens and keeps nothing
  authoritative (ADR 0013, ADR 0019); duplicates across replicas are
  harmless to an inbox.

The order in which this would land:

1. The register-authority ADR and its implementation (point 6).
2. The signed connection assertion on `/mcp`, `tik_next`, and
   `tik_explain`/`tik_actions` for the connection's actor.
3. `tik_prepare`/`tik_submit`, the CLI's remote mode and public-key
   signing; then the shared token and the server's key are removed.
4. Functional roles and `[:signed-by]` guards in the metio processes,
   through the version-bump gate with a negative test per signature,
   and the register filled by the maintainer.
5. The `/inbox` stream and a dispatcher of `claude -p` workers.

## Alternatives considered

- **A custodial server holding one key per agent.** The smallest change,
  since `tik serve` already passes `--actor` per call and could pass
  `--key` the same way, and signatures would still verify offline. It is
  declined because the server can then forge any actor whose key it
  holds, and one compromise forges the store.
- **A small local stdio MCP server per employee**, holding or reaching
  the key and forwarding to the shared server. It would also be the only
  place a channel could live. It is declined because it is one more
  process per employee to install, run and update, for no capability the
  `headersHelper` assertion and the CLI's remote mode lack.
- **Delegated ephemeral keys**, a human root authorising short-lived
  per-agent keys. Deferred to its own ADR. ADR 0037's delegation does not
  fit: it credits the delegator and never the agent, caps the window at
  five minutes, scopes to heads the reviewer was shown, allows no chains,
  and the metio store requires a touch for it. A local rung-2 issuer
  binding ephemeral keys (ADR 0023) is the other route, at the cost of a
  binding event per session and an issuer key whose custody equals a root
  key's.
- **An actor named in a tool argument for reads.** Reading another
  actor's inbox discloses little the board does not show. It is declined
  because a dispatcher and its workers act on what the inbox returns, and
  an inbox that answers for whatever actor a caller names routes work by
  the caller's claim; the identity of an identity-dependent read has to
  come from a signature, as the identity of a write does.

## Resolved questions

The maintainer answered these on 2026-11-25.

1. **Worker identities are stable slots.** Dispatched workers act as
   `claude-worker-1`, `claude-worker-2` and so on, each an ordinary key in
   `actors` (rung 1). A slot outlives the session that used it, so its
   inbox and history stay readable, and role routing needs nothing
   beyond the register. Per-session identities wait for the delegation
   ADR.
2. **Starting a batch of workers costs one security-key touch.** The
   touch signs the list of slots and an expiry, the shape ADR 0037 gives
   a review batch, so a batch does not cost one touch per worker every
   15 minutes.
3. **infra.run's agents keep their own identities on their own server.**
   infra.run is a separate organisation with its own store, keys and
   server, and work between the two organisations keeps going through the
   `request` process with the requesting organisation's actor
   (`metio-claude`, `infra.run-claude`). Support tickets from infra.run's
   customers belong in infra.run's store, served by infra.run's server.
4. **Interactive sessions keep the plain `claude` identity.** A session
   the maintainer watches acts like the maintainer driving a tool, and its
   writes already pass the gated frontier. Per-agent names are for
   dispatched workers, which act unattended.
5. **The first functional roles are few, and widen when a real split
   appears.** `implementer` for `change`, `skilled-change` and
   `package-addition`; `infra-operator` for the plan half of
   `infra-change`; `request-handler` for `request`; and `support` once a
   support process exists. Each role starts holding today's actor, so
   nothing reroutes on the day the roles land.
6. **Only the maintainer edits the register at first.** Every register
   edit is signed by the maintainer, and a role that gates a maintainer
   fact refuses agent actors (point 6). A dispatcher limited to moving
   workers between agent-only roles can follow once the register ADR is
   implemented and tested.
7. **Only new tickets are routed by the new guards.** In-flight tickets
   keep the definition they pinned, because `tik reprocess` re-judges
   evidence signed under the old rules. A long-lived ticket, such as an
   open request or a vulnerability, moves one at a time with
   `tik reprocess`, dry run first, when routing it matters.
