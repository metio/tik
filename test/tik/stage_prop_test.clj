;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.stage-prop-test
  "Differential tests against the reference kernel, plus derivation laws
  the fixpoint must uphold on arbitrary histories."
  (:require [clojure.set :as set]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [tik.gen-events :as ge]
            [tik.reduce :as red]
            [tik.reference :as ref]
            [tik.stage :as stage]))

(defspec evolve-agrees-with-the-reference-kernel 60
  (prop/for-all [events ge/gen-events]
    (= (stage/evolve ge/process events ge/roles)
       (ref/evolve ge/process events ge/roles))))

(defspec effective-reached-agrees-with-the-reference-kernel 60
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (= (stage/effective-reached ge/process events now ge/roles)
       (ref/effective-reached ge/process events now ge/roles))))

(defspec timeline-is-prefix-consistent 30
  ;; the fold is honestly incremental: evolving the first k events yields
  ;; exactly the first k entries of the full timeline
  (prop/for-all [events ge/gen-events]
    (let [ordered (vec (red/ordered events))
          full (:timeline (stage/evolve ge/process ordered ge/roles))]
      (every? (fn [k]
                (= (vec (take k full))
                   (:timeline (stage/evolve ge/process (take k ordered)
                                            ge/roles))))
              (range 1 (inc (count ordered)))))))

(defspec sticky-stages-never-unreach 100
  (prop/for-all [events ge/gen-events]
    (let [tl (:timeline (stage/evolve ge/process events ge/roles))
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
