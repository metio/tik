---
type: decision
status: proposed
date: 2026-11-27
title: The mail bridge records a DKIM-authenticated sender of a new ticket as a keyed pseudonym, keeps the address in a private table outside the store, and refuses blocked senders before writing anything
supersedes: null
---

# ADR 0043: A mail reporter is a keyed pseudonym

## Decision

Proposed on 2026-11-27. Not accepted. It is implemented in `tik.reporter`,
`tik.reporter-cmd` and `tik.bridge` under ticket 717990fb of the metio
store, and builds on ADR 0019 and ADR 0042. Each choice names the
alternatives and why it was taken, ranked by security first, then
extensibility and re-use, then performance.

**A mailbox with `:reporter` records the DKIM-authenticated sender of
each new ticket as a fact holding `hmac-sha256:` and the lower-case hex
HMAC-SHA256 of the normalised address under a key the store never sees.
The pseudonym → address mapping lives in a private table on the
bridge's machine, outside the store, and `tik reporter forget` erases a
row for an erasure request. A mailbox with `:block` refuses listed
senders after the DKIM gate and before any event is written.**

```clojure
{:reporter {:key {:credential "reporter-key"}
            :table "~/.local/state/tik/reporters"
            :fact [:reporter :id]}
 :block {:addresses ["noise@example.net"]
         :domains ["spam.example"]
         :pseudonyms ["hmac-sha256:<64 hex digits>"]}}
```

The receiving process declares the fact:

```clojure
:process/facts {[:reporter :id] [:re "^hmac-sha256:[0-9a-f]{64}$"]}
```

### 1. A pseudonym in the log, the address outside it

The log is append-only, content-addressed and signed, so a plaintext
address in an event could never be erased without rewriting every
descendant id and signature. An erasure request under the GDPR then has
no answer.

- **Chosen: a keyed pseudonym.** The fact is stable per sender, so the
  store can count reports per sender and a block list can name a sender
  without naming the address. Without the key, the pseudonym can be
  neither reversed nor recomputed. With the key, a guessed address can be
  checked against it; that is accepted, and the key is guarded like a
  signing key.
- Rejected: plaintext in the log (no erasure), an unkeyed hash (anybody
  with a list of addresses reverses it in seconds), and a random id per
  sender held only in the table (the same sender would get a new id on a
  second machine or after the table is lost, and blocking by id would
  need the table).
- **Determinism.** The pseudonym is a pure function of the key and the
  address, so two bridges with the same key mint the same fact for the
  same message, as ADR 0021 requires of a self-minted event.

### 2. Only an authenticated sender becomes a reporter

The fact is asserted only when the mailbox requires DKIM and the gate
(`tik.mail/require-dkim!`) let the message through. The bridge consumes
the gate's outcome and does not judge `Authentication-Results` itself,
so a change to the gate carries over. A config with `:reporter` and
without `:dkim {:require true :authserv-id …}` is refused at start, and
`ingest-message!` checks again at run time, so an unauthenticated From
never becomes a reporter identity.

### 3. New tickets only

A comment on an existing ticket records nothing, whoever sends it. The
fact answers who opened the report; a second sender asserting it would
replace the opener and blur "who sent the most reports", and recording
every participant would keep addresses of people who only replied. A
participant fact can be added later under its own path. A `tik>` line in
a mail can never set the reporter fact, nor `[:reporter :id]` on a
mailbox without `:reporter`: a sender would otherwise forge another
sender's pseudonym.

### 4. Normalisation

The address is the addr-spec the bridge already extracts, trimmed and
lower-cased, with one pair of enclosing angle brackets removed (for an
address typed at `tik reporter forget`). Nothing else is folded: plus
tags, dots in a Gmail local part and IDN forms are rules of particular
providers, and folding them would merge mailboxes that are distinct
elsewhere. A sender who varies plus tags to escape an address block is
caught by a domain block.

### 5. The key

`:key` is a secret spec resolved once when the bridge starts, through
`tik.secret`. A literal (it would sit in a config file) and `{:env …}`
(every child inherits it, ADR 0041) are refused, as is a key shorter
than 32 bytes, HMAC-SHA256's own security level. The key is the UTF-8
bytes of the resolved line; `openssl rand -hex 32` gives 64 bytes of
which 32 are entropy. The bridge refuses to start when the key is
missing or short, when the process does not declare the fact, or when
the declared schema rejects a pseudonym, so a misconfiguration fails at
start instead of on the first report.

### 6. The table

The table is a directory with mode 0700 holding one file per pseudonym
with mode 0600, `<64 hex>.edn`, each `{:address … :first-seen …}`.

- **Outside the store.** The bridge refuses a table inside the store's
  root, so it is never committed, pushed or bundled. Erasure is deleting
  a file; after `tik reporter forget`, the pseudonym on the tickets
  resolves to nothing.
- **Written whole.** A row is written to a 0600 temp file in the same
  directory, forced to disk and renamed into place, so a reader sees a
  whole row or none. The row is written before the ticket's events: a
  ticket whose pseudonym nobody can resolve is worse than a row with no
  ticket.
- **No lock.** One file per row means two bridges sharing a table never
  rewrite each other's rows; two recording the same sender write the same
  address and the later rename wins. A single file would need a lock to
  avoid losing rows.
- **Permissions are checked.** A table directory or row that a group or
  others can use, or that is a symbolic link, is refused on read and
  write.
- **Not encrypted.** Encryption at rest would need a key on the same
  machine, readable by the same user that reads the table, so it adds no
  protection against that user and none against a stolen disk that a
  disk encryption does not already give. It would also not change
  erasure, which still deletes the row. Backups of the table keep what
  they hold until they expire, and an erasure request has to name them.

### 7. Blocking

`:block` is checked after the DKIM gate and before any event or row is
written, and the refusal is logged with the rule that matched (an
address's index, a domain or a pseudonym), never the address or the
body. The poll skips the message like any other refusal and continues.

- **Domains** match themselves and every subdomain on a label boundary:
  `spam.example` blocks `mail.spam.example` and not `nospam.example`.
  Entries are bare lower-case names; a leading dot, `@` or `*` is
  refused rather than guessed at.
- **Pseudonyms** keep plaintext out of the config. They need `:reporter`,
  whose key computes a sender's pseudonym, and they still hold after the
  sender's row was forgotten.
- A block also applies to mail on a mailbox without DKIM, where it can
  only refuse more.

### 8. Analytics

`tik reporter stats` counts tickets per pseudonym across the store with a
pure function over derived rows and the table, showing the address where
a row exists and `(forgotten)` where none does. `tik stats --by
reporter.id` already counts per pseudonym; `tik reporter stats` adds the
resolution.

## Consequences

- A process that receives reports declares `[:reporter :id]`; the metio
  `vulnerability` process gains it in a new version.
- The key and the table are new things to back up and guard on the
  bridge's machine, and a lost key makes every recorded pseudonym
  unmatchable by a new mail.
- Not done: participant pseudonyms on comments, and blocking without
  DKIM by anything but the From address.
