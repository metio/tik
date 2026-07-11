<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# Ideas — unjudged, uncommitted

This file holds raw material: ideas nobody has evaluated against the
plan's laws yet. Nothing here is a commitment or even an endorsement.
The contract with `PLAN.md`: the plan holds *verdicts* — adopted (a
section), banked with a phase (§19), or rejected with the reason
written down (§19). An idea leaves this file by graduating into one of
those buckets or by being deleted. If an idea here would obviously fail
an existing §19 rejection, delete it rather than re-litigating.

A pattern worth preserving as the file grows: everything here changes
authoring, rendering, distribution, execution, or commercialization —
**never the kernel**. The kernel's responsibility is settled ("given
immutable signed claims, deterministically derive everything logically
entailed — and explain why"); ideas accumulate around it. The sections
answer different questions: *Adoption* ideas exist to make H1–H3
succeed; *After H3* ideas only matter once they have; *Commercial*
ideas fund the rest; *Kernel validation hypotheses* test whether the
abstractions generalize.

## Adoption — making H1–H3 succeed

- ~~**`tik repl`**~~ — shipped as `tik sim` (live scratch ticket,
  auto-reloading definition) plus `tik test` (scripted steps, expected
  stages, explain on failure). Remaining idea: richer step vocabulary
  (concurrent actors, witnessed clocks) when Phase 1 lands.
