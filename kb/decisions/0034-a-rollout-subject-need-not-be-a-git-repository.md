---
type: decision
status: accepted
date: 2026-11-22
title: A rollout takes its members from a declared population, and a subject needs no git repository
supersedes: null
---

# ADR 0034: A rollout subject need not be a git repository

## Decision

**`rollout` mints one ticket per member of a population, and a member is a
subject string with an optional directory.** Without a population the
members are the git repositories under the store's holder, exactly as
before. A population is declared by name in `populations.edn` in the store
root, as a `:list` of members or a `:command` printing `subject[<TAB>dir]`
lines, and is chosen with `--population` or the definition's
`:population`.

1. **The definition names the subject fact.** `:subject` (a keyword or a
   path) is the fact every child carries: `[:repo]` for the git walk and
   `[:project]` for a population unless the definition says otherwise. A
   member with a directory below the holder also gets `[:repo]`, the
   directory its probe runs in.
2. **Populations are porcelain configuration.** Only `rollout` reads
   `populations.edn`, at the moment it runs. No guard reads it, and no
   ticket stores a reference to it, so editing a population changes which
   tickets the next run mints or retires and never what an existing
   ticket derives.
3. **An unanswered question never reads as an empty population.** A
   failing population command stops the run before anything is written. A
   member whose directory is missing or outside the holder is reported,
   left out of the run, never retired, and fails the run.
4. **Ids stay a pure function of intent (ADR 0021).** A `[:repo]` subject
   keeps the id inputs the git walk always used, so replicas on different
   tik versions converge. Any other subject path joins the inputs, so one
   definition rolled out over the git walk and over a population never
   merges `aro` the repository with `aro` the project into one ticket.
5. **`probe` follows the subject.** A ticket without `[:repo]` is probed
   from the holder, with `TIK_SUBJECT` set, only when its definition
   declares a `:subject` and the ticket carries that fact. A ticket with
   neither stays unprobeable, so a change minted with `--no-repo` never
   runs a probe that assumes a repository.

## Context

`rollout` and `probe` assumed that every subject of standing work is a git
repository below the store. Work about hosts, products or tenants had no
way in, and a store holder without git could not use either verb.

Two alternatives were rejected. A store-wide register of projects read by
guards would let a value changed in one file move stages on in-flight
tickets and would stop a bundle from re-deriving on its own (ADR 0004
scope, ADR 0030). A built-in source per inventory (aro, NetBox, kajero)
would couple tik to each of them, while a command with a fixed output
contract composes with all of them: `aro list | cut -f1,4` adapts aro's
listing in the shell.

## Consequences

- A population's members enter tickets only as facts `rollout` asserts,
  signed by the actor running it, so the log keeps what each ticket was
  about.
- `rollout`'s parent checklist still uses `[:link <subject>]` facts. ADR
  0029's relation events would replace them; that change is independent of
  populations.
- A population command runs with the privileges of whoever runs
  `rollout`, like `:scope` and `:probe` already do.
