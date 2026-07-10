## 1. What tik is

A ticket is an **append-only log of signed, content-addressed facts**. The
ticket's stage is never stored — it is a **pure function
`f(events, now)`**, derived on read, the way a build system derives
targets or Datalog derives relations. Everything else follows from
refusing to store what can be derived:

- Nobody "moves" a ticket. People and agents assert facts; stages emerge.
- Disputes regress stage *by derivation* — reject the fact and the stage
  that depended on it is simply no longer derivable. No workflow engine,
  no transition table, no state to repair.
- Two replicas merge by set union — the log is append-only and events are
  content-addressed, so merge is `cp` that never conflicts.
- Any auditor can re-derive every conclusion from the log, offline, years
  later. **Derived beats declared** — the system's one law, applied to
  stage, to cost, and (§13) to work records.

Lineage: GSM/CMMN artifact-centric process models (stages guarded by
conditions over data, not edges in a flowchart), build systems (derive,
don't store), stratified Datalog (fixpoint semantics with provable
determinism), git (content-addressed immutable history).

The one-line positioning, sharpened against git itself: git answers
"what changed?"; tik answers **"what is true, why is it true, who proved
it, and can anyone reproduce the conclusion?"** The kernel underneath is
a general evidence-derivation engine — immutable claims in, explainable
decisions out — and the ticket is its first projection, chosen because
H1 (§16) makes it falsifiable. The same kernel projects onto compliance
records, provenance chains, and agent accountability trails (§19,
IDEAS); those stay projections-in-waiting until the first one has
earned its keep. Stated once, precisely: **the kernel is intentionally
more general than its first application, and the project is
intentionally not marketed as a platform until that application proves
the abstractions.**

The whole system in one layering — each layer only ever consumes the
one below:

- **Kernel** — immutable claims → deterministic derivation →
  explanation (§2–§8).
- **Authoring** — EDN, and eventually GUI/LLM drafting/debugger, all
  compiling to pinned canonical EDN; simulation and process tests
  (§6, §18, §19).
- **Consumption** — CLI, web UI, MCP, notifications, dashboards: every
  one a rendering of explain and the timeline (§12).
- **Automation** — effect planners and agents that observe derivation
  and emit new signed claims, never mutations (§12 Effects, §13).
- **Commercialization** — witnessing, anchoring, evidence bundles,
  signed process libraries (§10, IDEAS).
- **Research** — candidate domains that may, or may not, validate the
  kernel's generality (IDEAS: kernel validation hypotheses).

Most review cycles end by changing the edges, not the center — that is
the layering working: once core semantics are small and explicit, new
value accumulates at the edges.

## 2. Events

An event is an immutable claim by an actor:

```edn
{:event/ticket  #uuid "..."
 :event/type    :fact/assert
 :event/actor   "seb"
 :event/at      #inst "2026-07-08T10:01:00Z"
 :event/parents #{"sha256-..."}
 :event/body    {:fact/path [:category] :fact/value :technical}}
```

The **closed vocabulary, v1**: `:ticket/create`, `:fact/assert`,
`:fact/retract`, `:fact/dispute`, `:artifact/attach`,
`:attestation/add`, `:process/migrate` — seven types. Closed and
versioned on purpose: the semantics of a verifiable kernel must be
enumerable. New capability arrives as new event types and guards under a
version bump — never as pluggable code whose behavior an auditor cannot
enumerate (ADR 0001).

Seven, because everything else was expressible (the v6 subtraction):
a **comment** is an `:artifact/attach` of a text blob — comments are
unstructured content addressed by hash, and one type covers both. A
**link** is a fact (`{:fact/path [:link :blocks] :fact/value #uuid …}`),
which buys links dispute, retraction, and supersession semantics for
free — as a dedicated type they had none. A **work record** is an
attestation claim (`:attestation/add` with a typed `:work` body, §13) —
work records and attestations were both signed claims read by lenses,
deliberately outside ticket-state; one kind of thing, one type. And a
**witness countersignature is not an event at all** (§10): appending it
would create a new head, so the thing witnessed would never be the
current state — it is a detached sidecar over a head, the ADR 0007
pattern.

**Canonical form.** Events serialize to canonical EDN — sorted keys, no
whitespace variance, `format-version 1` — and the event id is
`sha256-<hex>` of exactly those bytes. Golden-value tests pin the encoding
byte-for-byte.

**Parents are mandatory (ADR 0004).** Every event carries the set of head
ids the actor observed; `:ticket/create` is the unique root with `#{}`.
Parents live inside the hashed region, so the log is a **Merkle DAG**: one
head hash commits to the entire history. This buys, simultaneously: sync
as head-compare + ancestry-walk; *structural* concurrency (two events,
neither an ancestor of the other) as the precondition for conflict
semantics (§5); single-signature witnessing of whole histories (§10); and
reproducible federation attestations pinned to a head. Reduction order
remains `(at, id)` over the event *set* — parents are for integrity and
causality, deliberately not for ordering, so derivation stays a pure
function of the set.

**Stored bytes are the hashed region (ADR 0007).** An event file contains
exactly the canonical bytes of the event *without* `:event/id` — the
filename is the id. Consequence: `sha256sum <file>` literally equals the
filename; store integrity is checkable with coreutils and nothing else.
Signatures are **detached sidecars** (`<event-id>.sig.<fingerprint>`)
over those exact bytes — the `.edn` is the claim, `.sig` files are
endorsements of the claim, and multiple signatures never touch the hashed
region. This ADR exists because an independent `sha256sum` check failed
while the internal verifier passed: the file had embedded the id its hash
was supposed to equal. Found by verification, fixed by simplification.

## 3. The reducer

`events → state` under three laws, all property-tested:

- **Total** over well-formed events: unknown types are logged and
  otherwise ignored. A replicated system cannot retroactively reject an
  event that already exists on three replicas.
- **Commutative**: internally ordered by `(at, id)`, so any permutation of
  the same set reduces identically.
- **Idempotent under duplication**: events are deduplicated by id before
  the fold. "Merge is set union" is literal.

The handler map is closed and versioned with the vocabulary.
`:attestation/add` (including its `:work` claims, §13) is read by
lenses, not by ticket-state — absent from the handler map on purpose,
not by omission. The definition to hold onto: **an attestation is a
signed claim whose semantics the kernel ignores.** Confidence scales,
assessment bodies, work telemetry — all legal as claim data, none of it
interpreted by derivation.

