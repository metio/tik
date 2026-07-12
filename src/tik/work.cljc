;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.work
  "The work-evidence lens (PLAN §13, hypothesis H6).

  The honesty constraint is structural and this namespace enforces it
  in its own output: EVENTS ARE POINTS; DURATION IS AN INFERENCE. A
  fact asserted at 14:32 proves presence at 14:32, not the preceding
  90 minutes. So: raw events are evidence (signed, indisputable);
  sessions are heuristic (the method is DECLARED in the result, every
  session traces to its producing event ids); and a derived duration is
  never stored — the flow is machine-drafted, human-signed (the human
  corrects and signs a :work claim; thirty seconds of review replaces
  thirty minutes of fabrication).

  Cost: record observations, not valuations — token counts, never
  euros. Money is a fold over usage × pricing-table-at-date, supplied
  by the caller; totals are always derived, never stored (a stored
  aggregate can drift from its log; a derived one cannot)."
  #?(:clj (:import (java.time Duration Instant))))

(defn- ->instant ^Instant [t]
  (if (instance? java.util.Date t) (.toInstant ^java.util.Date t) t))

(def method
  "The declared inference method, carried inside every draft so the
  reader never mistakes an inference for a measurement."
  {:method :gap-sessions
   :gap "PT30M"
   :floor "PT5M"
   :statement (str "consecutive events by the actor on one ticket, "
                   "gapped <= 30min, form a session; session duration "
                   "= last - first, floored at 5min. Durations are "
                   "inferences from event points, not measurements.")})

(defn sessions
  "Cluster one ticket's events (already filtered to an actor and time
  range) into sessions per the declared method. Pure; every session
  carries the event ids that produced it."
  [events]
  (let [gap (Duration/parse (:gap method))
        sorted (sort-by (comp str :event/at) events)]
    (reduce
     (fn [acc e]
       (let [t (->instant (:event/at e))
             cur (peek acc)]
         (if (and cur
                  (not (.isAfter t (.plus ^Instant (:end cur) gap))))
           (conj (pop acc) (-> cur
                               (assoc :end t)
                               (update :events conj (:event/id e))))
           (conj acc {:start t :end t :events [(:event/id e)]}))))
     []
     sorted)))

(defn session-duration ^Duration [{:keys [^Instant start ^Instant end]}]
  (let [d (Duration/between start end)
        floor (Duration/parse (:floor method))]
    (if (neg? (.compareTo d floor)) floor d)))

(defn draft
  "The machine-drafted activity record for one actor across tickets:
  {:method … :actor … :tickets [{:ticket :title :sessions n
  :duration iso :evidence [event-ids]}] :total iso}. A DRAFT — the
  human corrects and signs; nothing here is a claim yet."
  [per-ticket actor from to]
  (let [in-range? (fn [e]
                    (let [t (->instant (:event/at e))]
                      (and (= actor (:event/actor e))
                           (or (nil? from) (not (.isBefore t from)))
                           (or (nil? to) (.isBefore t to)))))
        rows (for [{:keys [ticket title events]} per-ticket
                   :let [mine (filter in-range? events)
                         ss (sessions mine)]
                   :when (seq ss)]
               {:ticket ticket
                :title title
                :sessions (count ss)
                :duration (str (reduce (fn [^Duration acc s]
                                         (.plus acc (session-duration s)))
                                       Duration/ZERO ss))
                :evidence (vec (mapcat :events ss))})]
    {:method method
     :actor actor
     :tickets (vec rows)
     :total (str (reduce (fn [^Duration acc {:keys [duration]}]
                           (.plus acc (Duration/parse duration)))
                         Duration/ZERO rows))}))

(defn work-records
  "All :work attestation claims in a ticket's log — agent telemetry and
  human-signed records alike. Read from the log (like the v2 guards):
  attestations stay out of ticket-state (ADR 0009)."
  [events]
  (for [e events
        :let [body (:event/body e)]
        :when (and (= :attestation/add (:event/type e))
                   (= :work (:claim body)))]
    (assoc body :at (:event/at e) :by (:event/actor e)
           :event (:event/id e))))

(defn usage-totals
  "Fold raw usage observations. With a pricing table
  {model {:input $/Mtok :output … :cache-read … :cache-write …}} the
  fold also prices them — money as a lens, never stored."
  [records pricing]
  (let [add (fnil + 0)
        totals (reduce (fn [acc {:keys [usage] :as r}]
                         (let [model (:agent/model r)]
                           (-> acc
                               (update-in [model :input-tokens] add
                                          (:input-tokens usage 0))
                               (update-in [model :output-tokens] add
                                          (:output-tokens usage 0))
                               (update-in [model :cache-read-tokens] add
                                          (:cache-read-tokens usage 0))
                               (update-in [model :cache-write-tokens] add
                                          (:cache-write-tokens usage 0)))))
                       {}
                       (filter :usage records))]
    (if-not pricing
      {:observations totals}
      {:observations totals
       :priced (into {}
                     (for [[model u] totals
                           :let [p (get pricing model)]
                           :when p]
                       ;; exact; rounding is the renderer's business —
                       ;; rounding in the fold would erase sub-cent truth
                       [model
                        (+ (* (:input-tokens u 0) (/ (:input p 0) 1e6))
                           (* (:output-tokens u 0) (/ (:output p 0) 1e6))
                           (* (:cache-read-tokens u 0)
                              (/ (:cache-read p 0) 1e6))
                           (* (:cache-write-tokens u 0)
                              (/ (:cache-write p 0) 1e6)))]))})))
