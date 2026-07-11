;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns bench
  "Micro-benchmarks over the kernel's hot paths (criterium: JMH-grade
  statistics — JIT warmup, GC accounting, outlier detection). Run with
  `bb bench`; treat deltas across commits as the signal, absolute
  numbers as machine-local. The macro (whole-store) benchmark lives in
  dev/bench-store.sh."
  (:require [criterium.core :as crit]
            [tik.canonical :as canonical]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.reduce :as red]
            [tik.stage :as stage])
  (:import (java.time Instant)))

(def process
  {:process/id :bench :process/version 1 :process/guard-vocab 1
   :process/facts {[:a] :int [:b] :int [:c] :int}
   :process/stages [{:stage/id :s0 :guards []}
                    {:stage/id :s1 :after [:s0] :guards [[:fact [:a]]]}
                    {:stage/id :s2 :after [:s1] :guards [[:fact [:b]]]}
                    {:stage/id :s3 :after [:s2] :stage/sticky? true
                     :guards [[:fact [:c]]]}]})

(defn events-of-size [n]
  (let [ticket (random-uuid)
        t0 (Instant/parse "2026-01-01T00:00:00Z")]
    (apply event/chain
           (fn [_] (event/create-ticket {:ticket ticket :actor "seb"
                                         :at t0 :title "bench"
                                         :process :bench}))
           (for [i (range (dec n))]
             (fn [parents]
               (event/assert-fact {:ticket ticket :actor "seb"
                                   :at (.plusSeconds t0 (inc i))
                                   :parents parents
                                   :path [(nth [:a :b :c] (mod i 3))]
                                   :value i}))))))

(defn -main [& _]
  (let [ev10 (events-of-size 10)
        ev100 (events-of-size 100)
        now (Instant/parse "2026-06-01T00:00:00Z")]
    (println "== canonical/content-address (one event)")
    (crit/quick-bench (canonical/content-address (dissoc (first ev10)
                                                         :event/id)))
    (println "== reduce/ticket-state, 10 events")
    (crit/quick-bench (red/ticket-state ev10))
    (println "== reduce/ticket-state, 100 events")
    (crit/quick-bench (red/ticket-state ev100))
    (println "== stage/effective-reached, 100 events")
    (crit/quick-bench (stage/effective-reached process ev100 now {}))
    (println "== explain, 100 events")
    (crit/quick-bench (explain/explain process ev100 now {}))))
