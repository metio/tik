;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pipeline-run-test
  "Running a multi-store backend's pipelines as child processes: the
  labelled journal lines, the command a run is started with and the
  environment it gets, the timeout that kills a stuck run, no
  overlapping runs of one pipeline, and the failure report. The child
  is a small shell script standing in for tik, so each behaviour (slow,
  failing, hanging) is chosen by the pipeline's :run."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tik.backend :as backend]
            [tik.harness :as h]
            [tik.pipeline-run :as run])
  (:import (java.io StringWriter)
           (java.time LocalDate)))

;; ------------------------------------------------------------ pure parts

(deftest journal_lines_carry_the_label_and_a_priority_only_for_the_journal
  (is (= "[metio/github-metio] swept 3 repos"
         (run/journal-line false :info "[metio/github-metio]" "swept 3 repos")))
  (is (= "<6>[metio/github-metio] swept" (run/journal-line true :info "[metio/github-metio]" "swept")))
  (is (= "<4>[a/b] x" (run/journal-line true :warning "[a/b]" "x")))
  (is (= "<3>[a/b] x" (run/journal-line true :err "[a/b]" "x")))
  (is (= "<5>[a/b] x" (run/journal-line true :notice "[a/b]" "x")))
  (is (= "[a/b] one two" (run/journal-line false :info "[a/b]" "one\r\ntwo"))
      "one line written is one journal entry, so a line break cannot forge another pipeline's line"))

(deftest a_label_names_the_store_and_the_pipeline
  (is (= "[metio/github-metio]" (run/label {:id :github-metio :tik/store {:name "metio"}})))
  (is (= "[weekly]" (run/label {:id :weekly}))))

(deftest a_run_is_started_as_this_tik_with_the_verb_its_delegate_and_its_config
  (let [today (LocalDate/of 2026 11 27)]
    (is (= ["/usr/bin/tik" "-Djavax.net.ssl.trustStore=/etc/pki/ca.p12"
            "bridge" "github" "--actor" "github" "--config" "/s/github.edn"]
           (run/run-argv [["/usr/bin/tik"] []] {"javax.net.ssl.trustStore" "/etc/pki/ca.p12"}
                         today backend/tick-argv
                         {:id :g :run ["bridge" "github"] :as "github" :config-path "/s/github.edn"})))
    (is (= ["bb" "--config" "/repo/bb.edn" "tik" "recur" "track" "--actor" "claude" "--period" "2026-W48"]
           (run/run-argv [["bb"] ["--config" "/repo/bb.edn" "tik"]] {} today backend/tick-argv
                         {:id :w :run ["recur" "track"] :as "claude" :period :iso-week})))))

(deftest this_tik_names_itself_or_asks_for_exec
  (is (= [["/x/tik"] []] (run/self-command ["/x/tik"] {})) ":exec wins")
  (is (= [["/opt/tik"] []] (run/self-command nil {"org.graalvm.nativeimage.imagecode" "runtime"
                                                  "tik.self.exe" "/opt/tik"}))
      "a native image runs its own executable")
  (is (= [["/bin/bb"] ["--config" "/r/bb.edn" "tik"]]
         (run/self-command nil {"babashka.version" "1" "babashka.config" "/r/bb.edn" "tik.self.exe" "/bin/bb"}))
      "babashka runs its checkout")
  (is (= [["/bin/bb"] ["--config" "/r/bb.edn" "tik"]]
         (run/self-command nil {"babashka.version" "1" "babashka.config" "/r/bb.edn" "tik.self.exe" "/bin/bb"
                                "org.graalvm.nativeimage.imagecode" "runtime"}))
      "babashka is a native image as well, and still runs its checkout")
  (is (nil? (run/self-command nil {"babashka.version" "1" "tik.self.exe" "/bin/bb"}))
      "babashka without a config cannot name the checkout")
  (is (nil? (run/self-command nil {"tik.self.exe" "/usr/lib/jvm/bin/java"}))
      "a JVM cannot name its own command line, so the backend refuses to guess"))

(deftest a_failure_report_fills_its_placeholders_in_separate_arguments
  (is (= ["notify-send" "tik: metio/github-metio failed" "exited with code 1 (exit 1)"]
         (run/report-argv ["notify-send" "tik: {store}/{pipeline} failed" "{reason} (exit {exit})"]
                          {:store "metio" :pipeline "github-metio" :exit 1 :reason "exited with code 1"}))))

