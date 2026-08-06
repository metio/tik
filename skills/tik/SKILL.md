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
  human anything. A step marked `⊘ … — nobody can ever do this` and a
  stage headed `(unreachable)` mean waiting is pointless: an empty role,
  a negation over a sticky stage already reached, or a prerequisite that
  is itself dead. Fix the definition or the register — do not wait.
- `tik status <id>` — derived stage, facts, links, what's next.
- `tik retract <id> <k>` / `tik dispute <id> <k>` — withdraw or reject a
  fact; the stage **regresses by derivation** (no manual rollback). A
  dispute rejects the value that stood when it was raised, so only a
  **different** value answers it — re-asserting the same one leaves the
  path disputed. The disputer takes their own objection back with
  `tik dispute <id> <k> --withdraw`; nobody can withdraw somebody else's.
- `tik roles` / `tik roles add <role> <actor>` / `tik roles remove <role>
  <actor>` — who gates what, and the store's role register behind it. A
  definition declares which roles exist and who starts in them; the
  register decides who is in one today, for every ticket at once. Use it
  for a hire or a departure: it takes effect on in-flight tickets
  immediately, with no definition version bump and no `reprocess`.
- `tik whatif <id> k=v +PT48H retract:k` — counterfactual stage diff,
  nothing written. Use to check what a fact *would* unlock before setting it.
- `tik plan [<file.html>]` — the dependency-link roadmap: ready / blocked /
  done / cyclic, the critical path, and each item's unlock impact — all
  derived, never stale. A `.html` argument writes a fancy self-contained page.
- `tik probe [<id>] [--command C]` — re-derive facts from the world instead of
  remembering them: run the process's `:probe` (an executable printing
  `key=value` lines) with cwd in the ticket's `[:repo]` repository, and assert
  each **changed** value as an ordinary signed fact, so a ticket regresses on
  its own when reality does. The probe's environment carries `TIK_TICKET`,
  `TIK_REPO`, and every present fact as `TIK_FACT_<PATH>` (`[:candidate :repo]`
  → `TIK_FACT_CANDIDATE__REPO`) — that is what lets one repository hold many
  subjects, a package or tenant or workload per ticket, each probe told which
  subject it is running for. Naming an id refuses with a reason when that
  ticket cannot be probed; the whole-store sweep skips and counts instead.
- `tik reprocess <id> <new.edn> [--apply]` — migrate ONE ticket onto a newer
  version of its process. A ticket is judged by the rules it was minted
  under, so a definition that grows (a value added to an enum, a stage
  added) leaves existing tickets deriving under the old one until you move
  them — deliberately, never automatically, because an implicit upgrade
  would re-judge a whole store on an edit. The re-pin is a signed
  `:process/migrate` event, so the log keeps which rules judged the ticket
  when. Dry-run by default: it prints the pinned-vs-proposed hashes, which
  stages would be gained or REGRESS, and the new blockers, writing nothing
  until `--apply`. (`store migrate` is unrelated — that moves where events
  live.)
- `tik debug <id>` — the fixpoint with its working shown: every sweep, every
  guard verdict. An id alone debugs the definition the ticket **pins**, so it
  agrees with `status`; `tik debug <process> <id>` asks about a different
  definition instead and warns on stderr that it is not the pin.
- `tik verify` — audit the whole store (hashes, signatures, re-derivation).
  L1 credits a signature when the key is in `actors` (rung 1) **or** a verified
  binding grants it (rung 2). A binding whose issuer nobody pinned is a note,
  not a failure — it grants nothing, and events are never deleted, so failing
  would trap a store forever. Evidence of forgery does fail.
- `tik bundle <id> --out ev.tgz` — one ticket as a portable evidence bundle
  (`tik-evidence-bundle` version 1): events, signatures, witness marks, key
  bindings and their issuer keys, the pinned definition, `roles.edn`, and a
  `verify.sh` that checks the lot with coreutils + `ssh-keygen`.
- `tik rederive <ev.tgz|dir|https url> [--edn]` — check a bundle and recompute
  what its facts imply under the definition it pinned, **at the instant you
  ask**: a guard with a freshness window (`:attested-within`,
  `:elapsed-since`) grants a stage today and withholds it next month on the
  same bytes. Exits 1 when the bundle fails to verify. It never runs the
  bundle's own `verify.sh`, unpacks the archive itself (refusing traversal,
  symlinks and unbounded expansion), and refuses a pinned definition that does
  not lint. `--serve [--port N]` is the same over HTTP —
  `POST /rederive`, `GET /derivation?bundle=<url>`, `GET /badge.svg?bundle=<url>`
  — deriving per request and caching only on `[content-hash, minute]`.
