---
type: runbook
title: supply-chain-release / :attested
---

# supply-chain-release · attested

What the artifact is made of, and how it came to be — each said by a
party the registry trusts, each recent enough to be about this build.

- Record the artifact's digest and have `ci` sign it:
  `tik set <id> artifact.digest="\"sha256:…\""`.
- Bring the SBOM and the provenance in as attestations. If your pipeline
  already emits in-toto or SLSA documents, `tik bridge intoto <file>`
  maps their `predicateType` onto the claim these guards read, so no
  format is invented and nothing is transcribed by hand.
- Both windows are one day. An attestation older than that describes a
  different build, and the stage will not hold — re-attest as part of
  the release rather than replaying an older document.

tik checks that a trusted attester said these things. It does not read
the SBOM and forms no opinion about its contents, so this stage is worth
exactly what the attester is worth. Choose who may sign accordingly, and
say so to whoever reads the badge.
