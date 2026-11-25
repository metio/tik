---
type: decision
status: proposed
date: 2026-11-25
title: The role register is a log of edits signed by authorities its genesis names, and a role a definition reserves for humans refuses agent actors
supersedes: null
---

# ADR 0039: The role register is a signed log

## Decision

Proposed on 2026-11-25. Not accepted and not implemented. ADR 0038
(point 6 and resolved question 6) asks for this decision before agents
get identities of their own; the maintainer agreed that it lands first.
Each choice below names the alternatives and why it was taken, ranked by
security first, then extensibility and re-use, then performance.

**Who is in a role becomes a fact on one well-known register ticket,
asserted by events that count only when a register authority signed
them. The authorities are named by public key in the register's first
event, which every one of them signs, and a reader can pin them from
outside the store. A role that any definition in the store reserves for
humans refuses every actor the register does not declare human, and a
fact credited through such a role counts only when it carries a
signature by a key the register pins for that human.**

1. **Register edits are signed events.** The register is a ticket
   pinned to a built-in `role-register` process, the shape the identity
   registry already has (PLAN §9, `processes/identity-registry.edn`).
   Membership is the fact `[:role <role> :members]`, whose value is the
   sorted vector of actor names, so one assertion replaces a role
   wholly, exactly as a `roles.edn` entry does today (`resolve-roles`,
   `src/tik/process.cljc:134`). The register ticket's id is the name UUID
   of its sorted root key fingerprints, so `tik register init` is
   deterministic and a second register with other roots has another id.
   A store holding more than one `role-register` ticket fails
   `tik verify`.

2. **The authorities are rooted in the register's genesis and named by
   key.** The fact `[:register :authorities]` holds a vector of
   `{:actor "seb" :key "<OpenSSH public key>" :pq-key "<base64 SPKI>"
   :touch true}`. Its first assertion, the genesis, must carry a valid
   `tik-event` signature by every key it names. Every later assertion of
   `[:register :authorities]`, `[:register :humans]` or
   `[:role … :members]` counts only when signed by a key in the authority
   set derived from the event's own ancestors. A key whose entry sets
   `:touch true` counts only with the security key's user-presence flag,
   which `tik.sshsig` already reads (`src/tik/sshsig.clj`, namespace
   docstring). Authority keys are verified against the register itself
   and never against `actors`, so appending a line to `actors` cannot
   create an authority.

   **A removal revokes causally.** An edit signed by a key counts only if
   it is an ancestor of every authority change that removes that key.
   Edits the removal saw keep counting, which is ADR 0010's prospective
   revocation; an edit the removed key made on a fork from older heads,
   concurrent with or after the removal, counts for nothing however it
   is dated.

   **A reader can pin the root.** `tik register pin` records the root
   fingerprints for a store in the user's configuration, outside the
   store, after the maintainer has compared them; `tik verify` and
   `tik rederive` take `--expect-register-root <fpr>,…` for the same
   check without a file. An unpinned root is a note that prints the
   fingerprints, and a root that differs from the pin fails.

3. **Roles are resolved at the derivation instant, by the CLI, and the
   kernel stays as it is.** Before deriving, the CLI folds the register
   ticket's events claimed at or before the instant it evaluates at
   (`eval-instant`, `cli/tik/cli_core.clj:999`, which `--at` sets),
   keeps only the edits point 2 credits, and hands the result to
   `resolve-roles` in place of the file. A role whose
   `[:role … :members]` is `:conflicted` between two authority edits
   resolves to the members every conflicting value names, so a
   concurrent pair never grants a member that one of the authorities
   withheld. Signature checks run in process through `tik.sshsig` and
   are cached by event id and sidecar name. Membership at the instant a
   fact was asserted (ADR 0010, deferred in PLAN §19) stays deferred,
   and this form makes it expressible later without a new event type.

4. **An actor is human only when the register says so.** The fact
   `[:register :humans]` maps an actor name to the fingerprints of the
   keys that actor signs with, classical and post-quantum. Every actor
   it does not name is an agent, so a newly registered worker slot is an
   agent from its first event, and no attribute anywhere else has to be
   remembered.