- `tik gc [--apply]` — remove archived process definitions no ticket pins
  (versions every ticket has been moved off with `reprocess`). Dry-run by
  default; `verify` stays PASS, only historical `--at` degrades. Tidiness,
  not disk.

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
  `{:credential …}`/`{:command …}`/`{:file …}`/`{:env …}`). TLS is implicit
  and on by default (IMAPS 993 / POP3S 995); set `:tls false` in the `:imap`/
  `:pop3` block only for a loopback or trusted-relay mailbox (an in-pod
  stunnel/gateway sidecar), which switches to plaintext 143/110. Atom-bomb proof:
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
- `tik bridge jwks --issuer <url>` — pin an issuer's signing keys at
  `<root>/jwks/<issuer>.json`. Rung 2's trust anchor: verification never calls
  the IdP, so the keys must be in the store before a binding can count. Fetched
  once and committed like `actors`; re-pinning MERGES by `kid`, so a rotated
  key never retires the one that signed an older binding.
- `tik bridge workload --github | --token-file F | --token-env VAR
  [--public-key key.pub] [--registry ID]` — identity rung 2 for **machines**.
  A pipeline generates a keypair per run, presents the OIDC token its platform
  already issues, and binds the two, so no long-lived key is stored anywhere:

  ```sh
  ssh-keygen -t ed25519 -N "" -f ./ci_key
  tik bridge workload --github --registry <id> --public-key ./ci_key.pub --actor ci
  TIK_KEY=./ci_key tik set <ticket> commit=$GITHUB_SHA --actor ci
  ```

  A binding counts only when its token's signature is the issuer's, its `sub`
  and `iss` match what the attestation claims, and it was LIVE when the binding
  was written — so a leaked expired token cannot mint one (ADR 0023). The token
  is checked before the binding is written, because a log never deletes.
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
- `tik backend [--config pipelines.edn]` — the supervised server that runs
  your **pipelines** as a delegate: continuous ones (`:watch true` — `serve`,
  `bridge imap --watch`) each get a restart-on-exit thread, scheduled ones
  fire a verb on an `:every` ISO-8601 interval (`recur`/`probe`/`bridge pop3`/
  `effects`). Each runs as its `:as` delegate with `TIK_KEY` the delegate's
  key, so every event it produces is signed and traces back — via a §9
  delegation attestation — to the human who authorized the delegate. It never
  changes what a fact means; it only decides WHEN to run a porcelain verb, and
  idempotency (recur is deterministic) means it needs no leader and a missed
  fire self-heals. This is the all-in-one deployment; the chart can also run
  the slices as separate workloads.

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
- `tik sim <process.edn>` — a live scratch ticket that reloads on every save:
  `set k=v`, `retract <k>`, `dispute <k> <why>`, `attach <name>`,
  `attest <claim>`, `now +P2D`, `actor <name>`. The fastest way to find out a
  guard means something other than what you read into it. Quote string values
  (`set version="2026.8.1"`) — a bare word becomes a keyword and will fail a
  `[:string …]` schema.
- `tik test <tests.edn>` — scripted cases, evidence in and expected stages out,
  deterministic and store-free; a failing case prints explain. Steps are
  `[:actor "x"] [:now "+P2D"] [:set path v] [:retract path] [:dispute path why]
  [:attach path] [:attest {:claim :x}]`. **Write the negative cases** — that a
  role's signature is genuinely required, that a freshness window really does
  expire, that a stage does NOT derive without its evidence. The positive path
  is the easy half and proves the least.
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

## Going deeper

The documentation site is <https://tik.projects.metio.wtf/> — including a
machine-readable `/llms.txt` index and a concatenated `/llms-full.txt`. Reach
for it when a command's flags, a guard's exact semantics, or a runbook a
`:hint` names would otherwise be a guess.

## What NOT to do

- Do not store or set a stage/status — stages are derived; there is no such verb.
- Do not hand-edit files under `tickets/` — write through the CLI so events stay
  signed and content-addressed.
- Do not invent a new event type or guard operator — both vocabularies are
  closed and versioned (see `CLAUDE.md` and `docs/PLAN.md` §19).
- Do not cache a derived value as authoritative — that violates the one law.
  A cache is legal when every input to the derivation is in its key: the
  bundle service memoizes on `[content-hash, minute]` because derivation is a
  function of `(events, now)`, and dropping the whole cache costs only time.
- Do not write a `:malli` guard or fact schema that names `:fn` or `:multi` —
  those compile a child through `m/eval`, so they are code rather than data.
  Derivation refuses them (`:schema/unsupported`) and `tik lint` errors.
