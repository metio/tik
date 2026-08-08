---
type: runbook
title: automated-release / :attested
---

# automated-release · attested

What the artifact is made of, and how it came to be — each said by a
party the registry trusts, each recent enough to be about this build.

- Record the artifact's digest and have `ci` sign it:
  `tik set <id> artifact.digest="\"sha256:…\""`.
- Bring the SBOM and the provenance in as attestations. Where the
  pipeline already emits in-toto or SLSA documents, `tik bridge intoto
  <file>` maps their `predicateType` onto the claim these guards read, so
  nothing is invented and nothing is transcribed by hand.
- Both windows are one day, and they are measured against the moment the
  attestation was written. Produce them in the release job; an older
  document describes an older build.

tik checks that a trusted attester said these things. It never reads the
SBOM and forms no opinion about its contents, so this stage is worth
exactly what the attester is worth — choose who may sign accordingly, and
say so to whoever reads the badge.

The stage is sticky, so what it asserts is "when this version shipped, a
fresh SBOM and provenance existed", which stays true. Whether today's
advisories still agree is a question about a new scan, on a new ticket.
