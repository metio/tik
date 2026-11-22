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

(defn- depth-of
  "A stage's depth along :after, by plain recursion: 0 without :after, else
  one more than its deepest prerequisite; 0 on a cycle."
  ([process id] (depth-of process id #{}))
  ([process id seen]
   (let [s (first (filter #(= id (:stage/id %)) (:process/stages process)))
         after (:after s [])]
     (if (or (empty? after) (contains? seen id))
       0
       (inc (apply max (map #(depth-of process % (conj seen id)) after)))))))

(defn reached-fixpoint
  "Naive fixpoint: rescan every stage until nothing changes. The sticky
  carry `seed` is history: guards see it as reached from the first sweep,
  and :after waits for a carried stage of depth d until sweep d, where it
  would enter had it derived again."
  [process state now roles seed]
  (loop [reached #{} sweep 0]
    (let [ctx {:state state :process process :now now
               :roles roles :reached (into reached seed)}
          r' (into (into reached (filter #(<= (depth-of process %) sweep)) seed)
                   (keep (fn [s]
                           (when (and (not (contains? reached (:stage/id s)))
                                      (every? reached (:after s []))
                                      (every? #(:satisfied?
                                                (guard/eval-guard % ctx))
                                              (:guards s [])))
                             (:stage/id s))))
                   (:process/stages process))]
      (if (and (= r' reached) (every? #(contains? reached %) seed))
        reached
        (recur r' (inc sweep))))))

(defn- sticky-seen [timeline sticky]
  (into #{} (mapcat #(set/intersection (:reached %) sticky)) timeline))

(defn evolve
  "Prefix replay, same shape of result as tik.stage/evolve — including
  the clamp: no prefix is evaluated at a time later than the read's now."
  [process events roles now]
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
                  at (:event/at e)
                  reached (reached-fixpoint process state
                                            (if (pos? (compare at now)) now at)
                                            roles carry)]
              (recur (inc k)
                     (conj timeline {:event-id (:event/id e)
                                     :at at
                                     :reached reached})))))]
    {:state (red/ticket-state ordered)
     :reached (:reached (peek timeline) #{})
     :sticky-ever (sticky-seen timeline sticky)
     :timeline timeline}))

(defn effective-reached [process events now roles]
  (let [{:keys [state sticky-ever]} (evolve process events roles now)]
    (reached-fixpoint process state now roles sticky-ever)))