5. **A definition declares which actors a role admits.** A role in
   `:process/roles` may carry `:admits`, one of `:humans`, `:anyone` and
   `:agents`. It is a rule of the process, so it lives in the hashed,
   publisher-signed definition (ADR 0015) and changes through a version
   bump, while membership stays store state. The register keys roles
   store-wide, so a role's kind in the store is the strictest any
   definition in `processes/` or `processes/by-hash/` declares, ordered
   `:humans` over `:anyone` over `:agents`. A role no definition
   declares counts as `:humans` for register edits. "A role that gates a
   maintainer fact" (ADR 0038 point 6) is therefore a role whose store
   kind is `:humans`.

6. **The rule is enforced in four places.**
   - **Resolution** drops from a `:humans` role every member the register
     does not declare human, so a derivation never runs with one.
   - **`tik roles add`** writes a signed register edit and refuses one
     that would put an agent into a `:humans` role, or that the signing
     key could not make as an authority.
   - **Derivation**, for a role a ticket's pinned definition itself
     declares `:admits :humans`: a fact discharges a
     `[:signed-by <role> path]` guard only when its event carries a
     signature by a key `[:register :humans]` pins for the asserting
     actor. Otherwise the guard fails with the reason
     `:role/unauthenticated`, naming the actor and the path. The CLI
     computes the set of such authenticated event ids, as it computes
     roles, and passes it to the kernel as one more input, so the kernel
     stays pure and the fact stays admitted and explainable (ADR 0011).
     Definitions that declare no `:admits` derive exactly as before.
   - **`tik verify`** fails an authority-signed edit that breaks the rule,
     as ADR 0026 fails a binding its policy does not permit, and fails a
     fact credited through a `:humans` role without a pinned key's
     signature.

   The gated write surface of ADR 0038 (`submit`) refuses a register edit
   that point 2 would not credit, before it is appended.

7. **`tik verify` reports the register, and stores move to it once.**

   | State | Report |
   | --- | --- |
   | No register ticket, no `roles.edn` | nothing |
   | No register ticket, a `roles.edn` | note: the register is unsigned, with `tik register init` as the fix |
   | A register whose genesis is not signed by every root it names | FAIL |
   | An edit claiming an authority's actor whose signature does not verify against that authority's key | FAIL |
   | An authority-signed edit that puts an agent into a `:humans` role | FAIL |
   | A fact credited through a declared `:humans` role without a pinned key's signature | FAIL |
   | More than one `role-register` ticket | FAIL |
   | A root that differs from the reader's pin | FAIL |
   | An unpinned root | note, with the fingerprints |
   | An edit by a non-authority under its own actor name | note: admitted, counts for nothing |
   | A `roles.edn` beside a register | note: ignored |

   `tik register init --key <authority key>` mints the register, asserts
   the genesis, declares the authority human, and imports an existing
   `roles.edn` as signed `[:role … :members]` facts. From then on the
   file is no longer read. `tik roles add` and `tik roles remove` stop
   writing `roles.edn` in every store and point a store without a
   register to `tik register init`; a store that keeps an unsigned
   `roles.edn` derives under it as today, with the note above.

8. **Register signatures follow ADR 0036 without a path of their own.**
   Register edits are events, so `signatures.edn` applies to them like
   to any other: after `:from`, an edit counts only when both its
   SSHSIG and its ML-DSA-65 sidecar verify. The ML-DSA half is checked
   against the authority's `:pq-key` in the register, never against
   `pq-signers`, for the same reason point 2 ignores `actors`. An
   authority entry without `:pq-key` cannot edit after `:from`, and the
   genesis of a register minted under a policy carries both halves.

9. **A later dispatcher is a scoped delegation from an authority.** An
   ADR 0037-shaped `:delegation` whose `:delegation/facts` names only
   `[:role <role> :members]` paths of `:agents` roles, and whose
   delegated edits may name only actors the register does not declare
   human, gives a dispatcher exactly the power ADR 0038 resolved
   question 6 describes. ADR 0037's delegation does not fit as it stands:
   it is capped at five minutes (`max-window`,
   `src/tik/delegation.cljc:29`) and scoped to ticket heads the reviewer
   was shown. The standing shape is left to its own ADR, after this one
   is implemented and tested.

## Context

