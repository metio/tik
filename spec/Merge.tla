------------------------------- MODULE Merge -------------------------------
(* SPDX-FileCopyrightText: The tik Authors                                  *)
(* SPDX-License-Identifier: 0BSD                                            *)
(*                                                                          *)
(* Union-merge replication (PLAN §1, hypothesis H2): replicas append        *)
(* content-addressed events and sync by set union, in any interleaving.    *)
(* Because derivation is a pure function of the event SET (ordered         *)
(* internally by (at, id)), set convergence IS state convergence — so the  *)
(* whole replication theorem reduces to: the sets grow monotonically, no   *)
(* action ever blocks (merge cannot conflict), and once appends quiesce,   *)
(* syncing makes the replicas equal forever.                               *)
EXTENDS FiniteSets

Events == {"e1", "e2", "e3"}

VARIABLES a, b, quiesced
vars == <<a, b, quiesced>>

Init == a = {} /\ b = {} /\ quiesced = FALSE

AppendA == /\ ~quiesced
           /\ \E e \in Events : a' = a \cup {e}
           /\ UNCHANGED <<b, quiesced>>

AppendB == /\ ~quiesced
           /\ \E e \in Events : b' = b \cup {e}
           /\ UNCHANGED <<a, quiesced>>

Quiesce == quiesced' = TRUE /\ UNCHANGED <<a, b>>

SyncA == a' = a \cup b /\ UNCHANGED <<b, quiesced>>   \* a pulls from b
SyncB == b' = b \cup a /\ UNCHANGED <<a, quiesced>>   \* b pulls from a

Next == AppendA \/ AppendB \/ Quiesce \/ SyncA \/ SyncB

Spec == Init /\ [][Next]_vars
             /\ WF_vars(Quiesce) /\ WF_vars(SyncA) /\ WF_vars(SyncB)

(* Stores are append-only: no step ever removes an event.                  *)
GrowOnly == [][a \subseteq a' /\ b \subseteq b']_<<a, b>>

(* Eventual consistency: after appends stop, the replicas converge and    *)
(* stay converged. Derived state converges with them by purity.           *)
Convergence == <>[](a = b)
=============================================================================
