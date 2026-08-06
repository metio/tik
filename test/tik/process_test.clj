;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.process-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [tik.lint :as lint]
            [tik.process :as process]))

(def sample (edn/read-string (slurp "processes/support-request.edn")))

(defn- errors [p] (filter #(= :error (:level %)) (lint/lint p)))
(defn- warnings [p] (filter #(= :warning (:level %)) (lint/lint p)))

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
                 (->> (lint/lint
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

(deftest elapsed-since-reference-must-be-a-known-clock-anchor
  ;; guard/eval-elapsed resolves only :ticket/create; any other ref
  ;; throws on EVERY derivation of a ticket pinned to the definition.
  ;; Lint validates the closed set exactly as it validates the duration.
  (let [bad {:process/id :p :process/version 1
             :process/stages
             [{:stage/id :a :guards []}
              {:stage/id :b :after [:a]
               :guards [[:elapsed-since :ticket/created "PT48H"]]}]}   ; typo
        good (assoc-in bad [:process/stages 1 :guards 0 1] :ticket/create)]
    (is (some #(re-find #":elapsed-since reference :ticket/created is unknown"
                        (:msg %))
              (errors bad)))
    (is (not-any? #(re-find #":elapsed-since reference" (:msg %))
                  (errors good)))))

(deftest pathless-signed-by-is-a-lint-error
  ;; a :signed-by with no over-path ranges over the fact at path nil,
  ;; whose status is always :absent — the guard can never pass, so the
  ;; stage is permanently blocked (and [:not [:signed-by :r]] is
  ;; vacuously true). Lint rejects it; the path form is fine.
  (let [without {:process/id :p :process/version 1
                 :process/roles {:r {:members ["a"]}}
                 :process/stages
                 [{:stage/id :a :guards []}
                  {:stage/id :b :after [:a] :guards [[:signed-by :r]]}]}
        bare (assoc-in without [:process/stages 1 :guards 0] [:signed-by])
        with (assoc-in without [:process/stages 1 :guards 0]
                       [:signed-by :r [:ok]])]
    (is (some #(re-find #":signed-by over no fact" (:msg %)) (errors without)))
    (is (some #(re-find #":signed-by over no fact" (:msg %)) (errors bare)))
    (is (not-any? #(re-find #":signed-by over no fact" (:msg %)) (errors with)))))

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

;; ---------------------------------------------------- schemas must be data

(def ^:private sexp-schema
  "A malli `:fn` whose child is an s-expression. On a runtime carrying
  sci, compiling this schema EVALUATES the string — so a definition
  could run whatever it liked inside derivation, and a definition
  arrives from evidence bundles strangers produce."
  [:fn "(fn [_] (throw (ex-info \"a schema called something\" {})))"])

(deftest a_schema_that_can_call_a_function_never_compiles
  (is (false? (process/schema-compiles? sexp-schema)))
  (is (false? (process/schema-compiles? [:multi {:dispatch "(fn [_] :a)"}
                                         [:a :any]])))
  (testing "and every data schema still does"
    (is (true? (process/schema-compiles? [:string {:min 6}])))
    (is (true? (process/schema-compiles? [:enum :ship :hold])))
    (is (true? (process/schema-compiles? [:map-of [:vector :keyword] :any])))
    (is (true? (process/schema-compiles? [:and :int [:> 3]])))))

(deftest evaluating_such_a_schema_refuses_rather_than_runs_it
  ;; the s-expression throws if it is ever called, so reaching a plain
  ;; ::unsupported is the whole assertion
  (is (= :tik.process/unsupported (process/schema-holds? sexp-schema {})))
  (is (nil? (process/schema-errors sexp-schema {}))))

(deftest lint_names_a_schema_that_is_not_data
  (let [p {:process/id :p :process/version 1 :lint {:runbooks :off}
           :process/facts {[:a] sexp-schema}
           :process/stages [{:stage/id :s :guards [[:malli sexp-schema]]}]}
        msgs (map :msg (errors p))]
    (is (some #(re-find #"fact \[:a\] carries a schema that is not data" %) msgs))
    (is (some #(re-find #":malli guard carries a schema that is not data" %)
              msgs))))
