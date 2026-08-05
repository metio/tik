;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.impossible-test
  "explain distinguishes waiting on somebody from waiting forever."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.explain :as explain])
  (:import (java.time Instant)))

(def proc
  {:process/id :dead-ends
   :process/version 1
   :process/guard-vocab 2
   :process/roles {:auditor {:members []}
                   :dev {:members ["seb"]}}
   :process/stages
   [{:stage/id :open}
    {:stage/id :signed :after [:open]
     :guards [[:signed-by :auditor [:report]]]}
    {:stage/id :audited :after [:open]
     :guards [[:stage-reached :signed]]}
    {:stage/id :waiting :after [:open]
     :guards [[:fact [:note]]]}
    {:stage/id :locked :after [:open] :stage/sticky? true
     :guards [[:fact [:lock]]]}
    {:stage/id :unlocked :after [:open]
     :guards [[:not [:stage-reached :locked]]]}
    {:stage/id :either :after [:open]
     :guards [[:or [:signed-by :auditor [:report]] [:fact [:note]]]]}]})

(def roles (:process/roles proc))
(def tid #uuid "018f2f6e-7c1a-7000-8000-000000000009")
(defn at [s] (Instant/parse s))
(def now (at "2026-07-08T12:00:00Z"))

(defn- add [events ctor] (conj events (ctor #{(:event/id (peek events))})))

(defn- events-with [facts]
  (reduce (fn [evs [i [path value]]]
            (add evs #(event/assert-fact
                       {:ticket tid :actor "seb" :parents %
                        :at (at (format "2026-07-08T10:%02d:00Z" (inc i)))
                        :path path :value value})))
          (event/chain
           (fn [_] (event/create-ticket {:ticket tid :actor "seb"
                                         :at (at "2026-07-08T10:00:00Z")
                                         :title "dead ends"
                                         :process :dead-ends})))
          (map-indexed vector facts)))

(defn- block [events stage]
  (first (filter #(= stage (:stage %))
                 (explain/explain proc events now roles))))

(deftest a-fact-anyone-can-supply-is-not-impossible
  (let [b (block (events-with []) :waiting)]
    (is (= :fact/missing (:reason (first (:missing b)))))
    (is (not (:impossible? b)))
    (is (not-any? :permanent? (:missing b)))))

(deftest an-empty-role-can-never-sign
  (testing "while the fact is absent the block is merely waiting"
    (let [b (block (events-with []) :signed)]
      (is (= :fact/missing (:reason (first (:missing b)))))
      (is (not (:impossible? b)))))
  (testing "once asserted, no member of the role exists to have signed it"
    (let [b (block (events-with [[[:report] "written"]]) :signed)]
      (is (= :role/unsatisfied (:reason (first (:missing b)))))
      (is (:permanent? (first (:missing b))))
      (is (:impossible? b)))))

(deftest a-dead-prerequisite-stage-is-transitively-impossible
  (let [b (block (events-with [[[:report] "written"]]) :audited)]
    (is (= :stage/not-reached (:reason (first (:missing b)))))
    (is (:impossible? b)
        "the stage it waits on can never be reached, so neither can it")))

(deftest negating-a-reached-sticky-stage-can-never-hold-again
  (let [b (block (events-with [[[:lock] true]]) :unlocked)]
    (is (= :must-not-hold (:reason (first (:missing b)))))
    (is (:impossible? b))))

(deftest a-choice-survives-while-one-branch-lives
  (let [evs (events-with [[[:report] "written"]])
        b (block evs :either)
        [reason] (:missing b)]
    (is (= :alternatives (:reason reason)))
    (is (not (:permanent? reason))
        "the auditor branch is dead but [:fact [:note]] is anyone's to supply")
    (is (not (:impossible? b)))))

(deftest a-choice-dies-when-every-branch-does
  (let [dead-proc (update proc :process/stages
                          (fn [stages]
                            (mapv #(if (= :either (:stage/id %))
                                     (assoc % :guards
                                            [[:or [:signed-by :auditor [:report]]
                                              [:signed-by :auditor [:note]]]])
                                     %)
                                  stages)))
        evs (events-with [[[:report] "written"] [[:note] "n"]])
        b (first (filter #(= :either (:stage %))
                         (explain/explain dead-proc evs now roles)))]
    (is (:impossible? b))
    (is (:permanent? (first (:missing b))))))

(deftest an-impossible-step-belongs-to-nobody
  (let [b (block (events-with [[[:report] "written"]]) :signed)
        [mine] (explain/for-actor [b] roles "seb")]
    (is (empty? (:missing mine)))
    (is (= 1 (:hidden mine))
        "counted as hidden rather than offered as work")))

(deftest render-names-an-unreachable-stage
  (let [text (explain/render [(block (events-with [[[:report] "written"]])
                                     :signed)])]
    (is (re-find #"unreachable" text))
    (is (re-find #"nobody can ever do this" text))))
