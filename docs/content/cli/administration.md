---
title: Store administration
description: Identity, roles, verification, migration, storage backends, and alerts.
tags: [cli, verify, roles, migration, effects]
---

## Identity

```sh
tik actor add alice ~/.config/tik/id_ed25519.pub
tik sign 3184
tik witness 3184
```

`actor add` registers a signer in the store's allowed-signers registry. An
actor name is letters, digits and `._@+-`, and the key file holds one
OpenSSH public key on one line; anything else is refused, because the name
and key are written verbatim into the registry, where `*`, `?`, `!` and `,`
are principal patterns and a line break starts another grant. A rung-2
binding (`tik bridge oidc`, `tik bridge workload`) whose actor or key fails
the same check grants nothing.

The registry may also be edited by hand, with OpenSSH's allowed-signers
options. tik reads Ed25519 signatures in process and honours them as
`ssh-keygen` does: `namespaces="…"` is a pattern list where `*` and `?` are
wildcards and a matching `!pattern` refuses, a `cert-authority` line never
accepts a direct signature by the CA key, and a line with an option OpenSSH
does not know grants nothing. A line carrying `valid-before` or
`valid-after` depends on the time of the check, so `verify` hands it to
`ssh-keygen`, and reports the signature unchecked on a host without one.

A hardware security key works as a signer too. Generate a key that lives
on the device, register its public half, and point `TIK_KEY` at the key
handle; every signature then needs a touch, and with `-O verify-required`
the device PIN as well:

```sh
ssh-keygen -t ed25519-sk -O verify-required -f ~/.config/tik/id_ed25519_sk
tik actor add alice ~/.config/tik/id_ed25519_sk.pub
```

tik verifies these signatures in process and counts one only when the
device reports that a user was present, a flag inside the signed bytes.
`ssh-keygen -Y verify` accepts a security-key signature made without a
touch, so `verify` and the bundle's `verify.sh` read the flag themselves.

Exporting `TIK_KEY` signs every write as it happens; `sign` catches up on
your own earlier events, and only your own — signing somebody else's would
assert something false. `witness` countersigns a ticket's head, and one
signature timestamps the entire ancestry beneath it.

## Roles

```sh
tik roles
tik roles add triager alice
tik roles remove triager bob
```

A process definition declares which roles exist and who starts in them; the
store's register decides who is in one today, for every ticket at once. Use
it for a hire or a departure — it takes effect on in-flight tickets
immediately, with no definition version bump and no per-ticket migration.
See [Roles and authority](/authoring/roles-and-authority/).

## Verification

```sh
tik verify
tik verify --changed
tik root --witness
```

`verify` runs the ladder: content addressing, schema, signatures, and
re-derivation. `--changed` skips unchanged heads for a fast drift check
rather than the full audit. `root` prints one hash committing to the entire
store, optionally countersigned, optionally anchored to a third-party
timestamp.

A review verdict can also be signed by a throwaway key the reviewer
delegated to for one batch (ADR 0037). The delegation is an attestation
on the identity registry:

```clojure
{:claim :delegation
 :delegation/public-key "ssh-ed25519 AAAA…"
 :delegation/not-after #inst "2026-11-23T20:04:00Z"
 :delegation/scope #{[#uuid "…" "sha256-…"]}
 :delegation/facts #{[:review :verdict]}}
```

`verify` credits a signature by that key to the reviewer only when the
delegation is signed with a key `actors` grants the reviewer, and the
verdict lies inside it. The verdict must name the reviewer as its actor,
be claimed no more than five minutes after the delegation, and sit on a
ticket and head pair the scope lists. It must also assert a fact path
the delegation allows. A key granted through an identity binding or
another delegation cannot delegate. With
`{:delegation {:require-touch true}}` in `oidc.edn`, the delegation must
come from a touched security key. A delegation that credits nothing is
named in the output with the reason.

An event can also carry a post-quantum signature, an ML-DSA-65 sidecar
`<id>.pqsig.<fpr>` beside its SSHSIG one. `verify` checks every such
sidecar against `pq-signers` in the store root, which holds one line per
key:

```text
seb namespaces="tik-*" ml-dsa-65 MIIHsjALBglghkgBZQMEAxID…
```

The key is the base64 DER SubjectPublicKeyInfo, and `<fpr>` is the hex
SHA-256 of that DER. `namespaces` takes comma-separated patterns where
`*` is the only wildcard and every other character, brackets included,
stands for itself. A sidecar that does not verify, names a key
`pq-signers` lacks, or a key registered for another actor fails the
audit, and so does a malformed line. A store without the file is
checked as before.

To sign with ML-DSA-65, make a key with OpenSSL 3.5 or later, register
its public half, and point `TIK_PQ_KEY` at the private key:

