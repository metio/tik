;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.probe-test
  "`tik probe` is the one verb that derives facts from OUTSIDE the log,
  so its failure modes matter more than most: a probe that could not run
  must never render as a probe that found nothing changed. Both are zero
  facts; only one of them measured the world."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]
            [tik.cli-core]
            [tik.harness :as h]
            [tik.storeops]))

(defn- in
  [root & argv]
  (with-redefs-fn {#'tik.cli-core/root (constantly (str root))}
    (fn [] (cli/run-argv (mapv str argv)))))

(defn- sole-id
  "The one ticket's id, read back through the CLI's own listing."
  [root]
  (->> (str/split (:out (in root "ls" "--edn")) #"[^0-9a-f-]")
       (filter #(= 36 (count %)))
       first))

(defn- store-with-ticket!
  "A store holding one `track` ticket, plus the facts in `kvs`."
  [prefix & kvs]
  (System/setProperty "user.name" "tester")
  (let [root (h/temp-dir! prefix)]
    (in root "new" "track" "--title" "probe subject")
    (let [id (sole-id root)]
      (doseq [kv kvs] (in root "set" id kv))
      {:root root :id id})))

(deftest named_ticket_without_a_repo_fact_says_so_instead_of_reporting_zero
  ;; The reported failure: a ticket whose subject is a fact rather than a
  ;; repository fell through the [:repo] filter and left only the summary
  ;; line, which counts derived facts and so reads as a clean run.
  (let [{:keys [root id]} (store-with-ticket! "tik-probe-norepo" "workload=opengist")
        r (in root "probe" id "--command" "echo gate=green")]
    (is (= 1 (:exit r))
        "an id that cannot be probed is an error, not a quiet zero")
    (is (re-find #"cannot probe" (str (:out r) (:err r))))
    (is (re-find #"no \[:repo\] fact" (str (:out r) (:err r)))
        "the message names the missing fact, not just the refusal")
    (is (not (re-find #"0 fact\(s\) derived" (:out r)))
        "no success-shaped summary for a probe that never ran")))

(deftest named_ticket_whose_process_declares_no_probe_says_so
  (let [{:keys [root id]} (store-with-ticket! "tik-probe-noprobe" "repo=widget")
        _ (.mkdirs (io/file root "widget"))
        r (in root "probe" id)]
    (is (= 1 (:exit r)))
    (is (re-find #"declares no :probe" (str (:out r) (:err r))))))

(deftest named_ticket_with_a_missing_repository_directory_says_so
  (let [{:keys [root id]} (store-with-ticket! "tik-probe-nodir" "repo=absent")
        r (in root "probe" id "--command" "echo gate=green")]
    (is (= 1 (:exit r)))
    (is (re-find #"no such directory" (str (:out r) (:err r))))))

(deftest a_named_probe_that_exits_nonzero_fails_the_command
  ;; A probe that crashed measured nothing; the sweep tolerates one bad
  ;; repo, but an explicit request must not exit 0 on it.
  (let [{:keys [root id]} (store-with-ticket! "tik-probe-fail" "repo=widget")
        _ (.mkdirs (io/file root "widget"))
        r (in root "probe" id "--command" "echo boom >&2; exit 7")]
    (is (= 1 (:exit r)))
    (is (re-find #"probe failed" (str (:out r) (:err r))))))

(deftest the_sweep_counts_what_it_could_not_probe_rather_than_hiding_it
  ;; Skipping quietly is right for a whole-store sweep — but the summary
  ;; must still distinguish "nothing moved" from "nothing ran".
  (let [{:keys [root]} (store-with-ticket! "tik-probe-sweep" "workload=opengist")
        r (in root "probe" "--command" "echo gate=green")]
    (is (zero? (:exit r)) "one unprobeable ticket never aborts the sweep")
    (is (re-find #"0 probe\(s\) ran" (:out r)))
    (is (re-find #"1 ticket\(s\) had nothing to probe" (:out r)))))

(deftest a_probe_that_runs_derives_facts_and_reports_that_it_ran
  (let [{:keys [root id]} (store-with-ticket! "tik-probe-happy" "repo=widget")
        _ (.mkdirs (io/file root "widget"))
        r (in root "probe" id "--command" "echo gate=green")]
    (is (zero? (:exit r)))
    (is (re-find #"gate = :green" (:out r)))
    (is (re-find #"1 fact\(s\) derived" (:out r)))
    (is (re-find #"1 probe\(s\) ran" (:out r)))
    (testing "idempotent: the unchanged value asserts nothing the second time"
      (let [again (in root "probe" id "--command" "echo gate=green")]
        (is (re-find #"0 fact\(s\) derived" (:out again)))
        (is (re-find #"1 probe\(s\) ran" (:out again))
            "the probe ran — which is what separates this from the skip case")))))
