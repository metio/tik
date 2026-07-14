;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.backend
  "`tik backend`: one supervised process that runs your PIPELINES as a
  delegate — the long-running server the CLI and web UI are clients of. It
  is porcelain, not kernel: it never changes what a fact means, it only
  decides WHEN to run a porcelain verb, and every verb produces ordinary
  signed, content-addressed events. So it holds the two laws at once —
  truth stays derived (ADR 0013), and because those events are a pure
  function of their intent (recur is deterministic), the backend needs no
  leader, lock, or quorum (ADR 0021): run one, run ten, they converge.

  A pipeline is either SCHEDULED (fired on an `:every` interval — recur,
  probe, a pop3 poll, effects) or CONTINUOUS (`:watch true` — it holds a
  connection open, like `serve` or `bridge imap --watch`, and is restarted
  with backoff if it exits). Every verb runs AS the pipeline's delegate
  (`:as` → --actor, TIK_KEY the delegate key), so its events trace back to
  the human who authorized the delegate (a §9/§13 delegation attestation).
  Idempotency makes precise scheduling unnecessary: fire `recur` hourly and
  it mints only when the period rolls over; a missed fire self-heals.

  Config `pipelines.edn` at the store root:

    {:pipelines
     [{:id :board       :watch true  :run [\"serve\" \"--port\" \"7777\"]}
      {:id :mail        :watch true  :run [\"bridge\" \"imap\" \"--watch\"] :as \"tik-backend\"}
      {:id :release     :every \"PT1H\" :run [\"recur\" \"release-train\"] :period :iso-week :as \"tik-backend\"}
      {:id :dashboard   :every \"PT1H\" :run [\"probe\"] :as \"tik-backend\"}
      {:id :notify      :every \"PT5M\" :run [\"effects\"] :as \"tik-backend\"}]}"
  (:require [clojure.string :as str]
            [tik.args :refer [read-edn-file]]
            [tik.cli :as cli]
            [tik.cli-core :refer [die now root]])
  (:import (java.io File)
           (java.time Duration Instant LocalDate ZoneOffset)
           (java.time.temporal IsoFields)))

(def ^:dynamic *backend-ticks*
  "nil in production (the scheduler runs forever); a positive integer in
  tests caps the number of scheduled fires so the loop terminates."
  nil)

(def ^:private period-kinds #{:iso-week :month :quarter :day :year})

(defn period-label
  "The current period LABEL for a recur pipeline, computed from the
  backend's (porcelain) wall clock — the caller passes today's date so the
  function stays pure and testable. Matches the forms `recur` parses back
  into an instant (yyyy, yyyy-Qn, yyyy-MM, yyyy-MM-dd, yyyy-Www)."
  [^LocalDate d kind]
  (case kind
    :year (str (.getYear d))
    :month (format "%d-%02d" (.getYear d) (.getMonthValue d))
    :day (str d)
    :quarter (format "%d-Q%d" (.getYear d) (inc (quot (dec (.getMonthValue d)) 3)))
    :iso-week (format "%d-W%02d"
                      (.get d IsoFields/WEEK_BASED_YEAR)
                      (.get d IsoFields/WEEK_OF_WEEK_BASED_YEAR))))

(defn tick-argv
  "The CLI argv a pipeline fires: its `:run` verb, attributed to the
  delegate (`:as` → --actor), with `--period <current-label>` appended for
  a recur pipeline. `today` is passed in so this is pure."
  [^LocalDate today {:keys [run as period]}]
  (cond-> (mapv str run)
    as (into ["--actor" (str as)])
    period (into ["--period" (period-label today period)])))

(defn- label [p] (str "[" (name (:id p)) "]"))

(defn- fire!
  "Run one pipeline verb in ISOLATION — a failure logs and the scheduler
  moves on, never aborting the loop (atom-bomb proof, like mail ingest)."
  [p]
  (let [argv (tick-argv (LocalDate/now ZoneOffset/UTC) p)
        {:keys [exit out err]} (try (cli/run-argv argv)
                                    (catch Throwable e {:exit 1 :err (ex-message e)}))
        tail (some-> out str/split-lines first str/trim not-empty)]
    (println (str (label p) " " (str/join " " argv) " -> "
                  (if (zero? (long exit)) "ok" (str "exit " exit))
                  (when tail (str " · " tail))))
    (when-not (zero? (long exit))
      (binding [*out* *err*] (println (label p) "failed:" (str/trim (str err)))))))

(defn- start-watcher!
  "Spawn a CONTINUOUS pipeline (serve, bridge imap --watch) as a daemon
  thread that runs its verb and restarts it with exponential backoff if it
  ever returns — supervision for the long-lived slices."
  [p]
  (doto (Thread.
         ^Runnable (fn []
                     (loop [backoff 1000]
                       (try (cli/run-argv (tick-argv (LocalDate/now ZoneOffset/UTC) p))
                            (catch Throwable _ nil))
                       (binding [*out* *err*]
                         (println (label p) "watcher exited; restarting in" (quot backoff 1000) "s"))
                       (Thread/sleep backoff)
                       (recur (min (* 2 backoff) 60000))))
         (str "tik-backend-" (name (:id p))))
    (.setDaemon true)
    (.start)))

(defn- sleep-ms
  "How long to sleep until the earliest next fire — clamped so the loop
  re-checks at least once a minute and never busy-spins."
  ^long [^Instant t next-fire]
  (let [earliest (reduce min Long/MAX_VALUE
                         (map #(.toMillis (Duration/between t %)) (vals next-fire)))]
    (long (max 200 (min 60000 earliest)))))

(defn- run-scheduler
  "Fire each scheduled pipeline on its `:every` interval, starting
  immediately, sequentially and isolated. Runs forever unless
  *backend-ticks* caps it (tests)."
  [pipelines]
  (let [remaining (atom *backend-ticks*)
        more? #(or (nil? @remaining) (pos? @remaining))
        next-fire (atom (into {} (map (fn [p] [(:id p) (now)])) pipelines))]
    (loop []
      (when (more?)
        (let [t (now)
              due (filter #(not (.isAfter ^Instant (get @next-fire (:id %)) t)) pipelines)]
          (if (seq due)
            (do (doseq [p due :while (more?)]
                  (fire! p)
                  (swap! next-fire assoc (:id p) (.plus ^Instant (now) (Duration/parse (:every p))))
                  (when @remaining (swap! remaining dec)))
                (recur))
            (do (Thread/sleep (sleep-ms t @next-fire)) (recur))))))))

(defn- validate!
  "Fail closed on a malformed pipeline before anything starts."
  [pipelines]
  (doseq [{:keys [id run watch every period] :as p} pipelines]
    (when-not id (die (str "backend pipeline missing :id: " (pr-str p))))
    (when-not (and (sequential? run) (seq run))
      (die (str (label p) " needs a non-empty :run vector")))
    (when-not (or watch every)
      (die (str (label p) " needs :every <ISO-8601 duration> or :watch true")))
    (when (and every (not watch))
      (try (Duration/parse every)
           (catch Exception _ (die (str (label p) " :every " (pr-str every)
                                        " is not an ISO-8601 duration (e.g. PT5M, PT1H, P1D)")))))
    (when (and period (not (period-kinds period)))
      (die (str (label p) " :period must be one of " period-kinds)))))

(defn cmd-backend
  "backend [--config pipelines.edn]
  Run the configured pipelines as a supervised delegated-agent server:
  continuous pipelines (serve, bridge imap --watch) each get a restart-on-
  exit thread; scheduled pipelines fire on their :every interval. Set
  TIK_KEY to the delegate's key so every produced event is signed by the
  delegate (authorized by a delegation attestation on the registry).
  Blocks until killed."
  [{:keys [opts]}]
  (let [cfg-file (or (:config opts) (str (File. ^String (root) "pipelines.edn")))
        _ (when-not (.exists (File. ^String cfg-file))
            (die (str "no backend config at " cfg-file
                      " (need {:pipelines [{:id … :every|:watch … :run [\"…\"]} …]})")))
        pipelines (:pipelines (read-edn-file (File. ^String cfg-file)))
        _ (when-not (seq pipelines) (die (str "no :pipelines in " cfg-file)))
        _ (validate! pipelines)
        {watchers true scheduled false} (group-by (comp boolean :watch) pipelines)]
    (println (str "tik backend: " (count watchers) " continuous, "
                  (count scheduled) " scheduled pipeline(s)"))
    (doseq [p watchers] (start-watcher! p))
    (cond
      (seq scheduled) (run-scheduler scheduled)
      (seq watchers) @(promise)                            ; only watchers: block on them
      :else (die "no pipelines to run"))))
