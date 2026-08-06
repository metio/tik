---
title: Bundle format
description: tik-evidence-bundle version 1 — the on-disk contract, the checks a reader runs, and the rules that govern change.
tags: [evidence, bundle, format, specification, verification]
---

`tik-evidence-bundle`, version 1. A gzipped tar holding one ticket and
everything its derivation needs.

```sh
tik bundle <id> --out evidence.tgz
```

```text
bundle.edn                          the format name and version
actors                              the allowed-signers registry
roles.edn                           role membership, where the store keeps a register
identity/<event-id>.edn             key bindings, as signed registry events
identity/<event-id>.sig.<fpr>       their authorship signatures
identity/keys/<kid>.pem             the issuer keys those bindings rest on
processes/by-hash/<hash>.edn        the definition the ticket pinned
processes/by-hash/<hash>.sig.<fpr>  its publication signatures
tickets/<uuid>/events/<id>.edn      the append-only log
tickets/<uuid>/events/<id>.sig.<fpr>      authorship signatures
tickets/<uuid>/events/<id>.witness.<fpr>  countersignatures over a head
tickets/<uuid>/blobs/<hash>         attached artifacts, addressed by content
verify.sh                           a coreutils implementation of the checks
README.md                           what the recipient is holding
```

## What a reader may assume

**Every stored file's name is the SHA-256 of its bytes**, prefixed
`sha256-`. Event and definition files carry a `.edn` suffix after the
address; blobs are named by the bare address. `sha256sum` alone audits
the archive.

**Event bytes are exactly the hashed region.** An event file holds the
canonical serialization *without* its `:event/id`, because the filename
is the id. Re-emitting a parsed event reproduces the file byte for byte.

**Signatures are detached siblings.** `<id>.sig.<fingerprint>` covers the
event's stored bytes under the `tik-event` namespace; `<id>.witness.<fpr>`
covers a head under `tik-witness`; a definition's `.sig.<fpr>` uses
`tik-process`. Each verifies with `ssh-keygen -Y verify` against
`actors`.

**Exactly one ticket directory, with exactly one root event.** Every
parent an event names is present in the same directory, so the Merkle DAG
the log commits to is complete.

**The pinned definition travels.** The create event carries
`:ticket/process-hash`, and `processes/by-hash/<that hash>.edn` holds the
definition whose content address equals it.

**`identity/` holds excerpts.** A key binding travels as the single
registry event that made it, with its own authorship signature and the
issuer's public key. Its parents live in a registry ticket that stays
behind, so its standing rests on its content address plus the issuer's
signature over the id-token it carries.

**`bundle.edn` declares the packaging version and nothing else.** The
ticket, the head, the definition and the stage are derivable from the
files, so the manifest names none of them and nothing in it is trusted.
A bundle without a manifest is version 1.

## The checks

These are what the format promises. `verify.sh` implements them with
coreutils, `ssh-keygen` and — for key bindings — `openssl`;
[`tik rederive`](/evidence/re-deriving/) implements them in process. The
script travels inside the archive it checks, so read it before you run
it, or run this list yourself.

1. **Integrity.** Every `tickets/*/events/*.edn`,
   `processes/by-hash/*.edn` and `tickets/*/blobs/*` hashes to its own
   name.
2. **Shape.** Exactly one ticket directory.
3. **Completeness.** Every parent referenced by an event is present, and
   exactly one event has empty `:event/parents`.
4. **Authorship.** Every `.sig.` sidecar verifies *as the actor its own
   event names* — binding `-I` to `:event/actor` rather than accepting
   whoever happened to sign, so a registered actor cannot sign another's
   authorship.
5. **Countersignature.** Every `.witness.` sidecar verifies as a
   registered principal under `tik-witness`.
6. **Key bindings.** For each binding in `identity/`: the issuer signed
   the id-token, using the key at `identity/keys/<kid>.pem`; the token's
   `sub` and `iss` equal the binding's; the token was live at the
   binding's `:event/at`; and the key it binds appears in `actors`.

Check 6 is checkable offline because the issuer's key travels in the
archive, and it says exactly this much: *whoever built this bundle holds
a token that this key signed, for this subject, at that moment.*
Recognizing the key as the issuer's is the reader's job, the same way
recognizing an `actors` line is — a subject like
`repo:metio/tik:ref:refs/heads/main` is worth what the reader knows about
that repository and that IdP.

An unsigned event is authenticity unclaimed rather than a failure, and a
binding whose issuer key is absent grants nothing and is reported as
such. Everything else in the list is a failure.

Deriving the ticket's stages needs the definition as well, which is where
`tik rederive` goes on and `verify.sh` stops.

## Compatibility

Version 1 is the layout above. The rules below are what a reader written
against it may rely on.

**A reader ignores what it does not recognize.** Unknown files,
directories and manifest keys are passed over. That is what makes the
additions in the next paragraph safe.

**These may appear without a version bump:**

- new top-level files and directories;
- new files inside existing directories, including new sidecar kinds
  (`<id>.<kind>.<fingerprint>`);
- new keys in `bundle.edn`;
- new event types, fact paths, guard operators and process keys inside
  the files, which are versioned by the kernel's own vocabularies rather
  than by this format;
- any change to `verify.sh` and `README.md`, which carry no contract of
  their own.

**These force a version bump:**

- moving, renaming or removing anything the layout names;
- changing what a name means — a different hash function, a different
  suffix convention, a signature namespace;
- changing the canonical serialization, which changes every event id and
  is versioned separately by `tik.canonical/format-version`;
- carrying more than one ticket;
- anything else that makes a conforming version-1 reader compute a
  different answer or fail.

A reader holding a bundle whose major version it does not know declines
it and says so.

## Reading a bundle yourself

The archive unpacks to plain files, so an implementation in any language
is a hash, a signature check and an EDN reader.

```sh
mkdir bundle && tar xzf evidence.tgz -C bundle && cd bundle

# 1. integrity, without any tool of ours
sha256sum tickets/*/events/*.edn | while read sum f; do
  [ "sha256-$sum" = "$(basename "$f" .edn)" ] || echo "MISMATCH $f"
done

# 2. authorship, as the actor each event names
for sig in tickets/*/events/*.sig.*; do
  ev="${sig%%.sig.*}.edn"
  actor=$(sed -n 's/^{:event\/actor "\([^"]*\)".*/\1/p' "$ev")
  ssh-keygen -Y verify -f actors -I "$actor" -n tik-event -s "$sig" < "$ev"
done
```

Canonical bytes sort map keys, so `:event/actor` always leads an event
file — which is why one `sed` finds it.

## Relationship to in-toto

[in-toto](https://in-toto.io/) attestations describe a build: this
subject, this predicate, signed by this functionary. A bundle carries a
different kind of thing — the facts, the rules that judge them, and the
means to recompute the verdict offline. in-toto's nearest analogue is the
layout, and a tik process definition already is one.

The two compose. `tik bridge intoto` reads a SLSA provenance, an SPDX or
CycloneDX SBOM, or a vulnerability report into an attestation event, so
an `:attested-within` guard is satisfied by the format your pipeline
already emits, and the result travels in a bundle.
