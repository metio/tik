;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.dispute-test
  "A dispute rejects a specific claim: it stands until a DIFFERENT value
  supersedes it, the claim is retracted, or the disputer takes it back."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.reduce :as red])
  (:import (java.time Instant)))

(def ^:private tid #uuid "018f2f6e-7c1a-7000-8000-00000000d15b")
(def ^:private t0 (Instant/parse "2026-07-08T10:00:00Z"))
(def ^:private path [:category])

(defn- at [n] (.plusSeconds ^Instant t0 (long n)))
(defn- add [events ctor] (conj events (ctor #{(:event/id (peek events))})))

(def ^:private base
  (event/chain
   (fn [_] (event/create-ticket {:ticket tid :actor "seb" :at t0
                                 :title "d" :process :p}))))

(defn- assert! [evs n actor value]
  (add evs #(event/assert-fact {:ticket tid :actor actor :parents %
                                :at (at n) :path path :value value})))

(defn- dispute! [evs n actor & {:keys [withdraw]}]
  (add evs #(event/dispute-fact {:ticket tid :actor actor :parents %
                                 :at (at n) :path path :reason "no"
                                 :withdraw withdraw})))

(defn- retract! [evs n actor]
  (add evs #(event/retract-fact {:ticket tid :actor actor :parents %
                                 :at (at n) :path path :reason "oops"})))

(defn- status [evs] (:status (red/fact-status (red/ticket-state evs) path)))

(deftest re-asserting-the-disputed-value-does-not-clear-the-dispute
  (let [disputed (-> base (assert! 1 "seb" :billing) (dispute! 2 "auditor"))]
    (is (= :disputed (status disputed)))
    (is (= :disputed (status (assert! disputed 3 "seb" :billing)))
        "the disputed party cannot override an objection by repeating it")
    (is (nil? (red/fact-value (red/ticket-state (assert! disputed 3 "seb" :billing))
                              path))
        "and the repeated value does not go live")))

(deftest a-corrected-value-answers-the-dispute
  (let [disputed (-> base (assert! 1 "seb" :billing) (dispute! 2 "auditor"))
        corrected (assert! disputed 3 "seb" :technical)]
    (is (= :present (status corrected)))
    (is (= :technical (red/fact-value (red/ticket-state corrected) path)))))

(deftest retracting-the-claim-leaves-nothing-to-dispute
  (let [evs (-> base (assert! 1 "seb" :billing) (dispute! 2 "auditor")
                (retract! 3 "seb"))]
    (is (= :retracted (status evs)))))

(deftest a-disputer-can-withdraw-their-own-dispute
  (let [evs (-> base (assert! 1 "seb" :billing) (dispute! 2 "auditor"))]
    (is (= :disputed (status evs)))
    (is (= :present (status (dispute! evs 3 "auditor" :withdraw true))))
    (testing "but only their own"
      (is (= :disputed (status (dispute! evs 3 "seb" :withdraw true)))
          "the disputed party withdrawing somebody else's dispute is a no-op"))))

(deftest disputes-accumulate-and-clear-independently
  (let [evs (-> base (assert! 1 "seb" :billing)
                (dispute! 2 "auditor") (dispute! 3 "billing"))]
    (is (= 2 (count (:disputes (red/fact-status (red/ticket-state evs) path)))))
    (testing "one withdrawal leaves the other standing"
      (let [one-gone (dispute! evs 4 "auditor" :withdraw true)]
        (is (= :disputed (status one-gone)))
        (is (= "billing"
               (:by (red/fact-status (red/ticket-state one-gone) path))))))
    (testing "a corrected value answers both at once"
      (is (= :present (status (assert! evs 4 "seb" :technical)))))))

(deftest a-dispute-of-an-unasserted-path-rejects-no-particular-value
  (let [evs (dispute! base 1 "auditor")]
    (is (= :disputed (status evs))
        "the objection is on record before anyone claims anything")
    (is (= :present (status (assert! evs 2 "seb" :billing)))
        "and the first claim answers it, so the path is not blocked forever")))

(deftest withdrawing-a-dispute-that-was-never-raised-changes-nothing
  (is (= :absent (status (dispute! base 1 "auditor" :withdraw true))))
  (is (= :present (status (-> base (assert! 1 "seb" :billing)
                              (dispute! 2 "seb" :withdraw true))))))

(deftest the-earliest-live-dispute-is-the-one-reported
  (let [evs (-> base (assert! 1 "seb" :billing)
                (dispute! 2 "auditor") (dispute! 3 "billing"))
        {:keys [by note]} (red/fact-status (red/ticket-state evs) path)]
    (is (= "auditor" by))
    (is (= "no" note))))
