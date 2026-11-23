;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.stage-prop-test
  "Differential tests against the reference kernel, plus derivation laws
  the fixpoint must uphold on arbitrary histories."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [tik.event :as event]
            [tik.gen-events :as ge]
            [tik.reduce :as red]
            [tik.reference :as ref]
            [tik.stage :as stage])
  (:import (java.time Instant)))

(defspec evolve-agrees-with-the-reference-kernel 60
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (= (stage/evolve ge/process events ge/roles now)
       (ref/evolve ge/process events ge/roles now))))

(defspec effective-reached-agrees-with-the-reference-kernel 60
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (= (stage/effective-reached ge/process events now ge/roles)
       (ref/effective-reached ge/process events now ge/roles))))

(defspec timeline-is-prefix-consistent 30
  ;; the fold is honestly incremental: evolving the first k events yields
  ;; exactly the first k entries of the full timeline
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [ordered (vec (red/ordered events))
          full (:timeline (stage/evolve ge/process ordered ge/roles now))]
      (every? (fn [k]
                (= (vec (take k full))
                   (:timeline (stage/evolve ge/process (take k ordered)
                                            ge/roles now))))
              (range 1 (inc (count ordered)))))))

(defspec sticky-stages-never-unreach 100
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [tl (:timeline (stage/evolve ge/process events ge/roles now))
          sticky (stage/sticky-ids ge/process)]
      (every? (fn [[a b]]
                (set/subset? (set/intersection (:reached a) sticky)
                             (:reached b)))
              (partition 2 1 tl)))))

(defspec derivation-ignores-stage-declaration-order 40
  ;; ADR 0005's promise: with stratification enforced, the reached set is
  ;; a function of the process, never of iteration order
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [shuffled (update ge/process :process/stages (comp vec shuffle))]
      (= (stage/effective-reached ge/process events now ge/roles)
         (stage/effective-reached shuffled events now ge/roles)))))

(defspec reached-respects-the-after-graph 100
  ;; no stage is ever reached before all of its prerequisites
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [reached (stage/effective-reached ge/process events now ge/roles)]
      (every? (fn [s]
                (or (not (contains? reached (:stage/id s)))
                    (every? reached (:after s []))))
              (:process/stages ge/process)))))

(defspec current-stages-are-maximal-and-reached 100
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [reached (stage/effective-reached ge/process events now ge/roles)
          current (stage/current-stages ge/process reached)]
      (and (set/subset? current reached)
           ;; no current stage is upstream of another reached stage
           (every? (fn [c]
                     (not-any? #(contains? (stage/ancestor-closure
                                            ge/process %) c)
                               reached))
                   current)
           ;; every reached stage is current or upstream of a current one
           (every? (fn [r]
                     (or (contains? current r)
                         (some #(contains? (stage/ancestor-closure
                                            ge/process %) r)
                               current)))
                   reached)))))

(defspec trace-sweeps-agrees-with-reached-set 60
  ;; the debugger's data source IS the fixpoint: same reached set, and
  ;; the union of sweep additions reconstructs it exactly
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [state (red/ticket-state events)
          {:keys [reached sweeps]} (stage/trace-sweeps ge/process state
                                                       now ge/roles)]
      (and (= reached (stage/reached-set ge/process state now ge/roles))
           (= reached (reduce into #{} (map :added sweeps)))))))

(defspec trace-sweeps-with-the-sticky-carry-agrees-with-effective-reached 60
  ;; tik debug shows the fixpoint status reports, which is seeded with
  ;; every sticky stage the fold ever reached
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [{:keys [state sticky-ever]} (stage/evolve ge/process events ge/roles now)
          {:keys [reached sweeps]} (stage/trace-sweeps ge/process state now ge/roles sticky-ever)]
      (and (= reached (stage/effective-reached ge/process events now ge/roles))
           (= reached (reduce into #{} (map :added sweeps)))))))

(deftest a-carried-sticky-stage-shows-in-the-trace
  (let [p {:process/id :lock :process/version 1 :process/guard-vocab 2
           :process/facts {[:lock] :boolean}
           :process/stages [{:stage/id :open :guards []}
                            {:stage/id :locked :after [:open] :stage/sticky? true
                             :guards [[:fact [:lock]]]}
                            {:stage/id :after-lock :after [:locked] :guards []}]}
        tid #uuid "018f2f6e-7c1a-7000-8000-00000000d0d0"
        at #(Instant/parse %)
        c (event/create-ticket {:ticket tid :actor "seb" :at (at "2026-07-08T10:00:00Z")
                                :title "lock" :process :lock})
        a (event/assert-fact {:ticket tid :actor "seb" :at (at "2026-07-08T10:01:00Z")
                              :parents #{(:event/id c)} :path [:lock] :value true})
        r (event/retract-fact {:ticket tid :actor "seb" :at (at "2026-07-08T10:02:00Z")
                               :parents #{(:event/id a)} :path [:lock]})
        events [c a r]
        now (at "2026-07-08T11:00:00Z")
        {:keys [state sticky-ever]} (stage/evolve p events {} now)]
    (is (= #{:open :locked :after-lock} (stage/effective-reached p events now {})))
    (is (= #{:open :locked :after-lock}
           (:reached (stage/trace-sweeps p state now {} sticky-ever))))))