**fact-status** is the single choke point for "why does this fact (not)
satisfy guards": `:present | :absent | :retracted | :disputed |
:conflicted`, with `:by/:at/:note` attached. Guards consult nothing else
about facts; every user-facing "why" traces to this one function.

Fact semantics: a later assert **supersedes** (history retained); retract
means "should not exist, no replacement"; dispute is rejection with a
reason and makes the fact unusable until superseded by a corrected value.
Regression is always by derivation, never by command.

## 4. Stage derivation

A process defines stages with `:after` prerequisites (graph edges),
guard conditions, and optional `:stage/sticky?`. (v6 removed the `:when`
applicability condition: not-applicable was a third stage modality —
beside blocked and reached — that complicated explain and the fixpoint,
while every real use was expressible as a guard or as a separate forked
definition, which §6 blesses. It returns only if a real process proves
it necessary.) The reached set is a **fixpoint**: stages whose
prerequisites are reached and whose guards hold, iterated to closure —
internally a lattice computation; user-facing language says "process
graph" and "current stage". (The product consequence of derivation is
§12's three-questions law — every surface answers *what is true, what
could become true, what evidence is missing* — which is the bridge from
these semantics to what using tik actually feels like. A traditional
tracker exposes state; tik exposes **justification**: current truth,
plus why, plus the next evidence.)

**One fold (`evolve`).** State, the reached set, sticky retention, and
the notifier's timeline are all views of a single left fold over the
ordered event set — `{:state :reached :sticky-ever :timeline}` —
O(n·|stages|), replacing what were three traversals including an O(n²)
sticky prefix replay. One thing to test, one thing to trust.

**Sticky stages** (GSM milestones): reached-once-stays-reached, carried
monotonically by the fold. `:closed` survives a later ack retraction; the
log shows both truths ("was closed", "ack was withdrawn") without the
derived present lying about either.

**Stratified negation (ADR 0005).** `[:not [:stage-reached …]]` is
negation inside a fixpoint — non-monotone, the classic Datalog problem.
(v6 removed the dedicated `:not-stage` keyword: it was a second spelling
of the composite form, and the linter had to police both.) The linter
enforces
the classic Datalog answer: negate only strata that have finished deciding
(stratum = longest `:after` depth; targets must be strictly earlier).
Determinism becomes provable rather than incidental. The rule immediately
earned its keep by catching our own sample process, whose "escalated =
48h and not triaged" was remodeled to fact-level negation
(`[:not [:fact [:category]]]`) — monotone-safe, and the more honest
claim. Negation over facts is unrestricted: facts are inputs to the
fixpoint, not derived by it.

**Conditional prerequisites are guards, not edges** — so parallel branch
tips can both be current (`#{:resolved :reproducible}` in the sample);
maximality is computed against the `:after` graph only.

## 5. Guards

