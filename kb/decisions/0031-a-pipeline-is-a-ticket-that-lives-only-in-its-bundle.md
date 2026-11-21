---
type: decision
status: accepted
date: 2026-11-22
title: A pipeline run is one ticket in a throwaway store, its jobs bind their own keys, and the bundle it publishes is the only record
supersedes: null
---

# ADR 0031: A pipeline is a ticket that lives only in its bundle

## Decision

**A CI pipeline can use tik without a store anyone keeps.** The run
creates a throwaway store, mints one ticket against a process definition
that the repository pins by hash, records each job's evidence as signed
facts, and publishes `tik bundle` of that ticket next to the release
artifacts. The bundle is the only record that survives the run, and
`tik rederive --expect-definition --expect-stage` is how anyone checks it.
No new kernel concept is needed: this is the release pipeline of this
repository with the store made ephemeral, and the rules below are what
makes that safe.

1. **The trust anchors are pinned in the repository, never fetched by the
   run.** The process definition, the issuer keys under `jwks/`, the
   binding policy in `oidc.edn` (ADR 0026) and `roles.edn` come from the
   commit being built. A run that fetched them would let whoever controls
   the network choose the rules that judge it. `tik init --from <dir>`
   seeds a fresh store from those files and refuses one that does not
   lint.
2. **Every job is its own actor and binds its own key.** A job generates
   a keypair, binds it with `tik bridge workload --github` against its own
   OIDC token, and signs only its own facts. The binding policy names
   the subject that may bind each actor, and it matches the token's `sub`
   claim and nothing else. GitHub's default `sub` names the repository
   and the ref, not the workflow or the job, so on its own it cannot tell
   two jobs of one run apart. A pipeline that wants one actor per job
   either customizes the repository's subject claim to include the job's
   workflow reference (GitHub offers this per repository; check its
   current documentation before relying on the exact template), or tik
   grows policy matching on further claims such as `job_workflow_ref`,
   which would be its own decision. A pipeline whose jobs share one
   actor proves only that the workflow ran, not which step did what.
3. **Jobs hand over events, not stores.** A job uploads its signed events
   as a CI artifact (`tik export`), and a final job `tik import`s them all
   into one store, where the union by content address makes the order of
   arrival and duplicates irrelevant. That final job derives and bundles;
   it holds no authority the others lack, and a forged import fails
   `tik verify` like any other forgery.
4. **The stage is what the definition derives from the bundle, and only
   that.** A consumer pins the definition hash and the stage it requires.
   The pipeline never writes "passed"; a skipped job leaves its stage
   underived, and the bundle says less, which is the true answer.
5. **A bundle verifies offline forever.** It carries the pinned issuer
   keys and the tokens that bound each key, so checking it needs neither
   GitHub nor the network (ADR 0023, ADR 0025). Freshness guards
   (`:attested-within`) still judge at the instant of the check, so a
   consumer that wants "built within 90 days" gets it from the same bytes.

## Context

The idea came from wanting tik's signature, evidence and process model for
one pipeline at a time: the process says which steps a release needs, the
jobs prove with their workload identity that each step ran, and the
signature travels with the artifacts. Most of that exists. The release
workflow of this repository already generates a key per run, binds it to
the workflow's OIDC token, records the release as a ticket and publishes
`evidence-<version>.tgz` with the release. What it does not do is stand
alone: the store is this repository, so the pipeline depends on the
repository being a tik store, and every step runs in one job as one actor.

A store-less mode removes the first dependency and fixes the second. A
repository that is not a tik store pins a definition and the issuer keys
it trusts, and gets the same evidence. Splitting the actors per job lets a
definition say "the build job and the scan job are different identities",
which is the property a supply-chain reader actually wants.

The overlap with SLSA provenance and in-toto attestations is deliberate
and bounded. Those formats describe what was built from what; tik
describes which steps of a declared process have evidence and who signed
each. The `supply-chain-release` process already attests SBOM and
provenance as claims without reading them, and a pipeline-mode ticket can
carry an in-toto statement's digest as a fact in the same way. tik does
not become an in-toto verifier.

## Consequences

- `tik init --from <dir>` is new porcelain: it copies a pinned
  definition, `jwks/`, `oidc.edn` and `roles.edn` into a fresh store and
  lints them. Everything else (workload binding, export, import, bundle,
  rederive) exists today.
- A definition written for pipeline mode names one role per job, with the
  binding policy as the only way into each role. Its tests need a negative
  case per job: a fact signed by another job's actor does not satisfy the
  guard.
- Issuer key rotation is handled the way ADR 0027 already handles it: a
  run pins the keys it trusts at its commit, and re-pinning merges by
  `kid`, so a bundle signed under an older key stays checkable.
- Open: whether the policy should match claims beyond `sub` (today it
  does not, `cli/tik/identity_trust.clj`); how a reusable workflow called from
  another repository is named in the policy; and whether a pipeline that
  spans several workflow runs (a release triggered by a tag after a
  separate build) is one ticket or two linked ones. Each needs an answer
  before a definition depends on it.
