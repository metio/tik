;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.reach-prototype-test
  "PROTOTYPE (IDEAS.md: 'Formal verification of the user's process
  definition'). A process definition is a small state machine over a
  CLOSED guard basis, so 'can this stage ever fire?' is a decidable
  satisfiability question — answered here in pure Clojure, no external
  solver, over a definition alone (authoring-time analysis, ADR 0009;
  the kernel never runs this).

  Two analyses:
  - satisfiable?  — is there ANY world (facts, signatures, reached
    stages, elapsed time) in which a guard holds? A guard is a boolean
    formula over independent atoms, except that a fact cannot equal two
    different values at once, and fact= implies fact-present.
  - dead-stages   — stages no evidence can ever reach: an unsatisfiable
    guard, or a guard that depends (via :stage-reached) only on stages
    that are themselves unreachable. A least-fixpoint over the :after
    graph.

  This is prototype scope: the algorithm and its tests, not a CLI
  command or kernel placement — those await a PLAN §19 verdict on the
  feature. SMT for larger guards and author-stated invariant checking
  are the documented extensions."
  (:require [clojure.test :refer [deftest is testing]]))

;; ------------------------------------------------ atom collection

(defn- guard-atoms
  "Every atomic (non-combinator) guard in the tree."
  [guard]
  (cond
    (not (vector? guard)) #{}
    (#{:and :or} (first guard)) (into #{} (mapcat guard-atoms) (rest guard))
    (= :not (first guard)) (guard-atoms (second guard))
    :else #{guard}))

;; ------------------------------------ boolean evaluation + constraints

(defn- holds?
  "Does `guard` hold under `truth` (a map atom->bool)? Combinators
  recurse; atoms read their truth value (default false)."
  [guard truth]
  (cond
    (not (vector? guard)) false
    (= :and (first guard)) (every? #(holds? % truth) (rest guard))
    (= :or (first guard)) (boolean (some #(holds? % truth) (rest guard)))
    (= :not (first guard)) (not (holds? (second guard) truth))
    :else (boolean (truth guard))))

(defn- consistent?
  "Reject truth assignments that no real world could produce: two
  fact= on the SAME path with different values cannot both be true, and
  a true fact= implies the path is present."
  [truth present-atoms]
  (let [true-eqs (filter (fn [[g v]] (and v (vector? g) (= :fact= (first g))))
                         truth)
        by-path (group-by (comp second first) true-eqs)]
    (and
     ;; at most one value per path
     (every? (fn [[_ eqs]] (>= 1 (count (distinct (map (comp #(nth % 2) first) eqs)))))
             by-path)
     ;; fact= present ⇒ the [:fact path] atom (if it exists) is present
     (every? (fn [[g _]]
               (let [fact-atom [:fact (second g)]]
                 (or (not (contains? present-atoms fact-atom))
                     (truth fact-atom))))
             true-eqs))))

(defn- assignments
  "All truth maps over the given atoms (2^n; guards are small)."
  [atoms]
  (let [atoms (vec atoms)]
    (reduce (fn [maps a] (mapcat (fn [m] [(assoc m a true) (assoc m a false)]) maps))
            [{}]
            atoms)))

(defn satisfiable?
  "Is there a world in which `guard` holds? Empty/nil guard is vacuously
  true (an unguarded stage reaches immediately). `reachable` limits
  which :stage-reached atoms may be true — a stage cannot depend on an
  unreachable one."
  ([guard] (satisfiable? guard nil))
  ([guard reachable]
   (let [atoms (guard-atoms guard)]
     (if (empty? atoms)
       true
       (let [present (set atoms)]
         (boolean
          (some (fn [truth]
                  (and (consistent? truth present)
                       ;; stage-reached atoms only true for reachable stages
                       (every? (fn [[g v]]
                                 (or (not v) (not (vector? g))
                                     (not= :stage-reached (first g))
                                     (nil? reachable)
                                     (contains? reachable (second g))))
                               truth)
                       (holds? guard truth)))
                (assignments atoms))))))))

;; ------------------------------------------------ reachability

(defn- stage-guard [stage]
  (let [gs (:guards stage [])]
    (case (count gs) 0 nil 1 (first gs) (into [:and] gs))))

(defn reachable-stages
  "Least fixpoint: a stage is reachable when its guard is satisfiable in
  a world whose reached :stage-reached atoms are themselves reachable."
  [process]
  (let [stages (:process/stages process)]
    (loop [reached #{}]
      (let [grown (into reached
                        (comp (remove #(reached (:stage/id %)))
                              (filter #(satisfiable? (stage-guard %) reached))
                              (map :stage/id))
                        stages)]
        (if (= grown reached) reached (recur grown))))))

(defn dead-stages
  "Stage ids no evidence can ever reach."
  [process]
  (let [reached (reachable-stages process)]
    (->> (:process/stages process)
         (map :stage/id)
         (remove reached)
         set)))

;; ------------------------------------------------------- tests

(deftest satisfiable_over_the_basis
  (testing "an unguarded stage is vacuously reachable"
    (is (satisfiable? nil))
    (is (satisfiable? [:and])))
  (testing "plain positive guards are satisfiable"
    (is (satisfiable? [:fact [:x]]))
    (is (satisfiable? [:or [:fact [:x]] [:signed-by :cfo]]))
    (is (satisfiable? [:and [:fact [:x]] [:elapsed-since :ticket/create "PT1H"]])))
  (testing "a contradiction is UNsatisfiable"
    (is (not (satisfiable? [:and [:fact [:x]] [:not [:fact [:x]]]]))))
  (testing "a fact cannot equal two values at once"
    (is (not (satisfiable? [:and [:fact= [:sev] :low] [:fact= [:sev] :high]])))
    (is (satisfiable? [:or [:fact= [:sev] :low] [:fact= [:sev] :high]]))))

(deftest dead_stage_detection
  (testing "a healthy linear process has no dead stages"
    (let [p {:process/stages
             [{:stage/id :a :guards []}
              {:stage/id :b :after [:a]
               :guards [[:and [:fact [:x]] [:stage-reached :a]]]}
              {:stage/id :c :after [:b] :guards [[:stage-reached :b]]}]}]
      (is (= #{} (dead-stages p)))
      (is (= #{:a :b :c} (reachable-stages p)))))
  (testing "a self-contradictory guard makes its stage dead"
    (let [p {:process/stages
             [{:stage/id :a :guards []}
              {:stage/id :dead
               :guards [[:and [:fact= [:sev] :low] [:fact= [:sev] :high]]]}]}]
      (is (= #{:dead} (dead-stages p)))))
  (testing "a stage gated only on a dead stage is itself dead"
    (let [p {:process/stages
             [{:stage/id :a :guards []}
              {:stage/id :dead
               :guards [[:and [:fact [:x]] [:not [:fact [:x]]]]]}
              {:stage/id :orphan :after [:dead]
               :guards [[:stage-reached :dead]]}]}]
      (is (= #{:dead :orphan} (dead-stages p)))
      (is (= #{:a} (reachable-stages p))))))
