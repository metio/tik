;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.event-prop-test
  "Content addressing of events: minting is stable and self-verifying,
  and any tampering with the hashed region changes the id."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.canonical :as canonical]
            [tik.event :as event]
            [tik.gen-events :as ge]
            [tik.reduce :as red])
  (:import (java.time Instant)))

(defspec every-event-id-verifies 100
  ;; sha256(canonical bytes without :event/id) IS the id — for every
  ;; event the generators can produce, including hand-minted unknowns
  (prop/for-all [events ge/gen-events]
    (every? #(= (:event/id %) (event/event-id %)) events)))

(defspec minting-is-idempotent 100
  (prop/for-all [events ge/gen-events]
    (every? (fn [e]
              (or (= :something/new (:event/type e))
                  (= e (event/mint (dissoc e :event/id)))))
            events)))

(defspec minted-events-survive-the-store-unchanged 100
  ;; The bytes ARE the hashed region (ADR 0007), so what a store hands
  ;; back must be the very map that was minted. When it is not, (at, id)
  ;; order becomes a property of how far an event has travelled rather
  ;; than of the event set, and commutativity holds only within one
  ;; side of the serialization boundary.
  (prop/for-all [events ge/gen-events]
    (every? (fn [e]
              (let [region (dissoc e :event/id)]
                (= region (canonical/parse (canonical/emit region)))))
            events)))

(deftest mint-normalizes-a-nanosecond-clock
  (testing "Instant/now has nanosecond precision; the canonical form has
            milliseconds, and the minted event carries the canonical one"
    (let [e (event/assert-fact
             {:ticket ge/tid :actor "seb"
              :at (Instant/parse "2026-07-29T10:00:00.000999999Z")
              :parents #{"sha256-parent"} :path [:category] :value :billing})]
      (is (= (Instant/parse "2026-07-29T10:00:00Z") (:event/at e)))
      (is (= (dissoc e :event/id)
             (canonical/parse (canonical/emit (dissoc e :event/id))))))))

(deftest same-millisecond-mints-collapse-to-one-event
  (testing "two raw clock readings inside one millisecond are one claim:
            equal bytes, equal id, and dedupe keeps exactly one"
    (let [mk (fn [t] (event/assert-fact
                      {:ticket ge/tid :actor "seb" :at (Instant/parse t)
                       :parents #{"sha256-parent"} :path [:category]
                       :value :billing}))
          a (mk "2026-07-29T10:00:00.000111Z")
          b (mk "2026-07-29T10:00:00.000999Z")]
      (is (= (:event/at a) (:event/at b)))
      (is (= (:event/id a) (:event/id b)))
      (is (= 1 (count (red/dedupe-events [a b])))))))

(defspec tampering-changes-the-id 100
  (prop/for-all [events ge/gen-events
                 pick gen/nat]
    (let [e (nth events (mod pick (count events)))]
      (and (not= (event/event-id e)
                 (event/event-id (update e :event/actor str "-evil")))
           (not= (event/event-id e)
                 (event/event-id (update e :event/at
                                         #(.plusSeconds ^java.time.Instant % 1))))
           (not= (event/event-id e)
                 (event/event-id (assoc-in e [:event/body :injected] true)))
           (not= (event/event-id e)
                 (event/event-id (update e :event/parents conj "sha256-bogus")))))))
