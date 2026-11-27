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
            [tik.backend-stores :as stores]
            [tik.cli :as cli]
            [tik.cli-core :refer [die now root]]
            [tik.context :as context]
            [tik.pipeline-run :as run])
  (:import (java.io File)
           (java.time Duration Instant LocalDate ZoneOffset)
           (java.time.temporal IsoFields)))

(def ^:dynamic *backend-ticks*
  "nil in production (the scheduler runs forever); a positive integer in
  tests caps the number of scheduled fires so the loop terminates."
  nil)

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

(defn- label [p] (str "[" (when-let [s (:tik/store p)] (str (:name s) "/")) (name (:id p)) "]"))

(defn- in-store
  "Call `f` inside the store context of a multi-store pipeline (its
  :tik/store), or as it is for a single-store one."
  [p f]
  (if-let [s (:tik/store p)]
    (context/with-store s (f))
    (f)))

(defn- fire!
  "Run one pipeline verb in ISOLATION — a failure logs and the scheduler
  moves on, never aborting the loop (atom-bomb proof, like mail ingest)."
  [p]
  (let [argv (tick-argv (LocalDate/now ZoneOffset/UTC) p)
        {:keys [exit out err]} (try (in-store p #(cli/run-argv argv))
                                    (catch Throwable e {:exit 1 :err (ex-message e)}))
        tail (some-> out str/split-lines first str/trim not-empty)]
    (println (str (label p) " " (str/join " " argv) " -> "
                  (if (zero? (long exit)) "ok" (str "exit " exit))
                  (when tail (str " · " tail))))
    (when-not (zero? (long exit))
      (binding [*out* *err*] (println (label p) "failed:" (str/trim (str err)))))))

(defn watch-once!
  "Run a CONTINUOUS pipeline's verb once, its output streaming to `out`
  and `err` while it runs, and report on `err` how it ended. Returns the
  exit code. A watcher that keeps exiting must say why each time: a
  captured run would hold a bad config's message until the verb ends and
  then drop it."
  [p out err]
  (let [code (try (in-store p #(cli/run-argv-to (tick-argv (LocalDate/now ZoneOffset/UTC) p) out err))
                  (catch Throwable e
                    (binding [*out* err] (println (label p) "watcher threw:" (ex-message e)))
                    1))]
    (binding [*out* err]
      (println (label p) "watcher exited with code" code))
    code))

(defn- start-watcher!
  "Spawn a CONTINUOUS pipeline (serve, bridge imap --watch) as a daemon
  thread that runs its verb and restarts it with exponential backoff if it
  ever returns — supervision for the long-lived slices."
  [p]
  (let [out *out* err *err*]
    (doto (Thread.
           ^Runnable (fn []
                       (loop [backoff 1000]
                         (watch-once! p out err)
                         (binding [*out* err]
                           (println (label p) "restarting in" (quot backoff 1000) "s"))
                         (Thread/sleep backoff)
                         (recur (min (* 2 backoff) 60000))))
           (str "tik-backend-" (name (:id p))))
      (.setDaemon true)
      (.start))))

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
  (doseq [p pipelines]
    (when-not (:id p) (die (str "backend pipeline missing :id: " (pr-str p))))
    (when (contains? p :store)
      (die (str (label p) " names a :store, but this config has no :stores;"
                " a single-store backend runs against TIK_ROOT")))
    (when-let [k (first (filter #{:config :jitter :timeout} (keys p)))]
      (die (str (label p) " names " k ", which only a multi-store backend (a config with :stores) honours")))
    (when-let [problem (first (stores/pipeline-problems p))]
      (die (str (label p) " " problem)))))

(defn- start-thread!
  "A daemon thread running `f` with this thread's output streams."
  [thread-name f]
  (let [out *out* err *err*]
    (doto (Thread. ^Runnable (fn [] (binding [*out* out *err* err] (f))) ^String thread-name)
      (.setDaemon true)
      (.start))))

(defn- start-notifier!
  "Run the notifier over the backend's stores, restarted with backoff if
  it ever ends. It derives each store under its own root and signs
  nothing, so it runs outside every store context."
  [notify-cfg opened]
  (let [cfg ((requiring-resolve 'tik.notifier/config-from)
             (assoc notify-cfg :stores (into {} (for [[n {:keys [ctx]}] opened] [n (:root ctx)])))
             {})
        notify! (requiring-resolve 'tik.notifier/notify-forever!)]
    (start-thread! "tik-backend-notify"
                   (fn []
                     (loop [backoff 1000]
                       (try (notify! cfg)
                            (catch Throwable e
                              (binding [*out* *err*] (println "[notify] stopped:" (ex-message e)))))
                       (Thread/sleep backoff)
                       (recur (min (* 2 backoff) 60000)))))))

(defn- self-of [cfg]
  (run/self-command (:exec cfg) (run/runtime-properties)))

(defn- run-env
  "What the pipeline runner needs from the config: how to start this tik,
  the properties a run inherits, the argv of a fire, the failure report
  and how often a pipeline that keeps failing is reported again."
  [cfg]
  {:self (self-of cfg)
   :props (run/runtime-properties)
   :tick-argv tick-argv
   :on-failure (:on-failure cfg)
   :repeat-ms (or (some-> cfg :on-failure :repeat Duration/parse .toMillis) 3600000)})

(defn run-stores
  "Run a multi-store backend config (ADR 0041) until killed: check it,
  open every store or refuse them all, then serve, notify and run the
  pipelines, each inside its store's context. Returns only in tests, when
  *backend-ticks* caps the scheduler and nothing else blocks."
  [cfg cfg-file]
  (when-let [problems (seq (stores/check cfg))]
    (die (str "refusing to start " cfg-file ":\n  " (str/join "\n  " problems))))
  (let [{:keys [problems] opened :stores pipelines :pipelines} (stores/open-stores! cfg (into {} (System/getenv)))]
    (when (seq problems)
      (die (str "refusing to start " cfg-file "; no store was started:\n  " (str/join "\n  " problems))))
    (when (and (seq pipelines) (nil? (self-of cfg)))
      (die (str "refusing to start " cfg-file ": this tik cannot name its own executable to run"
                " pipelines with; set :exec [\"/path/to/tik\"]")))
    (context/strict!)
    (let [remaining (atom *backend-ticks*)
          more? #(or (nil? @remaining) (pos? (long @remaining)))
          on-fire #(when @remaining (swap! remaining dec))
          {watchers true scheduled false} (group-by (comp boolean :watch) pipelines)
          {:keys [host port] :or {host "127.0.0.1" port 7777}} (:serve cfg)
          stop (when (:serve cfg)
                 ((requiring-resolve 'tik.serve/start-store-router!)
                  {:host host :port port :stores opened}))]
      (println (str "tik backend: " (count opened) " store(s) (" (str/join ", " (sort (keys opened))) "), "
                    (count watchers) " continuous, " (count scheduled) " scheduled pipeline(s)"
                    (when stop (str "; serving http://" host ":" port "/s/<store>/"))))
      (doseq [[n {:keys [ctx token]}] (sort opened)]
        (println (str "  " n ": " (:root ctx) " as " (:actor ctx) " (" (:org ctx) ")"
                      (when stop (str ", board /s/" n "/")) (when token (str ", MCP /s/" n "/mcp")))))
      (flush)
      (when (:notify cfg) (start-notifier! (:notify cfg) opened))
      (let [env (run-env cfg)]
        (doseq [p watchers] (run/start-watcher! p env))
        (cond
          (seq scheduled) (run/run-scheduled! scheduled (assoc env :more? more? :on-fire on-fire))
          (and *backend-ticks* (empty? scheduled)) nil
          :else @(promise)))
      (when stop (stop)))))

(defn cmd-backend
  "backend [--config pipelines.edn|backend.edn]
  Run the configured pipelines as a supervised delegated-agent server:
  continuous pipelines (serve, bridge imap --watch) each get a restart-on-
  exit thread; scheduled pipelines fire on their :every interval. Set
  TIK_KEY to the delegate's key so every produced event is signed by the
  delegate (authorized by a delegation attestation on the registry).
  A config with :stores serves several stores from one process instead
  (ADR 0041, tik.backend-stores). Blocks until killed."
  [{:keys [opts]}]
  (let [cfg-file (or (:config opts) (str (File. ^String (root) "pipelines.edn")))
        _ (when-not (.exists (File. ^String cfg-file))
            (die (str "no backend config at " cfg-file
                      " (need {:pipelines [{:id … :every|:watch … :run [\"…\"]} …]})")))
        cfg (read-edn-file (File. ^String cfg-file))]
    (if (stores/multi-store? cfg)
      (run-stores cfg cfg-file)
      (let [pipelines (:pipelines cfg)
            _ (when-not (seq pipelines) (die (str "no :pipelines in " cfg-file)))
            _ (validate! pipelines)
            {watchers true scheduled false} (group-by (comp boolean :watch) pipelines)]
        (println (str "tik backend: " (count watchers) " continuous, "
                      (count scheduled) " scheduled pipeline(s)"))
        (doseq [p watchers] (start-watcher! p))
        (cond
          (seq scheduled) (run-scheduler scheduled)
          (seq watchers) @(promise)                            ; only watchers: block on them
          :else (die "no pipelines to run"))))))