(deftest how_a_run_ended_in_one_sentence
  (is (nil? (run/reason {:exit 0} 1000)))
  (is (= "exited with code 3" (run/reason {:exit 3} 1000)))
  (is (= "could not start" (run/reason {:exit 127} 1000)))
  (is (= "timed out after 600 s and was killed" (run/reason {:exit 143 :timed-out? true} 600000))))

(deftest a_run_sees_its_own_stores_variables_only
  (let [env (run/child-env {:id :x :tik/store {:name "a" :root "/stores/a" :key "/run/a.key" :actor "github"}})]
    (is (= "/stores/a" (get env "TIK_ROOT")))
    (is (= "/run/a.key" (get env "TIK_KEY")))
    (is (= "github" (get env "TIK_ACTOR")))
    (is (not (contains? env "TIK_PQ_KEY")))
    (is (not (contains? env "JOURNAL_STREAM")) "a run writes to a pipe, never to the journal itself")))

;; ------------------------------------------------------------ child processes

(defn- stub!
  "A stand-in for tik: `<mode> <log> …`. It logs `start`/`end` lines to
  <log>, prints a line to stdout and one to stderr, and then: `ok` exits
  0, `slow` sleeps first, `fail` exits 3, `hang` sleeps a minute, `env`
  prints TIK_ROOT."
  [dir]
  (let [f (io/file dir "stub.sh")]
    (spit f (str "#!/bin/sh\n"
                 "mode=$1; log=$2\n"
                 "echo \"start $$\" >> \"$log\"\n"
                 "echo \"to stdout from $mode\"\n"
                 "echo \"to stderr from $mode\" >&2\n"
                 "case \"$mode\" in\n"
                 "  slow) sleep 1.2 ;;\n"
                 "  hang) sleep 60 ;;\n"
                 "  env) echo \"root=$TIK_ROOT\" ;;\n"
                 "esac\n"
                 "echo \"end $$\" >> \"$log\"\n"
                 "[ \"$mode\" = fail ] && exit 3\n"
                 "exit 0\n"))
    (str f)))

(defn- env-for [dir & {:as more}]
  (merge {:self [["sh" (stub! dir)] []] :props {} :tick-argv backend/tick-argv} more))

(defn- pipeline [id mode log & {:as more}]
  (merge {:id id :run [mode (str log)] :every-ms 300 :jitter-ms 0
          :tik/store {:name "s" :root "/stores/s" :key "/k" :actor "a"}}
         more))

(defn- capture
  "Run f with *out* and *err* captured; [result out err]."
  [f]
  (let [o (StringWriter.) e (StringWriter.)
        r (binding [*out* o *err* e] (f))]
    [r (str o) (str e)]))

