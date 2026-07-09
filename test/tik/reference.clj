;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.reference
  "The reference kernel (PLAN §8): a deliberately slow, obvious
  transcription of the derivation semantics. No fusion, no incremental
  carry — every timeline entry is recomputed from scratch over its prefix,
  and the sticky carry is re-derived as the union over all earlier
  prefixes. O(n²) and proud of it. The optimized single-fold evolve merely
  has to agree with this; differential tests assert exactly that."
  (:require [clojure.set :as set]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage]))

(defn reached-fixpoint
  "Naive fixpoint: rescan every stage until nothing changes."
  [process state now roles seed]
  (loop [reached seed]
    (let [ctx {:state state :process process :now now
               :roles roles :reached reached}
          r' (into reached
                   (keep (fn [s]
                           (when (and (not (contains? reached (:stage/id s)))
                                      (every? reached (:after s []))
                                      (every? #(:satisfied?
                                                (guard/eval-guard % ctx))
                                              (:guards s [])))
                             (:stage/id s))))
                   (:process/stages process))]
      (if (= r' reached) reached (recur r')))))

(defn- sticky-seen [timeline sticky]
  (into #{} (mapcat #(set/intersection (:reached %) sticky)) timeline))

(defn evolve
  "Prefix replay, same shape of result as tik.stage/evolve."
  [process events roles]
  (let [ordered (vec (red/ordered events))
        sticky (stage/sticky-ids process)
        timeline
        (loop [k 1 timeline []]
          (if (> k (count ordered))
            timeline
            (let [prefix (subvec ordered 0 k)
                  e (peek prefix)
                  state (red/ticket-state prefix)
                  carry (sticky-seen timeline sticky)
                  reached (reached-fixpoint process state (:event/at e)
                                            roles carry)]
              (recur (inc k)
                     (conj timeline {:event-id (:event/id e)
                                     :at (:event/at e)
                                     :reached reached})))))]
    {:state (red/ticket-state ordered)
     :reached (:reached (peek timeline) #{})
     :sticky-ever (sticky-seen timeline sticky)
     :timeline timeline}))

(defn effective-reached [process events now roles]
  (let [{:keys [state sticky-ever]} (evolve process events roles)]
    (reached-fixpoint process state now roles sticky-ever)))
