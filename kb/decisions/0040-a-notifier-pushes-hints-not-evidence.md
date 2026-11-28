---
type: decision
status: proposed
date: 2026-11-27
title: A local notifier pushes hints of verified stage changes to subscribers on a private Unix socket, and never evidence
supersedes: null
---

# ADR 0040: A notifier pushes hints, not evidence

## Decision

Proposed on 2026-11-27. Not accepted. It is implemented as
`tik notify`, under ticket 19030002 of the metio store. Each choice
below names the alternatives and why it was taken, ranked by security
first, then extensibility and re-use, then performance.

**`tik notify` watches one or more local stores. It re-derives and
verifies every ticket whose log changed, and pushes a hint of each
change of its current stages to the local subscribers that asked for
that ticket.** A hint names the store, the ticket, its title, the
stages it left and entered, and the actor of the event that moved it.
It carries nothing else. The receiver treats it as a reason to look,
and re-derives with `tik status` before acting.

1. **Hints only.** The payload is exactly the keys `store`, `ticket`,
   `title`, `from`, `to` and `actor` (`tik.notify/hint-keys`). It holds
   no fact values and no instructions. A fact can hold a customer's
   data or text written to steer an agent, and a hint goes into a
   model's context. The title and the actor are folded to one line of at
   most 160 and 64 characters, with control, format and bidi-override
   characters dropped. That keeps a title from forging a second JSON
   line or reordering what a terminal shows. The alternative, a payload
   carrying the facts that moved the ticket, was declined: it would turn
   the notifier into a second read path that bypasses whatever a reader
   may see. It would also make the hint look like evidence, which the
   three-questions law (PLAN §12) reserves for derivation.

2. **Re-derived and verified.** The notifier never trusts an event
   because it arrived. For every ticket whose fingerprint moved, it
   derives the ticket from the store with the same functions `tik ls`
   uses, then requires three things:
   - the store has an actors registry;
   - every event the cursor had not seen carries a signature;
   - the whole verify ladder over the ticket passes: L0 integrity, the
     DAG, every signature present, and the signature policy.

   A ticket that fails sends nothing and logs why. Its cursor entry
   keeps the last verified stages, so the next verified change diffs
   against stages that were verified. Taking stage changes from a
   stream of incoming events was declined, because it would believe an
   event before `verify` does.

3. **Local only.**
   - **Socket.** Subscribers connect to a Unix socket in a directory
     the notifier creates `rwx------`, or refuses when it exists with
     another owner or wider permissions. The socket itself is
     `rw-------`.
   - **Peer check.** Every accepted connection's kernel peer credentials
     (`SO_PEERCRED`) must name the notifier's own user. A peer the
     platform cannot name is refused.
   - **No network.** There is no network listener. Delivery to another
     host stays with the effects bridge (ADR 0019) or a future
     notifier that runs inside the shared server (ADR 0038).
   - **Alternatives declined.** A loopback HTTP endpoint would reach
     every local user and every web page that can rebind DNS. A
     desktop bus would tie the notifier to one desktop.

4. **Store-aware.** A subscription names one store. It is admitted
   only when that store's root directory belongs to the subscriber's
   user, the same test the operating system applies to reading the
   store itself. A notifier configured with several stores
   (`notify.edn` `:stores`) never tells a subscriber of one store about
   another. Today that boundary is the operating-system user. Once
   connections carry the signed actor assertion of ADR 0038 point 4,
   the admission check is the place to require that the actor may read
   the store. That is how one organisation's session stays out of
   another organisation's tickets on a shared machine.

5. **Coalesced and rate-limited.** Diffs wait in a coalescer keyed by
   store and ticket for a window (5 s by default). A ticket pending in
   the window keeps the stages it started from and takes the newest
   stages, title and actor, so a burst of events gives one hint per
   ticket per window. A ticket that came back to where it started gives
   none. Each subscriber has a token bucket (10 hints, one more every
   6 s by default). Past it, hints are dropped and logged, never
   queued without bound.

6. **Idempotent across restarts.** A cursor records, per store and
   ticket, the fingerprint, the events and the stages last acted on. It
   is written atomically under `$XDG_STATE_HOME/tik/notify/` whenever it
   moves, and a restart diffs against it. The cursor is disposable
   porcelain (ADR 0013). Losing it costs one silent baseline. It never
   costs a replay of old hints, because a first start records the
   stores without reporting them. Delivery is therefore at most once.
   That is the right failure for a hint, where ADR 0019's effects choose
   at least once for messages that must arrive.