### The register today

`roles.edn` in the store root is read by `role-register`
(`cli/tik/cli_core.clj:270`), which validates its shape against
`RoleBindings` (`src/tik/process.cljc:127`) and dies on a malformed file.
`ticket-ctx` (`cli/tik/cli_core.clj:478`) passes it through
`resolve-roles`, which overrides a definition role by role and takes no
`now`, so `--at` re-derives a past instant under today's membership (the
docstring of `resolve-roles` says so). `tik roles add` (`cmd-role`,
`cli/tik/admin.clj:148`) rewrites the file with `spit` and checks no
signature and no authority. `tik verify` re-derives each ticket under the
file (`cli/tik/audit.clj:1495`) and reports nothing about the file
itself. The derived-state cache keys a ticket's row on the file's hash
(`event-ids-fingerprint`, `cli/tik/cli_core.clj:649`). A bundle copies the
file (`cli/tik/audit.clj:1068`) and its README calls it "who was in which
role when this was bundled". `tik rederive` reads it with its own
`role-register` (`cli/tik/bundle.clj:723`), which answers `nil` for a file
that does not parse or validate, so a malformed register in a bundle
silently falls back to the definition's members, where the CLI's
docstring calls that fallback a widening of authority.

Neither store this machine holds has a register: on 2026-11-25 there was
no `roles.edn` in `~/git/github.com/metio/.tik` nor in
`~/git/git.infra.run/.tik` (checked read-only with `test -e`). Migration
therefore changes nothing for either store until the first role is
written.

### Why the file is not enough

A `[:signed-by role path]` guard compares the fact's `:by`, the event's
claimed actor, with the resolved members (`eval-signed-by`,
`src/tik/guard.cljc:124`); signatures are checked by `tik verify` alone.
An unsigned event is reported as "authenticity unclaimed, not failed"
(`cli/tik/audit.clj:1402`). Three things follow for an agent that can
write the store's files:

- it can add itself to `:maintainer` in `roles.edn`, which ADR 0038
  point 6 names;
- it can append a line binding its own key to the actor `seb` in
  `actors`: `ssh-keygen -Y verify` accepts several keys per principal,
  and the file is unsigned;
- it can write an unsigned event claiming the actor `seb`, which
  derivation credits and `verify` only notes.

Signing `roles.edn` closes the first and leaves the other two, which is
why point 6 also requires, for roles a definition reserves for humans, a
signature by a key the register pins. Under ADR 0038 agents write only
through `submit`, which verifies every signature before appending, so
the second and third close for the shared server; they stay open for a
local replica an agent can write.

### What `actors` can carry

`actors` is an allowed-signers file that `ssh-keygen -Y verify` reads
directly, and OpenSSH refuses a line with an option it does not know.
Checked on 2026-11-25 with OpenSSH 10.2p1: a line
`seb namespaces="tik-*",agent ssh-ed25519 …` made `ssh-keygen -Y verify`
print `bad options: unknown key option` and refuse a signature it
otherwise accepted. `tik.sshsig` refuses such a line too
(`grant`, `src/tik/sshsig.clj:319`). An agent attribute cannot live
there. Every line `tik actor add` writes carries `namespaces="tik-*"`
(`allowed-signers-line`, `src/tik/sign.clj:239`), and the same check
showed that pattern admitting a `tik-register` signature, so a separate
signature namespace would not restrict which keys may sign the register
either; only naming the keys does.

### Signed trust input elsewhere

Definitions are signed as sidecars in the namespace `tik-process`
(ADR 0015) and checked against `actors` (`definition-publishers`,
`cli/tik/cli_core.clj:709`). Key bindings are attestations on
identity-registry tickets, found by process name
(`compute-registry-events`, `cli/tik/cli_core.clj:516`) and each verified
against an issuer key pinned in the store (ADR 0023, ADR 0026). Batch
delegations are attestations on the same tickets
(`src/tik/delegation.cljc`), judged by `cli/tik/delegation_trust.clj`, and
travel in a bundle's `identity/` directory. The signature policy of
ADR 0036 is `signatures.edn`, read by `tik.sigpolicy`.

## Consequences

