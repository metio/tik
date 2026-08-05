---
title: Writing a definition
description: The shape of a process definition, the closed guard vocabulary, and the design law that separates a process from a task list.
tags: [authoring, processes, guards, edn]
---

## The design law

**A stage is defined by what must be TRUE to reach it, never by who moved
what.** Ask of every stage: what evidence, on record, would convince a
sceptical auditor a year from now? Write that as guards. If the honest
answer is "somebody said so", then make *that* the fact — a named person's
signature on a named claim — rather than a status somebody sets.

A definition whose stages are really a checklist of activities is a task
list wearing a process costume. It derives nothing, because nothing about
it follows from evidence.

## The shape

```clojure
{:process/id :support-request
 :process/version 1
 :process/guard-vocab 2

 :process/roles
 {:triager {:members ["seb"]}
  :billing {:members ["billing"]}}

 :process/facts
 {[:category]        [:enum :billing :technical :account :abuse]
  [:severity]        [:enum :low :normal :high :critical]
  [:resolution :ref] [:string {:min 8}]}

 :process/stages
 [{:stage/id :received
   :hint "kb/runbooks/support-request-received.md"
   :guards []}

  {:stage/id :triaged
   :after [:received]
   :hint "kb/runbooks/support-request-triaged.md"
   :guards [[:fact [:category]]
            [:fact [:severity]]
            [:signed-by :triager [:category]]]}

  {:stage/id :closed
   :after [:resolved]
   :stage/sticky? true
   :guards [[:fact [:customer :ack]]]}]}
```

`:process/facts` declares the schema for each path, which is what lets
`explain` tell somebody not merely that a fact is missing but what shape it
must take — and what lets a web form be generated from the reason.

`:hint` names a runbook for the stage. Those files are published here under
[Runbooks](/runbooks/), so the hint a person sees in `explain` resolves to
a page.

## The guard vocabulary

Twelve operators, closed and versioned. New keywords need a version bump,
because the semantics of a verifiable kernel have to be enumerable:

| Operator | Holds when |
|---|---|
| `[:fact path]` | a value stands at `path` |
| `[:fact= path v]` | that value equals `v` |
| `[:artifact "prefix"]` | an attached artifact's path starts with `prefix` |
| `[:signed-by :role path]` | the fact at `path` was asserted by a member of `:role` |
| `[:stage-reached :id]` | that stage is in the reached set |
| `[:elapsed-since :ticket/create "PT48H"]` | that much time has passed |
| `[:attested-within claim "P7D"]` | a fresh-enough attestation of `claim` exists |
| `[:different-person path-a path-b]` | two facts came from distinct actors |
| `[:malli schema]` | the fact map satisfies a schema |
| `[:and …]` `[:or …]` `[:not …]` | the connectives |

`:different-person` is the four-eyes principle as a derivable condition.
`:attested-within` closes the stale-evidence gap: a replayed "CI green"
from last month is cryptographically valid and fails the guard honestly.

There is no conditional operator, because material implication already
spells one — "technical implies reproduced" is:

```clojure
[:or [:not [:fact= [:category] :technical]]
 [:stage-reached :reproducible]]
```

## Guards never query

A guard reads one ticket's own log and nothing else. No service call, no
database lookup, no other ticket, no clock of its own. Anything from the
outside world enters as a signed attestation event first, which is what
keeps evaluation offline, reproducible, and true years from now.

## Facts over flags

Prefer a fact that carries information to a boolean that carries a
decision. `[:customer :ack] = true` records that somebody clicked; a
richer fact records what they actually agreed to. The linter warns about
bare booleans for this reason, and the warning is opt-out per definition
when a flag genuinely is the whole truth.

## Versions and pinning

A ticket pins the definition's content hash at creation. Editing a
definition therefore changes its identity and leaves existing tickets
deriving under the version that judged them, until somebody moves each one
with `tik reprocess`. The version number is a human label; the hash is the
identity.
