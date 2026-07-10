;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store-discovery-test
  "Root discovery, git-style: TIK_ROOT wins, else the nearest ancestor
  with a hidden .tik store, else the nearest with a classic tickets/
  directory, else the cwd. Each test runs tik with a REAL working
  directory (bb --config keeps the repo's task while cwd points into
  the scenario) and no TIK_ROOT in the environment."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- tmpdir []
  (.toFile (Files/createTempDirectory "tik-disco" (make-array FileAttribute 0))))

(defn- tik-at
  "Run tik with cwd `dir`, TIK_ROOT scrubbed unless supplied."
  [dir env & args]
  (apply sh/sh (concat ["bb" "--config" (str repo "/bb.edn") "tik"]
                       (map str args)
                       [:dir (str dir)
                        :env (merge (dissoc (into {} (System/getenv))
                                            "TIK_ROOT")
                                    {"TIK_ACTOR" "seb"}
                                    env)])))

(deftest hidden_store_is_found_from_any_depth
  (let [top (tmpdir)
        deep (io/file top "repos" "jaas" "src")]
    (.mkdirs deep)
    (testing "init --hidden marks the portfolio directory"
      (let [r (tik-at top nil "init" "--hidden")]
        (is (zero? (:exit r)) (:err r))
        (is (.isDirectory (io/file top ".tik" "tickets")))))
    (testing "a ticket filed from deep inside a repo lands in .tik"
      (let [r (tik-at deep nil "new" "track" "--title" "portfolio-wide idea")]
        (is (zero? (:exit r)) (:err r))
        (is (= 1 (count (.listFiles (io/file top ".tik" "tickets")))))))
    (testing "ls from anywhere beneath shows the same board"
      (is (re-find #"portfolio-wide idea"
                   (:out (tik-at deep nil "ls"))))
      (is (re-find #"portfolio-wide idea"
                   (:out (tik-at top nil "ls")))))))

(deftest classic_visible_stores_keep_working_from_subdirectories
  (let [top (tmpdir)
        sub (io/file top "docs")]
    (.mkdirs sub)
    (tik-at top nil "new" "track" "--title" "visible store")
    (is (re-find #"visible store" (:out (tik-at sub nil "ls"))))))

(deftest resolution_order_is_env_then_nearest_marker
  (let [outer (tmpdir)
        inner (io/file outer "team")
        elsewhere (tmpdir)]
    (.mkdirs inner)
    (tik-at outer nil "init" "--hidden")
    (tik-at inner nil "init" "--hidden")
    (tik-at outer nil "new" "track" "--title" "outer ticket")
    (tik-at inner nil "new" "track" "--title" "inner ticket")
    (tik-at elsewhere {"TIK_ROOT" (str elsewhere)} "new" "track"
            "--title" "env ticket")
    (testing "the NEAREST .tik wins over an outer one"
      (let [out (:out (tik-at inner nil "ls"))]
        (is (re-find #"inner ticket" out))
        (is (not (re-find #"outer ticket" out)))))
    (testing "TIK_ROOT beats any discovery"
      (let [out (:out (tik-at inner {"TIK_ROOT" (str elsewhere)} "ls"))]
        (is (re-find #"env ticket" out))
        (is (not (re-find #"inner ticket" out)))))))

(deftest a_markerless_directory_stays_a_fresh_store
  ;; temp dirs live outside $HOME with no ancestor markers: the cwd
  ;; must win, exactly as before discovery existed
  (let [lonely (tmpdir)]
    (is (re-find #"no tickets yet" (:out (tik-at lonely nil "ls"))))
    (tik-at lonely nil "new" "track" "--title" "born here")
    (is (.isDirectory (io/file lonely "tickets")))))

(deftest init_refuses_to_double_init
  (let [top (tmpdir)]
    (tik-at top nil "init" "--hidden")
    (let [r (tik-at top nil "init" "--hidden")]
      (is (= 1 (:exit r)))
      (is (re-find #"already a store" (:err r))))))