Closed vocabulary v1, an orthogonal basis of nine: `:fact`, `:artifact`,
`:signed-by`, `:stage-reached`, `:elapsed-since`, `:and`, `:or`, `:not`,
`:malli`. The v6 subtraction cut everything derivable inside the basis:
`:if` was boolean algebra over `:and`/`:or`/`:not`; `:not-stage` was a
second spelling of `[:not [:stage-reached …]]`; `:fact=` survives only
as documented authoring sugar that *expands* to a `:malli` guard before
evaluation, so the evaluator and the stratification linter see the basis
and nothing else. A smaller basis is a smaller thing to prove,
corpus-test, and reimplement. Guards are deterministic pure functions of
`(state, now, reached)` — **guards never query**. Anything external
(CI green, payment cleared, another ticket's stage) enters as a signed
attestation event; the attestation is the materialized answer, and
evaluation scope stays one ticket's log. That single law dissolves the
distributed-query problem: `verify` re-evaluates everything offline
because everything evaluable is local.

Guard failures are **structured EDN reasons**, never English:
`:fact/missing`, `:fact/invalid`, `:fact/retracted` (+who/why),
`:fact/disputed` (+who/why), `:fact/conflicted`, `:artifact/missing`,
`:role/unsatisfied` (+role, +actual actor), `:stage/not-reached`,
`:time/not-elapsed` (+due), `:schema/unsatisfied`, `:alternatives`,
`:must-not-hold`. The
kernel speaks data; rendering to CLI text, web forms, or MCP task specs is
a lens concern. This is what makes `explain` a product surface rather
than an error message.

**Conflicts block (ADR 0003).** Causally concurrent competing asserts
(structural, via the DAG) make a fact `:conflicted` — which blocks guards
exactly like a dispute until a *human signs a superseding value*. There
is deliberately no resolution-policy language: tik is an accountability
system, and "the protocol picked a winner" is precisely the
accountability hole we refuse to ship. **Detection is built**: the
causally-maximal writes on a path (asserts and retracts, per the
parents DAG) conflict when they disagree; concurrent agreement is
corroboration, not conflict; any write that observed all competitors
resolves — including a retract, which is also a human judgment on the
record. The DAG answers only "did these writes see each other?" —
effective values of non-conflicted facts stay with `(at, id)` order,
so backdated claimed times cannot fake or hide concurrency (detection
is computed from the complete log precisely because an incremental
frontier would be order-dependent under backdating). The corpus case
`concurrent-conflict` pins the semantics.

Blocking scales because conflicts are rare by construction: one requires
two actors asserting *different values* for the *same fact path* on the
*same ticket* in causally concurrent windows — and facts are fine-grained
(a path, not a document), tickets have few concurrent writers, and most
asserts are additive rather than competing. The expected steady state is
conflicts per month, not per hour, and each one is exactly a case where
silent auto-resolution would have hidden a real disagreement. Chronic
conflict volume in a deployment is a process-modeling smell (one fact
path doing the work of several), not a kernel scaling wall.

Where volume is real anyway (integration-heavy deployments — ERP and
CRM disagreeing about the same customer), resolution *policy* has an
honest home: an **accountable actor**, not protocol semantics. A bot
authorized to sign superseding facts under declared rules ("prefer the
ERP for customer status") is just another actor — keyed, logged,
auditable, revocable. "The preference bot picked one and signed it"
sits in the log like any human decision. The kernel still never picks
winners; policy exists one level up, wearing a name.

**Three clocks, named.** Claimed time (`:event/at`, actor-asserted),
observed time (witness countersignature over a head), evaluation time
(`now` in derivation). Backdating is detectable — a claimed time before a
countersigned observation is visible to any verifier — not preventable,
and pretending otherwise would be dishonest. Guards default to claimed
time; `{:clock :witnessed}` opts into observed time where it matters.

**Deferred, reserved:** an SCI-sandboxed guard tier and edge admission
checks (effectful pre-write validation) keep their seams in the design
but are explicitly not built — the closed vocabulary has yet to prove
insufficient, and unproven extension points are complexity debt.

## 6. Processes: pinned, hashed, linted

Process definitions are pure EDN, reviewed in merge requests, deployed
GitOps-style. **Tickets pin the definition's content hash** (ADRs
0002/0006) at creation: `verify` never trusts file naming; the version
number is a human label, the hash is the identity. Definition changes
never silently re-derive old tickets — migration is an explicit, signed
`:process/migrate` event carrying the new hash, auditable like everything
else. The grandfathering loophole (tickets never migrated, evaluated
under rules everyone else abandoned) is a *policy* concern: migration
sweeps are porcelain, the kernel keeps pinning honest.

**Process definitions are signed** — a detached signature sidecar over
the definition's canonical bytes, keyed by the definition's own content
hash: the ADR 0007 pattern, reused verbatim. Git history says who
*changed* a definition; the signature says who *published* it as
authoritative, which is the question a regulator actually asks ("only
Compliance may publish v4 of the change process"). `verify` checks
definition signatures on the same L1 rung as event signatures —
coreutils-checkable, offline, no registry in the trust path. Any future
distribution mechanism (git remotes, bundles, a catalog) is transport
porcelain; the hash stays the identity and the signature stays the
authority, exactly as with events.

**Forking and customization have one answer: a new pinned definition.**
There is no in-place customization, no inheritance, no overlay — a team
that needs a variant copies the EDN, changes it, and publishes a new
hash, optionally recording lineage with a link fact
(`[:link :forked-from]`) on the process registry ticket. This is deliberate: every evaluated ticket names exactly
one enumerable rule set, and "which rules applied here" never requires
resolving an override chain.

`tik lint` enforces what the kernel cannot: unknown stage references
(error), unreachable stages (error), stratified negation (error, ADR
0005, on the single `[:not [:stage-reached …]]` spelling — v6's removal
of `:not-stage` halved what the lint must police; arbitrary
negation-parity nesting is a documented lint TODO), and
facts-over-flags (warning: a bare boolean fact is a checkbox with extra
steps; per-process opt-out via `{:lint {:boolean-facts :off}}` keeps the
lint honest rather than dogmatic).

## 7. Hashing policy (ADR 0006)

SHA-256, **on purpose** — the argument is ecosystem weight, not a
prediction about cryptography: it is deployed widely enough that the
verification ecosystem around it currently outweighs any alternative's
benefits. git's own hash transition targets it (SHA-512, SHA-512/256,
BLAKE2, K12 were evaluated and rejected by git); OCI,
Sigstore/in-toto/DSSE, Nix, and FIPS 180-4 already speak it; and
`sha256sum` ships in coreutils, which is what keeps verify L0 checkable
with tools from 1995. Length-extension is irrelevant — tik hashes
complete canonical byte sequences and never uses the hash as an
authentication primitive: hashes answer "is this byte sequence
unchanged?", detached signatures answer "who authorized it?" (ADR 0007).
Performance is irrelevant at event sizes.

Ids are self-describing (`sha256-<hex>`), but **exactly one algorithm per
store per format-version** — agility in the format, discipline in the
policy, because "verifier accepts whatever" is a downgrade-attack
surface. Migration, if ever needed, is additive: new prefix under a
format-version bump, old events stay exactly as signed, mixed-prefix DAGs
verify. Unlike git — whose uniform object-id namespace makes its
transition agonizing — tik references ids as verbatim strings, which is
what makes the additive path possible; that property is retained on
purpose. CNSA 2.0 territory would mean a `sha384-`/`sha512-` prefix — a
policy change, not a redesign.

## 8. The verify ladder and the conformance corpus

Verification is a product surface, not a test suite. The ladder:

- **L0 — integrity**: every file's bytes are exactly the canonical hashed
  region; `sha256(bytes) = filename = id`; all parents present; exactly
  one root. Checkable with coreutils alone (ADRs 0006/0007).
- **L1 — authenticity**: detached signatures verify against stored bytes;
  keys resolve through the identity ladder (§9).
- **L1.5 — anchored existence**: OpenTimestamps proof that a head existed
  before a Bitcoin block (§10) — trustless, checkable by anyone.
- **L2 — reproducibility**: re-derive stages under the hash-pinned
  process definition; derived state matches claimed state.
- **L3 — provenance**: artifacts resolve by hash; witness
  countersignatures and cross-instance attestations check out. A deleted
  blob is reported as **verifiable absence** — the hash stands, the
  event stands, verify says "blob absent" rather than lying. Deletion
  (GDPR, leaked secrets) is a designed property, not a failure mode.

`tik verify` shipped L0 + L2 in Phase 0 — it already caught ADR 0007's
bug during development, which is the entire argument for building it
first. **L1 is now live**: detached SSH sidecars via `ssh-keygen -Y`
(no crypto code of our own), verified against the store's `actors`
registry (allowed-signers format — identity rung 1, §9). Events are
signed at write time when `TIK_KEY` is set; unsigned events are
reported as unclaimed, never failed (ADR 0011); a signature that
does not verify as its event's claimed actor fails the ladder.

The **conformance corpus** — directories of real event files plus
expected derived results — is simultaneously the regression suite, the
executable specification of the store format and derivation semantics,
and the compatibility contract for any future second implementation.
The corpus, not the Clojure, is the definition of tik.

Three commitments extend the ladder inward:

- **A reference kernel as oracle** (built: `test/tik/reference.clj`).
  Alongside the optimized single-fold `evolve` lives a deliberately
  slow, obvious implementation of the same semantics — no fusion, no
  incremental carry, O(n²) prefix replay, direct transcription of the
  laws. Differential property tests assert equality over generated
  event histories. The trusted computing base is the small boring
  version; the fast version merely has to agree with it. The property
  suite paid for itself on its first run: it found that canonical map
  ordering keyed on `pr-str`, which is identity-hash-unstable for
  `java.time.Instant` keys — fixed by sorting on the canonical form
  itself, with the golden tests proving no existing hash moved.
- **explain is sound and complete, as property-tested laws** (built:
  `test/tik/explain_prop_test.clj`). Sound: every `:satisfied`/
  `:missing` entry is re-derivable from the log — explain never
  speculates. Complete: every unreached stage whose prerequisites are
  reached appears with reasons — explain never omits. explain earning
  trust as a product surface (§12) depends on these being laws, not
  aspirations.
- **Model-checked semantics** (built: `spec/`, TLC in the dev shell via
  `bb tla`). `Merge.tla` checks union-merge replication: grow-only,
  never-blocking, quiescent convergence — H2's theorem, verified before
  H2's code. `SweepFixpoint.tla` + `ChaoticFixpoint.tla` pin the
  derivation semantics and produced a real finding: **stratification is
  necessary but not sufficient** — a linter-clean process (later
  stratum negating an earlier one) is order-dependent under
  fire-one-stage-at-a-time iteration; TLC exhibits the counterexample,
  and the check suite *requires* it to. Determinism additionally needs
  synchronous-sweep evaluation, which is therefore a normative part of
  the derivation semantics, pinned executably by the corpus case
  `sweep-order-negation` (ADR 0005). Machine-checked proofs (Lean)
  remain banked.

## 9. Identity

A ladder, each rung sufficient, each next rung strengthening:

1. **SSH keys** (built): actors are keys; signatures are detached
   sidecars (ADR 0007) minted by `ssh-keygen -Y` and checked against
   the store's `actors` allowed-signers registry (`tik actor add`).
   Works offline forever; the fingerprint in the sidecar name is
   coreutils-recomputable from the public key.
2. **OIDC/Keycloak bridge**: a bridge service performs device-flow login
   and issues signed *attestation events* binding a key to an IdP
   subject, with a reconcile loop re-attesting on schedule. Verification
   never calls the IdP — attestations are in the log, so offline-forever
   verification survives the IdP's death. Rotation, multi-key, and
   delegation are themselves attestations.
3. **Sigstore-keyless**: Fulcio-style ephemeral certificates; Rekor is a
   Merkle transparency log — the same shape as tik's own DAG, and the
   flavor of "decentralized trust" that has actually earned production
   keep (§11).

The identity registry is designed as — naturally — a ticket: a
well-known log whose facts are key bindings, evaluated by the same
kernel, verifiable by the same ladder. Design settled; implementation
Phase 2.

## 10. Witnessing, anchoring, federation

A witness countersigns a **head** — one signature timestamps the entire
ancestry (ADR 0004 pays for itself here). The countersignature is a
**detached sidecar** (`<head>.witness.<fingerprint>`), not an event: an
event would create a new head, so the state witnessed would never be the
current state. Endorsements of existing bytes all use one pattern —
`.sig` for authorship, `.witness` for observation, `.ots` for anchoring
— and verify checks them all the same way (v6, generalizing ADR 0007).
Two attestation modes:
**reproducible** (witness hands over enough of the log that anyone can
re-derive the attested claim) and **attested** (witness vouches; you
trust the key). witnessed.dev sells the fast, fine-grained, accountable
version of this.

**Trustless anchoring (adopted):** aggregate witness heads into
OpenTimestamps — a detached `<head>.ots` sidecar (the ADR 0007 pattern
again) proving existence before a Bitcoin block to any third party, no
tokens, no accounts, no trust in witnessed.dev's clock. Commercial judo:
sell precision and accountability, bundle trustlessness. Adopted in
design, built with H5 and not before — a witness without customers does
not need trustless anchoring (v6).

**Federation** is cross-instance stage attestations: instance B doesn't
query instance A; A's witness asserts "ticket X reached `:approved` at
head H" as a signed attestation event in B's log. Guards evaluate
locally; trust is explicit and per-attestor. (ActivityPub was evaluated
and rejected: push-based social broadcast is the wrong shape for
pull-based verifiable claims.)

## 11. Decentralization verdicts (asked and answered)

tik is already a content-addressed Merkle DAG with signed nodes and
union-merge replication — the good parts of web3 without the coin. Each
further piece must add verifiability per unit of dependency weight:

- **IPFS as optional blob transport: maybe, later, behind the existing
  store seam.** `sha256-<hex>` maps mechanically to a CIDv1 (raw codec),
  so an `ipfs-blob-store` needs zero kernel changes. But IPFS provides
  addressing, not availability (someone still pins); the DHT *announces
  what you hold* — provider records for support-ticket blobs leak
  operational metadata to a public network; and its permanence marketing
  collides with our verifiable-absence guarantee. Optional transport for
  public/OSS artifacts; never the core.
- **OpenTimestamps anchoring: adopted** (§10) — the one web3-adjacent
  piece that adds pure verifiability at near-zero dependency cost.
- **Consensus, contracts, tokens, DAOs: rejected, with the reason
  written down.** Blockchains solve double-spend — one global order among
  mutually distrusting parties. Tickets have no double-spend, and tik
  deliberately chose the opposite semantics: concurrent conflicting
  claims **block until a human signs a resolution** (ADR 0003). Replacing
  accountable human judgment with miner ordering is strictly worse for a
  system whose product is accountability. Token incentives are the
  Goodhart problem (§13) with a flamethrower.
- **Identity: transparency logs yes (Rekor, §9), on-chain identity no.**
  `did:key` is a harmless notation we may adopt someday; it changes
  nothing structural.

## 12. Lenses: explain, next, MCP

**The three-questions law.** Every user-facing surface — CLI, web, MCP,
notification — answers the same three questions, in this order: *what is
currently true* (derived facts and stages, with who/when), *what could
become true next* (the frontier), and *exactly what evidence is missing*
(structured reasons, with who-can-act). Traditional trackers answer only
the first ("Status: In Review"); tik can answer all three
deterministically, which is what turns opaque workflow rules into
inspectable logic. The interaction model this implies is the inversion
users actually feel: nobody operates a workflow — they satisfy missing
facts from a checklist whose consequences are automatic. No UI ever
offers "move to stage X"; it offers "assert the fact stage X is waiting
for". Events, hashes, and derivation stay below the waterline — they
are for auditors and `verify`, not for the person approving a vacation.

**explain** is the product. For every frontier stage, a structured block
— `:stage`, `:satisfied` (the checkmarks; earns trust), `:missing`
(structured reasons incl. who-can-act), `:blocks` (downstream closure:
what this unlocks), `:hint` (authored knowledge link) — every field
derived and true, no speculation. The same data renders as CLI text, web
forms (generated from the fact schemas in the reasons), and MCP task
specs whose acceptance criteria *are* the guards. A conversational
surface falls out for free: "why can't I close this?" is answered by
rendering explain's blocks as prose — no model reasoning in the loop,
deterministic and exactly as trustworthy as the derivation.

**The evidence timeline.** `log` interleaves two kinds of line: stored
events ("Alice: category = technical") and **derived transitions**
("stage :triaged became reachable here"), computed at render time from
the evolve fold's timeline and never stored — the one law applied to
the UI's own furniture. Notifications phrase themselves the same way:
not "ticket moved to Review" but "resolution added by Alice — ticket is
now eligible for QA": the fact, then its consequence, because the
second tells you *why*. **diff** is the timeline compressed: between
any two points in the log, which stages became derivable and which
facts changed — rendered as evidence gained, never as transitions
performed.

**next** (the inbox): "the smallest set of facts that unlocks the most
work." H3 starts with the simplest honest version — frontier facts
sorted by unlock count, grouped by action, filtered by who-can-act; the
greedy weighted set-cover across tickets is an optimization of an inbox
nobody has used yet, and earns its keep only if the sorted list proves
insufficient — the boundary to respect: next is an *inbox*, and must
not accidentally become a scheduler. Effort appears only as an optional
declared annotation (`:effort "PT2H"`), never inferred.

**Effects (derived, non-authoritative).** Lenses have an outbound twin:
derivation is pure, and *effects observe derivation*. An effect planner
watches derived frontier transitions and fires outbound integrations —
webhooks, mail, chat — but delivery lives entirely outside the log:
success or failure of a webhook never touches ticket truth, and there
are no `:webhook/*` event types, because transport is not a domain
concept. Idempotency needs no stored state machine — the effect key is
the content hash of `(ticket, stage, effect-id, head)`, so replays and
re-derivations dedupe structurally. Inbound is symmetric and already
covered: an external system's webhook is just another actor whose bridge
appends signed events (§5, §9). When the *business fact* matters —
"customer was notified" — that is asserted as a fact or attestation like
any other claim; the transport that carried it stays out of the
vocabulary.

**MCP lens**: the process definition is, for an agent, four things at
once — its **authorization boundary** (offered exactly the
fact-assertions and artifact-attachments the frontier admits for its
role, nothing else), its **plan** (the frontier is the todo list), its
**acceptance criteria** (the guards, verbatim), and — with §13 — its
**audit log**: what it was allowed to do, what it did, what it cost,
and the transcript hash to prove it. That is considerably richer than
tool permissions; it is the operational shape AI-governance rules keep
asking for.

**Five personas, five surfaces** — the same kernel, barely overlapping
views: the *process author* lives in `sim`, `test`, and `lint`, and
never edits hashes; the *operator* lives in `explain`, `next`, `set`,
and `attach`, and never thinks about derivation; the *auditor* lives in
`verify`, `log`, and `diff`, and cares exactly about the things the
operator never sees; the *administrator* handles identities, witnesses,
and storage, and never edits processes; the *agent* receives the
frontier and returns signed events. Each surface hides everything the
persona doesn't need — Merkle DAGs and fixpoints stay below the
waterline for everyone but the auditor and `verify`.

## 13. Work evidence: derived beats declared

The enterprise timesheet is self-reported fiction with a signature line —
reconstructed Friday-afternoon guesswork shaped by what the hours are
supposed to look like. tik's log is signed, timestamped, witnessed
evidence of what actually happened. Same law as stage and cost: **the
work record is derived from evidence, not asserted from memory.**

Work records are `:attestation/add` events with a `:work` claim body
(v6: a work record *is* a signed claim read by lenses — it never needed
its own event type), covering agent and human work symmetrically.

**Machine work.** Agent runs carry exact telemetry:

```edn
{:claim :work
 :work/kind :agent-run
 :agent/model "claude-fable-5"
 :usage {:input-tokens 182034 :output-tokens 45210
         :cache-read-tokens 1203944 :cache-write-tokens 88123}
 :artifact/hash "sha256-…"}   ; the transcript blob, transferable lazily
```

Record **observations, not valuations**: raw token counts, not euros —
prices change, observations don't rot; money is a lens over
`usage × pricing-table-at-date`, and invoice truth, when it exists, is
its own observation event. Totals are **derived by folding, never
stored** — a stored aggregate can drift from its log; a derived one
cannot, and verify L2 covers it for free. Because the evolve fold's
timeline knows the reached set at every event, every work record lands in
a stage interval automatically: cost-per-stage attribution with zero
instrumentation, and the baseline for prediction (per process/stage/
category segments, median and p90, honest about low n — hypothesis H6).

**Human work.** Here the honesty constraint is structural: **events are
points; duration is an inference.** A fact asserted at 14:32 proves
presence at 14:32, not the preceding 90 minutes. Raw events are evidence
(signed, indisputable); intervals are heuristic (declared method, stated
confidence, traceable to the producing events); a derived duration is
never stored as a fact. Where attested hours are legally or contractually
required (billing, Arbeitszeitgesetz, government contracts), the flow is
**machine-drafted, human-signed**: the lens prefills "Tuesday: ~3.5h on
ticket X, evidence: these 11 events"; the human corrects and signs a
`:work` claim. Thirty seconds of review replaces thirty minutes of
fabrication, and the claim carries its evidence inside the provenance
chain — categorically stronger than any timesheet an auditor has seen.
For agencies: invoice line items linking to countersigned evidence the
client can verify is a witnessed.dev product on its own. Transcripts plus
authorization boundaries plus cost is also precisely the operational
logging trail emerging AI regulation asks for.

**Monitoring is a design constraint, not a compliance footnote.** The
same architecture that kills the timesheet can build the panopticon;
the difference is explicit choices: evidence granularity is
per-ticket-event, never per-keystroke; the worker sees everything derived
about them (symmetric transparency is native — it is their log);
cross-person aggregation happens only in lenses with declared purpose,
never in stored state; and the *human-signed claim*, not the raw
inference, feeds payroll and billing — the worker remains the authority
over their own attested record. Built this way it is pro-worker by
construction (evidence protects the person whose invisible work never
made it into a worklog) and it is the version that survives §87 BetrVG
co-determination — which is where enterprise time tracking goes to die.
Goodhart applies with teeth: visible evidence streams invite evidence
theater. Observability first; incentives later, if ever.

Sensitive-content caveat: transcripts from real environments contain
paths, hostnames, occasionally secrets. Blob deletion with verifiable
absence (§8 L3) is the designed answer.

## 14. Storage and packaging

`EventStore` protocol with exactly two backends, **both built**:
**file/git** (Phase 0 — union-merge replication for free,
`sha256sum`-auditable) and **SQLite** (single-file ops: `TIK_DB=…`,
exact canonical bytes in a BLOB, verify L0 recomputes hashes from the
raw rows, and the ADR 0020 contract is a cross-backend test both must
pass — including a property that they derive identically). The file
store remains the auditor-grade interchange format; `tik export`
materializes any store as one. Two implementations keep the seam honest; anything
heavier (Postgres for multi-tenant SaaS, bitemporal stores) is a future
third implementation of the same protocol, listed nowhere until it earns
its keep (v6 — name the seam, delete the list). Blobs stored by hash
next to events; transfer is lazy.

**One serialization** (v6): canonical EDN is the stored bytes, the
hashed bytes, the signed bytes, *and* the wire bytes. Transit was a
second encoding that existed only as wire optimization — a standing
opportunity for two encodings to disagree about the same event. If
profiling ever demands a faster wire, it arrives as transparent
transport compression of the canonical bytes, never as a second
serialization of the data. All-in-one container for the SaaS shape;
babashka-first CLI so the local shape has no JVM startup tax.

## 15. What exists today (all verified end-to-end)

Kernel (v6 shape): canonical form with golden bytes; the seven-type
event vocabulary with mandatory parents and hash-pinned process
references; the evolve fold; fact-status; the nine-operator guard basis
with `:fact=` expanding to `:malli` at the eval boundary; structured
guard reasons; lint enforcing the closed basis, stratified negation
(single spelling), and facts-over-flags with per-process lint config;
explain as data + renderer; `tik.dag`; **`tik.next`** — the inbox lens,
sound/complete against explain by property test, who-can-act aware of
`:signed-by` restrictions. CLI: `new set retract dispute
attach comment status explain log diff ls next lint sim test actor
sign` and **`verify` (L0+L1+L2)**; structural conflict detection live
(corpus case `concurrent-conflict`); union-merge replication validated
over real git clones (`tik.sync-test`) and across environments over
`git://` (`dev/h2-two-machines.sh`); SQLite EventStore backend
(`TIK_DB`, `tik export`, cross-backend contract tests); `tik migrate` (dry-run by default,
`--apply` appends the signed event) with definitions archived
content-addressed under `processes/by-hash/` so pinning is honored on
READ — grandfathered tickets keep deriving and verifying under their
archived definition after the named file moves on — independently checked with coreutils; `comment`
attaches a text blob by hash (no dedicated event type); `log` is the
evidence timeline (derived transitions interleaved at render time);
`sim` is live process design (scratch ticket, auto-reloading
definition, per-keystroke re-derivation); `test` runs scripted process
tests — steps in, expected stages out, deterministic by fixed epoch,
with explain printed on failure (`processes/support-request.tests.edn`
is the sample suite). Store: file store where
`sha256sum(file) = filename = id`; blobs by hash. Conformance corpus:
first scenario (full lifecycle to `:closed`, ack retracted, sticky
retention) with computed expectations, regenerated under the v6 process
hash. Tests: golden canonical bytes, reducer laws incl. a property test
for commutativity+idempotence, derivation incl. dispute regression and
sticky, lint incl. closed-basis rejection of removed operators, corpus
runner. Sample process remodeled v6-clean (`:when` became a guard;
`:if` became material implication over `:or`/`:not`). kb/ OKF bundle
with ADRs 0001–0007.

## 16. Roadmap as falsifiable hypotheses

- **H1 (Phase 0, now)** — *A file-backed, derive-on-read ticket CLI is
  pleasant for a single operator.* Kill: if daily-driving tik for
  infra.run's own work is abandoned within a month, the core loop is
  wrong, not under-featured. **Clock started 2026-07-10**: tik's own
  development runs in tik — the `tik-dev` process (captured → triaged →
  implemented → landed-sticky, with a reasoned `:parked` exit), its
  scripted test suite, and a signed working store committed in-repo at
  the root (`tickets/`, `actors`), replicated by the same git that
  carries the code. The backlog tickets are real; `tik next` is the
  project's actual inbox.
- **H2 (Phase 1)** — *Signatures + verify L1 + multi-replica union merge
  work across two machines without a coordinator.* Kill: if merge ever
  requires human conflict resolution at the *file* level, the store
  design failed. **Status: every component is built and the kill
  criterion is a passing integration test** (`tik.sync-test`): two git
  clones append divergently, merge without a single file conflict,
  derive identically from the union, surface a cross-replica
  disagreement as `:conflicted`, and propagate its resolution — plus
  the content-addressing dividend, the identical event minted on both
  replicas colliding as byte-identical files and deduping to one.
  The flow also passes across two **environments** connected only by
  the git protocol over TCP (`dev/h2-two-machines.sh`: machine B is a
  podman container with its own rootfs, clock, and user, cloning over
  `git://`; `MODE=remote REMOTE=user@host` runs the same script against
  a real second box, which is the last inch of the sign-off).
- **H3 (Phase 1)** — *`explain`/`next` reduce time-to-action vs. a Jira
  baseline for a real support workload.* Kill: if users still open the
  raw log to find out what to do, the lens is decoration.
- **H4 (Phase 2)** — *The OIDC bridge + registry ticket make identity
  onboarding a non-event in a Keycloak shop.* Kill: >30 min to onboard an
  org's first ten actors.
- **H5 (Phase 2)** — *Witness countersigning + OTS anchoring produce
  attestations a third party accepts without trusting us.* Kill: if the
  reproducible mode is never chosen over the attested mode, the
  verifiability premium is imaginary and witnessed.dev is a plain SaaS.
- **H6 (Phase 3)** — *Work evidence: pilot users produce attested weekly
  records in under five minutes with fewer corrections than their old
  timesheets; cost predictions per stage segment reach ±50% at p50 after
  30 tickets.* Kill: miss either and it is a demo, not a product.
- **H7 (Phase 3)** — *MCP lens: an agent completes a real support ticket
  end-to-end inside its authorization boundary, with the accountability
  trail (§13) intact.* Kill: if boundary violations require prompt-side
  enforcement, the lens design failed.

Engineering hypotheses are not enough — H1–H7 could all pass and tik
still fail as a product. Three market hypotheses, same falsifiable form:

- **H8 (with H5)** — *Someone pays.* A real organization pays for
  witnessed.dev attestations or support before Phase 3 ships. Kill: if
  every prospect says "great, we'll self-host the OSS part," the
  commercial layer is mispriced or misaimed.
- **H9 (with H3)** — *Adoptable without the doc.* A newcomer gets from
  install to a derived, explained ticket using only `tik --help` and
  error messages. Kill: if onboarding requires reading this plan, the
  porcelain is a research demo.
- **H10 (with H3)** — *explain is understood in ten minutes.* A user who
  has never seen tik reads an `explain` block and correctly states what
  is blocking and who can act. Kill: if they ask "but what do I *do*,"
  the product surface failed at its one job.
- **H11 (with the authoring ladder, §18/§19)** — *A non-developer
  authors a working process without editing EDN.* Given the authoring
  lens (form builder, wizard, or LLM-drafted definition — all compiling
  to canonical EDN), a domain expert produces a linted, simulated,
  test-passing process for their own workflow. Kill: if every process
  in production was ultimately hand-written by a developer, the
  authoring layer is decoration and the adoption ceiling (§18) is the
  kernel's audience after all.

## 17. Comparison (what they do better, honestly)

| | tik |
|---|---|
| Jira/Linear | Their UX polish and ecosystem are decades ahead; tik wins on auditability, merge-without-server, derived truth. |
| GitHub Issues | Better network effects forever; tik wins on process semantics and offline verification. |
| ServiceNow | Their enterprise integration breadth is unmatchable; tik wins on "an auditor can re-derive everything." |
| Temporal | Better at *executing* long-running code; tik is not an executor — it is the accountable record executors report into. |
| Camunda/BPMN | Better modeling tooling; tik refuses the flowchart ontology on purpose (facts unlock stages; arrows don't move tokens). |
| git-bug | Closest relative; tik adds process semantics, guards, witnessing, and the verify ladder. |

## 18. Risks, named

Ecosystem gravity (Jira is a habit, not a preference); the linter's
judgment calls annoying real processes (mitigation: per-process lint
config, already shipped); witness economics unproven until H5; work
evidence triggering works-council rejection if §13's constraints are ever
compromised for a deal; scope discipline — the closed vocabulary will be
pressured constantly, and ADR 0001's acceptance test ("new events, new
guards, new lenses — or no") is the only thing standing between tik and
becoming the workflow engine it exists to replace.

**The semantic threat model.** The cryptographic layer is not where tik
gets hurt. The attacks that matter abuse one property: tik faithfully
derives correct answers from whatever signed evidence and pinned process
it is given — so the attacks target the evidence, the process, and the
incentives, never the derivation. The guarantee boundary, stated once so
nobody over-promises on tik's behalf: **tik guarantees that conclusions
are reproducible from recorded evidence. It does not guarantee that the
evidence is complete, truthful, well-chosen, or useful** — it guarantees
that if an organization records bad evidence, everyone can later prove
exactly which bad evidence led to which decision.

The deepest attack exploits the social gap between those two: *derived*
gets presented as *true*. "tik proved it" actually means "given this
immutable evidence and this immutable rule set, this conclusion
follows" — and a manipulator profits from the audience hearing more
than that. The standing defense is explain's own shape: every
conclusion renders with its *because* (facts, actors, definition hash,
guards) and never claims reality itself was verified. Keeping that
provenance visible is a core security property, not a UX nicety.
Classes, with dispositions:

- **Evidence theater / Goodhart** (§13 already names it) and its
  management flavor, *organizational capture* ("measure people by
  evidence events"): not preventable, only visible. Defenses are
  process design — bind approvals to the thing approved by hash ("QA
  approved artifact `sha256-…`", not a bare boolean; the
  facts-over-flags lint pushes here), witnessing where it matters, and
  the standing rule from §13: observability first, incentives later,
  if ever.
- **Dispute denial-of-service.** Disputes must stay cheap — blocking on
  rejection *is* the accountability model — but a dispute is a signed
  event by an actor: bad-faith disputing is attributable, visible in
  the log, and governable by deployment policy (who may dispute what is
  a role question). The kernel does not rate-limit conscience;
  deployments govern actors.
- **Conflict bombing** (noisy integrations flip-flopping a fact): the
  §5 answer — chronic conflict volume is a modeling or integration
  smell; the policy-bot-as-accountable-actor pattern absorbs the
  legitimate cases; rate limiting belongs upstream in the bridge.
- **Process complexity** — the attack that matters most, because it is
  how BPMN died: 87 stages, 312 facts, 19 serial approvals, everything
  verifying and nobody understanding it. The kernel cannot forbid bad
  processes and must not try; the counterweight is authoring tooling —
  complexity lints and analyses, deferred in §19 — plus the checkbox
  degeneration this enables in explain (a ten-approval stage renders
  ten missing reasons; ranking is a lens concern, IDEAS).
- **Stale evidence.** A replayed attestation ("CI green", yesterday) is
  cryptographically valid and semantically stale. Freshness is a real
  gap in the guard basis: a recency guard (attestation observed within
  a window, on the witnessed clock where it matters) is a legitimate
  candidate keyword through the version-bump gate — deferred in §19.
  Until then, freshness is process design: reconcile loops re-attest
  (§9), and processes should demand the re-attestation.
- **Timestamp games**: already answered by the three clocks (§5) —
  backdating is detectable against witnessed observations, not
  preventable, and the plan refuses to pretend otherwise.
- **Approval laundering, signature cargo-culting, process laundering**
  — one epistemic answer, stated plainly: **tik proves who signed what,
  when, under which pinned rules, and that the rules evaluated
  correctly. It does not prove why they signed, that the signature was
  uncoerced, or that the rules are wise.** "Signed" means signed, not
  true; "verified" means correctly evaluated, not well-governed. No
  audit system does better; the difference is that tik makes the
  laundering *inspectable* — the terrible process is hash-pinned and
  readable, the rubber-stamp approval has a name and a timestamp on it.
- **AI evidence spam** (an agent flooding the log with signed
  analyses): granularity is already bounded per-ticket-event (§13);
  the rest is lens filtering by claim type and actor kind, and the
  agent's own work records make the spam's cost attributable.
- **Storage exhaustion and fact spam** (the append-only tax: churning
  asserts/retracts, attaching hundreds of artifacts — nothing invalid,
  everything unpleasant): blob transfer is lazy by design (§14), lenses
  collapse superseded history, and quotas/rate limits are porcelain. The
  kernel intentionally does **not** solve log hygiene — hygiene is
  policy about actors, and every byte of spam carries its author's
  signature. If reduction cost ever demands checkpoints (a verified
  reduction summary pinned to a head), the verdict is pre-recorded:
  checkpoints are **untrusted accelerators** — a verifier may use one,
  but replay always outranks it, because a summary is derived state and
  the moment it becomes authoritative the one law is broken at the
  store layer. Corollary worth saying to every deployment: *permission to
  assert facts is also permission to create review work* — write
  authorization is the real admission control. The same lesson applies
  to roles: `:signed-by :manager` is a security boundary exactly as
  wide as the role, and organizations reliably over-broaden roles —
  500 people holding "manager" means the cheapest attack is one
  account, not any cryptography. The decay mode is temporal: the role
  quietly widens while `[:signed-by :manager]` stays textually
  identical, and a later auditor sees valid signatures over an
  authority that no longer means what the process meant. The answer is
  to give role bindings the same discipline as process definitions —
  attestation-backed grants with provenance, temporal validity, and
  explicit migration (§19 identity concretions), so "was 'manager'
  still the same authority when this was signed?" is an answerable log
  question.
- **Lying by omission** (true facts asserted, the damning one withheld):
  no evidence system prevents it; process design does, by requiring
  sufficient evidence before important stages unlock — which is what
  guards are for. Same family: a guard that checks only "an artifact
  exists" is satisfied by any PDF; bind guards to the reviewed thing
  (hash-bound approvals, above) when it matters.
- **Semantic drift** — the slowest and most corrosive attack: over
  years, `customer-confirmed` drifts from "customer replied positively"
  to "sales thinks it'll close". Same fact path, same guards, same
  derivation, entirely different meaning — every cryptographic
  guarantee intact while the evidence quietly stops meaning anything.
  The kernel cannot detect this (git cannot detect that "approved"
  changed meaning either); the defenses are governance: signed
  definition ownership (§6), process review and explicit migration
  (§6), fact schemas that constrain values (§5 `:malli`), and the
  authoring-layer fact-reuse analyses (IDEAS) that surface when one
  path is doing several jobs.

The pattern across all of these, worth stating because three
independent threat reviews converged on it: hardening the kernel
**migrates the serious attacks upward** — into evidence quality, role
semantics, process governance, and presentation. That is the intended
boundary, not a gap: the kernel's job is to make the upward-migrated
attacks *visible and attributable* (every one of the classes above
leaves signed, inspectable traces), and the governance-observability
lenses (IDEAS) exist to turn those traces into measurements. Rough
severity order, per the reviews: semantic drift, authority inflation,
bad definitions, evidence manipulation, write volume — with
cryptographic attacks last, which is where a design like this wants
them.

**Process authoring as adoption ceiling.** The process language is not
the product; if authoring a process requires understanding fixpoints and
stratified negation, the audience is Clojure enthusiasts, not
organizations. The designed answer is the authoring ladder banked in
§19 — canonical EDN as the compiled, pinned, reviewed representation
(EDN is the IR, not the authoring language), with form builders,
wizards, and even LLM-drafted definitions compiling down to it. Two
disciplines keep the ladder honest: the compiled EDN must remain what
humans *review* in the merge request, not merely what machines pin —
a visual builder whose output nobody reads recreates "workflow rules
nobody understands" one level up; and the ladder is built when H3's
real users balk at EDN, not before (its success has its own kill
criterion, H11) — H1's single operator writes 57
lines of EDN happily, and an authoring stack for users who don't exist
yet is the same premature platformization §19 rejects elsewhere.

## 19. Banked and rejected (asked and answered, round two)

An external fifteen-angle design review — and a sixteenth follow-up
arguing the platform framing — was triaged against the plan's laws.
Recording the verdicts here — in the §11 spirit — so settled questions
stay settled.

The two lists below carry different weights and should be read that
way: **deferred** items are sound and expected to happen once their
trigger condition arrives; **rejected** items are refusals on principle
— reopening one requires overturning the law it failed against, not
merely waiting.

**Deferred — sound, awaiting their phase or trigger:**

- **Author-time process composition** (named gates, libraries, imports):
  acceptable only as deterministic macro expansion where the ticket pins
  the hash of the *fully expanded* definition — the kernel and verifier
  never see imports. Build only when copy-pasting guard trees across
  real processes actually hurts; §5's own test ("the closed vocabulary
  has yet to prove insufficient") is not yet met. Same gate applies to
  any human-friendlier authoring DSL: sugar compiles to canonical kernel
  EDN, the compiled form is what is pinned and verified. **Compilation
  is one-way and the compiled definition is authoritative** — the
  authoring representation is convenience only, never a second source
  of truth. When the day comes, the mechanism sketch for process
  *families* (ISO variant per country, diverging over time) is
  git-shaped: `definition = hash(parent + delta)` — lineage without
  overlays, the evaluated object still one immutable hash.
- **A query lens (`tik query`)**: derived queries across many logs —
  "which decisions relied on disputed facts?", "which certificates
  depend on revoked evidence?", "all approvals missing a compliance
  witness". Pure derivation, offline, zero kernel change: explain
  generalized from one frontier to the whole estate. Less a feature
  than another projection the model already implies — a *when*, not an
  *if*; the sequencing (Phase 2, after H3 proves the single-ticket
  lenses) is the only thing deferred.
- **Counterfactual lenses** (`simulate`/`diff`): what unlocks if this
  fact lands, what stops being derivable if it is disputed, would this
  ticket pass under process v3. Nearly free by purity — derivation over
  a hypothetical event set is the same pure function. Same phase as
  query.
- **Identity concretions for Phase 2**: time-aware role validity ("was
  an approver *when* they approved" — falls out of attestations plus the
  three clocks, with `{:clock :witnessed}` where it matters); delegation
  as an attestation claim with capability scope and `:valid-until`,
  including human→agent delegation for MCP; a `:different-person`
  separation-of-duties guard (a legitimate new guard keyword, through
  the version-bump gate); **authority chains** as the natural closure —
  "Alice, delegated by Bob, delegated by Compliance, therefore
  authorized" is just derivation over delegation attestations. All
  shapes recorded; none touch Phase 0.
- **Property tests generated from process definitions**
  (reachability/unreachability cases derived from the definition
  itself): natural extension of corpus-as-spec; wait for the guard
  vocabulary and sample processes to stabilize. The end state is a
  `check-process` that proves every stage reachable, every guard
  satisfiable, no dead ends — the lint's graph checks extended to the
  guard level. (`tik test` already covers the hand-written half:
  scripted steps, expected stages, explain on failure.)
- **Complexity lints** (the §18 process-complexity counterweight):
  warnings on nesting depth, guard fan-out, serial-approval chain
  length, graph diameter, singleton facts used by exactly one guard —
  cyclomatic complexity for processes. Authoring tooling, zero kernel
  impact; build alongside the authoring ladder, informed by which
  degenerations real processes actually exhibit.
- **A recency guard** (attestation observed within a window, defaulting
  to the witnessed clock): closes the stale-evidence gap named in §18.
  A new guard keyword, so it goes through the version-bump gate; until
  then freshness is process design over re-attestation loops (§9).
- **Threshold witnessing (N-of-M) and witness role taxonomy**: after H5
  proves single-witness economics, not before.
- **Artifact lineage** (`:derived-from` provenance edges) and a temporal
  query surface (`status` at an arbitrary instant — already just
  `f(events, now)` with a chosen now): expressible, unneeded, deferred.
- **Rekor as an additional public anchor** for witness heads, beside
  OTS: fine as another detached-sidecar rung; never the primary store
  (below).

**Rejected on principle, with the reason written down — not
provisional:**

- **New event types for things that are already claims** —
  `:decision/*`, `:identity/*`, `:webhook/*`. A decision is a signed
  `:fact/assert` by an authorized actor; identity operations are
  attestation claims (§9); transport is not a domain concept (§12
  effects). All three fail ADR 0001's acceptance test.
- **Storing derived values as state** — proof/verification certificates,
  SLSA-style assurance levels, priority scores. Each is a materialized
  derivation that can drift from its log, the exact failure §13 rejects
  for stored totals; each is welcome as *lens output* (verify already
  is the assurance ladder, `next` already is the priority lens) and
  banned as stored truth.
- **An epistemic layer feeding derivation** (assessments/confidence as
  a stratum between facts and stages). The sound half — agents emit
  `:assessment/add`, humans promote to signed facts — is already the
  design (§3 keeps assessments out of the handler map on purpose). The
  structural half would inject non-decidable inputs into the fixpoint
  and break offline re-derivability. Confidence and basis live in
  assessment bodies as data the kernel does not interpret.
- **Rekor as the primary home of witness countersignatures**: verifying
  a countersignature must never require reaching a network service —
  the sidecar in the log is the record, transparency logs are extra
  anchoring (§8, §9).
- **A "non-verifiable process" tier with pluggable evaluators**: a
  two-class system where some tickets cannot be re-derived offline is
  the workflow engine wearing our clothes. The reserved-but-unbuilt SCI
  seam (§5) remains the whole concession.
- **Softening "derived beats declared" to a preference**: the law is the
  differentiator, and the plan already carves out declared data where
  declaration is the honest form (effort, priority annotations, signed
  human claims in §13). A law with named exceptions beats a suggestion.
- **Renaming kernel vocabulary for readability** (`:guard`→`:requires`
  and kin): churn in a closed, golden-tested vocabulary buys nothing;
  friendlier words belong in the authoring lens, if one ever earns its
  keep.
- **Platformizing before H1** (stage → generic "derived predicate" as
  the kernel primitive; shipping `tik-core` as a standalone derivation
  engine; repo split into core + ticket packages). The kernel *is*
  general — that is §1's positioning — and a stage already is a derived
  predicate, one with an `:after` graph, sticky semantics, and a
  stratification lint. Renaming it buys generality on paper and loses
  the process language humans can read; a platform with no proven first
  application is the failure mode of every general workflow engine tik
  exists to replace. The namespace seam already exists in the code;
  packaging follows users, not the other way around. Revisit after
  H1/H3 pass.

**Proven by implementation — verdicts that graduated to evidence:**
structured reasons made explain a product surface instead of an error
message; the single evolve fold replaced three traversals including an
O(n²) sticky replay; detached sidecars fixed a real bug an internal
verifier missed (ADR 0007); the v6 subtraction survived contact — the
property suite, reference kernel, and TLA+ models built afterwards
found bugs in the *encoding* (pr-str map ordering) and the *evaluation
contract* (sweep semantics), never in the subtracted design; and the
sim/test lenses were buildable in an afternoon precisely because
derivation is pure. These are no longer decisions; they are results.

**Kept on purpose, untouched by the v6 subtraction:** mandatory parents,
the single evolve fold, fact-status as the one choke point, structured
guard reasons, hash pinning, the conformance corpus, conflicts-block.
Every subtraction was judged by one question: *does removing this
reduce the number of fundamental ideas without weakening the laws?*
These seven are what every other feature turned out to depend on — the
architectural nucleus, not the current implementation's accidents.
Everything v6 removed was removable precisely because they are not.

Two cautions the ledger itself must carry. First: "kernel correct,
governance responsible" is the right disposition for every semantic
attack in §18, but it must not become a reflex that ends design
discussions. The follow-up question that keeps it honest: **can the
kernel (or a lens) make the governance failure more *visible* without
taking the governance decision itself?** Capability-filtered explain is
the template — derivation unchanged, presentation carries the burden;
the governance-observability lenses in IDEAS (semantic hotspots, role
concentration, dead evidence) are the same move applied to drift,
approval inflation, and evidence theater. Making governance failures
observable is "derived beats declared" applied to the organization
itself. Second: the project has become good at saying no, and the
failure mode of that skill is rejecting authoring-layer ideas by
evaluating them against kernel-layer laws.
Graphical editors, rule builders, templates, LLM-drafted definitions —
none of these touch verification if they compile to canonical EDN. The
standing posture: **the kernel is extremely conservative; the authoring
experience is aggressively experimental.** A "no" in this section must
name the law it defends; if it can't, it belongs in IDEAS, not here.
