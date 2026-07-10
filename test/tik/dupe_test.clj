;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.dupe-test
  (:require [clojure.test :refer [deftest is testing]]
            [tik.dupe :as dupe]))

(deftest similarity_behaves_like_a_judgment_should
  (testing "same words, any order or case, is 1.0"
    (is (= 1.0 (dupe/similarity "Login fails on Firefox"
                                "firefox fails login"))))
  (testing "nothing shared is 0.0"
    (is (= 0.0 (dupe/similarity "billing invoice wrong"
                                "deploy pipeline broken"))))
  (testing "stopwords and single letters carry no signal"
    (is (= 1.0 (dupe/similarity "the login fails" "a login fails")))
    (is (= 0.0 (dupe/similarity "the a an of" "with on is are"))))
  (testing "empty inputs are 0.0, never a crash"
    (is (= 0.0 (dupe/similarity "" "")))
    (is (= 0.0 (dupe/similarity nil "words here")))))

(deftest lookalikes_pairs_open_rows_best_first
  (let [rows [{:id "aaa" :text "login fails on firefox after update"}
              {:id "bbb" :text "firefox login failure since the update"}
              {:id "ccc" :text "purchase order for new laptops"}
              {:id "ddd" :text "login fails on firefox after update"}]
        pairs (dupe/lookalikes rows 0.5)]
    (is (= [["aaa" "ddd"]] (map (juxt :a :b) (take 1 pairs)))
        "identical texts score highest")
    (is (not-any? #(or (= "ccc" (:a %)) (= "ccc" (:b %))) pairs)
        "the unrelated ticket pairs with nothing")
    (is (every? #(>= (:score %) 0.5) pairs))))

(deftest radar_warns_about_candidates
  (let [rows [{:id "aaa" :text "login fails on firefox"}
              {:id "bbb" :text "purchase order laptops"}]]
    (is (= ["aaa"] (map :id (dupe/radar "firefox login fails again"
                                        rows 0.5))))
    (is (empty? (dupe/radar "something entirely different" rows 0.5)))))
