;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.stats-test
  "The stats lens: its arithmetic over valid rows, and its boundary over
  rows that are not."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.stats :as stats]))

(defn- rejection [f & args]
  (let [thunk #(apply f args)]
    (try (thunk) ::accepted
         (catch clojure.lang.ExceptionInfo e (:expected (ex-data e))))))

(deftest a_duration_summary_is_nearest_rank
  (is (nil? (stats/duration-summary [])))
  (is (nil? (stats/duration-summary nil)))
  (is (= {:n 4 :mean-ms 25 :median-ms 20 :p90-ms 40 :max-ms 40}
         (stats/duration-summary [40 10 30 20]))))

(deftest sums_and_differences_promote_instead_of_overflowing
  (is (= Long/MAX_VALUE
         (:mean-ms (stats/duration-summary [Long/MAX_VALUE Long/MAX_VALUE]))))
  (let [[g] (stats/group-stats [{:facts {} :created-ms Long/MIN_VALUE
                                 :reached-ms {:done Long/MAX_VALUE}}]
                               {:until :done})]
    (is (= (-' Long/MAX_VALUE Long/MIN_VALUE) (get-in g [:duration :max-ms])))))

(deftest rows_and_options_are_typed
  (testing "instants are integer milliseconds"
    (is (= :integer (rejection stats/duration-summary [1.5])))
    (is (= :integer (rejection stats/duration-summary [nil])))
    (is (= :integer (rejection stats/group-stats [{:created-ms "0"}] {})))
    (is (= :integer (rejection stats/group-stats
                               [{:created-ms 0 :reached-ms {:done ##NaN}}] {}))))
  (testing "rows, facts, reached and options are plain maps"
    (is (= :maps (rejection stats/group-stats [7] {})))
    (is (= :collection (rejection stats/group-stats true {})))
    (is (= :map (rejection stats/group-stats
                           [{:created-ms 0 :facts (sorted-map 1 2)}] {:by [:x]})))
    (is (= :map (rejection stats/group-stats [{:created-ms 0 :reached-ms [1]}] {})))
    (is (= :map (rejection stats/group-stats [] '(:by [:x] :split))))
    (is (= :collection (rejection stats/duration-summary 5))))
  (is (= [] (stats/group-stats [] nil)) "no rows, no groups"))
