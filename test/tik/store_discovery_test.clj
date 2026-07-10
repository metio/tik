;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store-discovery-test
  "Root discovery, git-style: TIK_ROOT wins, else the nearest ancestor
  with a hidden .tik store, else the nearest with a classic tickets/
  directory, else the cwd. Each test runs tik with a REAL working
  directory (bb --config keeps the repo's task while cwd points into
  the scenario) and no TIK_ROOT in the environment."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(deftest context_facts_make_the_repo_dimension_automatic
  (let [top (tmpdir)
        jaas (io/file top "jaas" "src")
        chart (io/file top "charts" "stageset")]
    (.mkdirs jaas)
    (.mkdirs chart)
    (.mkdirs (io/file top "jaas" ".git"))
    (tik-at top nil "init" "--hidden")
    (testing "the enclosing git repo becomes a signed repo fact, unasked"
      (let [r (tik-at jaas nil "new" "track" "--title" "renovate for jaas")
            id (str/trim (:out r))]
        (is (re-find #"context: repo=:jaas" (:err r)))
        (is (re-find #"\[:repo\] = :jaas"
                     (:out (tik-at top nil "status" id))))))
    (testing "marker files annotate everything beneath them"
      (spit (io/file top "charts" ".tik-facts.edn")
            "{:team :platform [:component :kind] :chart}")
      (let [r (tik-at chart nil "new" "track" "--title" "bump appVersion")
            id (str/trim (:out r))
            status (:out (tik-at top nil "status" id))]
        (is (re-find #"\[:team\] = :platform" status))
        (is (re-find #"\[:component :kind\] = :chart" status))))
    (testing "an explicit marker beats the automatic repo"
      (spit (io/file top "jaas" ".tik-facts.edn") "{:repo :jaas-monorepo}")
      (let [r (tik-at jaas nil "new" "track" "--title" "second jaas idea")
            id (str/trim (:out r))]
        (is (re-find #"\[:repo\] = :jaas-monorepo"
                     (:out (tik-at top nil "status" id))))))
    (testing "the portfolio view slices by the derived dimension"
      (is (re-find #"renovate for jaas"
                   (:out (tik-at top nil "query" "fact" "repo" ":jaas")))))))

(deftest rollout_builds_the_living_checklist
  (let [top (tmpdir)]
    (doseq [r ["alpha" "beta" "gamma"]]
      (.mkdirs (io/file top r ".git")))
    (.mkdirs (io/file top "not-a-repo"))
    (tik-at top nil "init" "--hidden")
    (.mkdirs (io/file top ".tik" "processes"))
    (spit (io/file top ".tik" "processes" "mig.edn")
          (pr-str {:process/id :mig :process/version 1
                   :process/guard-vocab 1
                   :lint {:runbooks :off}
                   :process/facts {[:proof] [:string {:min 2}]}
                   :process/stages [{:stage/id :started :guards []}
                                    {:stage/id :done :after [:started]
                                     :stage/sticky? true
                                     :guards [[:fact [:proof]]]}]}))
    (testing "one ticket per git repo, a parent, links rendered live"
      (let [r (tik-at top nil "rollout" "mig")]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"3 ticket\(s\) created, 0 already covered, 3 repo\(s\) total"
                     (:out r)))
        (is (re-find #"alpha -> [0-9a-f]{8} alpha \(started\)" (:out r)))
        (is (not (re-find #"not-a-repo" (:out r))))))
    (testing "children carry the repo dimension"
      (is (re-find #"beta" (:out (tik-at top nil "query" "fact" "repo" ":beta")))))
    (testing "re-runs are idempotent and the checklist derives progress"
      (let [beta-id (-> (tik-at top nil "query" "fact" "repo" ":beta")
                        :out str/split-lines first (str/split #"\s+") first)]
        (tik-at top nil "set" beta-id "proof=\"pr-42\"")
        (let [r (tik-at top nil "rollout" "mig")]
          (is (re-find #"0 ticket\(s\) created, 3 already covered" (:out r)))
          (is (re-find #"beta -> [0-9a-f]{8} beta \(done\)" (:out r))
              "the checkmark derived itself from the child's evidence"))))))
