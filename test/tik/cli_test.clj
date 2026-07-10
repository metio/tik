;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.cli-test
  "The porcelain's parsing contract — the H9 surface: values people
  actually type must round-trip without EDN knowledge."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]))

(def parse-value #'cli/parse-value)

(deftest values_people_actually_type
  (testing "one complete EDN form stays EDN"
    (is (= 120 (parse-value "120")))
    (is (= :green (parse-value ":green")))
    (is (= "quoted" (parse-value "\"quoted\"")))
    (is (= [1 2] (parse-value "[1 2]"))))
  (testing "bare single words become keywords (facts over strings)"
    (is (= :billing (parse-value "billing")))
    (is (= :tik-author (parse-value "tik-author"))))
  (testing "everything else is the literal string, never a die"
    (is (= "tik author: guided interview" (parse-value "tik author: guided interview")))
    (is (= "31ffd53" (parse-value "31ffd53")))
    (is (= "hello world" (parse-value "hello world")))
    (is (= "a,b{" (parse-value "a,b{")))))