- **Every derivation reads the register ticket.** Resolving roles costs a
  fold over the register's events and an in-process signature check per
  unseen edit; a register holds tens of edits, against the thousands of
  events a `tik ls` already reads. Authenticated credit for `:humans`
  roles adds one signature check per fact such a guard reads, cached by
  event id. Neither cost was measured; the 10k-ticket benchmark behind
  the derived-state cache is where to measure it. The cache key moves
  from the file's hash to the register's heads, their sidecar names and
  the pinned root.
- **`--at` becomes faithful for membership.** A re-derivation at a past
  instant uses the register as it stood then, as far as claimed times
  allow (ADR 0012); a witness countersignature over the register's heads
  makes an authority's backdated edit detectable, as it does for every
  claimed time.
- **Omitting an event is the remaining rollback.** The log is grow-only
  and union-merged, so an older register cannot be restored by copying a
  file over a newer one. A replica that never received a removal still
  credits the removed member until it syncs. A witness over the
  register's heads, or a pinned head in `--expect-register-root`, bounds
  that; neither is part of the first version.
- **Bundles carry the register** in a `register/` directory, events and
  sidecars, in place of `roles.edn`. `verify.sh` checks the genesis
  self-signatures and each edit's signature with `ssh-keygen` against
  the authority keys the genesis and later authority facts name, and
  `tik rederive --expect-register-root` applies the reader's pin.
  `rederive` refuses a malformed register instead of falling back.
- **The `request` process's `:handlers` and the bridge roles** declare no
  `:admits`, so the register cannot add an agent to them until a
  definition version declares the role `:anyone` or `:agents`. Their
  members declared in the definitions keep counting as they do now.
- **`actors` stays unsigned.** Points 4 and 6 close the hole for roles
  reserved for humans. Moving rung-1 key grants into the same register
  log would close it for every actor, and is left to its own ADR.
- **Losing every root key loses the register.** No edit can then count,
  and recovery is a new register with a new root, which every reader's
  pin must accept by hand. Enrolling a second root key held offline is
  the mitigation.

The work lands as small pull requests, each with its own tests and every
signature rule with a negative case:

1. **Role kinds in definitions.** `:admits` in `ProcessDef`
   (`src/tik/process.cljc:41`), a pure `role-kinds` over a set of
   definitions, and lint for an unknown value and for kinds that differ
   across the store. Tests: each value; strictest wins; an undeclared
   role is `:humans`; a definition without `:admits` hashes as before
   (golden).
2. **The register reader, pure.** A `tik.register` namespace taking the
   register's events and the set of `[event-id key-fingerprint]` pairs
   that verified, and answering the authorities, the humans and the
   roles at an instant. Tests: a genesis missing one root's signature
   credits nothing; an edit by a non-authority, by an unknown key, and
   by an untouched signature where `:touch` is set each count for
   nothing; an edit the removal descends from keeps counting; a forked
   edit by a removed key does not; a conflict resolves to the
   intersection; `--at` before and after an edit; an agent named in a
   `:humans` role is dropped.
3. **CLI resolution and the commands.** `role-register` resolves the
   register and falls back to `roles.edn` with the note;
   `tik register init` and `tik register pin`; `tik roles add/remove`
   write signed edits. Tests: init imports a `roles.edn`; `roles add`
   refuses without a key, with a key that is no authority, and for an
   agent in a `:humans` role; the cache key changes when the register
   does.
4. **`tik verify`.** Every row of the table in point 7, each with a test.
5. **Authenticated credit.** The authenticated-events input to the
   kernel, `:role/unauthenticated` in `explain`, the reference kernel
   (`test/tik/reference.clj`) and a corpus case. Tests: an unsigned fact
   claiming a human fails the guard; a fact signed by an `actors` key the
   register does not pin fails; a pinned key passes; a definition
   without `:admits` derives as before.
6. **Bundles.** `register/`, `verify.sh`, `--expect-register-root` and the
   refusal of a malformed register. Tests: tampered edit, missing
   genesis signature, wrong pin, each failing both `verify.sh` and
   `rederive`.
7. **Post-quantum.** `:pq-key` in authority entries and the policy check
   of point 8, tested with an OpenSSL-made ML-DSA-65 signature and its
   negatives: missing sidecar after `:from`, sidecar by a key that is
   not the authority's.
