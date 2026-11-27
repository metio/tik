;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.schedule-test
  "The pure scheduler of the multi-store backend: the properties a
  pipeline's runs keep whatever the clock does — never two runs of one
  pipeline at once, one catch-up run after a gap instead of a burst,
  fire times inside their jitter window and on their grid, bounded
  sleeps and run times, and restarts that back off and recover."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.schedule :as s]))

(def ^:private minute 60000)

(defn- pipeline [every-ms jitter]
  {:every-ms every-ms :jitter-ms (s/jitter-ms every-ms jitter)})

(def ^:private gen-pipeline
  (gen/let [every (gen/choose 1000 (* 60 minute))
            jitter (gen/one-of [(gen/return nil) (gen/choose 0 (* 10 minute))])]
    (pipeline every jitter)))

(def ^:private gen-r (gen/double* {:min 0.0 :max 0.999999 :NaN? false :infinite? false}))

;; ------------------------------------------------------------ jitter and grid

(defspec jitter_never_exceeds_half_the_interval 200
  (prop/for-all [every (gen/choose 2 (* 60 minute))
                 jitter (gen/one-of [(gen/return nil) (gen/choose 0 (* 60 minute))])]
    (let [j (s/jitter-ms every jitter)]
      (and (<= 0 j) (<= j (quot every 2))
           (or (nil? jitter) (<= j jitter))))))

(defspec a_draw_stays_inside_its_bound 200
  (prop/for-all [bound (gen/choose 0 (* 10 minute)) r gen-r]
    (let [d (s/draw bound r)]
      (and (<= 0 d) (or (zero? bound) (< d bound))))))

(defspec the_next_base_is_the_first_grid_point_after_now 300
  (prop/for-all [base (gen/choose 0 1000000000)
                 every (gen/choose 1 (* 60 minute))
                 ahead (gen/choose -1000000 100000000)]
    (let [now (+ base ahead)
          b (s/next-base base every now)]
      (and (< now b)
           (zero? (mod (- b base) every))
           (<= (- b every) (max base now))))))

(defspec the_first_fire_lands_in_the_jitter_window 200
  (prop/for-all [p gen-pipeline now (gen/choose 0 1000000000) r gen-r]
    (let [{:keys [base next running?]} (s/initial now p r)]
      (and (= base now) (not running?)
           (<= now next) (<= next (+ now (max 0 (dec (:jitter-ms p))) 1))))))

;; ------------------------------------------------------------ no overlap

