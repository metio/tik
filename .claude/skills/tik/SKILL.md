---
name: tik
description: Drive the tik CLI and author sound process definitions. Use when working in a repository that is a tik store (has a tickets/ or .tik/ directory, an actors file, or processes/*.edn), when the user mentions tik, tickets, processes, stages, or asks to record work / check what is next / design a workflow-as-evidence. Covers the CLI verbs and the one design law that separates good process definitions from task lists.
---

# tik

tik is **a process system, not a ticket system**. A ticket is an append-only
log of signed, content-addressed events; its stage is **never stored** — it is
derived on read by a pure function of the events. The one law is **derived
beats declared**: if something can be derived, storing it as authoritative
state is a bug. The kernel answers *"what follows from these signed facts?"* —
never *"what should happen next?"*

Internalize that law before doing anything else here: you record **evidence**;
stages **derive themselves**. You never set a status.

## Is this a tik store?

A directory is a store if it (or an ancestor) holds `tickets/`, a `tik.db`,
or `.tik/`. Commands find it git-style: `TIK_ROOT` env wins, else the
nearest ancestor with such a marker, else the current directory. Signing
needs `TIK_KEY` (a path to an ed25519 private key) and `TIK_ACTOR` (your
actor name, registered in the store's `actors` file). Confirm with `tik ls`.

A store's **backend** is derived from its own shape, never a global
setting: a `tickets/` tree is the file/git store (sha256sum-auditable, the
signed interchange format); a `tik.db` is the SQLite store (single-file
ops). Choose at creation — `tik init` (file) or `tik init --sqlite` — and
switch in place with `tik store migrate --to sqlite|file`, which is
lossless: events and their detached signatures/witnesses both travel
(each backend holds sidecars). The SQLite driver is embedded in the
binary, so no external `sqlite3` is needed. Working in one store never
reroutes another.

## The daily workflow (driving an existing store)

Run these; do not hand-edit `tickets/` — events are content-addressed and
`tik verify` will catch tampering.

- `tik ls [--all] [--long] [--where SELECTOR]` — the board of tickets with
  their **derived** stages (open by default; `--all` includes settled). Start
  here. A SELECTOR is space-separated terms, all ANDed, each optionally `not`:
  `stage=:blocked`, `fact:severity=:high`, `actor=seb`, `disputed`, `~text`,
  e.g. `tik ls --where 'stage=:blocked and not disputed'`, or
  `tik ls --all --where 'fact:repo=:beta'` to select across the whole store.
  The same grammar drives `tik search <words>` (sugar for `--all --where '~w
  …'`). `tik dupes` reports near-title lookalikes. Any lens takes `--edn` /
  `--format json` for machine output.
- `tik next [--actor A]` — your inbox: what you can act on now.
- `tik new <process> --title "…"` — mint a ticket against a process
  (pins the process definition's hash).
- `tik set <id> k=v [k=v …]` — **record facts**; the stage re-derives
  itself. Dotted keys nest (`parked.reason="…"`). This is the verb you
  use most. You do not advance a stage — you make a fact true and the
  stage follows. Links are facts too: `tik set <id> link.depends-on=<other-id>`
  makes this ticket depend on another — `next` then holds it back as
  blocked until that upstream ticket is settled (`status` names the blocker).
- `tik explain <id> [--actor A]` — what evidence is missing to advance,
  and who can act. This is the product surface; read it before asking a
  human anything.
- `tik status <id>` — derived stage, facts, links, what's next.
- `tik retract <id> <k>` / `tik dispute <id> <k>` — withdraw or reject a
  fact; the stage **regresses by derivation** (no manual rollback).
- `tik whatif <id> k=v +PT48H retract:k` — counterfactual stage diff,
  nothing written. Use to check what a fact *would* unlock before setting it.
- `tik plan [<file.html>]` — the dependency-link roadmap: ready / blocked /
  done / cyclic, the critical path, and each item's unlock impact — all
  derived, never stale. A `.html` argument writes a fancy self-contained page.
- `tik verify` — audit the whole store (hashes, signatures, re-derivation).
- `tik gc [--apply]` — remove archived process definitions no ticket pins
  (versions everything migrated away from). Dry-run by default; `verify`
  stays PASS, only historical `--at` degrades. Tidiness, not disk.

### Bringing the outside world in — the bridges

Everything external enters as a **signed event**, so ingestion lives in
bridges (porcelain that speaks a wire protocol and mints attestations; the
kernel never reaches out). Each records a bridge-signed attestation whose
trust flows through the bridge (ADR 0019), verifiable offline forever:

- `tik bridge email [--config bridge.edn] < message` — one RFC822 message
  on stdin (MTA-agnostic): the sender maps to an actor; the message
  associates to a ticket by (most reliable first) an `X-Tik-Ticket` header,
  the tik-shaped `Message-ID` a reply threads on (`In-Reply-To`/`References`,
  set automatically by the sender's client from what the outbound email
  sink stamped), or a `[tik <id>]` subject tag — else it opens a new ticket.
  A reply's `tik> key=value` lines become signed facts; everything else is a
  comment. With `:dkim {:require true :authserv-id "your-mx"}` in the config,
  the sender's From must be DKIM-authenticated (a `dkim=pass` from your MTA's
  own `Authentication-Results`, pinned by `authserv-id` so a forged verdict
  is ignored) before it is trusted enough to attribute events to an actor.
- `tik bridge imap [--config imap.edn]` — poll an IMAP mailbox over TLS and
  ingest new mail through the same routing, DKIM gate, and MIME decoding
  (multipart/HTML → text) as `bridge email`, so a cron/timer runs the inbox.
  Config adds an `:imap {:host … :user … :password <secret-spec> :mailbox
  "INBOX" :search "UNSEEN"}` block (the password resolves via `tik.secret` —
  `{:credential …}`/`{:command …}`/`{:file …}`/`{:env …}`). Atom-bomb proof:
  every message is ingested in isolation (a refusal/error skips one with a
  clean stderr line, the poll continues), idempotent (content-addressed, so
  re-polling dedups), and loop-safe — our own returned mail is dropped and
  auto-replies/bulk/bounces (RFC 3834) are recorded but never answered.
  `--watch` turns the one-shot poll into a long-running service: it holds
  the connection open and uses IMAP IDLE (RFC 2177) to ingest the instant
  mail arrives, re-issuing IDLE before the ~29-min server drop and
  reconnecting with backoff on failure — the delivery timing changes, the
  isolated/idempotent/loop-safe ingest does not.
- `tik bridge pop3 [--config pop3.edn]` — the same for POP3 mailboxes
  (config under a `:pop3` key). `:delete false` (default) leaves mail on
  the server and re-fetches each poll — harmless because dedup is by
  content address; `:delete true` removes a message only after it ingests
  cleanly, so a mid-poll crash loses nothing (the next poll re-fetches,
  dedups, then deletes).
- `tik bridge oidc [--registry ID] [--actor A]` — identity rung 2 (§9): a
  device-flow (or `--user` + a password) login binds an IdP subject to a
  signing key as an attestation on the registry ticket; verification never
  calls the IdP. Supply the password without exposing it in `ps`: prefer
  `--password-command '<pass show …>'`, `--password-file`, or the
  `TIK_OIDC_PASSWORD` environment variable over a literal `--password`.
  Identity fetches require HTTPS (loopback excepted for a local test IdP).
- `tik bridge oid4vci --credential vc.jwt --registry ID [--jwks-url U |
  --jwks FILE]` — ingest a **verifiable credential** (a VC is an attestation
  with an external issuer): verify the issuer signature against its JWKS
  (JWT-VC and SD-JWT-VC; EdDSA today, RS256/ES256 planned), then mint it as
  a bridge-signed attestation carrying the credential. A process gates on it
  with the guards that already exist — `[:signed-by :bridge [:credential]]`,
  `[:fact= [:credential :type] :kyc]`, `[:attested-within [:credential] "P90D"]`.

### Working as an agent — the gated surface

An agent never gets a free hand on the store; it works through the frontier,
which admits exactly the actions its actor may take on a ticket right now.

- `tik agent actions <id> --actor A` — the admissible action set (EDN): the
  authorization boundary, derived from the process, not the prompt.
- `tik agent set <id> k=v --actor A` / `tik agent attest <id> <claim> --actor A`
  — assert or attest, **refused** unless the frontier admits it (exit 3, with
  the admissible set printed). Every accepted action lands as a signed event.
- `tik mcp` — the same board / explain / actions / gated assert+attest surface
  spoken over stdio as an MCP server, for an LLM client. Tool payloads are JSON
  (the verbs route through `--format json`). `TIK_ACTOR` is the agent's
  identity; with `TIK_KEY` set each accepted call is signed. It runs from the
  shipped binary (`tik mcp`) — the enforcement is the derivation, so it holds
  whatever the client sends.

Facts take EDN values but you rarely need to know EDN: a bare word becomes a
keyword (`sev=high` → `:high`), a number stays a number, and anything the
parser cannot read as one clean form is kept as the literal string — so
`commit=a051932` and `desc="two words"` both do the right thing. Prefer a
declared enum/choice over a free string when the process declares one.

## Authoring a process — let tik do it

**Do not hand-write a process definition from EDN knowledge.** The tooling
already encodes the philosophy and the exact lint rules, and keeping the guidance
in the tool (not transcribed here) is what stops it from rotting:

- `tik author` — a guided interview that writes a linted definition plus a
  test skeleton. The path for a human at a terminal.
- `tik author prompt` — prints the **canonical LLM recipe**: the exact output
  shape, the "how to think about a process" guidance, and the rules
  `tik author check` enforces. **When you (an agent) are asked to design a
  process, run this first and follow it** rather than improvising a shape.
- `tik author check <answers.edn>` — lints a drafted definition (schema +
  smells) without writing. Iterate against it until clean.
- `tik lint <process.edn>` — the full linter on a saved definition (closed
  guard basis, graph sanity, stratified negation, facts-over-flags).
- `tik show <process|file.edn> [<id>]` — draw the process as a vertical ASCII
  stage graph: stages top-to-bottom, a single child continuing the lane
  (`│`/`▼`), forks branching (`├─▶`/`└─▶`), a join (diamond) drawn once under
  its deepest parent with `⋈ after …`, and a terse guard gloss per stage. A
  pure picture of the definition — the fastest way to see a process's shape,
  and what the `tik-processes` README diagrams are rendered from. With a
  ticket id, overlays that ticket's derived progress (`✓` reached, `◆`
  actionable now, `·` blocked).
- `tik adopt <file>` — install a process from the shared library into this
  store. A plain `.edn` definition is copied verbatim; a `.tmpl.edn`
  **template** is expanded first — tik reads the template's own malli
  `:tik/params` spec and asks for each input at the prompt (typed and
  validated), so you never hand-write EDN. Templates are inert DATA (two
  markers, `[:tik/param k]` / `[:tik/when flag elem]`), never code — the
  expanded, linted, hash-pinned definition is what's authoritative.

### The design law, in one screen

A process is a chain of **states of evidence**, each defined by what has become
**true** and what proves it. The rules the linter enforces (so internalize them
and you author clean the first time):

- **Evidence, not tasks.** Before writing any requirement, ask: *what would an
  auditor want to SEE a year from now?* Record the thing itself — a reference,
  an address, a value, or a demanded file — never a checkbox boolean.
- **Never name a fact like a checkbox** (`config-created`, `yaml-removed`,
  `uses-x`). Several checkboxes are often **one** piece of evidence: if three
  tasks land in one commit, the commit reference is the fact.
- **Never restate a system of record.** If git, a registry, or a dashboard
  already proves it, the fact *references* it (`path@commit`, a URL, an id).
- **Accountability is a signature, not a checkbox** — whoever stands behind a
  judgment signs it (`{:kind :signature :role …}`).
- **Stages are states, not tasks** — name them for what has become true
  (`submitted`, `approved`, `paid`), never `in-progress`/`wip`/`doing`.
- **Every stage after the first needs ≥1 requirement**, or it derives
  instantly and says nothing. **3–6 stages** is almost always right.
- **Prefer a `:choice` over a yes/no fact; prefer facts over flags.**

Design in prose and requirements first; let `tik author`/`check` produce and
validate the EDN. A definition changes its hash, so a sample process pinned by
the conformance corpus needs the corpus regenerated — mention that if you edit
one.

## Recording work in this repo's own store (dogfood)

If the repo is tik's own (a `processes/tik-dev.edn`, actor `seb`), the project
is run *in* tik: `tik next --actor seb` is the real inbox, and when a feature
lands you record it on its ticket — `tik set <id> commit=<sha> gate=:green` —
never by flipping a status. Read `CLAUDE.md` for the store's signing setup and
the full local gate before calling work done.

## What NOT to do

- Do not store or set a stage/status — stages are derived; there is no such verb.
- Do not hand-edit files under `tickets/` — write through the CLI so events stay
  signed and content-addressed.
- Do not invent a new event type or guard operator — both vocabularies are
  closed and versioned (see `CLAUDE.md` and `docs/PLAN.md` §19).
- Do not cache a derived value as authoritative — that violates the one law.
