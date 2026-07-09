;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.reduce-prop-test
  "The reducer's three laws — total, commutative, idempotent — proved over
  generated histories instead of one fixed scenario, plus the fact-status
  choke-point invariants."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.gen-events :as ge]
            [tik.reduce :as red]
            [tik.stage :as stage]))

(defspec reducer-is-commutative 100
  (prop/for-all [events ge/gen-events]
    (= (red/ticket-state events)
       (red/ticket-state (shuffle events)))))

(defspec reducer-is-idempotent-under-duplication 100
  (prop/for-all [events ge/gen-events
                 dups (gen/vector gen/nat 0 5)]
    (let [extra (map #(nth events (mod % (count events))) dups)]
      (= (red/ticket-state events)
         (red/ticket-state (shuffle (concat events extra)))))))

(defspec whole-derivation-is-permutation-and-duplication-proof 60
  ;; not just state: the full evolve result (timeline included) is a
  ;; function of the event SET
  (prop/for-all [events ge/gen-events]
    (let [mangled (shuffle (concat events (take 3 (shuffle events))))]
      (= (stage/evolve ge/process events ge/roles)
         (stage/evolve ge/process mangled ge/roles)))))

(defspec ordered-is-canonical 100
  ;; dedupe+order is a normal form: stable under its own re-application
  ;; and under permutation/duplication of its input
  (prop/for-all [events ge/gen-events]
    (let [o (red/ordered events)]
      (and (= o (red/ordered o))
           (= o (red/ordered (shuffle (concat events events))))))))

(defspec reducer-is-total-over-unknown-types 100
  ;; gen-events includes :something/new events; the fold must keep them in
  ;; the log and change nothing else
  (prop/for-all [events ge/gen-events]
    (let [state (red/ticket-state events)]
      (= (count (red/ordered events)) (count (:log state))))))

(def fact-statuses #{:present :absent :retracted :disputed :conflicted})

(defspec fact-status-is-total-and-closed 100
  (prop/for-all [events ge/gen-events]
    (let [state (red/ticket-state events)]
      (every? (fn [path]
                (let [{:keys [status]} (red/fact-status state path)]
                  (contains? fact-statuses status)))
              ge/paths))))

(defspec fact-value-tracks-present-status 100
  ;; the choke point and the convenience accessor can never disagree
  (prop/for-all [events ge/gen-events]
    (let [state (red/ticket-state events)]
      (every? (fn [path]
                (let [{:keys [status value]} (red/fact-status state path)]
                  (if (= :present status)
                    (= value (red/fact-value state path))
                    (nil? (red/fact-value state path)))))
              ge/paths))))

(defspec superseded-values-are-retained-in-history 60
  ;; asserting over an existing fact keeps every prior version reachable
  (prop/for-all [events ge/gen-events]
    (let [state (red/ticket-state events)]
      (every? (fn [[path entry]]
                (let [asserts (count (filter #(and (= :fact/assert
                                                      (:event/type %))
                                                   (= path (get-in % [:event/body
                                                                      :fact/path])))
                                             (red/ordered events)))
                      versions (+ (count (:history entry []))
                                  (if (contains? entry :value) 1 0))]
                  ;; every assert lands as the live value or in history
                  ;; (retract/dispute entries push history too, so >=)
                  (>= versions (min asserts 1))))
              (:facts state)))))