(defn- ticks [n]
  (let [left (atom n)]
    {:more? #(pos? @left) :on-fire #(swap! left dec)}))

(deftest a_runs_output_reaches_the_backend_labelled_line_by_line
  (let [dir (h/temp-dir! "tik-run")
        log (io/file dir "log")
        [r out err] (capture #(run/run-child! "[s/x]" ["sh" (stub! dir) "env" (str log)]
                                              (run/child-env (pipeline :x "env" log)) 5000))]
    (is (zero? (:exit r)))
    (is (str/includes? out "[s/x] to stdout from env"))
    (is (str/includes? out "[s/x] root=/stores/s") "the run got its store's root")
    (is (str/includes? err "[s/x] to stderr from env") "stderr stays stderr")))

(deftest a_run_that_outlives_its_timeout_is_killed
  (let [dir (h/temp-dir! "tik-run")
        log (io/file dir "log")
        t0 (System/currentTimeMillis)
        [r] (capture #(run/run-child! "[s/x]" ["sh" (stub! dir) "hang" (str log)] {} 500))]
    (is (:timed-out? r))
    (is (not (zero? (:exit r))))
    (is (< (- (System/currentTimeMillis) t0) 20000) "it did not run its minute")
    (is (not (str/includes? (slurp log) "end")) "it was stopped before it finished")))

(deftest a_command_that_cannot_start_is_exit_127
  (let [[r _ err] (capture #(run/run-child! "[s/x]" ["/nonexistent/tik"] {} 1000))]
    (is (= 127 (:exit r)))
    (is (str/includes? err "cannot start /nonexistent/tik"))))

(defn- overlaps?
  "Do two `start` lines follow each other without an `end` between?"
  [log-text]
  (loop [[l & more] (str/split-lines log-text) open? false]
    (cond
      (nil? l) false
      (str/starts-with? l "start") (if open? true (recur more true))
      (str/starts-with? l "end") (recur more false)
      :else (recur more open?))))

(deftest a_pipeline_never_overlaps_its_own_runs
  (let [dir (h/temp-dir! "tik-run")
        log (io/file dir "log")
        [_ out] (capture #(run/run-scheduled! [(pipeline :slow "slow" log :timeout-ms 10000)]
                                              (merge (env-for dir) (ticks 3))))
        text (slurp log)]
    (is (= 3 (count (re-seq #"start" text))) "three runs")
    (is (= 3 (count (re-seq #"end" text))) "each finished")
    (is (not (overlaps? text)) "never two at once, although each run outlasts the 300 ms interval")
    (is (str/includes? out "[s/slow] skipped: the previous run is still running")
        "the fires that fell due during a run were skipped, which shows overlap was possible")))

(deftest a_failing_pipeline_is_reported_and_the_others_carry_on
  (let [dir (h/temp-dir! "tik-run")
        log-ok (io/file dir "ok.log")
        log-bad (io/file dir "bad.log")
        report (io/file dir "report")
        on-failure {:run ["sh" "-c" "printf '%s|%s|%s|%s\\n' \"$1\" \"$2\" \"$3\" \"$4\" >> \"$0\""
                          (str report) "{store}" "{pipeline}" "{exit}" "{reason}"]}
        [outcomes out err] (capture #(run/run-scheduled! [(pipeline :good "ok" log-ok)
                                                          (pipeline :bad "fail" log-bad)]
                                                         (merge (env-for dir :on-failure on-failure :repeat-ms 0)
                                                                (ticks 4))))]
    (is (pos? (count (re-seq #"end" (slurp log-ok)))) "the good pipeline ran")
    (is (re-find #"\[s/good\] ok \S+ -> ok" out) "and reported ok")
    (is (str/includes? err "[s/bad] FAILED: exited with code 3"))
    (is (str/includes? (slurp report) "s|bad|3|exited with code 3")
        "the report command ran with the placeholders filled")
    (is (false? (get-in outcomes [:bad :ok?])))
    (is (true? (get-in outcomes [:good :ok?])))))

(deftest a_failure_is_not_reported_again_within_the_repeat_window
  (let [dir (h/temp-dir! "tik-run")
        report (io/file dir "report")
        [_ _ err] (capture #(run/run-scheduled! [(pipeline :bad "fail" (io/file dir "log"))]
                                                (merge (env-for dir :on-failure {:run ["sh" "-c" "echo x >> \"$0\"" (str report)]}
                                                                :repeat-ms 3600000)
                                                       (ticks 3))))]
    (is (= 3 (count (re-seq #"\[s/bad\] .* -> exit 3" err))) "every failed run is logged")
    (is (= 1 (count (re-seq #"FAILED" err))) "but reported once")
    (is (= "x\n" (slurp report)))))

(deftest a_timed_out_run_is_reported_as_such
  (let [dir (h/temp-dir! "tik-run")
        report (io/file dir "report")
        [_ _ err] (capture #(run/run-scheduled! [(pipeline :stuck "hang" (io/file dir "log") :timeout-ms 400)]
                                                (merge (env-for dir :on-failure {:run ["sh" "-c" "echo \"$1\" >> \"$0\"" (str report) "{reason}"]})
                                                       (ticks 1))))]
    (is (str/includes? err "[s/stuck] FAILED: timed out after 0.4 s and was killed"))
    (is (str/includes? (slurp report) "timed out"))))

(deftest a_watcher_is_restarted_with_backoff_and_reported_once
  (let [dir (h/temp-dir! "tik-run")
        log (io/file dir "log")
        report (io/file dir "report")
        stop (atom false)
        o (StringWriter.) e (StringWriter.)
        t (binding [*out* o *err* e]
            (run/start-watcher! (dissoc (pipeline :inbox "fail" log) :every-ms :jitter-ms)
                                (env-for dir :stop? #(deref stop)
                                         :on-failure {:run ["sh" "-c" "echo x >> \"$0\"" (str report)]})))]
    (Thread/sleep 3800)                                   ; runs at 0 s, 1 s and 3 s
    (reset! stop true)
    (.join ^Thread t 10000)
    (let [starts (count (re-seq #"start" (slurp log)))]
      (is (<= 2 starts 4) (str "restarted, with backoff, " starts " times"))
      (is (str/includes? (str e) "[s/inbox] exited with code 3"))
      (is (re-find #"restarting in 1 s" (str e)))
      (is (re-find #"restarting in 2 s" (str e)) "the second wait is twice the first")
      (is (= "x\n" (slurp report)) "a crash loop sends one report, not one per restart"))))
