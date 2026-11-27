;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pipeline-run
  "Running a multi-store backend's pipelines: the impure half of the
  scheduler (`tik.schedule` decides, this executes). Each run is a child
  process of the same binary, started with its store's variables only
  (`tik.context/child-env`), its output streamed line by line into the
  backend's own, labelled with the pipeline, and killed when it outlives
  its timeout. See ADR 0042 for why a run is a process and not a call.

  Output is labelled `[store/pipeline]`. When the backend's output goes
  to the journal (systemd sets JOURNAL_STREAM), each line also carries
  an sd-daemon priority prefix, so `journalctl -p warning` shows the
  pipelines' error output and the failure reports without the sweeps'
  progress lines."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.context :as context]
            [tik.schedule :as schedule])
  (:import (java.io BufferedReader File InputStream InputStreamReader)
           (java.nio.charset StandardCharsets)
           (java.time LocalDate ZoneOffset)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

;; ------------------------------------------------------------ output

(def levels
  "sd-daemon(3) priorities of the lines the backend writes."
  {:err 3 :warning 4 :notice 5 :info 6})

(defn journal-line
  "One output line: `text` labelled with `label`, and prefixed with the
  priority of `level` when `journal?`. Line breaks inside `text` are
  flattened, so one line written is one journal entry."
  ^String [journal? level label text]
  (str (when journal? (str "<" (levels level 6) ">"))
       label " "
       (str/replace (str text) #"[\r\n]+" " ")))

(defn label
  "The label of a pipeline's lines: `[store/id]`."
  [{:keys [id] :as p}]
  (str "[" (when-let [s (:tik/store p)] (str (:name s) "/")) (name id) "]"))

(defn- journal?
  "Does the backend's output go to the journal? Read per line, never at
  load time: the native image initializes namespaces when it is built."
  []
  (some? (System/getenv "JOURNAL_STREAM")))

(def ^:private out-lock (Object.))

(defn emit!
  "Write one labelled line to the backend's stdout (`:info`, `:notice`) or
  stderr (`:warning`, `:err`), whole, never interleaved with another."
  [level lbl text]
  (let [line (journal-line (journal?) level lbl text)]
    (locking out-lock
      (if (#{:warning :err} level)
        (binding [*out* *err*] (println line) (flush))
        (do (println line) (flush))))))

;; ------------------------------------------------------------ the command

(def forwarded-properties
  "System properties a run inherits from the backend as `-D` arguments:
  the trust store a private CA needs reaches every sweep."
  ["javax.net.ssl.trustStore" "javax.net.ssl.trustStorePassword" "javax.net.ssl.trustStoreType"])

(defn self-command
  "How the backend starts itself: [prefix suffix], with the forwarded
  `-D` properties going between them. `exec` from the config wins;
  otherwise a native image runs its own executable and babashka its
  checkout. Any other runtime (a JVM REPL, the test suite) has no way to
  name itself, and nil asks the caller to refuse."
  [exec props]
  (cond
    (seq exec) [(vec exec) []]
    ;; babashka is a native image too, so it is asked first
    (get props "babashka.version")
    (when (and (get props "babashka.config") (get props "tik.self.exe"))
      [[(get props "tik.self.exe")] ["--config" (get props "babashka.config") "tik"]])
    (= "runtime" (get props "org.graalvm.nativeimage.imagecode"))
    (when-let [exe (get props "tik.self.exe")] [[exe] []])))

(defn- own-executable []
  (try (str (java.nio.file.Files/readSymbolicLink (.toPath (File. "/proc/self/exe"))))
       (catch Exception _ nil)))

(defn runtime-properties
  "The properties `self-command` reads, from this process."
  []
  (cond-> (into {} (for [k (concat ["org.graalvm.nativeimage.imagecode" "babashka.version" "babashka.config"]
                                   forwarded-properties)
                         :let [v (System/getProperty k)] :when v] [k v]))
    (own-executable) (assoc "tik.self.exe" (own-executable))))

(defn run-argv
  "The full argv of one run: the backend's own command, the forwarded
  properties, the pipeline's verb with its delegate and period, and its
  config file."
  [[prefix suffix] props ^LocalDate today tick-argv {:keys [config-path] :as p}]
  (vec (concat prefix
               (for [k forwarded-properties :let [v (get props k)] :when v] (str "-D" k "=" v))
               suffix
               (tick-argv today p)
               (when config-path ["--config" config-path]))))

(def dropped-variables
  "Variables a run never inherits: its output is a pipe read by the
  backend, so it must not believe it writes to the journal itself."
  #{"JOURNAL_STREAM"})

(defn child-env
  "The environment of a run of pipeline `p`: the backend's, with the
  store variables of the pipeline's store context and nothing of any
  other store's."
  [p]
  (let [env (context/with-store (:tik/store p) (context/child-env))]
    (apply dissoc env dropped-variables)))

;; ------------------------------------------------------------ one run

(def ^:private live (atom #{}))

(defn- tree-fn [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn- kill-tree!
  "Stop a process and whatever it started (a password manager, an ssh
  tunnel): ask first, force after `grace-ms`. On babashka, which cannot
  list a process's descendants, only the process itself is stopped."
  [^Process proc grace-ms]
  (let [kids (or (some-> (tree-fn 'tik.process-tree/descendants-of) (apply [proc])) [])
        destroy! (or (tree-fn 'tik.process-tree/destroy!) (fn [_ _]))]
    (destroy! kids false)
    (.destroy proc)
    (when-not (.waitFor proc (long grace-ms) TimeUnit/MILLISECONDS)
      (destroy! kids true)
      (.destroyForcibly proc)
      (.waitFor proc 5 TimeUnit/SECONDS))))

(defonce ^:private hook
  (delay (.addShutdownHook (Runtime/getRuntime)
                           (Thread. ^Runnable (fn [] (doseq [p @live] (kill-tree! p 2000)))))))

(defn- daemon!
  "Start `f` on a daemon thread named `thread-name`."
  ^Thread [^String thread-name ^Runnable f]
  (doto (Thread. f thread-name)
    (.setDaemon true)
    (.start)))

(defn- pump!
  "Copy each line of `in` to the backend's output under `lbl`."
  ^Thread [^InputStream in level lbl]
  (daemon! (str "tik-pump " lbl)
           (bound-fn []
             (with-open [r (BufferedReader. (InputStreamReader. in StandardCharsets/UTF_8))]
               (loop []
                 (when-let [l (try (.readLine r) (catch java.io.IOException _ nil))]
                   (emit! level lbl l)
                   (recur)))))))

(defn run-child!
  "Run `argv` with `env` as one run labelled `lbl`, its output streamed
  as it comes, and kill it after `timeout-ms` (nil: no bound). Returns
  {:exit :ms :timed-out?}; a command that cannot start is exit 127."
  [lbl argv env timeout-ms]
  @hook
  (let [t0 (System/currentTimeMillis)
        pb (doto (ProcessBuilder. ^java.util.List (vec argv))
             (.redirectInput (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/null"))))
        _ (doto (.environment pb) (.clear) (.putAll ^java.util.Map env))
        ^Process proc (try (.start pb)
                  (catch java.io.IOException e
                    (emit! :err lbl (str "cannot start " (first argv) ": " (ex-message e)))
                    nil))]
    (if-not proc
      {:exit 127 :ms 0 :timed-out? false}
      (do
        (swap! live conj proc)
        (let [o (pump! (.getInputStream proc) :info lbl)
              e (pump! (.getErrorStream proc) :warning lbl)
              done? (if timeout-ms
                      (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)
                      (do (.waitFor proc) true))]
          (when-not done? (kill-tree! proc 5000))
          (.join o 2000)
          (.join e 2000)
          (swap! live disj proc)
          {:exit (.exitValue proc)
           :ms (- (System/currentTimeMillis) t0)
           :timed-out? (not done?)})))))

;; ------------------------------------------------------------ failure reports

(defn report-argv
  "The failure report command with its placeholders filled: {store},
  {pipeline}, {exit} and {reason}. Every value comes from the backend
  (names from its config, a number, a sentence it wrote), never from a
  remote, and the command runs without a shell."
  [run {:keys [store pipeline exit reason]}]
  (mapv #(-> (str %)
             (str/replace "{store}" (str store))
             (str/replace "{pipeline}" (str pipeline))
             (str/replace "{exit}" (str exit))
             (str/replace "{reason}" (str reason)))
        run))

(defn seconds
  "Milliseconds as seconds for a person: whole when they are whole."
  [ms]
  (let [ms (long ms)]
    (if (zero? (rem ms 1000)) (str (quot ms 1000)) (format "%.1f" (/ (double ms) 1000)))))

(defn reason
  "One sentence on how a run ended, or nil when it succeeded."
  [{:keys [exit timed-out?]} timeout-ms]
  (cond
    timed-out? (str "timed out after " (seconds timeout-ms) " s and was killed")
    (= 127 exit) "could not start"
    (not (zero? (long exit))) (str "exited with code " exit)))

(defn report!
  "Send one failure report: label it, and run the `:on-failure` command
  when there is one, bounded to 30 seconds."
  [{:keys [on-failure]} p r why]
  (let [lbl (label p)]
    (emit! :err lbl (str "FAILED: " why))
    (when-let [cmd (:run on-failure)]
      (let [argv (report-argv cmd {:store (some-> p :tik/store :name) :pipeline (name (:id p))
                                   :exit (:exit r) :reason why})
            res (run-child! (str lbl " [on-failure]") argv (into {} (System/getenv)) 30000)]
        (when-not (zero? (long (:exit res)))
          (emit! :err lbl (str "the failure report itself failed: "
                               (reason res 30000))))))))

;; ------------------------------------------------------------ the loops

(defn- today [] (LocalDate/now ZoneOffset/UTC))

(defn run-scheduled!
  "Run the scheduled pipelines until `more?` says stop: each fires on its
  grid with jitter, at most one run of a pipeline at a time, each run
  bounded by its timeout and reported when it fails. Returns once
  `more?` is false and every started run has ended.

  `env` is {:self … :props … :tick-argv … :on-failure … :repeat-ms …}."
  [pipelines {:keys [self props tick-argv repeat-ms more? on-fire]
              :or {repeat-ms 3600000 more? (constantly true) on-fire (fn [])} :as env}]
  (let [ended (LinkedBlockingQueue.)
        by-id (into {} (map (juxt :id identity)) pipelines)
        rnd #(rand)
        now #(System/currentTimeMillis)
        t0 (now)]
    (loop [scheds (into {} (for [p pipelines] [(:id p) (schedule/initial t0 p (rnd))]))
           outcomes {}]
      (let [running (count (filter :running? (vals scheds)))]
        (if (and (not (more?)) (zero? running))
          outcomes
          (let [t (now)
                [scheds started]
                (if (more?)
                  (reduce (fn [[ss started] [id s]]
                            (let [p (by-id id)
                                  [action s'] (schedule/decide s p t (rnd))]
                              (case action
                                :skip (do (emit! :notice (label p) "skipped: the previous run is still running")
                                          [(assoc ss id s') started])
                                :start [(assoc ss id s') (conj started p)]
                                [(assoc ss id s') started])))
                          [scheds []]
                          (sort-by key scheds))
                  [scheds []])]
            (doseq [p started]
              (on-fire)
              (let [argv (run-argv self props (today) tick-argv p)
                    tmo (schedule/timeout-ms p)]
                (daemon! (str "tik-run " (label p))
                         (bound-fn []
                           (let [r (try (run-child! (label p) argv (child-env p) tmo)
                                        (catch Throwable e
                                          (emit! :err (label p) (str "run threw: " (ex-message e)))
                                          {:exit 1 :ms 0}))]
                             (.put ended [(:id p) r]))))))
            (let [wait (schedule/sleep-ms (vals scheds) (now))
                  done (.poll ended wait TimeUnit/MILLISECONDS)]
              (if-not done
                (recur scheds outcomes)
                (let [[id r] done
                      p (by-id id)
                      t (now)
                      why (reason r (schedule/timeout-ms p))
                      last-o (get outcomes id)
                      report? (schedule/report? last-o (some? why) t repeat-ms)]
                  (emit! (if why :err :info) (label p)
                         (str (str/join " " (tick-argv (today) p)) " -> "
                              (if why (str "exit " (:exit r)) "ok")
                              " (" (format "%.1f" (/ (double (:ms r 0)) 1000)) " s)"))
                  (when report? (report! env p r why))
                  (when (and (not why) last-o (not (:ok? last-o)))
                    (emit! :info (label p) "recovered"))
                  (recur (update scheds id #(schedule/finished % p t (rnd)))
                         (assoc outcomes id {:ok? (nil? why)
                                             :reported-at (if report? t (:reported-at last-o))
                                             :last r})))))))))))

(defn start-watcher!
  "Run a continuous pipeline as a child process, restarted with backoff
  whenever it exits, on a daemon thread, until `stop?` (tests) says
  stop. A non-zero exit is a failure and reported like a scheduled
  run's. Returns the thread."
  [p {:keys [self props tick-argv repeat-ms stop?] :or {repeat-ms 3600000 stop? (constantly false)} :as env}]
  (daemon!
   (str "tik-backend-" (name (:id p)))
   (bound-fn []
           (loop [backoff schedule/first-backoff-ms last-o nil]
             (let [argv (run-argv self props (today) tick-argv p)
                   r (try (run-child! (label p) argv (child-env p) nil)
                          (catch Throwable e
                            (emit! :err (label p) (str "run threw: " (ex-message e)))
                            {:exit 1 :ms 0}))
                   why (reason r nil)
                   t (System/currentTimeMillis)
                   report? (schedule/report? last-o (some? why) t repeat-ms)
                   {:keys [wait] :as nxt} (schedule/restart backoff (:ms r 0))]
               (emit! (if why :err :notice) (label p)
                      (str "exited with code " (:exit r) " after "
                           (format "%.1f" (/ (double (:ms r 0)) 1000)) " s; restarting in "
                           (quot (long wait) 1000) " s"))
               (when report? (report! env p r why))
               (Thread/sleep (long wait))
               (when-not (stop?)
                 (recur (:backoff nxt)
                        {:ok? (nil? why) :reported-at (if report? t (:reported-at last-o))})))))))

