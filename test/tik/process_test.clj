;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.process-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [tik.process :as process]))

(def sample (edn/read-string (slurp "processes/support-request.edn")))

(defn- errors [p] (filter #(= :error (:level %)) (process/lint p)))
(defn- warnings [p] (filter #(= :warning (:level %)) (process/lint p)))

(deftest sample-process-is-error-free
  (is (empty? (errors sample)))
  (is (some #(re-find #"bare boolean" (:msg %)) (warnings sample))
      "the deliberate facts-over-flags demo warning"))

(deftest boolean-lint-opt-out-is-explicit
  (let [opted (assoc sample :lint {:boolean-facts :off})]
    (is (not-any? #(re-find #"bare boolean" (:msg %)) (warnings opted)))))

(def strat-violation
  {:process/id :p :process/version 1
   :process/stages
   [{:stage/id :a :guards []}
    ;; :b and :c are the same stratum; :c negates :b -> ADR 0005 error
    {:stage/id :b :after [:a] :guards []}
    {:stage/id :c :after [:a] :guards [[:not [:stage-reached :b]]]}]})

(deftest stratified-negation-enforced
  (is (some #(re-find #"stratified negation" (:msg %))
            (errors strat-violation)))
  (testing "strictly earlier strata are fine"
    (let [p {:process/id :p :process/version 1
             :process/stages [{:stage/id :a :guards []}
                              {:stage/id :b :after [:a]
                               :guards [[:not [:stage-reached :a]]]}]}]
      (is (not-any? #(re-find #"stratified negation" (:msg %)) (errors p))))))

(deftest contradiction-detection-groups-by-path-not-value
  ;; [:fact= p v1] and [:fact= p v2] on the SAME path can never both hold —
  ;; a real contradiction. Two DIFFERENT paths at the same value is
  ;; satisfiable. The check must group by path, not value.
  (let [never? (fn [guard]
                 (->> (process/lint
                       {:process/id :c :process/version 1
                        :process/stages [{:stage/id :a :guards []}
                                         {:stage/id :b :after [:a] :guards [guard]}]})
                      (some #(and (= :error (:level %))
                                  (re-find #"NEVER be satisfied" (:msg %))))
                      boolean))]
    (is (never? [:and [:fact= [:sev] :low] [:fact= [:sev] :high]])
        "same path, two values — a real contradiction (was silently missed)")
    (is (not (never? [:and [:fact= [:x] 1] [:fact= [:y] 1]]))
        "different paths at the same value — satisfiable (was a false error)")))

(deftest closed-guard-basis-enforced
  (testing "operators outside the basis are lint errors, not runtime throws"
    (doseq [g [[:not-stage :a] [:if [:fact [:x]] [:fact [:y]]]]]
      (let [p {:process/id :p :process/version 1
               :process/stages [{:stage/id :a :guards [g]}]}]
        (is (some #(re-find #"not admitted by guard-vocab" (:msg %)) (errors p))
            (pr-str g)))))
  (testing ":fact= is accepted sugar"
    (let [p {:process/id :p :process/version 1
             :process/facts {[:x] :keyword}
             :process/stages [{:stage/id :a :guards [[:fact= [:x] :y]]}]}]
      (is (empty? (errors p))))))

(deftest unknown-stage-refs-are-errors
  (let [p {:process/id :p :process/version 1
           :process/stages [{:stage/id :a
                             :guards [[:stage-reached :ghost]]}]}]
    (is (some #(re-find #"unknown stage :ghost" (:msg %)) (errors p)))))

(deftest process-hash-is-stable-identity
  (is (= (process/process-hash sample)
         (process/process-hash (update sample :process/stages vec))))
  (is (not= (process/process-hash sample)
            (process/process-hash (assoc sample :process/version 2)))))

(deftest roles-gating-inverts-the-signature-graph
  (let [gating (process/roles-gating sample)]
    (is (= [:triaged] (get-in gating [:triager :stages]))
        "the triager gates exactly the stage demanding its signature")
    (is (= ["seb"] (get-in gating [:triager :members])))
    (testing "declared-but-not-gating roles still appear"
      (is (contains? gating :billing))
      (is (= [] (get-in gating [:billing :stages]))))
    (testing "a role used by :signed-by but never declared surfaces memberless"
      (let [p {:process/id :p :process/version 1
               :process/stages [{:stage/id :a
                                 :guards [[:signed-by :ghost [:x]]]}]}]
        (is (= {:members [] :stages [:a]}
               (:ghost (process/roles-gating p))))))))
