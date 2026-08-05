---
type: runbook
title: release / :attested
---

<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# release: attested

The container image exists, an SBOM and build provenance were produced for it,
and the checksums carry a signature.

```sh
tik set <id> image=ghcr.io/metio/tik@sha256:... --actor ci
tik set <id> signature.bundle=<sha256 of SHA256SUMS.bundle> --actor ci
tik attest <id> :sbom --body '{:format "spdx-json" :digest "sha256:..."}' --actor ci
tik attest <id> :provenance --body '{:format "slsa-v1" :digest "sha256:..."}' --actor ci
```

The guard checks that a trusted attester said an SBOM exists. It does not read
the SBOM — guards never query anything, which is what keeps evaluation offline
and reproducible forever. The derivation is therefore exactly as trustworthy as
`:ci`, and that is a property to state plainly rather than bury.

Name the digest in the attestation body so a reader can fetch the document and
check it themselves.
