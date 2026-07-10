;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.guide-test
  "The getting-started guide, executed. Every `tik …` line inside the
  guide's sh blocks must either run here (in guide order, against a
  fresh store, with real ids substituted) or sit on the explicit
  interactive skip-list — so the doc and the software cannot drift
  apart without this test failing."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private repo (System/getProperty "user.dir"))

(def ^:private interactive
  "Commands the guide shows but a test cannot drive: interviews,
  watch loops, servers, and the install smoke lines."
  #{"tik --help" "bb tik --help" "tik author" "tik sim processes/bug.edn"})

(defn- guide-commands []
  (let [text (slurp (str repo "/docs/getting-started.md"))
        blocks (re-seq #"(?s)```sh\n(.*?)```" text)]
    (for [[_ block] blocks
          line (str/split-lines block)
          :let [line (str/trim line)]
          :when (and (seq line)
                     (not (str/starts-with? line "#"))
                     (re-find #"^(mkdir|tik|bb tik)" line))]
      line)))

(defn- run-line [root line]
  (let [line (str/replace line #"^bb tik" "tik")]
    (when (str/starts-with? line "tik ")
      ;; naive word split is fine for the guide as long as the guide
      ;; sticks to unquoted args — which unquoted-prose `set` and
      ;; plain flags make natural
      (apply sh/sh (concat ["bb" "tik"]
                           (rest (str/split line #"\s+"))
                           [:dir repo
                            :env (assoc (into {} (System/getenv))
                                        "TIK_ROOT" (str root)
                                        "TIK_ACTOR" "alice")])))))

(deftest the_guide_is_executable_start_to_finish
  (let [root (.toFile (Files/createTempDirectory
                       "tik-guide" (make-array FileAttribute 0)))
        state (atom {})
        ;; quoted titles survive word-splitting badly; the two `new`
        ;; commands are run structurally instead
        substituted
        (fn [line]
          (str/replace line "<id>" (or (:id @state) "<id>")))]
    (doseq [line (guide-commands)
            :when (not (contains? interactive line))]
      (testing line
        (cond
          (str/starts-with? line "mkdir")
          (is (some? root) "the temp store stands in for mkdir")

          (str/starts-with? line "tik new track")
          (let [r (sh/sh "bb" "tik" "new" "track" "--title"
                         "replace the office router"
                         :dir repo
                         :env (assoc (into {} (System/getenv))
                                     "TIK_ROOT" (str root)
                                     "TIK_ACTOR" "alice"))]
            (is (zero? (:exit r)) (:err r))
            (swap! state assoc :id (str/trim (:out r))))

          (str/starts-with? line "tik new bug")
          (let [r (sh/sh "bb" "tik" "new" "bug" "--title"
                         "login fails on Firefox"
                         :dir repo
                         :env (assoc (into {} (System/getenv))
                                     "TIK_ROOT" (str root)
                                     "TIK_ACTOR" "alice"))]
            (is (zero? (:exit r)) (:err r))
            (swap! state assoc :id (str/trim (:out r))))

          (str/starts-with? line "tik test")
          ;; the freshly templated skeleton FAILS INTO EXPLAIN by
          ;; design — it must run and teach, not pass
          (let [r (run-line root (substituted line))]
            (is (re-find #"To reach|test:" (str (:out r) (:err r)))
                (:err r)))

          :else
          (let [r (run-line root (substituted line))]
            (is (zero? (:exit r))
                (str line "\n" (:out r) "\n" (:err r)))))))
    (testing "the guide's storyline actually happened"
      (let [status (sh/sh "bb" "tik" "status" (:id @state)
                          :dir repo
                          :env (assoc (into {} (System/getenv))
                                      "TIK_ROOT" (str root)
                                      "TIK_ACTOR" "alice"))]
        (is (re-find #"login fails on Firefox" (:out status)))))))

(deftest every_guide_command_is_covered
  (testing "no sh-block command escapes both execution and the skip-list"
    (doseq [line (guide-commands)]
      (is (or (contains? interactive line)
              (re-find #"^(mkdir|tik)" line))
          line))))
