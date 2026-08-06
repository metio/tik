---
type: decision
status: accepted
date: 2026-08-06
title: The evidence bundle is a named, versioned format, and re-derivation is a command before it is a service
supersedes: null
---

# ADR 0025: The evidence bundle is a named, versioned format

## Decision

**The bundle is `tik-evidence-bundle`, version 1, and version 1 is what
already shipped.** The format published today is the layout the release
pipeline has been producing, so every bundle in the wild is conformant
and nothing has to be re-issued.

**A `bundle.edn` manifest declares the format name and the version, and
nothing else.** The ticket, the head, the definition and the stage are
all derivable from the files; a manifest that carried them would be
inviting a reader to believe the producer instead of the evidence, which
is the one thing a bundle exists to make unnecessary. A bundle without a
manifest is version 1 — the baseline predates it.

**The CHECKS are normative; `verify.sh` is one implementation of them
that happens to travel inside the archive it checks.** A reader who runs
a stranger's script has run a stranger's code, so the checks are
published as a list anyone can implement, and `tik rederive` implements
them without executing anything from the bundle.

**Re-derivation is `tik rederive`, and the service is that command in a
loop.** `verify.sh` proves the bytes are genuine with coreutils alone;
saying what they ADD UP TO means running the pinned definition, which is
what tik is. The HTTP face renders the same derivation as a badge and a
page, and both print the commands that reach it without the service
running.

**A badge names the derivation and never grades it**: the stages the
pinned rules grant, the definition hash that judged them, and the instant
the answer was computed. Not "compliant", which means nothing until it
names a policy.

**A derivation may be cached on the bundle's content address AND the
minute it was computed for — never on content alone.** Derivation is a
function of `(events, now)`. A guard with a freshness window is satisfied
by evidence that is fresh and unsatisfied by the same bytes later, so
memoizing on bytes alone would serve a stale answer with no way to
notice. Keyed on both, an entry cannot go stale: every input is in the
key, and the whole cache can be dropped at any moment with nothing lost
but time (ADR 0013).

**A definition's schemas must be data.** `tik.process/schema-registry` is
malli's default registry minus `:fn` and `:multi`, the two schemas that
compile a child through `m/eval`. Derivation cannot evaluate a schema
outside it — the reason is `:schema/unsupported` — and `tik lint`
reports one as an error.

## Context

The bundle was already the artifact; what it lacked was a name, a version
and a written contract. The pressure to write one came from the release
pipeline: `evidence-<version>.tgz` now ships on every release, `tik
import` reads it, and the first outside reader will need to know what
they may assume.

The bundle carries a different KIND of thing from an in-toto statement,
which is why this is not a competing envelope. A statement attests facts
about a build; a bundle carries the facts, the hash-pinned rules that
judge them, and the means to recompute the verdict offline. in-toto's
nearest analogue is the layout, and a process definition already is one.
`tik bridge intoto` reads their format; this publishes ours.

Three findings came out of building it, none of them anticipated.

**A cache keyed by content alone is wrong.** The idea as banked said the
content hash "cannot go stale by construction". That holds only for
time-independent guards. `:attested-within` and `:elapsed-since` make the
instant a second input, and the release process uses both — so the key
had to carry the minute as well. That is not a weakening: it is what
makes the memo a memo rather than a stored verdict.

**The role register decides derivations, so it travels.** `:signed-by`
resolves a role to its members, and a store keeping a `roles.edn`
overrides what the definition declares. A bundle without it re-derives
under different membership than the store it came from, which is
precisely the drift evidence exists to rule out.

**A definition is read from material a stranger produced.** A `:malli`
guard carrying `[:fn "(fn [_] …)"]` executes that string on any runtime
with sci — which babashka is. Nothing shipped uses `:fn`, and nothing
could legitimately: a schema that calls a function is not offline, not
reproducible, and not a pure function of `(state, now, reached)`. The
registry restriction enforces a law that was already written rather than
adding one.

## Consequences

- `tik bundle` writes `bundle.edn` and carries `roles.edn` when the store
  keeps one. Both are additive; a version-1 reader that ignores what it
  does not recognize is unaffected, which is the compatibility rule the
  format states.
- `tik rederive <bundle|url> [--edn]` checks and re-derives; `--serve`
  runs it over HTTP with `POST /rederive`, `GET /derivation?bundle=…`
  and `GET /badge.svg?bundle=…`.
- The reader treats the archive as hostile: `tik.bundle/untar-gz!`
  unpacks it instead of `tar`, refusing absolute paths, traversal,
  symlinks and archives that unpack without bound. `tik import` uses the
  same unpacker, because it reads other people's archives too.
- The service fetches only `https` URLs whose every resolved address is
  public, and follows redirects one hop at a time so a redirect into a
  private network cannot defeat a check made on the first URL.
- A definition carrying `[:fn …]` or `[:multi …]` stops deriving. Nothing
  in the corpus, the shipped processes or any authored definition uses
  one, so no pinned definition changes meaning — but this is a semantic
  narrowing and it is recorded here rather than in a changelog line.
- The badge's honesty is load-bearing and fragile. It reports the stage a
  freshness-windowed guard grants TODAY, which means a release badge can
  show fewer stages next month on the same evidence. That is the evidence
  aging, said out loud, and any pressure to make the badge "stable" is
  pressure to store a derived value.
- Adoption remains the unsolved half. A format nobody publishes verifies
  nothing, and the only wedge is one-line CI integration over evidence a
  project already produces. `release.yml` demonstrates it; whether anyone
  else adopts it is not a design question and this decision does not
  pretend to settle it.
