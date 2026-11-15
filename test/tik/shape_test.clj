;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.shape-test
  "The boundary contracts: each check is the identity on what it accepts,
  one ex-info on what it does not, and invisible to valid derivation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [tik.gen-events :as ge]
            [tik.shape :as shape]
            [tik.stage :as stage])
  (:import (java.time Instant)))

(defn- rejection
  "The ex-data `f` throws on `args`, or ::accepted."
  [f & args]
  (let [thunk #(apply f args)]
    (try (thunk) ::accepted
         (catch clojure.lang.ExceptionInfo e (ex-data e)))))

(def ^:private t0 (Instant/parse "2026-07-08T10:00:00Z"))

(def ^:private ev
  {:event/id "sha256-a" :event/at t0 :event/type :fact/assert
   :event/parents #{"sha256-0"} :event/body {:fact/path [:x] :fact/value 1}})

(deftest checks_return_their_argument_unchanged
  (is (= {:a 1} (shape/plain-map :m {:a 1})))
  (is (nil? (shape/plain-map :m nil)))
  (is (= [1] (shape/plain-coll :c [1])))
  (is (= #{1} (shape/plain-set :s #{1})))
  (is (= [{:a 1}] (shape/maps :ms [{:a 1}])))
  (is (= t0 (shape/instant :t t0)))
  (is (nil? (shape/optional-instant :t nil)))
  (is (= "x" (shape/string :s "x")))
  (is (= inc (shape/function :f inc)))
  (is (= [ev] (shape/events [ev])))
  (is (= ge/process (shape/process ge/process)))
  (is (= ge/roles (shape/roles ge/roles))))

(deftest a_rejection_names_the_argument_and_what_it_expected
  (is (= {:reason :kernel/malformed-argument :argument :now
          :expected :instant :value "2026"}
         (rejection shape/instant :now "2026")))
  (is (= :map (:expected (rejection shape/plain-map :spec '(0 0 0)))))
  (is (= :set (:expected (rejection shape/plain-set :reached [:a]))))
  (is (= :function (:expected (rejection shape/function :step [0]))))
  (is (= :maps (:expected (rejection shape/maps :records [{} 1])))))

(deftest sorted_collections_are_not_kernel_data
  (testing "a keyword lookup into (sorted-set 1 2) would throw from the comparator"
    (is (= :map (:expected (rejection shape/plain-map :m (sorted-map 1 2)))))
    (is (= :collection (:expected (rejection shape/plain-coll :c (sorted-set 1 2)))))
    (is (= :set (:expected (rejection shape/plain-set :s (sorted-set 1 2)))))
    (is (nil? (shape/field (sorted-set 1 2) :event/id)))
    (is (= :event/malformed (:reason (rejection shape/event (into (sorted-map) ev)))))))

(deftest an_event_must_carry_what_ordering_and_folding_read
  (doseq [[why e] {"not a map" [1 2]
                   "id not a string" (assoc ev :event/id 7)
                   "at a Double" (assoc ev :event/at 1.5)
                   "at a string" (assoc ev :event/at "2026-07-08T10:00:00Z")
                   "at a Date" (assoc ev :event/at (java.util.Date. 0))
                   "type not a keyword" (assoc ev :event/type "fact/assert")
                   "body a set" (assoc ev :event/body #{1 2})
                   "body a list" (assoc ev :event/body '(:a 1 :b))}]
    (is (= :event/malformed (:reason (rejection shape/event e))) why))
  (is (= :collection (:expected (rejection shape/events true))))
  (let [bare (dissoc ev :event/body)]
    (is (= bare (shape/event bare)) "a body may be absent")))

(deftest malformed_parents_read_as_none_instead_of_failing
  (is (= #{"a" "b"} (shape/parent-ids {:event/parents ["a" "b" "a"]})) "a set, whatever came in")
  (is (= #{"a"} (shape/parent-ids {:event/parents '("a")})))
  (doseq [p [7 "a" :k {"a" "b"} #{1 2} (sorted-set "a") ["a" 1] [nil]]]
    (is (nil? (shape/parent-ids {:event/parents p})) (pr-str p))))

(deftest a_process_must_have_stage_maps_with_collection_edges
  (doseq [[why p] {"not a map" [:stages]
                   "stages a scalar" {:process/stages true}
                   "stage a sorted set" {:process/stages [(sorted-set 1 2)]}
                   "stage a scalar" {:process/stages [7]}
                   "after a scalar" {:process/stages [{:stage/id :a :after :b}]}
                   "guards a map" {:process/stages [{:stage/id :a :guards (sorted-map 1 2)}]}
                   "roles a list" {:process/stages [] :process/roles '(:a 1)}}]
    (is (= :kernel/malformed-argument (:reason (rejection shape/process p))) why))
  (is (= {} (shape/process {})) "an empty definition derives nothing, but derives"))

(deftest roles_map_a_role_to_its_members
  (is (nil? (shape/roles nil)))
  (doseq [r [[:a] {:a 1} {:a {:members 5}} {:a (sorted-map 1 2)} (sorted-map :a {})]]
    (is (= :kernel/malformed-argument (:reason (rejection shape/roles r))) (pr-str r))))

(deftest state_indexes_must_be_maps
  (is (= {} (shape/state {})))
  (doseq [s [7 '(0 0 0) {:facts 1} {:writes [1]} {:parents (sorted-map 1 2)} {:log 5}]]
    (is (= :kernel/malformed-argument (:reason (rejection shape/state s))) (pr-str s))))

(defspec valid_logs_pass_every_check_unchanged 100
  (prop/for-all [events ge/gen-events]
    (and (= events (shape/events events))
         (every? #(= % (shape/event %)) events))))

(defspec the_checks_leave_valid_derivation_alone 50
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (set? (stage/effective-reached ge/process events now ge/roles))))
