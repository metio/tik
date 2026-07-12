;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.spec-conformance-test
  "The bridge that attaches the TLA+ models to the real kernel. A model
  checker verifies a hand-written spec, not the code — so a spec can be
  correct while the implementation drifts. Here the ACTUAL kernel
  (tik.stage/effective-reached) derives the very processes the TLA+
  modules model, and must produce the fixpoint each module names as its
  `Expected`/`Full`. The scenario and its expected answer are the shared
  artifact: if the model's constant or the code's derivation changes
  that outcome, this test fails, so the two cannot silently diverge.

  - spec/SweepFixpoint.tla: a→b→d with d guarded by 'not reached c',
    c←a. Synchronous sweeps give reached = {a,b,c} (c is decided before
    d is prerequisite-enabled, so d's negation fails). Expected = {a,b,c}.
  - spec/MonotonePositive.tla: a→b (needs x), a→c→d (needs y), positive
    guards only. With x and y present the fixpoint is {a,b,c,d} = Full."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [tik.event :as event]
            [tik.plan :as plan]
            [tik.stage :as stage])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-01-01T00:00:00Z"))
(def ^:private ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef")

(defn- create-only []
  [(event/create-ticket {:ticket ticket :actor "a" :at t0
                         :title "x" :process :p})])

(defn- with-facts
  "A create plus an assertion of each given fact path."
  [paths]
  (apply event/chain
         (fn [_] (event/create-ticket {:ticket ticket :actor "a" :at t0
                                       :title "x" :process :p}))
         (map-indexed
          (fn [i path]
            (fn [parents]
              (event/assert-fact {:ticket ticket :actor "a"
                                  :at (.plusSeconds ^Instant t0 (long (inc i)))
                                  :parents parents :path path :value true})))
          paths)))

;; --- the exact SweepFixpoint.tla process, as a real tik definition ---

(def ^:private sweep-process
  {:process/id :sweepfixpoint :process/version 1
   :process/stages
   [{:stage/id :a :guards []}
    {:stage/id :b :after [:a] :guards []}
    {:stage/id :c :after [:a] :guards []}
    ;; d is prerequisite-enabled by b but negates c — the sharp
    ;; stratified case ADR 0005's linter accepts
    {:stage/id :d :after [:b] :guards [[:not [:stage-reached :c]]]}]})

(deftest kernel_matches_SweepFixpoint_Expected
  ;; SweepFixpoint.tla asserts UniqueFixpoint: Terminal => reached =
  ;; {a,b,c}. The real synchronous-sweep kernel must agree exactly.
  (let [reached (stage/effective-reached sweep-process (create-only) t0 {})]
    (is (= #{:a :b :c} reached)
        "tik.stage must reproduce SweepFixpoint.tla's Expected fixpoint")
    (testing "d never reaches: c is decided before d is enabled"
      (is (not (contains? reached :d))))))

;; --- the exact MonotonePositive.tla process, as a real definition ---

(def ^:private mono-process
  {:process/id :monotonepositive :process/version 1
   :process/stages
   [{:stage/id :a :guards []}
    {:stage/id :b :after [:a] :guards [[:fact [:x]]]}
    {:stage/id :c :after [:a] :guards []}
    {:stage/id :d :after [:c] :guards [[:fact [:y]]]}]})

(deftest kernel_matches_MonotonePositive_Full
  ;; MonotonePositive.tla asserts the terminal reached set is Full =
  ;; {a,b,c,d} once both facts are available; and that reached is
  ;; monotone. The real kernel must reach Full with x,y present and a
  ;; strict subset without them.
  (let [full (stage/effective-reached mono-process (with-facts [[:x] [:y]]) t0 {})
        none (stage/effective-reached mono-process (create-only) t0 {})
        just-x (stage/effective-reached mono-process (with-facts [[:x]]) t0 {})]
    (is (= #{:a :b :c :d} full)
        "tik.stage must reach MonotonePositive.tla's Full fixpoint")
    (testing "monotone in available facts: fewer facts => subset reached"
      (is (set/subset? none just-x))
      (is (set/subset? just-x full))
      (is (= #{:a :c} none) "without facts, only the unguarded stages")
      (is (= #{:a :b :c} just-x) "x reaches b but not d (needs y)"))))

;; --- the exact PlanReadiness.tla dependency graph, via real tik.plan ---

(def ^:private plan-edges
  ;; a -> b -> c (chain) and d <-> e (cycle), matching PlanReadiness.tla
  {:a #{:b} :b #{:c} :d #{:e} :e #{:d}})

(deftest kernel_matches_PlanReadiness
  ;; PlanReadiness.tla asserts NoCyclicSettles (the cycle never settles)
  ;; and Completes (the acyclic chain always does). The real tik.plan
  ;; must reproduce both over the same graph.
  (testing "the cycle is detected — d,e can never become ready"
    (is (= #{:d :e} (plan/cyclic-nodes plan-edges)))
    (is (empty? (set/intersection #{:d :e} (plan/ready plan-edges #{})))))
  (testing "ready-progression settles exactly the acyclic chain (any order)"
    (let [final (loop [settled #{} steps 0]
                  (let [r (plan/ready plan-edges settled)]
                    (if (or (empty? r) (> steps 20))
                      settled
                      (recur (into settled r) (inc steps)))))]
      (is (= #{:a :b :c} final)
          "the acyclic nodes all settle; the cyclic ones never do")))
  (testing "the critical path is the whole remaining chain"
    (is (= [:c :b :a] (plan/critical-path plan-edges #{})))))
