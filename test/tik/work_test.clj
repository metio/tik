;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.work-test
  "The work lens's honesty invariants: sessions cluster by the declared
  gap, every session traces to its event ids, durations respect the
  floor, and usage totals are pure folds."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.work :as work])
  (:import (java.time Duration Instant)))

(defn- ev [id t]
  {:event/id id :event/at (Instant/parse t) :event/actor "seb"})

(deftest sessions-cluster-by-the-declared-gap
  (let [ss (work/sessions [(ev "a" "2026-07-10T10:00:00Z")
                           (ev "b" "2026-07-10T10:20:00Z")   ; <=30m: same
                           (ev "c" "2026-07-10T11:30:00Z")   ; >30m: new
                           (ev "d" "2026-07-10T11:40:00Z")])]
    (is (= 2 (count ss)))
    (testing "every session traces to its producing events"
      (is (= [["a" "b"] ["c" "d"]] (map :events ss))))
    (testing "duration = last - first"
      (is (= (Duration/parse "PT20M")
             (work/session-duration (first ss)))))))

(deftest single-event-sessions-get-the-floor-never-zero
  (let [[s] (work/sessions [(ev "a" "2026-07-10T10:00:00Z")])]
    (is (= (Duration/parse "PT5M") (work/session-duration s))
        "a point proves presence, the floor is the declared minimum")))

(deftest usage-totals-fold-and-price-only-on-request
  (let [records [{:agent/model :m :usage {:input-tokens 100
                                          :output-tokens 50}}
                 {:agent/model :m :usage {:input-tokens 900
                                          :cache-read-tokens 1000}}]
        raw (work/usage-totals records nil)
        priced (work/usage-totals records
                                  {:m {:input 3.0 :output 15.0
                                       :cache-read 0.3}})]
    (is (= {:input-tokens 1000 :output-tokens 50
            :cache-read-tokens 1000 :cache-write-tokens 0}
           (get-in raw [:observations :m])))
    (is (nil? (:priced raw)) "no pricing table, no money — ever")
    (is (pos? (get-in priced [:priced :m])))))
