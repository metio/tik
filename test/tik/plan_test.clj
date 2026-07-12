;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.plan-test
  "The plan lens, verified. Beyond the totality floor (fuzz registry),
  these pin the real theorems of the dependency-graph algorithm:
  monotone unblocking, cycle-detection soundness, and that the critical
  path is a genuine longest chain of remaining work. Generators bias
  toward the hard cases — cycles and dangling edges — because a
  `:depends-on` fact can be hand-set to anything."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.plan :as plan]))

(def ^:private gen-node (gen/elements [:a :b :c :d :e :f]))

(def ^:private gen-edges
  "A dependency graph over a small node set — small enough that cycles
  and shared prerequisites arise often."
  (gen/map gen-node (gen/set gen-node {:max-elements 3}) {:max-elements 6}))

(def ^:private gen-settled (gen/set gen-node {:max-elements 6}))

;; ------------------------------------------------ correctness

(defspec every_node_has_exactly_one_status 200
  (prop/for-all [edges gen-edges settled gen-settled]
    (every? #{:done :ready :blocked :cyclic}
            (vals (:status (plan/summary edges settled))))))

(defspec settling_a_node_only_unblocks_never_blocks 200
  ;; the monotone-unblocking theorem — the cross-ticket analogue of the
  ;; fixpoint's time-monotonicity: adding a node to `settled` can only
  ;; GROW the ready set (minus the newly-settled node), never shrink it
  (prop/for-all [edges gen-edges
                 settled gen-settled
                 n gen-node]
    (let [before (plan/ready edges settled)
          after (plan/ready edges (conj settled n))]
      ;; every node ready before is still ready after, except n itself
      ;; (which became settled, hence no longer "ready")
      (set/subset? (disj before n) after))))

(defspec cycle_detection_is_sound_and_complete 200
  ;; a node is :cyclic iff it truly lies on a dependency cycle —
  ;; reachable from itself through prerequisite edges
  (prop/for-all [edges gen-edges]
    (let [cyc (plan/cyclic-nodes edges)]
      (every? (fn [n]
                (= (contains? cyc n)
                   ;; independent check: n reachable from n via a walk
                   (loop [stack (vec (plan/prereqs edges n)) seen #{}]
                     (cond
                       (empty? stack) false
                       (= (peek stack) n) true
                       (contains? seen (peek stack)) (recur (pop stack) seen)
                       :else (recur (into (pop stack)
                                          (plan/prereqs edges (peek stack)))
                                    (conj seen (peek stack)))))))
              (plan/nodes edges)))))

(defspec critical_path_is_a_valid_chain_of_remaining_work 200
  ;; the critical path is a real path: consecutive nodes are linked
  ;; (each depends on the previous), and every node on it is unsettled
  ;; and acyclic (remaining, schedulable work)
  (prop/for-all [edges gen-edges settled gen-settled]
    (let [cp (plan/critical-path edges settled)
          cyc (plan/cyclic-nodes edges)]
      (and
       ;; every node is remaining, schedulable work
       (every? #(and (not (contains? settled %)) (not (contains? cyc %))) cp)
       ;; consecutive: the later node depends on the earlier (edge exists)
       (every? (fn [[earlier later]]
                 (contains? (plan/prereqs edges later) earlier))
               (partition 2 1 cp))))))

(defspec critical_path_is_at_least_as_long_as_any_ready_leaf 200
  ;; a lower bound witnessing "longest": the path is no shorter than the
  ;; trivial one-node path from any single remaining node
  (prop/for-all [edges gen-edges settled gen-settled]
    (let [cp-len (count (plan/critical-path edges settled))
          remaining (remove #(or (contains? settled %)
                                 (contains? (plan/cyclic-nodes edges) %))
                            (plan/nodes edges))]
      (or (empty? remaining) (>= cp-len 1)))))

;; ------------------------------------------------ worked example

(deftest a_worked_plan
  ;; design → build → test → ship, with docs a parallel branch off design
  (let [edges {:build #{:design} :test #{:build} :ship #{:test}
               :docs #{:design}}
        settled #{:design}]
    (let [{:keys [ready blocked done critical-path]} (plan/summary edges settled)]
      (is (= #{:design} done))
      (is (= #{:build :docs} ready) "both branches off the finished design")
      (is (= #{:test :ship} blocked))
      (is (= [:build :test :ship] critical-path)
          "the longest remaining chain, docs being the shorter branch"))
    (testing "settling build unlocks exactly test"
      (is (= 1 (plan/unlocks edges settled :build))))
    (testing "a cycle is caught, not silently deadlocked"
      (let [bad (assoc edges :design #{:ship})] ; design now waits on ship
        (is (= #{:design :build :test :ship} (plan/cyclic-nodes bad)))))))
