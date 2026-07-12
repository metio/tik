;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.metamorphic-test
  "Metamorphic properties: relations that must hold between the outputs
  of related inputs WITHOUT knowing the exact output — so no oracle is
  needed, and they catch bugs the reference kernel cannot (it could
  share the bug). The reduce property tests already pin commutativity,
  idempotence, and permutation-proofness; these add the assert/retract/
  dispute fact-algebra and a theorem about the fixpoint: over guards
  built only from MONOTONE operators, deriving at a later instant can
  only ADD reached stages, never remove one."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.set :as set]
            [tik.event :as event]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-01-01T00:00:00Z"))
(def ^:private ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef")
(def ^:private paths [[:a] [:b] [:c]])

(defn- at [secs] (.plusSeconds ^Instant t0 (long secs)))

;; ------------------------------------------------ fact algebra

(defn- create-only []
  (event/chain (fn [_] (event/create-ticket {:ticket ticket :actor "a"
                                             :at t0 :title "x" :process :p}))))

(defn- fact-guard-satisfied? [state path]
  (:satisfied? (guard/eval-guard [:fact path]
                                 {:state state :now (at 999)
                                  :roles {} :reached #{}})))

(defspec assert_then_retract_is_guard_neutral_but_status_distinct 100
  ;; two metamorphic facts at once. GUARD-observable neutrality: a
  ;; [:fact path] guard is unsatisfied after assert-then-retract, just
  ;; as it is when the path was never asserted — the retract inverts the
  ;; assert at the guard level. STATUS distinctness: the choke point
  ;; still tells them apart — :retracted (a signed tombstone) is not
  ;; :absent (never claimed) — so it never conflates the two.
  (prop/for-all [path (gen/elements paths)
                 v gen/small-integer]
    (let [never (red/ticket-state (create-only))
          a+r (red/ticket-state
               (event/chain
                (fn [_] (event/create-ticket {:ticket ticket :actor "a"
                                              :at t0 :title "x" :process :p}))
                #(event/assert-fact {:ticket ticket :actor "a" :at (at 10)
                                     :parents % :path path :value v})
                #(event/retract-fact {:ticket ticket :actor "a" :at (at 20)
                                      :parents % :path path})))]
      (and (not (fact-guard-satisfied? never path))
           (not (fact-guard-satisfied? a+r path))
           (= :absent (:status (red/fact-status never path)))
           (= :retracted (:status (red/fact-status a+r path)))))))

(defspec dispute_is_guard_neutral_and_marks_disputed 100
  ;; a disputed fact does not satisfy its guard (like absent/retracted),
  ;; but the status is honestly :disputed — a distinct third answer
  (prop/for-all [path (gen/elements paths)
                 v gen/small-integer]
    (let [a+d (red/ticket-state
               (event/chain
                (fn [_] (event/create-ticket {:ticket ticket :actor "a"
                                              :at t0 :title "x" :process :p}))
                #(event/assert-fact {:ticket ticket :actor "a" :at (at 10)
                                     :parents % :path path :value v})
                #(event/dispute-fact {:ticket ticket :actor "b" :at (at 20)
                                      :parents % :path path :reason "no"})))]
      (and (not (fact-guard-satisfied? a+d path))
           (= :disputed (:status (red/fact-status a+d path)))))))

;; -------------------------------- monotone-fixpoint time theorem

(def ^:private gen-monotone-guard
  "Guards built ONLY from operators monotone in 'more facts / more time
  / more reached' — no :not, no negation. Over such guards the fixpoint
  is monotone non-decreasing in `now`."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/fmap (fn [gs] (into [:and] gs)) (gen/vector inner 0 3))
       (gen/fmap (fn [gs] (into [:or] gs)) (gen/vector inner 1 3))]))
   (gen/one-of
    [(gen/fmap (fn [p] [:fact p]) (gen/elements paths))
     (gen/fmap (fn [secs] [:elapsed-since :ticket/create
                           (str "PT" (max 1 secs) "S")])
               (gen/choose 1 200))])))

(def ^:private gen-monotone-process
  (gen/let [n (gen/choose 1 4)
            guards (gen/vector gen-monotone-guard n)]
    {:process/id :mono :process/version 1
     :process/stages (vec (map-indexed
                           (fn [i g] {:stage/id (keyword (str "s" i))
                                      :guards [g]})
                           guards))}))

(def ^:private gen-fact-events
  "A create plus a few fact assertions on the fixed paths at increasing
  times — the world the monotone process derives over."
  (gen/let [asserts (gen/vector
                     (gen/tuple (gen/elements paths) gen/small-integer
                                (gen/choose 1 100))
                     0 5)]
    (apply event/chain
           (fn [_] (event/create-ticket {:ticket ticket :actor "a" :at t0
                                         :title "x" :process :p}))
           (map (fn [[path v secs]]
                  (fn [parents]
                    (event/assert-fact {:ticket ticket :actor "a"
                                        :at (at secs) :parents parents
                                        :path path :value v})))
                asserts))))

(defspec monotone_fixpoint_only_grows_with_time 100
  ;; the theorem: with negation-free guards, effective-reached at a
  ;; later instant is a SUPERSET of at an earlier one — evidence and
  ;; elapsed time only accumulate, so a reached stage never un-reaches
  (prop/for-all [process gen-monotone-process
                 events gen-fact-events
                 t1 (gen/choose 0 300)
                 delta (gen/choose 0 5000)]
    (let [earlier (stage/effective-reached process events (at t1) {})
          later (stage/effective-reached process events (at (+ t1 delta)) {})]
      (set/subset? earlier later))))