8. **In the metio store, through tickets:** `tik register init` with the
   maintainer's security key, then `:admits :humans` on `:maintainer` in
   every metio definition through the version-bump gate, which routes
   only new tickets (ADR 0038 resolved question 7).

Step 3 also rewrites the register section of
`docs/content/authoring/roles-and-authority.md`, which documents
`tik roles add` writing the file.

`submit`'s check of point 6 belongs to ADR 0038's second and third steps
and needs only `tik.register` from step 2.

## Alternatives considered

- **A detached signature over `roles.edn`, checked against named
  authorities.** It is the smallest change and reads in O(1). It is
  declined on security: any writer can restore an older file with its
  valid signature, which re-admits a removed member unless every reader
  keeps a serial number it last saw, and the file carries no history, so
  `--at` stays present-tense. It also replicates poorly, since ADR 0038's
  replicas share only the event set, and scoping a dispatcher to some
  roles would need every verifier to diff against the previous file,
  which it does not have. Events give history, union merge, delegation
  through the existing attestation machinery and the ADR 0036 policy
  without a second signing path.
- **Register edits as attestations on the identity registry.** It would
  reuse the registry scan and the bundle's `identity/` directory. It is
  declined because the identity registry is any ticket pinned to its
  process, by design, and membership edits need one causal order: two
  edits on different tickets are always concurrent, so every overlap
  would conflict.
- **Authorities named by actor in `actors` or a list file in the store.**
  Simpler to read. Declined because whoever writes the store's files can
  append a key for that actor name, so the name roots nothing.
- **Authorities rooted only in a reader's pin, with no genesis.** Every
  reader would then need a configuration file before any register could
  count, including a bundle's reader. The genesis gives every reader the
  same default and the pin hardens it.
- **An agent attribute in `actors`, or agents recognised by name.** The
  first breaks `ssh-keygen` (verified above). A naming convention such as
  `claude*` is a claim anyone can make for a new actor.
- **Humans-only roles computed from fact paths**, marking the facts
  (`[:review :verdict]`) and deriving the roles whose guards name them.
  Equivalent in effect and more indirect, because the register edits
  roles and a role gating both kinds of fact would need the same answer
  anyway.
- **Humans-only marked in the register.** Declined because ADR 0038 asks
  that even a signed edit cannot hand an agent the maintainer's
  authority; a marking the authorities sign could be removed by the same
  signature.
- **Enforcement in `tik verify` only.** Keeps derivation unchanged, at the
  cost of `tik status`, `tik next` and every gate crediting a forged
  maintainer fact until somebody runs `verify`. The authenticated-events
  input keeps the kernel pure and applies only where a definition opted
  in with `:admits :humans`.
- **Conflicts resolving to no members, or to the union.** No members
  blocks every ticket of the role on a harmless concurrent edit; the
  union grants what one authority withheld. The intersection does
  neither.

## Open questions

1. **Where the pin lives for automation.** `tik-merge` and `tik-infra` run
   on the maintainer's machine and can read the user's configuration; a
   CI job verifying a bundle would pass `--expect-register-root`. Is a
   per-user file keyed by store path acceptable, or should the pin travel
   with the scripts?
2. **A threshold for authority changes.** With one maintainer, one
   authority signature changes the authority set. Should
   `[:register :authorities]` carry a threshold from the start, so that a
   second maintainer can later require two signatures to change it?
3. **A second root key.** Should the metio register enrol an offline
   backup security key as a second root at `init`, since losing the only
   root key loses the register?
4. **`:touch true` for the metio authority.** Proposed yes, matching
   ADR 0037's setting for delegations.
5. **Authenticated credit in derivation.** Point 6 changes what
   `[:signed-by]` means for opted-in roles. Is the cost of a signature
   check per maintainer fact in every lens acceptable, or should it stay
   a `verify` rule?
6. **Undeclared roles default to `:humans`.** It is the secure default and
   means `request`'s `:handlers` cannot take a register-added agent until
   a definition version declares it. Is that the intended friction?
7. **Rung-1 key grants into the register.** Should the follow-up ADR that
   moves `actors` into the same log come before the shared server, or
   after?
