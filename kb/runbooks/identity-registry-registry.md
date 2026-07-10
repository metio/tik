<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# identity-registry: registry

The registry ticket accumulates signed `:identity` attestations, each
binding an IdP subject to an actor's public key. The ticket is always
in this stage — bindings are evidence, not workflow.

## Recording a binding

- `tik bridge oidc --registry <id> --actor <name>` runs the device-flow
  login and appends the binding, signed by the bridge's `TIK_KEY`.
- Rotation and re-attestation are newer attestations by the same
  bridge; readers take the latest binding per (issuer, subject).
- Revocation is a dispute of nothing — attestations are claims by the
  bridge; a binding the bridge no longer stands behind simply stops
  being re-attested, and lenses can require freshness via
  `:attested-within`.

## Reading bindings

`tik log <registry-id>` shows every binding with its signature;
`tik verify <registry-id>` checks them offline — no IdP call, ever.
