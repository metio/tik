;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.schedule
  "When a multi-store backend's pipelines run: the pure half of the
  scheduler. Every function takes the clock reading and the random draw
  it needs as arguments and returns plain data, so the properties the
  backend promises — no overlapping runs of one pipeline, one catch-up
  run after a suspend instead of a burst, jitter within its bound, a
  bounded run time, restarts that back off — are tested without a clock
  or a process. `tik.pipeline-run` executes the decisions.

  Times are epoch milliseconds. A scheduled pipeline keeps a grid point
  `:base` (the start plus a whole number of intervals) and the time it
  fires, `:next`, which is the base plus a draw from [0, jitter). Firing
  on a grid instead of `interval after the last run` keeps a pipeline
  from drifting later by its own run time, as a systemd `OnCalendar`
  timer does not drift."
  (:require [clojure.math :as math]))

(def default-jitter-ms
  "The spread of a scheduled pipeline's fire time when it names no
  `:jitter`: a minute, as the bridge timers' `RandomizedDelaySec=60`."
  60000)

(def max-sleep-ms
  "The longest the scheduler sleeps before it reads the wall clock again.
  A sleep does not advance while the machine is suspended, so this bounds
  how late after a resume an overdue pipeline is noticed."
  30000)

(def late-grace-ms
  "How late a fire may be and still run at once. A fire later than this
  missed its time while the machine slept or the backend was stopped,
  and every pipeline that missed would otherwise start in the same
  instant; it is re-drawn into its jitter window instead."
  (+ max-sleep-ms 5000))

(def first-backoff-ms 1000)
(def max-backoff-ms 60000)

(def stable-run-ms
  "A continuous pipeline that ran at least this long before it exited
  restarts after the first backoff again: it was working, and a single
  drop should not wait as long as a crash loop."
  60000)

(defn jitter-ms
  "The jitter bound of a pipeline whose interval is `every-ms`: the
  configured `jitter` when given, else the default, and never more than
  half the interval, so a fire always lands before the next grid point."
  ^long [every-ms jitter]
  (long (min (or jitter default-jitter-ms) (quot (long every-ms) 2))))

(defn draw
  "The offset a random number `r` in [0, 1) picks from [0, bound)."
  ^long [bound r]
  (long (math/floor (* (double bound) (double r)))))

(defn initial
  "The schedule of one pipeline when the backend starts at `now`: its
  first fire is a catch-up, drawn into its jitter window, because a
  start after a stop or a boot has missed whatever fell due meanwhile."
  [now {:keys [jitter-ms]} r]
  {:base now :next (+ (long now) (draw jitter-ms r)) :running? false})

(defn next-base
  "The first grid point after `now`: `base` plus the smallest positive
  whole number of intervals that passes `now`. Every fire missed in
  between collapses into the one about to run."
  ^long [base every-ms now]
  (let [base (long base) every (long every-ms) now (long now)
        k (inc (quot (max 0 (- now base)) every))]
    (+ base (* k every))))

(defn due?
  "Is the pipeline's fire time reached at `now`?"
  [{:keys [next]} now]
  (<= (long next) (long now)))

(defn late?
  "Did the fire time pass longer ago than the grace allows?"
  [{:keys [next]} now]
  (< late-grace-ms (- (long now) (long next))))

(defn decide
  "What to do with one pipeline at `now`, as [action schedule']:

  - `:wait`   nothing is due;
  - `:redraw` the fire is late (a suspend or a stop): its time moves to
              now plus a draw from the jitter window, so the pipelines
              that all missed do not start in the same instant;
  - `:skip`   it is due while its previous run is still going: it is
              not started a second time, and its next fire moves to the
              next grid point;
  - `:start`  it is due and idle: start it now.

  `r` is a random number in [0, 1)."
  [sched {:keys [every-ms jitter-ms]} now r]
  (cond
    (not (due? sched now))
    [:wait sched]

    (and (late? sched now) (not (:redrawn? sched)))
    [:redraw (assoc sched :next (+ (long now) (draw jitter-ms r)) :redrawn? true)]

    (:running? sched)
    (let [b (next-base (:base sched) every-ms now)]
      [:skip (assoc sched :base b :next (+ b (draw jitter-ms r)) :redrawn? false)])

    :else
    [:start (assoc sched :running? true :started now)]))

(defn finished
  "The schedule after a run that started at `:started` ended at `now`:
  idle again, its next fire at the first grid point after now plus a
  draw. A run longer than its interval therefore skips the grid points it
  covered rather than queueing them."
  [sched {:keys [every-ms jitter-ms]} now r]
  (let [b (next-base (:base sched) every-ms now)]
    (-> sched
        (assoc :running? false :base b :next (+ b (draw jitter-ms r)) :redrawn? false)
        (dissoc :started))))

(defn sleep-ms
  "How long the scheduler may sleep at `now` before something is due:
  until the earliest fire, at least 50 ms and at most `max-sleep-ms`. A
  running pipeline's fire counts too, so the fire it has to skip is
  skipped, and said so, when it falls due."
  ^long [scheds now]
  (let [earliest (reduce min Long/MAX_VALUE (map #(- (long (:next %)) (long now)) scheds))]
    (long (max 50 (min max-sleep-ms earliest)))))

(defn timeout-ms
  "How long one run of a scheduled pipeline may take: its `:timeout`, or
  its interval, so a stuck run never outlives the period it belongs to."
  ^long [{:keys [every-ms timeout-ms]}]
  (long (or timeout-ms every-ms)))

(defn restart
  "After a continuous pipeline exits: how long to wait before starting it
  again and the backoff to keep for the time after. It doubles from
  `first-backoff-ms` up to `max-backoff-ms` while the pipeline keeps
  failing fast, and starts over once a run lasted `stable-run-ms`."
  [backoff ran-ms]
  (let [b (if (<= stable-run-ms (long ran-ms)) first-backoff-ms (long (or backoff first-backoff-ms)))]
    {:wait b :backoff (min max-backoff-ms (* 2 b))}))

(defn report?
  "Should this outcome of a pipeline send a failure report? `last` is
  {:ok? :reported-at} from the run before; `repeat-ms` how long a
  pipeline that keeps failing waits between reports (0 reports every
  failure, as systemd's OnFailure= does)."
  [{:keys [ok? reported-at] :or {ok? true}} failed? now repeat-ms]
  (boolean
   (and failed?
        (or ok?
            (nil? reported-at)
            (<= (long repeat-ms) (- (long now) (long reported-at)))))))