```sh
openssl genpkey -algorithm ML-DSA-65 -out ~/.config/tik/id_ml_dsa_65.pem
echo "seb namespaces=\"tik-*\" ml-dsa-65 $(openssl pkey -in ~/.config/tik/id_ml_dsa_65.pem -pubout -outform DER | base64 -w0)" >> pq-signers
export TIK_PQ_KEY=~/.config/tik/id_ml_dsa_65.pem
```

Every write then carries both signatures. A write whose `TIK_PQ_KEY`
`pq-signers` does not grant the actor is refused before anything is
stored. `tik sign <id>` with `TIK_PQ_KEY` adds the sidecar to your own
events that lack one, which is how events written earlier gain it.

### Requiring post-quantum signatures

`signatures.edn` in the store root makes the second signature a
requirement from a chosen instant on:

```clojure
{:require #{:ssh-ed25519 :ml-dsa-65} :from #inst "2026-12-01T00:00:00Z"}
```

An event claimed at or after `:from` must carry a verifying signature by
its own actor for every algorithm in `:require`. An event claimed before
it needs the classical signature only, and must be anchored: it or a
later event in its ticket carries an ML-DSA-65 witness countersignature,
which seals the whole ancestry. Once Ed25519 can be forged, anyone can
write a new event with an old instant; the anchor tells that event apart
from the history that existed when the policy took effect. `:require`
always keeps `:ssh-ed25519`, so the scheme stays hybrid, and a file that
does not read as exactly this shape fails `verify` rather than counting
as no policy.

Switch it on in this order, with a key `pq-signers` allows to witness
(the `tik-*` pattern covers both `tik-event` and `tik-witness`):

```sh
echo '{:require #{:ssh-ed25519 :ml-dsa-65} :from #inst "2026-12-01T00:00:00Z"}' > signatures.edn
TIK_PQ_KEY=~/.config/tik/id_ml_dsa_65.pem tik anchor
tik verify
```

`tik anchor` writes `<head>.pqwitness.<fpr>` on every head of every
ticket. Anchor again whenever events claimed before `:from` were written
after the last anchor; `verify` names each event no anchor covers. Under
the policy, a write without `TIK_KEY` or, from `:from` on, without
`TIK_PQ_KEY` is refused before anything is stored. `tik review --batch`
meets the policy when `TIK_PQ_KEY` is set: its verdicts carry the
throwaway key's delegated Ed25519 signature and an ML-DSA-65 signature by
the reviewer's own key.

## Moving tickets to newer rules

```sh
tik reprocess 3184 processes/support-request.edn
tik reprocess 3184 processes/support-request.edn --apply
```

A ticket is judged by the rules it was minted under, so a definition that
grows leaves existing tickets deriving under the old one until you move
them — deliberately, because an implicit upgrade would re-judge a whole
store on an edit. The dry run prints the pinned-versus-proposed hashes,
which stages would be gained or would regress, and the new blockers.
`--apply` records the re-pin as a signed event, so the log keeps which
rules judged the ticket when.

## Storage

```sh
tik init --sqlite
tik store migrate --to file
tik export ./audit-copy
tik pack
tik gc --apply
```

The file store is the signed interchange format; SQLite keeps the same
events in one file. Migration is lossless in both directions — events,
their detached signatures and the attached blobs travel together. A file
store keeps a ticket's blobs in `tickets/<id>/blobs/`; a SQLite store keeps
them in `blobs/<id>/` beside `tik.db`. `export` materializes any store
as the file format. `pack` consolidates settled tickets into one
content-addressed pack each, and `gc` removes archived definitions no
ticket pins any more and no named definition is at, together with their
publication signatures.

## Handing a ticket to someone else

```sh
tik bundle 3184 --out evidence.tgz
tik import evidence.tgz
tik rederive evidence.tgz
```

`bundle` packs one ticket as an [evidence bundle](/evidence/): the events,
their signatures, the pinned definition and the registry, checkable with
coreutils. `import` brings someone else's events into this store, merging
by content address so importing twice is a no-op. `rederive` recomputes
what a bundle's facts imply without importing anything.

## Alerts out, mail in

```sh
tik effects run --config effects.edn --dry-run
tik bridge email < message.eml
```

`effects run` pushes derived stage transitions to Slack, Discord, Matrix,
Teams, ntfy, PagerDuty, plain webhooks, email, or any program through the
command sink. Any sink field may be a secret resolved at send time — from
an environment variable, a file, a command, or a systemd credential — so
no secret sits in the config file.

The email bridge turns messages into tickets and comments, and replies
carrying `tik> key=value` lines become facts, which closes the loop for
people who never leave their inbox.

## The gated agent surface

```sh
tik agent actions 3184 --actor bot
tik agent set 3184 severity=:high --actor bot
```

An agent sees only what the frontier admits for its role, and anything else
is refused with the derived reason. The boundary is the same derivation
everyone else reads, so it cannot be talked around.
