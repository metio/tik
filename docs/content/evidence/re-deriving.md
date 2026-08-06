---
title: Re-deriving a bundle
description: tik rederive checks a bundle and recomputes what its facts imply — at a terminal, or over HTTP with a badge that names the derivation.
tags: [evidence, bundle, badge, verification, cli]
---

`verify.sh` answers one question: are these bytes what they claim to be?
`tik rederive` answers the other one: what do they add up to?

```sh
tik rederive evidence.tgz
```

```text
bundle sha256-04a0bf413895fefb1c20e9231632fbc0d0a0d768275b42c097581ed19365973a
  format    tik-evidence-bundle version 1
  ticket    e26a1d57-6643-4564-99fa-8968ced7afa5  "2026.8.6132954"
  events    10
  judged by release sha256-3c577f361a46ec2166b7f4d6fb875d2de4e317f478d425eb4ae39f77fdcb9aca

verification
  ok   … hashes to its name and is schema-valid
  ok   every referenced parent is present
  ok   exactly one root event
  ok   … verifies as ci
  ok   ci is bound to its key by repo:metio/tik:ref:refs/heads/main, per …
  => the bytes are what they claim to be

derivation at 2026-08-06T20:29:26.198Z
  reached   :attested, :built, :scanned
  current   :attested, :scanned
  :built
      ✓ [:fact [:version]]
      ✓ [:signed-by :ci [:commit]]
      ✓ [:artifact "checksums/"]
  :scanned
      ✓ [:attested-within :vulnerability-scan "P1D"]
```

It takes a `.tgz`, an unpacked directory, or an `https` URL, and exits 1
when the bundle fails to verify — a derivation over bytes that are not
what they claim to be is worth nothing. `--edn` prints the same result as
data.

## Derivation names its instant

`reached` above is what the pinned rules grant **at the instant printed
under it**. The release process demands a vulnerability scan attested
within a day; a month from now the same archive, byte for byte, derives
`:built` alone — the scan it carries is real, signed, and too old for the
rule that reads it.

That is the reason to re-derive rather than record. A stored "passed"
survives its own evidence; a derivation ages with it.

## The service

```sh
tik rederive --serve --port 7788
```

```text
POST /rederive                     the bundle as the request body -> EDN
GET  /derivation?bundle=<url>      fetch it, re-derive, render the page
GET  /badge.svg?bundle=<url>       the same, as a badge
```

Every request re-derives. The one thing held between requests is a cache
keyed by the bundle's content address *and* the minute the answer was
computed for — both inputs to the derivation, both immutable, so an entry
is either exactly right or absent and the whole cache is disposable.

The service fetches `https` URLs whose every resolved address is public,
follows at most four redirects, checking each hop, and caps what it will
download. It runs nothing from inside a bundle: the `verify.sh` that
travels in an archive is a convenience for whoever chooses to run it, and
the service implements the checks itself.

Point a README badge at a release artifact:

```markdown
[![evidence](https://your-host/badge.svg?bundle=https://github.com/you/proj/releases/download/v1/evidence.tgz)](https://your-host/derivation?bundle=https://github.com/you/proj/releases/download/v1/evidence.tgz)
```

## What a badge says

The stages the pinned definition grants, and which definition granted
them: `release@3c577f36 · :attested · :scanned`. The badge links to a
page carrying the guards that held, the ones that did not, who may sign
in this bundle and on whose say-so, and the two commands that reach the
same answer with the service switched off.

A badge is a derived conclusion displayed as though it were a fact, which
is the one thing [derived beats
declared](/concepts/derived-beats-declared/) forbids storing. Three
properties keep it honest:

- **it is derived per request**, so it is a lens rather than a status;
- **it names the derivation** — stages, definition hash, instant — where
  a word like *compliant* would name a policy it cannot see;
- **the service is disposable.** Delete it and the bundle still checks
  out with coreutils. A verification service that becomes the authority
  has re-introduced exactly the trust the bundle removes.

The colour follows the same rule: one shade for a bundle that verifies,
red for one that does not. Green would read as approval, and the badge
reports a derivation, which is a different kind of claim.

## Publishing bundles from CI

The wedge is one line over evidence a pipeline already produces. tik's own
release workflow records the version, the commit, the image digest and
the checksums file as signed facts, imports the SBOM, provenance and scan
as attestations through `tik bridge intoto`, and ships the result:

```sh
tik bundle "$TICKET" --out "evidence-$VERSION.tgz"
```

CI signs with an ephemeral key bound to its workload identity ([ADR
0023](/decisions/0023-key-bindings-are-evidence/)), so the bundle carries
the binding and its issuer's key, and a recipient re-earns that trust from
the issuer's own signature.
