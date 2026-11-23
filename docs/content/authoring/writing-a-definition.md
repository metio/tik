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
An attestation dated after the moment of derivation does not count yet, so
dating one ahead cannot stretch its window.

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

## Exits

No verb closes or discards a ticket. A ticket is settled once it reaches a
sticky stage with no stage after it, and stays on the board until then. A
process whose work can stop before its goal therefore declares the way
out as stages, with guards that say why the work stopped and who decided:

```clojure
{:stage/id :abandon-proposed
 :guards [[:fact [:abandoned :reason]]
          [:not [:fact [:merge :commit]]]]}

{:stage/id :abandoned
 :after [:abandon-proposed]
 :stage/sticky? true
 :guards [[:signed-by :maintainer [:abandoned :reason]]]}
```

The proposal has no `:after`, so it applies from any point of the
process. It is a stage of its own because the gated agent surface admits
a write to a path only to the roles a stage asks to sign it: were the
reason guarded by the maintainer's signature alone, an agent could not
record it at all. Split in two, an agent records the reason, the ticket
derives `abandon-proposed`, and `tik review` lists the reason for the
maintainer to sign unchanged. What the exit requires is the process's own
decision: dropping a change needs a signed reason, while "not affected" in
a vulnerability process is an outcome with evidence of its own.

### Cleanup after an exit

Work stopped halfway can leave something behind: a server provisioned but
never deployed, a DNS record, a bucket. Follow the exit with a cleanup
stage, and the ticket stays unsettled, and in `tik next`, until the
evidence shows the leftovers are gone:

```clojure
{:stage/id :abandon-proposed
 :guards [[:fact [:abandoned :reason]]
          [:not [:fact= [:deploy :env] :prod]]]}

{:stage/id :abandoned
 :after [:abandon-proposed]
 :stage/sticky? true
 :guards [[:signed-by :maintainer [:abandoned :reason]]]}

{:stage/id :cleaned-up
 :after [:abandoned]
 :stage/sticky? true
 :guards [[:or [:not [:fact [:server :id]]] [:fact= [:server :state] :deleted]]
          [:or [:not [:fact [:dns :record]]] [:fact= [:dns :state] :removed]]]}
```

Each cleanup guard is an implication over what the work recorded: if a
server was provisioned, it must be deleted. One exit therefore covers every
point at which the work can stop. Abandoned before anything existed, the
ticket settles at once; abandoned after provisioning, it waits for the
server to go. A separate exit per stage would multiply with every stage
added.

The cleanup is most trustworthy when a [probe](/cli/recording-work/)
observes it: one that asks the provider for the server records
`server.state=deleted` because the server is gone, and records it running
again if it comes back.

Write these guards over facts rather than stages. A stage may negate only
a stage from a strictly earlier stratum (ADR 0005), and a proposal without
`:after` sits in the first one, so `[:not [:stage-reached :merged]]` on it
fails lint where `[:not [:fact [:merge :commit]]]` does not.

Two limits remain. The stages before the exit stay on the frontier: after
`abandoned`, `tik next` still offers the facts of the next forward stage,
and a `[:not [:fact [:abandoned :reason]]]` guard on that stage does not
remove them. And `:elapsed-since` measures only from the ticket's
creation, so no guard can say a cleanup has waited too long since the
exit.

## Versions and pinning

A ticket pins the definition's content hash at creation. Editing a
definition therefore changes its identity and leaves existing tickets
deriving under the version that judged them, until somebody moves each one
with `tik reprocess`. The version number is a human label; the hash is the
identity.
