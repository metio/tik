;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.causal-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [tik.causal :as causal]
            [tik.event :as event])
  (:import (java.time Instant)))

(def process (edn/read-string (slurp "processes/support-request.edn")))
(def roles (:process/roles process))
(def tid (random-uuid))
(def ^Instant t0 (Instant/parse "2026-01-01T00:00:00Z"))

(def events
  (event/chain
   (fn [_] (event/create-ticket {:ticket tid :actor "seb" :at t0
                                 :title "causal" :process :support-request}))
   #(event/assert-fact {:ticket tid :actor "seb" :at (.plusSeconds t0 1)
                        :parents % :path [:category] :value :technical})
   #(event/assert-fact {:ticket tid :actor "seb" :at (.plusSeconds t0 2)
                        :parents % :path [:severity] :value :high})
   #(event/attach-artifact {:ticket tid :actor "seb" :at (.plusSeconds t0 3)
                            :parents % :path "repro/steps.md"
                            :hash "sha256-cafe"})))

(deftest every_supporting_event_is_real_and_the_right_kind
  (let [by-id (into {} (map (juxt :event/id identity)) events)
        blocks (causal/causal process events (.plusSeconds t0 10) roles)
        by-stage (into {} (map (juxt :stage :support)) blocks)]
    (testing "the reached set drives which stages appear"
      (is (= #{:received :triaged :reproducible} (set (keys by-stage)))))
    (testing "triaged is supported by the category and severity asserts"
      (let [evs (mapcat :events (by-stage :triaged))]
        (is (every? by-id evs))
        (is (= #{[:category] [:severity]}
               (set (map #(get-in (by-id %) [:event/body :fact/path])
                         evs))))))
    (testing "reproducible cites the artifact attach"
      (is (some #(= :artifact/attach
                    (:event/type (by-id (first (:events %)))))
                (by-stage :reproducible))))
    (testing "supersession moves support to the surviving event"
      (let [e2 (event/assert-fact {:ticket tid :actor "billing"
                                   :at (.plusSeconds t0 5)
                                   :parents #{(:event/id (last events))}
                                   :path [:severity] :value :low})
            blocks (causal/causal process (conj events e2)
                                  (.plusSeconds t0 10) roles)
            triaged (:support (first (filter #(= :triaged (:stage %))
                                             blocks)))]
        (is (some #(= [(:event/id e2)]
                      (:events %))
                  triaged)
            "the superseding assert is now the consumed evidence")))))