(defn- simulate
  "Drive one pipeline through `steps` of [dt-ms action], where action is
  :tick or :finish, and record every decision."
  [p steps rs]
  (loop [sched (s/initial 0 p (first rs)) now 0 [[dt act] & more] steps rs (cycle rs) log []]
    (if-not dt
      log
      (let [now (+ now dt)]
        (case act
          :finish (if (:running? sched)
                    (recur (s/finished sched p now (first rs)) now more (rest rs) (conj log [:finish now]))
                    (recur sched now more rs log))
          :tick (let [[a sched'] (s/decide sched p now (first rs))]
                  (recur sched' now more (rest rs) (conj log [a now]))))))))

(def ^:private gen-steps
  (gen/vector (gen/tuple (gen/choose 0 (* 30 minute)) (gen/elements [:tick :tick :tick :finish])) 1 80))

(defspec a_pipeline_never_starts_while_its_run_is_going 300
  (prop/for-all [p gen-pipeline steps gen-steps rs (gen/vector gen-r 1 10)]
    (let [log (simulate p steps rs)]
      ;; between two :start there is always a :finish
      (loop [[[a] & more] log running? false]
        (cond
          (nil? a) true
          (= a :start) (if running? false (recur more true))
          (= a :finish) (recur more false)
          :else (recur more running?))))))

(deftest a_due_pipeline_that_still_runs_is_skipped_and_moves_on
  (let [p (pipeline (* 10 minute) 0)
        sched {:base (* 10 minute) :next (* 10 minute) :running? true :started 0}
        [a s'] (s/decide sched p (* 10 minute) 0.0)]
    (is (= :skip a))
    (is (:running? s') "the run keeps going")
    (is (= (* 20 minute) (:next s')) "its next fire is the next grid point")))

;; ------------------------------------------------------------ catch-up

(deftest a_suspend_collapses_every_missed_fire_into_one_run
  (let [p (pipeline (* 10 minute) 0)
        sched (s/initial 0 p 0.0)
        [a1 s1] (s/decide sched p 0 0.0)
        s1 (s/finished s1 p 1000 0.0)
        ;; the machine sleeps for two hours: twelve fires missed
        wake (* 130 minute)
        [a2 s2] (s/decide s1 p wake 0.5)]
    (is (= :start a1))
    (is (= (* 10 minute) (:next s1)))
    (is (= :redraw a2) "a fire two hours late is re-drawn instead of started blindly")
    (is (= wake (:next s2)) "with no jitter the re-drawn fire is now")
    (let [[a3 s3] (s/decide s2 p wake 0.5)]
      (is (= :start a3) "and then it runs, once")
      (let [s4 (s/finished s3 p (+ wake 1000) 0.0)]
        (is (= (* 140 minute) (:next s4)) "and the next fire is the next grid point, not a backlog")
        (is (= :wait (first (s/decide s4 p (+ wake 2000) 0.0))) "nothing else is due")))))

(defspec a_late_fire_is_redrawn_into_its_window_once 200
  (prop/for-all [p gen-pipeline late (gen/choose (inc s/late-grace-ms) (* 600 minute)) r gen-r]
    (let [sched (s/initial 0 p 0.0)
          now (+ (:next sched) late)
          [a s'] (s/decide sched p now r)
          [a2 _] (s/decide s' p (+ now (:jitter-ms p)) r)]
      (and (= :redraw a)
           (<= now (:next s')) (<= (:next s') (+ now (:jitter-ms p)))
           (= :start a2)))))

(deftest a_fire_within_its_grace_runs_at_once
  (let [p (pipeline (* 10 minute) minute)
        sched (s/initial 0 p 0.0)]
    (is (= :start (first (s/decide sched p (+ (:next sched) 1000) 0.3))))))

(deftest a_run_longer_than_its_interval_skips_the_grid_points_it_covered
  (let [p (pipeline (* 10 minute) 0)
        [_ s1] (s/decide (s/initial 0 p 0.0) p 0 0.0)
        s2 (s/finished s1 p (* 35 minute) 0.0)]
    (is (= (* 40 minute) (:next s2)))
    (is (not (:running? s2)))))

;; ------------------------------------------------------------ sleeping and timeouts

(defspec the_scheduler_sleeps_between_50_ms_and_the_cap 200
  (prop/for-all [nexts (gen/vector (gen/choose -100000 100000000) 0 6)
                 running (gen/vector gen/boolean 0 6)
                 now (gen/choose 0 1000000)]
    (let [scheds (map (fn [n r] {:next n :running? r}) nexts (concat running (repeat false)))
          ms (s/sleep-ms scheds now)]
      (<= 50 ms s/max-sleep-ms))))

(deftest the_scheduler_wakes_for_a_running_pipelines_fire_to_skip_it
  (is (= 1000 (s/sleep-ms [{:next 2000 :running? true} {:next 50000}] 1000))))

(deftest a_run_is_bounded_by_its_timeout_or_its_interval
  (is (= (* 10 minute) (s/timeout-ms {:every-ms (* 10 minute)})))
  (is (= 30000 (s/timeout-ms {:every-ms (* 10 minute) :timeout-ms 30000}))))

;; ------------------------------------------------------------ restarts

(deftest restarts_back_off_and_recover_after_a_stable_run
  (testing "fast failures double the wait up to the cap"
    (let [waits (->> {:backoff s/first-backoff-ms}
                     (iterate #(s/restart (:backoff %) 10))
                     rest (take 10) (map :wait))]
      (is (= [1000 2000 4000 8000 16000 32000 60000 60000 60000 60000] waits))))
  (testing "a run that lasted starts over at the first backoff"
    (is (= {:wait 1000 :backoff 2000} (s/restart 60000 s/stable-run-ms)))))

(defspec a_restart_wait_is_always_within_bounds 200
  (prop/for-all [backoff (gen/choose 1 1000000) ran (gen/choose 0 1000000)]
    (let [{:keys [wait backoff]} (s/restart backoff ran)]
      (and (pos? wait) (<= backoff s/max-backoff-ms)))))

;; ------------------------------------------------------------ failure reports

(deftest a_failure_is_reported_on_the_change_and_then_at_most_every_repeat
  (is (s/report? nil true 0 3600000) "the first failure")
  (is (s/report? {:ok? true} true 0 3600000) "a failure after a success")
  (is (not (s/report? {:ok? false :reported-at 0} true 1000 3600000)) "a repeat within the window")
  (is (s/report? {:ok? false :reported-at 0} true 3600000 3600000) "a reminder once the window passed")
  (is (s/report? {:ok? false :reported-at 0} true 1 0) "PT0S reports every failure")
  (is (not (s/report? {:ok? false :reported-at 0} false 5 0)) "a success is never a failure report"))
