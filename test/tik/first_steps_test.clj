;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.first-steps-test
  "The newcomer's first session, end to end, with assertions on every
  surface they touch — help, hints, errors, and the golden path. The
  guide test proves the DOCUMENT runs; this one pins the EXPERIENCE:
  friendly output for the things a first session actually does,
  including the mistakes."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- fresh-root []
  (.toFile (Files/createTempDirectory
            "tik-first" (make-array FileAttribute 0))))

(defn- tik* [root & args]
  (apply sh/sh (concat ["bb" "tik"] (map str args)
                       [:dir repo
                        :env (assoc (into {} (System/getenv))
                                    "TIK_ROOT" (str root)
                                    "TIK_ACTOR" "newbie")])))

(deftest help_is_reachable_every_way_a_newcomer_tries
  (let [root (fresh-root)]
    (doseq [args [["--help"] ["-h"] ["help"] []]]
      (let [r (apply tik* root args)]
        (is (zero? (:exit r)) (pr-str args))
        (is (re-find #"start here:" (:out r)) (pr-str args))
        (is (not (re-find #"is not a command" (:out r))) (pr-str args))))))

(deftest the_empty_store_teaches_the_first_three_commands
  (let [r (tik* (fresh-root) "ls")]
    (is (zero? (:exit r)))
    (is (re-find #"no tickets yet" (:out r)))
    (is (re-find #"tik author" (:out r)))
    (is (re-find #"tik new track" (:out r)))))

(deftest every_first_mistake_answers_with_the_next_command
  (let [root (fresh-root)]
    (testing "typo'd command"
      (is (re-find #"is not a command" (:out (tik* root "nwe")))))
    (testing "new without a process lists what exists"
      (let [r (tik* root "new")]
        (is (re-find #"tik author" (:err r)))))
    (testing "unknown process suggests author and track"
      (let [r (tik* root "new" "sprint")]
        (is (re-find #"no process named 'sprint'" (:err r)))))
    (testing "unknown ticket points at ls"
      (let [r (tik* root "status" "deadbeef")]
        (is (re-find #"tik ls" (:err r)))))
    (testing "bad set arguments teach key=value"
      (tik* root "new" "track" "--title" "x")
      (let [r (tik* root "set")]
        (is (re-find #"key=value" (:err r)))))
    (testing "lint on a missing file mentions the no-arg store lint"
      (let [r (tik* root "lint" "nope.edn")]
        (is (re-find #"lints the store" (:err r)))))))

(deftest the_golden_path_needs_no_quoting_knowledge
  (let [root (fresh-root)
        id (str/trim (:out (tik* root "new" "track"
                                 "--title" "my very first ticket")))]
    (testing "prose facts, numbers and keywords all just work"
      (tik* root "set" id "outcome=waited for parts, then fixed it")
      (let [status (:out (tik* root "status" id))]
        (is (re-find #"stage:\s+done" status))
        (is (re-find #"waited for parts, then fixed it" status))))
    (testing "prefix ids work like git"
      (is (re-find #"my very first ticket"
                   (:out (tik* root "status" (subs id 0 8))))))
    (testing "a lookalike of an OPEN ticket triggers the radar"
      (tik* root "new" "track" "--title" "replace the office chair")
      (let [r (tik* root "new" "track"
                    "--title" "replace the office chair maybe")]
        (is (re-find #"looks like" (:err r)))))
    (testing "settled tickets are not radar candidates"
      (let [r (tik* root "new" "track" "--title" "my very first ticket")]
        (is (not (re-find #"looks like" (str (:err r)))))))))

(deftest the_shipped_examples_hold_the_newcomer's_hand
  (let [root (fresh-root)]
    (testing "template processes lint clean and their tests run"
      (tik* root "author" "--template" "purchase-approval")
      (let [r (tik* root "lint" "processes/purchase-approval.edn")]
        (is (zero? (:exit r)) (:out r))))
    (testing "the examples directory is living documentation"
      (doseq [ex ["incident-response" "employee-onboarding"]]
        (let [r (sh/sh "bb" "tik" "test"
                       (str "examples/" ex ".tests.edn") :dir repo)]
          (is (zero? (:exit r)) (str ex ": " (:out r))))))))