7. **Pluggable delivery.** A sink is one protocol method,
   `deliver!`, returning whether it is still there. Three sinks ship:
   - a socket subscriber;
   - a configured command, run without a shell with `{ticket}`,
     `{title}`, `{line}` and the other placeholders substituted as
     whole arguments. `notify-send` gives a desktop notification this
     way.
   - a fake one, in the tests.

   Two subscribers ship as verbs:
   - `tik notify watch` prints one JSON line per hint.
   - `tik notify channel` is a stdio MCP server that forwards hints as
     Claude Code channel events.

8. **How a hint reaches a Claude Code session.** This was checked
   against the MCP 2026-07-28 specification, the Claude Code
   documentation and the strings of Claude Code 2.1.331 on 2026-11-27.
   In order of preference:
   - **The Monitor tool running `tik notify watch`.** Claude Code
     documents Monitor as generally available. It "runs a command in
     the background and feeds each output line back to Claude", under
     the session's Bash permission rules. It needs nothing beyond the
     notifier.
   - **A channel.** Claude Code registers `tik notify channel` as a
     channel when the session starts with
     `--dangerously-load-development-channels server:<name>`. Channels
     are a research preview, work over stdio only, and are not
     registered on revision 2026-07-28. The bridge therefore speaks only
     the legacy `initialize` handshake and answers `server/discover`
     with -32601. It reads each stdin line within the 1 MiB limit of
     `tik mcp`, holding at most that much of a line: a longer line, or
     one briko.json refuses, answers -32700 with a null id and the rule
     in `data.rule`, and the next line is read.
   - **MCP resource subscriptions: blocked.** Under 2026-07-28 a client
     must request `resourceSubscriptions` on `subscriptions/listen`
     before a server may send `notifications/resources/updated`.
     Claude Code 2.1.331 requests only the three `list_changed` kinds,
     and in the legacy era registers no handler for resource updates.
   - **The `cc-socks` inbox socket: declined.** Its message format is
     undocumented. The documented auth line serves a session's own
     child processes.

9. **One backend, later.** The verb runs as its own process today. It
   is shaped to become a continuous `tik backend` pipeline
   (`{:id :notify :watch true :run ["notify" "--config" "…"]}`), and the
   one-backend-many-stores plan reuses the multi-store loop directly.
   The CLI's store root became bindable for that (`tik.cli-core/*root*`),
   so one process derives each store in turn under its own root.

## Context

Agents and the maintainer learned about ticket changes by polling: an
hourly request check, plus `SendMessage` pings between the metio and
infra.run sessions written by hand. The kajero idea 25d4b7d4 proposed a
local notifier that finds the right session and pushes the change
instead, under the law that the notifier must never become a source of
truth.

The effects bridge (ADR 0019) already observes derivation and fires
outbound sinks. It answers a different question, at a different
latency:

- **What it reports.** Effects report every stage a ticket reaches,
  once per (ticket, stage, sink). A hint reports where the ticket is
  now, relative to where it was.
- **When it runs.** Effects run in batches, from a pipeline. A hint
  runs live.
- **Who it reaches.** Effects deliver at least once to sinks configured
  in advance. Hints go at most once to subscribers that come and go
  with their sessions.

Folding the notifier into effects would weaken both: effects would
gain subscriptions and a socket, and hints would gain a ledger keyed on
reached stages that cannot express "moved back".

## Consequences

- A session can replace a polling loop with `tik notify watch` under
  Monitor, plus a slow fallback poll for anything missed while it was
  not subscribed.
- Time alone can move a ticket, through `:elapsed-since` guards.
  Without an event there is no fingerprint change, so the notifier does
  not report such a move. The fallback poll covers it.
- The fingerprint reads names, not bytes. A signature replaced in place
  under the same name is judged at the next real change, and `tik
  verify` remains the audit.
- A write appends its event before its signature sidecar. A poll that
  falls between the two logs a refusal for the unsigned event; the
  sidecar then changes the fingerprint, and the next poll verifies the
  ticket and sends the hint.
- Several notifiers on one estate do not coordinate. Each keeps its own
  cursor and socket, which ADR 0021 permits for delivery. A user runs
  one per machine.

## Alternatives considered

- **Push stage changes through MCP resource subscriptions on the
  existing tik MCP server.** Blocked today; see point 8.
- **Write into Claude Code's per-session sockets.** Declined; see point
  8.
- **inotify instead of a poll.** The poll lists each ticket's event
  directory every 2 s, which is cheap at the store sizes in use and
  works the same on the SQLite store. A watcher can replace the poll
  without touching anything above `fingerprints`.
- **Deliver to every subscriber of a store without selectors.** Each
  subscription carries a `tik ls --where` selector instead, evaluated on
  the ticket's fresh derivation, so a session hears only about the
  tickets it asked for.
