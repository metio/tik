---
title: Clojure library
description: Read, verify and derive one ticket of a store in process with tik.api, read-only and without starting a process.
tags: [library, clojure, verification]
---

`tik.api` gives a JVM, babashka or native-image program the answers of
`tik status` and `tik verify` for one ticket without running the CLI. It
never writes to the store, signs, runs a probe, touches the network or
starts a process.

```clojure
(require '[tik.api :as api])

(def store (api/open-store "/path/to/.tik"))   ; the dir holding tickets/ or tik.db

(api/derive store "f106aa78")
;; => {:ticket #uuid "f106aa78-…" :title "…"
;;     :rules {:process :skilled-change :version 2 :hash "sha256-…"}
;;     :current [:scoped] :reached [:scoped]
;;     :facts {[:kind] {:status :present :value :feat :by "claude"} …}
;;     :missing-parents nil
;;     :definition {:pinned "sha256-…" :derived-under "sha256-…" :source :archive}
;;     :at #inst "…"}

(:ok? (api/verify-ticket store "f106aa78"))
```

Add tik as a git dependency in `deps.edn`:

```clojure
{:deps {io.github.metio/tik {:git/sha "<commit>"}}}
```

## The calls

- `(open-store root)` or `(open-store root {:limits {…}})` opens the store
  whose root is `root`, the directory holding `tickets/` (file store) or
  `tik.db` (SQLite store). Nothing is discovered from parent directories.
- `(resolve-id store ref)` takes a uuid, the full id, or a prefix of at
  least 8 characters naming exactly one ticket.
- `(ticket store ref)` returns the ticket's events in reduction order.
- `(derive store ref {:at instant})` returns the ticket's title, rules,
  current and reached stages and facts. They equal what
  `tik status <id> --edn --at <instant>` prints. When the definition found
  in the store does not hash to the ticket's pin, `derive` refuses unless
  `{:allow-unpinned-definition true}` is passed.
- `(verify-ticket store ref)` runs the `tik verify` ladder for that ticket
  with the same code as the CLI and returns it as data: `:ok?`,
  `:failures`, one summary per event (`:integrity` and `:signature`, which
  is `:verified`, `:delegated`, `:unsigned`, `:unchecked` or `:failed`)
  and every check as an item.

`tik.api/version` is the contract's version; it changes only when a
documented key, argument or refusal changes meaning or goes away.

## Refusals

Every refusal is an `ex-info` whose `:type` is in the `tik.api` namespace:
`not-a-store`, `ambiguous-store`, `backend-unavailable`,
`invalid-argument`, `invalid-id`, `unknown-ticket`, `ambiguous-id` (with
`:candidates`), `too-large` (with `:limit` and `:actual`), `unreadable`,
`definition-missing`, `definition-mismatch`, `malformed-roles`,
`underivable` and `read-only`.

## Differences from the CLI

- Signatures are verified in process (Ed25519, Ed25519 security keys,
  ML-DSA-65). A key type only ssh-keygen reads is `:unchecked`, and its
  check fails where `tik verify` would ask ssh-keygen.
- Every event, sidecar and config file is measured before it is read, and
  a symbolic link inside a ticket directory is refused. The defaults are
  `tik.api/default-limits`.
- A SQLite store is opened read-only and needs the SQLite driver, which
  babashka lacks. A native image that reads SQLite stores or identity
  bindings must require `tik.store.sqlite-jdbc` or `cheshire.core` at
  build time.

The reasoning is recorded in
[ADR 0044](/decisions/0044-a-library-reads-one-ticket-and-never-writes/).
