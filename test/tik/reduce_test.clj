;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.reduce-test
  (:require [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.reduce :as red])
  (:import (java.time Instant)))

(def tid #uuid "018f2f6e-7c1a-7000-8000-000000000001")
(defn at [s] (Instant/parse s))

(defn evs []
  (event/chain
   (fn [_] (event/create-ticket {:ticket tid :actor "seb"
                                 :at (at "2026-07-08T10:00:00Z")
                                 :title "login broken"
                                 :process :support-request}))
   (fn [p] (event/assert-fact {:ticket tid :actor "seb" :parents p
                               :at (at "2026-07-08T10:01:00Z")
                               :path [:category] :value :billing}))))

(defn add [events ctor]
  (conj events (ctor #{(:event/id (peek events))})))

(deftest mandatory-parents
  (testing "non-root events must reference heads (ADR 0004)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (event/assert-fact {:ticket tid :actor "seb" :parents #{}
                                     :at (at "2026-07-08T10:01:00Z")
                                     :path [:x] :value 1}))))
  (testing "the root must have empty parents (rule lives in mint)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (event/mint {:event/ticket tid :event/type :ticket/create
                              :event/actor "seb"
                              :event/at (at "2026-07-08T10:00:00Z")
                              :event/parents #{"sha256-bogus"}})))
    (is (some? (event/mint {:event/ticket tid :event/type :ticket/create
                            :event/actor "seb"
                            :event/at (at "2026-07-08T10:00:00Z")
                            :event/parents #{}})))))

(deftest supersede-by-assert
  (let [events (add (evs) #(event/assert-fact
                            {:ticket tid :actor "seb" :parents %
                             :at (at "2026-07-08T10:05:00Z")
                             :path [:category] :value :technical}))
        state (red/ticket-state events)]
    (is (= :technical (red/fact-value state [:category])))
    (is (= :present (:status (red/fact-status state [:category]))))
    (is (= [:billing]
           (map :value (get-in state [:facts [:category] :history])))
        "superseded values remain in history")))

(deftest retract-and-dispute-statuses
  (let [retracted (red/ticket-state
                   (add (evs) #(event/retract-fact
                                {:ticket tid :actor "seb" :parents %
                                 :at (at "2026-07-08T10:06:00Z")
                                 :path [:category] :reason "wrong"})))
        disputed (red/ticket-state
                  (add (evs) #(event/dispute-fact
                               {:ticket tid :actor "billing" :parents %
                                :at (at "2026-07-08T10:07:00Z")
                                :path [:category] :reason "not billing"})))]
    (is (nil? (red/fact-value retracted [:category])))
    (is (= {:status :retracted :by "seb" :note "wrong"}
           (select-keys (red/fact-status retracted [:category])
                        [:status :by :note])))
    (is (nil? (red/fact-value disputed [:category])))
    (is (= {:status :disputed :by "billing" :note "not billing"}
           (select-keys (red/fact-status disputed [:category])
                        [:status :by :note])))
    (is (= :absent (:status (red/fact-status disputed [:nonexistent]))))))

(deftest idempotent-under-duplication
  (let [events (evs)]
    (is (= (red/ticket-state events)
           (red/ticket-state (concat events events)))
        "merge is set union, literally: duplicates collapse before the fold")))

(deftest order-independence
  (let [events (evs)]
    (is (= (red/ticket-state events) (red/ticket-state (shuffle events))))))

(deftest reducer-is-total-over-unknown-types
  (let [weird {:event/id "sha256-x" :event/ticket tid
               :event/type :something/new :event/actor "future"
               :event/at (at "2026-07-08T10:08:00Z") :event/parents #{"sha256-y"}}
        state (red/ticket-state (conj (evs) weird))]
    (is (= :billing (red/fact-value state [:category])))
    (is (= 3 (count (:log state))) "unknown events are kept in the log")))
