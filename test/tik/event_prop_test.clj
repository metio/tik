;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.event-prop-test
  "Content addressing of events: minting is stable and self-verifying,
  and any tampering with the hashed region changes the id."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.event :as event]
            [tik.gen-events :as ge]))

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
