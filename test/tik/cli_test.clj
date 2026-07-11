;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.cli-test
  "The porcelain's parsing contract — the H9 surface: values people
  actually type must round-trip without EDN knowledge."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def parse-value #'cli/parse-value)

(deftest values_people_actually_type
  (testing "one complete EDN form stays EDN"
    (is (= 120 (parse-value "120")))
    (is (= :green (parse-value ":green")))
    (is (= "quoted" (parse-value "\"quoted\"")))
    (is (= [1 2] (parse-value "[1 2]"))))
  (testing "bare single words become keywords (facts over strings)"
    (is (= :billing (parse-value "billing")))
    (is (= :tik-author (parse-value "tik-author"))))
  (testing "everything else is the literal string, never a die"
    (is (= "tik author: guided interview" (parse-value "tik author: guided interview")))
    (is (= "31ffd53" (parse-value "31ffd53")))
    (is (= "hello world" (parse-value "hello world")))
    (is (= "a,b{" (parse-value "a,b{")))))

(defn- in
  "run-argv against a fresh store: the private root accessor is
  redirected at its var so every command resolves the temp dir, no
  TIK_ROOT env needed."
  [root & argv]
  (with-redefs-fn {#'cli/root (constantly (str root))}
    (fn [] (cli/run-argv (mapv str argv)))))

(deftest run_argv_is_the_in_process_entry_point
  ;; the second entry beside -main: same dispatch, output captured, and
  ;; every exit! trapped into a CODE instead of killing the process —
  ;; this is what lets the MCP server reuse the whole CLI without a
  ;; subprocess (cli/tik/mcp.clj)
  (let [root (.toFile (Files/createTempDirectory
                       "tik-runargv" (make-array FileAttribute 0)))]
    (System/setProperty "user.name" "tester")
    (testing "a successful command returns exit 0 and captures stdout"
      (let [_ (in root "new" "track" "--title" "in-process ticket")
            r (in root "ls" "--edn")]
        (is (zero? (:exit r)))
        (is (str/includes? (:out r) "in-process ticket"))))
    (testing "a die (unknown id) is trapped: nonzero exit, message on err"
      (let [r (in root "explain" "nomatch" "--edn")]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "no ticket starting with"))
        (is (str/blank? (:out r)))))
    (testing "a gated refusal is trapped with the frontier's exit code"
      (let [id (-> (in root "ls" "--edn") :out
                   (str/split #"[^0-9a-f-]") (->> (filter #(= 36 (count %))))
                   first)
            r (in root "agent" "set" id "x=1" "--actor" "outsider")]
        (is (= 3 (:exit r)))
        (is (re-find #"REFUSED|admissible|not admitted"
                     (str (:out r) (:err r))))))
    (testing "an unknown command does not throw out of the process"
      (let [r (in root "no-such-command")]
        (is (integer? (:exit r)))
        (is (str/includes? (str (:out r) (:err r)) "not a command"))))))
