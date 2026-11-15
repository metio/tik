;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.stats
  "Aggregate lens: tickets grouped by a fact, with counts and the time each
  took to reach a stage. Pure over pre-derived rows, so the numbers are a
  function of the logs and the instant asked, recomputed on every read.

  A row is {:facts {path value} :created-ms long :reached-ms {stage long}};
  :reached-ms holds the first instant each stage derived. Instants are
  epoch milliseconds as integers; arithmetic on them promotes rather than
  overflows."
  (:require [clojure.math :as math]
            [tik.shape :as shape]))

(defn- quantile
  "The nearest-rank `q` quantile of a sorted, non-empty vector."
  [sorted q]
  (let [n (count sorted)
        i (min (dec n) (max 0 (dec (long (math/ceil (* q n))))))]
    (nth sorted i)))

(defn- ms!
  "`x` when it is an integer number of milliseconds."
  [argument x]
  (if (integer? x) x (shape/reject argument :integer x)))

(defn duration-summary
  "{:n :mean-ms :median-ms :p90-ms :max-ms} of `durations`, or nil when empty."
  [durations]
  (shape/plain-coll :durations durations)
  (run! #(ms! :durations %) durations)
  (when (seq durations)
    (let [v (vec (sort durations))
          n (count v)]
      {:n n
       :mean-ms (quot (reduce +' 0 v) n)
       :median-ms (quantile v 0.5)
       :p90-ms (quantile v 0.9)
       :max-ms (peek v)})))

(defn- rows!
  "Rows are plain maps whose :facts and :reached-ms are plain maps and
  whose instants are integers."
  [rows]
  (shape/maps :rows rows)
  (doseq [r rows]
    (shape/plain-map :facts (:facts r))
    (ms! :created-ms (:created-ms r))
    (doseq [[_ t] (shape/plain-map :reached-ms (:reached-ms r))]
      (ms! :reached-ms t)))
  rows)

(defn- group-stats* [rows by split until]
  (->> (group-by #(get-in % [:facts by]) rows)
       (map (fn [[v rs]]
              (let [done (when until
                           (keep #(when-let [t (get-in % [:reached-ms until])]
                                    (-' t (:created-ms %)))
                                 rs))]
                (cond-> {:group v :tickets (count rs)}
                  split (assoc :split (frequencies (map #(get-in % [:facts split]) rs)))
                  until (assoc :reached (count done)
                               :open (- (count rs) (count done))
                               :duration (duration-summary done))))))
       (sort-by (juxt (comp - :tickets) (comp str :group)))
       vec))

(defn group-stats
  "Rows grouped by the value at fact path `by` (nil when absent).

  Each group is {:group v :tickets n :split {value n} :reached n
  :open n :duration {…}}; :split counts rows by the fact at `split`, and
  the :reached/:open/:duration keys appear only when `until` names a
  stage, measuring created → first reached. Groups sort by ticket count,
  then by value."
  [rows opts]
  (let [{:keys [by split until]} (shape/plain-map :opts opts)]
    (group-stats* (rows! rows) by split until)))
