---------------------------- MODULE MergeInductive ----------------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* Merge.tla's union-merge replication, stated for Apalache as an           *)
(* inductive invariant over any universe of events. TLC explores Merge.tla  *)
(* with three events; Apalache proves, for every state satisfying IndInv,   *)
(* that every step keeps it, so the result holds for executions of any      *)
(* length, and the event universe is symbolic up to the bound of the        *)
(* chosen CInit.                                                            *)
(*                                                                          *)
(* Convergence (<>[](a = b)) is a liveness property, which Apalache does    *)
(* not prove; TLC keeps checking it on Merge.tla. Its safety core is        *)
(* proved here instead:                                                     *)
(*                                                                          *)
(*   - GrowOnly: no step removes an event (Merge.tla's GrowOnly);           *)
(*   - Settled: once appends stop, the union of the replicas is the set     *)
(*     frozen at that moment, so syncing neither loses nor invents events;  *)
(*   - Approaches: after quiescence no step moves a replica away from that  *)
(*     set;                                                                 *)
(*   - StaysConverged: once the replicas agree after quiescence, they keep  *)
(*     agreeing (the [] half of <>[]).                                      *)
(*                                                                          *)
(* frozen is a ghost variable: Merge.tla has no counterpart and needs none, *)
(* it only names the union at quiescence so the invariant can refer to it.  *)
EXTENDS Integers, FiniteSets

CONSTANT
    \* @type: Set(Int);
    Events

VARIABLES
    \* @type: Set(Int);
    a,
    \* @type: Set(Int);
    b,
    \* @type: Bool;
    quiesced,
    \* @type: Set(Int);
    frozen

vars == <<a, b, quiesced, frozen>>

Init == a = {} /\ b = {} /\ quiesced = FALSE /\ frozen = {}

AppendA == /\ ~quiesced
           /\ \E e \in Events : a' = a \cup {e}
           /\ UNCHANGED <<b, quiesced, frozen>>

AppendB == /\ ~quiesced
           /\ \E e \in Events : b' = b \cup {e}
           /\ UNCHANGED <<a, quiesced, frozen>>

Quiesce == /\ quiesced' = TRUE
           /\ frozen' = IF quiesced THEN frozen ELSE a \cup b
           /\ UNCHANGED <<a, b>>

SyncA == a' = a \cup b /\ UNCHANGED <<b, quiesced, frozen>>
SyncB == b' = b \cup a /\ UNCHANGED <<a, quiesced, frozen>>

Next == AppendA \/ AppendB \/ Quiesce \/ SyncA \/ SyncB

TypeOK == /\ a \in SUBSET Events
          /\ b \in SUBSET Events
          /\ quiesced \in BOOLEAN
          /\ frozen \in SUBSET Events

IndInv == /\ TypeOK
          /\ quiesced => a \cup b = frozen
          /\ ~quiesced => frozen = {}

(* State properties, implied by IndInv.                                     *)
Settled == quiesced => a \subseteq frozen /\ b \subseteq frozen

(* Step properties, checked on every step out of an IndInv state.           *)
GrowOnly == a \subseteq a' /\ b \subseteq b'
Approaches == quiesced => /\ (frozen \ a') \subseteq (frozen \ a)
                          /\ (frozen \ b') \subseteq (frozen \ b)
StaysConverged == quiesced /\ a = b => a' = b'

(* Vacuity controls: each must be violated, which shows that some state     *)
(* satisfies IndInv and that some step leaves one.                          *)
NoState == FALSE
NoStep == ~(a \subseteq a')

(* Every universe of at most N events. CInitTLC is Merge.tla's.             *)
CInitTLC == Events = 1..3
CInit50 == Events \in SUBSET (1..50)
CInit200 == Events \in SUBSET (1..200)
=============================================================================
