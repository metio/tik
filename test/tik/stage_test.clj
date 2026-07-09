;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.stage-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.reduce :as red]
            [tik.stage :as stage])
  (:import (java.time Instant)))

(def proc (edn/read-string (slurp "processes/support-request.edn")))
(def roles (:process/roles proc))
(def tid #uuid "018f2f6e-7c1a-7000-8000-000000000002")
(defn at [s] (Instant/parse s))
(def now (at "2026-07-08T12:00:00Z"))

(defn add [events ctor] (conj events (ctor #{(:event/id (peek events))})))

(def base
  (event/chain
   (fn [_] (event/create-ticket {:ticket tid :actor "customer"
                                 :at (at "2026-07-08T10:00:00Z")
                                 :title "login broken"
                                 :process :support-request}))))

(defn triage [events actor]
  (-> events
      (add #(event/assert-fact {:ticket tid :actor actor :parents %
                                :at (at "2026-07-08T10:01:00Z")
                                :path [:category] :value :technical}))
      (add #(event/assert-fact {:ticket tid :actor actor :parents %
                                :at (at "2026-07-08T10:02:00Z")
                                :path [:severity] :value :high}))))

(deftest fresh-ticket-is-received-only
  (let [reached (stage/effective-reached proc base now roles)]
    (is (= #{:received} reached))
    (is (= #{:received} (stage/current-stages proc reached)))))

(deftest signed-by-guard-blocks-non-triagers
  (is (= #{:received}
         (stage/effective-reached proc (triage base "rando") now roles)))
  (is (= #{:received :triaged}
         (stage/effective-reached proc (triage base "seb") now roles))))

(deftest explain-is-structured-data
  (let [[block] (filter #(= :triaged (:stage %))
                        (explain/explain proc base now roles))
        reasons (set (map #(select-keys % [:reason :path]) (:missing block)))]
    (is (contains? reasons {:reason :fact/missing :path [:category]}))
    (is (contains? reasons {:reason :fact/missing :path [:severity]}))
    (is (contains? (:blocks block) :closed)
        "blocks is the downstream closure")
    (is (= "kb/concepts/triage.md" (:hint block)))))

(deftest role-reason-carries-the-role
  (let [[block] (filter #(= :triaged (:stage %))
                        (explain/explain proc (triage base "rando") now roles))]
    (is (some #(and (= :role/unsatisfied (:reason %))
                    (= :triager (:role %))
                    (= "rando" (:by %)))
              (:missing block))
        "who-can-act is data — the pivot `next` rotates on")))

(deftest technical-branch-and-conditional-resolution
  (let [events (add (triage base "seb")
                    #(event/assert-fact {:ticket tid :actor "seb" :parents %
                                         :at (at "2026-07-08T10:10:00Z")
                                         :path [:resolution :ref]
                                         :value "abc123def456"}))]
    (is (not (contains? (stage/effective-reached proc events now roles)
                        :resolved))
        "technical needs a repro before resolution counts")
    (let [events (add events
                      #(event/attach-artifact
                        {:ticket tid :actor "seb" :parents %
                         :at (at "2026-07-08T10:11:00Z")
                         :path "repro/crash.sh" :hash "sha256-deadbeef"}))
          reached (stage/effective-reached proc events now roles)]
      (is (contains? reached :reproducible))
      (is (contains? reached :resolved))
      ;; conditional prerequisites are guards, not edges: both tips maximal
      (is (= #{:resolved :reproducible}
             (stage/current-stages proc reached))))))

(deftest dispute-regresses-by-derivation
  (let [events (add (triage base "seb")
                    #(event/dispute-fact {:ticket tid :actor "billing"
                                          :parents %
                                          :at (at "2026-07-08T10:20:00Z")
                                          :path [:category]
                                          :reason "recategorize please"}))
        reached (stage/effective-reached proc events now roles)
        missing (mapcat :missing (explain/explain proc events now roles))]
    (is (= #{:received} reached) "nobody moved the ticket; the stage derived")
    (is (some #(and (= :fact/disputed (:reason %)) (= "billing" (:by %))
                    (= "recategorize please" (:note %)))
              missing))))

(deftest escalation-is-a-function-of-now
  (is (not (contains? (stage/effective-reached proc base now roles)
                      :escalated)))
  (is (contains? (stage/effective-reached proc base
                                          (at "2026-07-11T12:00:00Z") roles)
                 :escalated))
  (testing "and of fact-level negation: categorized tickets never escalate"
    (is (not (contains? (stage/effective-reached proc (triage base "seb")
                                                 (at "2026-07-11T12:00:00Z")
                                                 roles)
                        :escalated)))))

(defn closed-events []
  (-> (triage base "seb")
      (add #(event/assert-fact {:ticket tid :actor "seb" :parents %
                                :at (at "2026-07-08T10:10:00Z")
                                :path [:resolution :ref] :value "abc123def456"}))
      (add #(event/attach-artifact {:ticket tid :actor "seb" :parents %
                                    :at (at "2026-07-08T10:11:00Z")
                                    :path "repro/crash.sh"
                                    :hash "sha256-deadbeef"}))
      (add #(event/assert-fact {:ticket tid :actor "customer" :parents %
                                :at (at "2026-07-08T11:00:00Z")
                                :path [:customer :ack] :value true}))))

(deftest sticky-milestone-survives-retraction
  (let [closed (closed-events)
        retracted (add closed
                       #(event/retract-fact {:ticket tid :actor "customer"
                                             :parents %
                                             :at (at "2026-07-08T11:30:00Z")
                                             :path [:customer :ack]
                                             :reason "misclick"}))]
    (is (contains? (stage/effective-reached proc closed now roles) :closed))
    (is (contains? (stage/effective-reached proc retracted now roles) :closed)
        "reached-once-stays-reached; the evolve fold carries sticky forward")))

(deftest timeline-is-a-view-of-the-same-fold
  (let [{:keys [timeline reached]} (stage/evolve proc (closed-events) roles)]
    (is (= (count (closed-events)) (count timeline)))
    (is (= reached (:reached (last timeline))))
    (is (= #{:received} (:reached (first timeline))))))

(defspec fold-is-commutative-and-idempotent 30
  (prop/for-all [_ gen/nat]
    (let [events (closed-events)
          mangled (shuffle (concat events (take 3 (shuffle events))))]
      (and (= (red/ticket-state events) (red/ticket-state mangled))
           (= (stage/effective-reached proc events now roles)
              (stage/effective-reached proc mangled now roles))))))
