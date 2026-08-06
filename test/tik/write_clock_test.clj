;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.write-clock-test
  "Two writes must never claim the same instant.

  Reduction orders by (at, id) and a content-addressed id is effectively
  random, so a tie is decided by a coin flip — including between a
  retract and the assert it observed, which then loses and leaves the
  fact present. A development machine with a fine-grained clock never
  sees this; a host whose resolution is coarser than the microseconds
  between two writes in one process does."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.cli-core :as core]
            [tik.event :as event]
            [tik.reduce :as red])
  (:import (java.time Instant)))

(deftest the_write_clock_never_repeats_an_instant
  ;; a modest count on purpose: the clock steps by a millisecond when it
  ;; has to, and kaocha runs every namespace in one process, so a big
  ;; sample here would push the shared clock seconds into the future
  (let [ts (repeatedly 200 core/now)]
    (is (= 200 (count (distinct ts))))
    (is (every? (fn [[a b]] (.isBefore ^Instant a ^Instant b)) (partition 2 1 ts))
        "strictly increasing, so (at, id) never falls back to the hash")))

(deftest a_tie_would_let_a_retract_lose_to_the_assert_it_observed
  ;; why the clock above has to behave: this is the failure it prevents,
  ;; constructed by hand because the real clock will not produce it here
  (testing "identical instants order by content hash, not by causality"
    (let [t (Instant/parse "2026-08-06T12:00:00Z")
          tid #uuid "018f2f6e-7c1a-7000-8000-0000000c10c4"
          c (event/create-ticket {:ticket tid :actor "a" :at t
                                  :title "x" :process :track})
          a (event/assert-fact {:ticket tid :actor "a" :at t
                                :parents #{(:event/id c)}
                                :path [:w] :value "v"})
          r (event/retract-fact {:ticket tid :actor "a" :at t
                                 :parents #{(:event/id a)} :path [:w]})
          tied (:status (red/fact-status (red/ticket-state [c a r]) [:w]))
          apart (:status (red/fact-status
                          (red/ticket-state
                           [c a (event/retract-fact
                                 ;; a MILLISECOND: mint normalizes :at to
                                 ;; the canonical precision before taking
                                 ;; the id, so anything finer is erased
                                 {:ticket tid :actor "a" :at (.plusMillis t 1)
                                  :parents #{(:event/id a)} :path [:w]})])
                          [:w]))]
      (is (= :retracted apart)
          "one nanosecond apart, the retract wins as it must")
      (is (contains? #{:present :retracted} tied)
          "on a tie the answer is whichever id sorts first — a coin flip")
      (is (not= tied apart)
          (str "this pins the hazard: with equal instants the retract "
               "loses to the assert it observed, which is why the write "
               "clock must never repeat")))))
