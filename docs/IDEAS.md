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
- **`tik pack` / signed bundles / registry / federated discovery** —
  the distribution ladder beyond git-first. Transport porcelain only;
  hash stays identity, signature stays authority (§6).
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
