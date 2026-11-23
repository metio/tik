---
type: decision
status: proposed
date: 2026-11-23
title: Post-quantum signatures arrive as a hybrid ML-DSA-65 sidecar beside SSHSIG
supersedes: null
---

# ADR 0036: Post-quantum signatures are hybrid

## Decision

Proposed, not yet accepted: nothing described here is implemented.

**A store opts into post-quantum authenticity by requiring a second
signature per event, made with ML-DSA-65 (FIPS 204), beside the SSHSIG
Ed25519 signature it already carries.** An event counts as signed by an
actor only when every signature the store's policy requires verifies, so
the store keeps classical security if ML-DSA is broken and gains
post-quantum security if Ed25519 is.

1. **A closed algorithm set per format version.** Following ADR 0006 rule
   4, a format version names exactly the signature algorithms a verifier
   implements: today `ssh-ed25519` through SSHSIG, and from the next
   version also `ml-dsa-65`. A verifier never dispatches on whatever an
   algorithm tag says.
2. **A sidecar of its own.** OpenSSH has no post-quantum signature key
   type, so the ML-DSA signature cannot travel as SSHSIG. It is stored as
   `<id>.pqsig.<fpr>`, the raw FIPS 204 signature over the bytes
   `tik-event-v1\0` followed by the event's stored bytes. The prefix
   separates this use of the key from any other. The `.sig.` sidecar is
   unchanged, so an existing verifier still checks the classical half.
3. **A registry of its own.** `pq-signers` in the store root holds lines
   `<actor> namespaces="…" ml-dsa-65 <base64 SubjectPublicKeyInfo>`. It is
   kept out of `actors` because `ssh-keygen -Y verify` reads `actors` as an
   allowed-signers file, and a line it cannot parse must not change what
   it does with the others. `<fpr>` is the hex SHA-256 of the DER
   SubjectPublicKeyInfo.
4. **Policy in the store.** `signatures.edn` declares
   `{:require #{:ssh-ed25519 :ml-dsa-65} :from "<instant>"}`. Events
   claimed before `:from` need only the classical signature; events after
   it need both. A store without the file behaves as today.
5. **Signing in process.** `TIK_PQ_KEY` names a PKCS#8 PEM private key,
   and tik signs with the JDK's `ML-DSA` provider. `ssh-keygen` remains
   the Ed25519 signer.
6. **Checkable without tik.** The bundle's `verify.sh` checks `.pqsig.`
   sidecars with `openssl pkeyutl -verify`, which needs OpenSSL 3.5 or
   later, and fails a required signature it cannot check.
7. **Sealing classical history.** Signatures face no
   harvest-now-decrypt-later risk, but once Ed25519 can be forged, an
   attacker can produce new events with old instants. A witness holding an
   ML-DSA key countersigns every store's heads when the policy takes
   effect (`.pqwitness.<fpr>`), so classical history up to that moment is
   anchored by a signature that remains unforgeable.

## Context

The finding behind this proposal (tik-30 of the 2026-11 review) lists
where Ed25519 and SHA-256 are hard-wired: `tik.sshsig` verifies Ed25519
only, `tik.sign` shells out to `ssh-keygen -Y`, trust registries are
allowed-signers files, and `"sha256-"` is a literal in several namespaces.

Verified on 2026-11-23 on the development host:

- OpenSSH 10.2p1 (`ssh -Q key`) offers no post-quantum signature key
  type. Its post-quantum support is key exchange only
  (`mlkem768x25519-sha256`).
- The JDK the build uses (GraalVM 25.0.4) generates and verifies
  ML-DSA-65 through `KeyPairGenerator`/`Signature` with no extra
  provider; signatures are 3309 bytes and public keys 1974 bytes DER.
- OpenSSL 3.5.8 signs and verifies ML-DSA-65, and the two interoperate in
  both directions: a JDK signature verifies under `openssl pkeyutl
  -verify` with the JDK's X.509 public key, and an OpenSSL signature
  verifies under the JDK.

Not verified: whether GraalVM native-image, which builds the shipped
binary, includes the ML-DSA provider; whether either side exposes the
FIPS 204 context string consistently, which is why point 2 uses a message
prefix instead.

**Hashes stay as they are.** SHA-256 content addresses carry integrity
only (ADR 0006, ADR 0007). Grover's algorithm reduces preimage resistance
to about 2^128, and the best known quantum collision attack does not beat
classical collision search in practice, so SHA-256 is generally
considered adequate against quantum attackers. ADR 0006 rule 3 remains the
exit if that changes: a `sha384-` prefix under a format-version bump.
Routing the literal `"sha256-"` strings through one function is worth
doing on its own, as preparation, and needs no decision.

**Identity bindings are outside tik's control.** An id-token is signed by
its issuer with RS256, ES256 or EdDSA. tik cannot make that post-quantum;
it can only sign the binding attestation itself hybrid, and the binding
then records that a classical token was presented at a claimed moment,
which point 7's anchor dates.

## Consequences

- Every signed event carries about 3.3 KB more, and every actor a second
  key. Signing and verifying ML-DSA-65 is fast enough not to matter at
  event rates.
- A store that sets the policy cannot be verified by a tik that predates
  it. That follows from the format-version rule and is the intended
  behaviour.
- Implementation order, each step its own change: route content
  addressing through one function; add the `pq-signers` reader and the
  `.pqsig.` verifier with tests against OpenSSL-made signatures; add
  signing; extend `verify.sh` and the bundle format; add the policy file
  and the witness anchor; confirm native-image support first, since the
  rest depends on it.

## Alternatives considered

- **ML-DSA alone.** Smaller sidecars, but the scheme was standardised in
  2024 and has less cryptanalysis behind it than Ed25519. A hybrid costs
  space and buys a fallback in both directions.
- **SLH-DSA (FIPS 205).** Rests on hash assumptions only, which is
  attractive for a format meant to verify for decades, but signatures are
  7.8 KB or more and signing is slow. It remains a candidate for the
  witness anchor of point 7, where volume is low.
- **Waiting for OpenSSH.** Would keep a single sidecar format, but the
  timeline is unknown, and the decision about which algorithm a store
  requires should not depend on it.
