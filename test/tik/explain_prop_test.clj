;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.explain-prop-test
  "explain's two laws (PLAN §8), property-tested:
  SOUND — every block is re-derivable: the stage is genuinely unreached,
  its prerequisites genuinely reached, every :satisfied guard genuinely
  satisfied, and :missing never empty. explain never speculates.
  COMPLETE — every unreached stage whose prerequisites are reached
  appears as a block. explain never omits."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.explain :as explain]
            [tik.gen-events :as ge]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage]))

(defn- stage-def [id]
  (first (filter #(= id (:stage/id %)) (:process/stages ge/process))))

(defspec explain-is-sound 60
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [reached (stage/effective-reached ge/process events now ge/roles)
          ctx {:state (red/ticket-state events) :process ge/process
               :now now :roles ge/roles :reached reached}]
      (every? (fn [{:keys [stage satisfied missing blocks]}]
                (and (not (contains? reached stage))
                     (every? reached (:after (stage-def stage) []))
                     (every? #(:satisfied? (guard/eval-guard % ctx))
                             satisfied)
                     (seq missing)
                     (= blocks (stage/downstream ge/process stage))))
              (explain/explain ge/process events now ge/roles)))))

(defspec explain-is-complete 60
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [reached (stage/effective-reached ge/process events now ge/roles)
          blocked (into #{} (map :stage)
                        (explain/explain ge/process events now ge/roles))]
      (every? (fn [s]
                (or (contains? reached (:stage/id s))
                    (not (every? reached (:after s [])))
                    (contains? blocked (:stage/id s))))
              (:process/stages ge/process)))))

(defspec every-reason-renders 60
  ;; the lens boundary is total: every structured reason the kernel can
  ;; produce has a text rendering, and render never throws
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (let [blocks (explain/explain ge/process events now ge/roles)]
      (and (every? string?
                   (mapcat #(map explain/reason->text (:missing %)) blocks))
           (string? (explain/render blocks))))))

(defspec missing-is-ranked-by-actionability 60
  ;; ADR 0016: :missing is sorted most-actionable-first, stably — the
  ;; rank sequence is non-decreasing on every block, always
  (prop/for-all [events ge/gen-events
                 now ge/gen-now]
    (every? (fn [{:keys [missing]}]
              (let [ranks (map explain/actionability missing)]
                (apply <= 0 (concat ranks [10]))))
            (explain/explain ge/process events now ge/roles))))

(defspec capability-filter-loses-nothing 60
  ;; for-actor partitions: an actor's view plus its hidden count always
  ;; equals the full block — filtering can never silently drop a reason
  (prop/for-all [events ge/gen-events
                 now ge/gen-now
                 who (gen/elements ["seb" "billing" "rando" "nobody"])]
    (let [blocks (explain/explain ge/process events now ge/roles)
          mine (explain/for-actor blocks ge/roles who)]
      (every? (fn [[b m]]
                (and (= (count (:missing b))
                        (+ (count (:missing m)) (:hidden m)))
                     (every? #(explain/actionable-by? % ge/roles who)
                             (:missing m))))
              (map vector blocks mine)))))
