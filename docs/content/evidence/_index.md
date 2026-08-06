---
title: Evidence
description: The published bundle format, and how anyone re-derives what a bundle's facts imply under the rules it pinned.
tags: [evidence, bundle, supply-chain, verification]
---

A ticket travels as an evidence bundle: the signed facts, the hash-pinned
rules that judge them, the registry the signatures check against, and a
script that checks the lot with coreutils. Whoever holds one recomputes
the answer.

That is the whole claim, and it is a narrow one. A build system tells you
a process ran. A bundle tells you *which* rules judged it, what they
required, what was supplied, and lets you recompute the verdict on a
laptop with no network, years later.

- [Bundle format](/evidence/bundle-format/) — the on-disk contract, what
  a reader may assume, and what forces a version bump.
- [Re-deriving a bundle](/evidence/re-deriving/) — `tik rederive`, the
  HTTP service, and what a badge is allowed to say.

## What a bundle is worth

A guard checks that a trusted attester said something. It never reads the
SBOM. So a derivation is exactly as good as the attesters named in it,
and that belongs in the first paragraph rather than a footnote — the
signatures prove *who claimed*, the hashes prove *what was claimed*, and
the definition proves *which rule accepted it*.

What the format adds on top of a signed archive is the rules. Two people
holding the same bundle reach the same stages, because the definition
that judged the ticket travels inside it, addressed by its own hash.
