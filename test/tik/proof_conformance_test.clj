;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.proof-conformance-test
  "The bridge that attaches proofs/GSet.lean to the real kernel — the
  Lean analogue of tik.spec-conformance-test (which attaches the TLA+
  models). A proof assistant proves theorems about an ABSTRACT model
  (there, a grow-only set as a predicate); nothing about that mentions
  the Clojure code. The proof's shape is:

    IF `derive` is a pure function of the event SET,
    THEN it converges regardless of merge order  (derive_converges)
         and is invariant to duplicate events     (derive_dup_invariant).

  Lean discharges the THEN. This test discharges the IF for the REAL
  kernel, two ways at once:

  - hypothesis: the real derive (ticket-state) IS a set function — its
    output is invariant to the ORDER and MULTIPLICITY of the events fed
    in (a union is a set; concat with dedup is that union).
  - conclusions: the two Lean theorems, restated as equations on real
    event sets with the real union and real derive, must hold.

  Together: the proof holds for any set-function derive, and the real
  derive is shown to be one — so the real system inherits the proof's
  convergence. If the kernel ever stopped being a set function, these
  equations would break, detaching it from the proof."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [tik.canonical :as canonical]
            [tik.gen-events :as ge]
            [tik.reduce :as red]))

(defn- derive-addr
  "The real derivation, content-addressed so equality is byte equality."
  [events]
  (canonical/content-address (red/ticket-state events)))

(defn- union
  "The real replica merge: set union of event vectors. The reducer
  dedups by content-addressed id, so concatenation IS union."
  [a b]
  (into (vec a) b))

(defspec derive_is_a_set_function 100
  ;; the proof's HYPOTHESIS, for the real kernel: derive depends only on
  ;; the event SET — invariant to order (shuffle) and multiplicity
  ;; (duplication). Only when this holds does GSet.lean's theorem apply.
  (prop/for-all [events ge/gen-events]
    (= (derive-addr events)
       (derive-addr (shuffle events))
       (derive-addr (union events events)))))

(defspec merge_convergence_matches_derive_converges 100
  ;; GSet.lean/derive_converges: derive (union a b) = derive (union b a).
  ;; The real replica merge must converge regardless of merge direction.
  (prop/for-all [a ge/gen-events
                 b ge/gen-events]
    (= (derive-addr (union a b))
       (derive-addr (union b a)))))

(defspec duplicate_invariance_matches_derive_dup_invariant 100
  ;; GSet.lean/derive_dup_invariant: derive (union a a) = derive a.
  ;; Receiving a replica's events twice changes nothing.
  (prop/for-all [a ge/gen-events]
    (= (derive-addr (union a a))
       (derive-addr a))))
