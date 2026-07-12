;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.model-based-test
  "Model-based property testing: generate a random SEQUENCE of fact
  operations, apply it two ways — through the real event kernel and
  through a tiny independent in-memory model — and require the derived
  fact-statuses to agree. Where the per-function property tests check
  one operation, this checks their INTERACTION over a whole history,
  the way real use accumulates events. The model is deliberately naive
  (a map path -> last-op), so any agreement is evidence the kernel's
  fold matches an obviously-correct specification.

  Single actor, strictly increasing timestamps: reduction order is
  insertion order and no concurrent claims arise, so each path's status
  is decided by the LAST operation touching it — assert -> :present,
  retract -> :retracted, dispute -> :disputed, untouched -> :absent."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.event :as event]
            [tik.reduce :as red])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-01-01T00:00:00Z"))
(def ^:private ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef")
(def ^:private paths [[:a] [:b] [:c] [:nested :x]])

(def ^:private gen-op
  (gen/let [kind (gen/elements [:assert :retract :dispute])
            path (gen/elements paths)
            v gen/small-integer]
    {:kind kind :path path :value v}))

(defn- model-status
  "The naive spec: fold the ops, last-op-per-path wins by kind."
  [ops]
  (reduce (fn [m {:keys [kind path]}]
            (assoc m path (case kind
                            :assert :present
                            :retract :retracted
                            :dispute :disputed)))
          {}
          ops))

(defn- ops->events
  "Apply the op sequence through the real kernel, one event per op at a
  strictly increasing time, parents threaded."
  [ops]
  (apply event/chain
         (fn [_] (event/create-ticket {:ticket ticket :actor "a" :at t0
                                       :title "x" :process :p}))
         (map-indexed
          (fn [i {:keys [kind path value]}]
            (fn [parents]
              (let [at (.plusSeconds ^Instant t0 (long (inc i)))
                    base {:ticket ticket :actor "a" :at at :parents parents
                          :path path}]
                (case kind
                  :assert (event/assert-fact (assoc base :value value))
                  :retract (event/retract-fact base)
                  :dispute (event/dispute-fact (assoc base :reason "no"))))))
          ops)))

(defspec kernel_fold_matches_the_naive_model 200
  ;; the whole history, two ways: the real fold and the last-op model
  ;; must agree on every path's status — untouched paths stay :absent
  (prop/for-all [ops (gen/vector gen-op 0 12)]
    (let [state (red/ticket-state (ops->events ops))
          expected (model-status ops)]
      (every? (fn [path]
                (= (get expected path :absent)
                   (:status (red/fact-status state path))))
              paths))))

(defspec live_value_tracks_the_last_assert 200
  ;; a stronger check for the assert case: when the model says a path is
  ;; present, the kernel's live value is the value of the LAST assert to
  ;; that path — dedup/order picked the right winner
  (prop/for-all [ops (gen/vector gen-op 0 12)]
    (let [state (red/ticket-state (ops->events ops))
          last-assert (reduce (fn [m {:keys [kind path value]}]
                                (case kind
                                  :assert (assoc m path value)
                                  (dissoc m path)))
                              {}
                              ops)]
      (every? (fn [[path v]]
                (= v (red/fact-value state path)))
              last-assert))))
