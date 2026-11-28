---
type: decision
status: proposed
date: 2026-11-28
title: tik.api reads, verifies and derives one ticket of a store in process, never writes, and judges signatures with the code tik verify runs
supersedes: null
---

# ADR 0044: A library reads one ticket and never writes

## Decision

Proposed on 2026-11-28. Not accepted. It is implemented in `cli/tik/api.clj`
under ticket f106aa78 of the metio store. Each choice names the
alternatives, ranked by security first, then reuse, then performance.

**`tik.api` is the one namespace another program calls to read tik
evidence in process: `open-store`, `resolve-id`, `ticket`, `derive` and
`verify-ticket`, versioned by `tik.api/version` (1).** kajero checks
`tik:<id>` evidence with it instead of starting `tik status <id> --edn`
per ticket. The contract is the namespace docstring; this record says why
it has that shape.

1. **One ladder for the CLI and the library.** The per-ticket verify
   ladder moved out of `tik.audit` into `tik.verify/ticket-ladder`, which
   returns items (`:section`, `:check`, `:note`, `:skip`) instead of
   printing. `tik verify` prints `tik.verify/line` of each item, so its
   output is unchanged byte for byte, and `tik.api/verify-ticket` returns
   the items. The definition checks moved the same way
   (`tik.verify/definition-items`). Rejected: a second, library-only
   verifier, which would drift from the audit, and a verifier that parses
   the CLI's printed lines.

2. **Read-only by construction.** The handle's store refuses `append!`
   and `put-sidecar!` with `:tik.api/read-only`; a SQLite store is opened
   through an SQLite URI with `mode=ro`, so the connection itself can
   neither create the file, create a table nor change a row; nothing
   materializes the built-in track definition into `processes/` as the
   CLI does; and no derived-state cache is written.
   `tik.identity-trust/signer-view` returns allowed-signers text instead
   of writing a temporary file per set of granted keys. Rejected: relying
   on callers not to call writers, and opening SQLite read-write and
   promising not to write.

3. **No process, no network.** `tik.sign/*ssh-keygen-fallback*` is bound
   false for every library call. A signature by a key type tik.sshsig does
   not read (RSA, ECDSA) is then `:unchecked`, and its check fails where
   `tik verify` would ask ssh-keygen and might pass. The library is
   therefore stricter than the CLI for those keys and never weaker. The
   binding is read before the availability probe, which itself starts
   ssh-keygen. Rejected: allowing the fallback behind an option, because a
   library user that needs RSA keys can run `tik verify`, and an option
   is one more way to start a process from a long-running server.

4. **Bounds before reads.** Every call measures a ticket before reading
   it: event and sidecar sizes, the event count, the ticket's total bytes
   (`stat` per file for the file store, one `length(bytes)` query for
   SQLite), and refuses a symbolic link or anything that is not a regular
   file in a ticket directory, since its size says nothing about what
   reading it returns (`/dev/zero`). The config files the ladder reads
   whole (`actors`, `pq-signers`, `signatures.edn`, `oidc.edn`,
   `roles.edn`, `jwks/`) and definition files are bounded too. The
   defaults are in `tik.api/default-limits`; `open-store` takes lower or
   higher ones. Rejected: trusting the store reader, which bounds nesting
   depth but not size.

5. **The pinned definition, or a refusal.** `derive` refuses with
   `:tik.api/definition-mismatch` when the definition it finds does not
   hash to the ticket's pin. `tik status` derives under the named file
   with a warning on stderr in that case; a library caller would not see
   the warning. `{:allow-unpinned-definition true}` derives as status
   does, and `:definition` names the hash used.

6. **Ticket references.** A uuid, the full id, or a prefix of at least 8
   characters naming exactly one ticket; an ambiguous prefix is refused
   with its `:candidates`. Rejected: tik's CLI rule of any non-blank
   prefix, because a short prefix stored in another system's record
   silently starts naming a different ticket when the store grows.

7. **Stable refusals.** Every refusal is an ex-info with a `:type` in the
   `tik.api` namespace; store and parse failures become
   `:tik.api/unreadable`, derivation failures `:tik.api/underivable`.

8. **Small load.** Requiring `tik.api` loads 25 tik namespaces (the
   kernel, the stores, signature and identity verification) plus malli,
   and none of the CLI, MCP, bridges or HTTP code (measured with
   `all-ns` on the JVM and babashka: requiring `tik.cli` on top loads 58
   more namespaces). cheshire is resolved only when an identity binding
   must be verified, and the SQLite driver only for a SQLite store, so a
   native image that needs either requires `cheshire.core` or
   `tik.store.sqlite-jdbc` at build time, as `tik.main` does.

## Consequences

- The equivalence of `derive` with `tik status --edn` and of
  `verify-ticket` with `tik verify` is a test over every ticket of the
  corpus stores, a copy of the repository's own store and built file and
  SQLite stores (`test/tik/api_test.clj`).
- A change to the ladder changes the CLI and the library together; a
  change to what `tik.api` returns that removes or renames a documented
  key bumps `tik.api/version`.
- The library verifies one ticket and its pinned definition. The
  store-level checks of a whole-store `tik verify` (every archived
  definition, roots, identity binding health) stay in the CLI.