- **Process debugger** (`tik debug <process> <ticket>`) — not a
  designer, a debugger, for process authors whose definition doesn't
  behave as expected: every stage (not just the frontier) with every
  guard's verdict; which facts each guard consulted; fixpoint
  iteration shown sweep by sweep ("stage :d was evaluated in sweep 3
  against reached #{:a :b :c}"). A query-planner view for derivation —
  all the information already exists in the pure evaluation; this
  exposes it as an authoring aid.
- **Graph view** — render the `:after` graph with reached/frontier/
  blocked coloring so "why is this stuck" is visible spatially; the
  evidence edges (which facts feed which guards) are derivable from the
  definition.
- **Process-authoring GUI** — click-a-stage editing over the graph
  view; the underlying representation stays canonical EDN, the GUI is
  a lens over it (same gate as the authoring DSL in PLAN §19).
  Concretions worth stealing when built: schema-first fact definition
  (define the fields Airtable-style before rules can reference them —
  the undeclared-fact lint already pushes this way); requirement
  pickers phrased as questions ("fact exists / attachment exists /
  someone with role… / time elapsed / another stage reached / any of /
  all of" — a 1:1 skin over the guard basis); boolean logic as a tree
  editor or sentence builder, never nested syntax; a wizard for
  first-time authors ("what happens first? what does it require?");
  and live simulation with explain-while-editing — tick facts on a
  scratch ticket and watch stages derive, which the pure kernel gives
  for free.
- **Starter templates** — bug tracking, change requests, purchase
  approvals, incident response, hiring: nobody invents workflows from
  scratch, and the blank-page problem is an adoption concern before the
  compliance-library *product* (below) is. Ship a handful of linted,
  signed definitions with the CLI.
- **LLM-drafted process definitions** — describe the process in prose,
  get a proposed facts/stages/guards definition back. Sound precisely
  because of the architecture: the LLM's output is *data* that is
  linted, human-reviewed in an MR, and hash-pinned like any hand-written
  definition — author-time nondeterminism is harmless when the artifact
  is deterministic and reviewed. The model never participates in
  runtime truth; it only drafts artifacts. The one place an LLM slots
  into tik without touching the trust story.
- **Claude skill for tik** — package "how to drive tik and author a
  proper process" as a Claude skill (or equivalent agent playbook), so
  an agent picks up the workflow and the design law without a human
  transcribing them each time. Distinct from LLM-drafted definitions
  (that produces one artifact) and from `tik author prompt` (that emits
  a one-shot recipe): a skill is the durable, reusable know-how — the
  CLI verbs and when to reach for each (`new`/`set`/`explain`/`next`),
  and the authoring discipline the linter already enforces (closed
  guard basis, facts-over-flags, stratified negation, declared facts
  before rules reference them, derived-beats-declared). The natural
  source of truth is the code, not a hand-written doc that rots: the
  skill should draw from what `tik author prompt` and the lint rules
  already encode, so it stays honest as the vocabulary evolves — the
  same anti-staleness discipline tik applies to its own docs.
- **Task-oriented web UI** — the 90%-of-users surface: current state as
  checkmarks, frontier as checkboxes, blocked-because from explain,
  action buttons generated from the missing facts' schemas. Entirely a
  rendering of `status` + `explain`; no new kernel surface.
- **`:purpose` / `:question` authoring annotations** — per-stage prose
  ("why this stage exists", "what question blocks you") carried in the
  definition and surfaced by explain. Pure authoring data, no kernel
  meaning.
- **Meta-process for "no process yet"** — a well-known fallback
  (sub-)process entered whenever work arrives that no definition
  models: capture the facts now, model the process later. Turns process
  gaps into tickets instead of untracked work.
- **Explain ranking** — when a stage has many missing reasons (the
  checkbox-workflow degeneration, PLAN §18), rank them: hard blockers
  first, then by downstream unlock impact, optional last. Pure lens
  ordering over data explain already carries.
- **Priority decay and attention debt** — lens features: stale-urgent
  triggers reassessment; long-ignored tickets accumulate an attention
  signal. Expressible today via `:elapsed-since`; the concepts just
  need naming in `next`.

## After H3 — execution, distribution, governance at scale

- **`tik run` on core.async Flow** — an optional execution backend that
  reacts to derived frontier transitions and performs side effects
  (automation, agent workflows, human-task nudges). Strictly an effect
  planner per §12: emits events, never mutates truth. The interesting
  part is "one process definition, many interpreters" — the same EDN
  projects to the verifier, explain, MCP tools, an execution graph, and
  docs.
- **Effect agents instead of DSL side effects** — deploy small agents
  that subscribe to derived transitions and perform integrations, so
  the process definition never grows effect syntax. (This is the §12
  Effects section taken to its deployment conclusion.)
- **Roles for machines** — a role responsible for a task could bind to
  an external service or an SSH session rather than a person: the same
  `:signed-by` machinery authorizing "this service completes this
  task", covering mixed flows (SSH commands + API calls) under one
  accountable identity.
- **Nix flakes as stage execution environments** — a stage annotation
  (`:stage/environment {:flake "github:…" :narHash "sha256-…"}`) naming
  the hash-pinned toolchain in which that stage's work is performed.
  Not wild — tik-shaped: the annotation is authoring data the kernel
  never interprets (ADR 0009), the effect runner or agent (ADR 0019)
  materializes the environment with `nix develop`, and the resulting
  `:work` attestation claims "performed in environment `narHash` X" —
  making the *toolchain itself evidence*. A process can then guard on
  it ("the release build's environment hash equals the pinned one"),
  which is reproducible-builds discipline extended to organizational
  work. Composes with agent accountability (§13): transcript hash +
  environment hash = what ran, and in what world.
- **`tik pack` / signed bundles / registry / federated discovery** —
  the distribution ladder beyond git-first. Transport porcelain only;
  hash stays identity, signature stays authority (§6).
- **Multi-store MCP/HTTP gateway with mapped access** — today a server
  process is one store (`TIK_ROOT`), one claimed identity (`TIK_ACTOR`,
  taken on trust), write-authorized by the derived frontier and made
  accountable only after the fact by the event signature; reads are
  ungated. A gateway would serve many stores at once and map an
  authenticated caller to the stores and actor identity they may use.
  Strictly porcelain — access-to-a-store is a "what should happen"
  policy the kernel deliberately does not answer (§12); the frontier
  still gates every write, and the signed event stays the accountable
  record. Mapping options, cheapest first: a **static map**
  (caller → {stores, actor}) for small deployments; **OIDC group
  claims** → store/role, the login half of the existing OIDC bridge
  (§9) taken to authorization. Two options that fit the offline law
  better than an IdP call per request: **derive access from the
  store's own `actors` registry + role facts** — who is a registered
  signer with a role IS the access list, derived not declared, the
  most tik-shaped answer; and **macaroons** — caveat-scoped bearer
  tokens (`store=x`, `read-only`, `expires=…`) verifiable *without*
  the issuer, exactly as signatures and witness sidecars are checkable
  offline-forever, with OIDC groups as the mint step. Open question:
  whether read access wants gating at all, or stays wide (the board is
  already "derived, not secret") with only writes and store-visibility
  scoped.
- **OIDC bridge captures group claims** — the bridge (§9) already binds
  an IdP subject to a signing key as a signed attestation, the
  tik-native enrollment path; it records `sub`/`preferred_username` but
  drops the token's `groups`/`roles` claims. Capturing them into the
  binding event would give the access-derivation above richer material:
  "who may touch this store" could then derive from a signed record of
  *both* the key→identity binding and the identity's group membership
  at bind time — still one signed attestation, still offline-verifiable,
  the group snapshot itself becoming auditable evidence (and rotation
  just a newer binding). Enrollment stays the bridge's job, authorization
  stays a derivation; this only widens what the bridge signs. Care
  point: group claims are the IdP's word at a moment, so the event must
  record *when* and *from which issuer*, and downstream guards treat
  stale membership the way any time-bound fact is treated.
- **Customer information-request loop** — when a process needs facts
  only the customer can provide, everything decomposes into existing
  machinery: the *request* is an effect (ADR 0019: an email from an
  org-specific template, fired when the frontier blocks on
  customer-role facts); the *reply* enters through a bridge as signed
  fact assertions (the email/portal bridge is just an actor); the
  *completion* is derivation — the blocked stage derives the moment the
  facts land, no sub-process state machine needed. A "sub-process" view
  is a lens over the customer-role slice of the frontier; if a real
  detached sub-ticket is wanted, that is a link fact plus a cross-log
  attestation (§10 federation shape, banked composition). Two derived
  extras make it shine: the email can render *where the ticket stands*
  (the customer-filtered explain — capability-filtered explain's
  friendly twin) and *what may be asked later* — every fact path a
  customer-role guard anywhere downstream can demand is **statically
  derivable from the definition**, so the org can choose per process:
  drip-ask at each stage, or aggregate the full list into one email up
  front. That choice is a lens/effect policy, not process semantics —
  and "we asked for everything once" is itself evidence in the log.
- **Plural, per-role priority views** — support, security, engineering,
  finance each get their own derived ranking over the same facts; no
  forced convergence to one number.
- **Confidence vocabulary for assessment bodies** — a qualitative scale
  (`:possible :plausible :likely :confirmed`) with mandatory basis/
  provenance, as schema for `:assessment/add` bodies. Kernel never
  interprets it (§19 forbids assessments feeding derivation).
- **Capability-filtered explain** — explain is also an information
  oracle: "missing: CFO approval, security approval" teaches every
  reader the org's internal structure. A rendering-level filter shows
  ordinary users "waiting for organizational approval" while
  authorized roles see the full reasons. The derivation never changes;
  only the lens redacts — which keeps verify honest while the UI stays
  discreet.
- **Process governance analyses** — the rest of the §18 complexity
  counterweight beyond lint warnings: an explainability score (how many
  reasons a typical blocked stage produces), fact-reuse analysis
  (singleton facts vs shared business concepts), approval-graph
  analysis (serial chains, redundant gates), and simulation coverage
  (which branches no `tik test` case has ever exercised).
- **Governance-observability lenses** — make governance failures
  visible without the kernel taking governance decisions (the PLAN §19
  standing question). Three shapes: **semantic hotspots** (a lint
  warning when one fact path serves many unrelated guards — the early
  signal of §18's semantic drift), **role concentration** (which roles
  can currently unblock the most tickets — approval inflation made
  measurable), and **dead evidence** (facts asserted frequently that
  never influence any stage — evidence theater made measurable), and
  **conflict topology** (which fact paths conflict most, which actors
  repeatedly disagree, which integration is the noisy source — ADR
  0003's health metric made measurable). All pure derivations over logs
  and definitions.
- **Duplicate radar and duplicate-of folding** — ticket identity is a
  minted UUID on purpose (two reports of one outage are two claim
  streams; their two-ness is frequency information), and the kernel
  must never merge referents — but *evidence-indistinguishability* is
  derivable: two tickets under the same pinned process with equal
  effective fact-maps share a semantic state address
  (`hash(process-hash, fact-map)`), meaning the system cannot tell
  them apart. Indistinguishable = genuinely duplicate OR
  under-evidenced, and only a human knows which — so the lens
  surfaces collisions (the ADR 0003 shape: detect, never resolve) and
  the resolution is a fact either way: `[:link :duplicate-of]` (v6
  links-are-facts gives dispute/retract for free) or the
  distinguishing fact that separates them. ls/next then fold linked
  duplicates into their canonical the way settled tickets already
  fold away. Work needs no dedup at all: next already groups by
  action, so N duplicate tickets are one inbox row unlocking N.
- **Causal view** — DAG analysis as a lens (ADR 0004): which assertions
  were made from which evidence, which actors were working from an
  outdated head, where a branch diverged, which conflicts came from
  independent replicas. Becomes interesting with Phase 1 multi-replica
  sync; pure `tik.dag` computation, no kernel change.
- **Formal verification of the user's process definition** — turn the
  formal-methods investment outward: a process definition IS a small
  state machine over a closed guard basis, so it is a natural target
  for a checker, and this makes "provably-correct workflow" a product
  feature, not just kernel assurance. Two shapes. **SMT-backed
  reachability** (encode the guard basis into Z3): statically answer
  "can this stage ever fire?", "are these two stages mutually
  exclusive?", "is there a dead stage no evidence can reach?" — the
  linter already does graph sanity (ADR: reachable, stratified, acyclic
  `:after`); this adds *semantic* reachability the graph cannot see.
  **Author-stated invariants, machine-checked** (TLC/Apalache over the
  definition as a state machine): let an author write a property about
  their OWN process — `paid ⇒ approved`, "no stage reached without its
  signature" — and prove the definition cannot violate it. tik-shaped
  because the process is already the spec: no separate model to keep in
  sync, the `.edn` is the thing checked. The kernel stays out of it —
  this is an authoring-time analysis over the definition (ADR 0009
  authoring data), never a runtime gate. Composes with the process
  debugger and governance analyses: reachability failures ARE dead
  stages, invariant violations ARE the counterexample trace to show.

## Commercial

- **The "evidence bundle"** — a named, portable witnessed.dev
  deliverable: everything a third party needs to re-derive an attested
  claim offline (events, definition, signatures, anchors). §10's
  reproducible mode, productized with a name and a file format.
- **Compliance process libraries as a product** — curated, signed
  definitions (ISO 27001, medical device) sold or maintained as a
  catalog. Depends on banked process composition (§19) proving out.

## Kernel validation hypotheses

Not product ideas — experiments, each asking the same question: *can
this domain also be expressed as evidence → derivation?* Parked until
the ticket projection passes H1/H3 (PLAN §19 rejects platformizing
before then). Each is "claims + evidence + process definition +
actors", nothing more; each one that fits strengthens the claim that
the kernel abstractions generalize, and each one that doesn't teaches
where they stop:

- **Compliance certification** — controls as facts, audit artifacts by
  hash, auditor attestations; "certification valid" is derived, and the
  regulator's "show me why" is `explain`.
- **Supply-chain provenance** — produced/processed/tested/certified as
  stages, every transition evidence-backed; consumes in-toto/SLSA
  attestations (PLAN §19 predicate schemas).
- **Contracts** — obligations as guards, deadlines as `:elapsed-since`,
  breach and payment-due as derived predicates.
- **Insurance claims** — report, photos, inspection, approval;
  "eligible payout" derived, disputes native.
- **AI agent governance** — prompt/model/tools/approvals as events;
  "was the agent authorized, what did it rely on, who approved" derived
  from the log (PLAN §12/§13 already carry the ticket-shaped version).
- **Membership organizations / DAO-without-the-chain** — membership and
  rights as attestation-derived facts (PLAN §11 already rejects the
  chain part).

## Engineering notes (no design weight)

- Tooling picks suggested in review: Integrant (lifecycle), Reitit
  (HTTP), FlowStorm (walking derivation), tools.namespace reload,
  next.jdbc with `events(id TEXT PRIMARY KEY, bytes BLOB)` for the
  SQLite backend. Adopt opportunistically; none are load-bearing.
- **Deeper formal verification of the kernel itself** — beyond the
  current TLA+ (merge, fixpoint) + reference-oracle + corpus + golden
  bytes + totality registry, several additions with real leverage on a
  small pure kernel. **Cross-runtime differential property** (cheapest,
  highest ratio): generate inputs, assert the JVM and babashka runtimes
  produce *byte-identical* canonical output and identical derivations —
  a direct guard on the content-addressing forever-promise against
  runtime drift. **Metamorphic properties** (no oracle needed):
  retract-then-reassert ≡ never-touched, replay-with-duplicates ≡
  replay, timestamp permutation stable up to `(at, id)` — catch bugs
  the reference oracle cannot see because they need no known answer.
  **Stateful model-based testing**: generate random *sequences* of CLI
  commands, run against the real store and a tiny in-memory model,
  assert agreement — interaction bugs per-function fuzzing misses.
  **Machine-checked proofs** (Lean 4 / Isabelle) of the crown-jewel
  laws — reducer commutativity/associativity/idempotence over the event
  set, canonical determinism — turning statistical confidence into
  certainty for the theorems everything rests on. **Apalache** beside
  TLC: symbolic checking proves inductive invariants rather than
  BFS-exploring a bounded space. The cross-runtime property is the one
  to build first.
- ~~**MCP per-call latency**~~ — shipped: the stdio MCP server called
  a fresh `bb tik` subprocess per `tools/call`, paying babashka's
  ~0.85s load-from-source cost each time. `tik.cli/run-argv` is now a
  second entry point beside `-main` — same `dispatch`, output captured,
  process-exit trapped into a code via the `*exit-fn*` indirection — so
  the server (and any embedder) reuses the whole CLI in-process, no
  subprocess. Per-call latency dropped ~850ms → sub-ms. The same seam
  serves any future surface that would otherwise shell `bb tik`.
