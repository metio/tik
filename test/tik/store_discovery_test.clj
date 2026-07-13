;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store-discovery-test
  "Root discovery, git-style: TIK_ROOT wins, else the nearest ancestor
  with a hidden .tik store, else the nearest with a classic tickets/
  directory, else the cwd. Each test runs tik with a REAL working
  directory (bb --config keeps the repo's task while cwd points into
  the scenario) and no TIK_ROOT in the environment."
  (:require [tik.harness :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- tmpdir []
  (h/temp-dir! "tik-disco"))

(defn- tik-at
  "Run tik with cwd `dir`, TIK_ROOT scrubbed unless supplied."
  [dir env & args]
  (apply h/run-tik! {:root nil :dir dir :actor "seb" :env env} args))

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
    (testing "with no store reachable, ls leads with how to establish one"
      (let [out (:out (tik-at lonely nil "ls"))]
        (is (re-find #"no tik store here yet" out))
        (is (re-find #"tik init" out)
            "the guidance names tik init, not just author/new")))
    (tik-at lonely nil "new" "track" "--title" "born here")
    (is (.isDirectory (io/file lonely "tickets")))
    (testing "once a store exists, the empty-board hint drops the init lines"
      (let [top (tmpdir)]
        (tik-at top nil "init")
        (let [out (:out (tik-at top nil "ls"))]
          (is (re-find #"no tickets yet" out))
          (is (not (re-find #"tik init" out))
              "an established store must not tell you to init again"))))))

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
                   (:out (tik-at top nil "ls" "--all" "--where" (str "fact:repo=" ":jaas"))))))))

(deftest rollout_builds_the_living_checklist
  (let [top (tmpdir)]
    (doseq [r ["alpha" "beta" "gamma"]]
      (.mkdirs (io/file top r ".git")))
    (.mkdirs (io/file top "not-a-repo"))
    (.mkdirs (io/file top "group" "subgroup" "delta" ".git"))
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
        (is (re-find #"4 ticket\(s\) created, 0 already covered, 4 repo\(s\) total"
                     (:out r)))
        (is (re-find #"\(started\)\s+[0-9a-f]{8} mig: alpha" (:out r)))
        (is (re-find #"\(started\)\s+[0-9a-f]{8} mig: group/subgroup/delta"
                     (:out r))
            "GitLab-style nested repos are found; identity is the path")
        (is (not (re-find #"not-a-repo" (:out r))))))
    (testing "children carry the repo dimension"
      (is (re-find #"beta" (:out (tik-at top nil "ls" "--all" "--where" (str "fact:repo=" ":beta"))))))
    (testing "re-runs are idempotent and the checklist derives progress"
      (let [beta-id (-> (tik-at top nil "ls" "--all" "--where" (str "fact:repo=" ":beta"))
                        :out str/split-lines first (str/split #"\s+") first)]
        (tik-at top nil "set" beta-id "proof=\"pr-42\"")
        (let [r (tik-at top nil "rollout" "mig")]
          (is (re-find #"0 ticket\(s\) created, 4 already covered" (:out r)))
          (is (re-find #"\(done\)\s+[0-9a-f]{8} mig: beta" (:out r))
              "the checkmark derived itself from the child's evidence"))))))

(deftest probe_derives_facts_from_the_world
  (let [top (tmpdir)]
    (doseq [r ["alpha" "beta"]]
      (.mkdirs (io/file top r ".git")))
    (tik-at top nil "init" "--hidden")
    (.mkdirs (io/file top ".tik" "processes"))
    (spit (io/file top ".tik" "processes" "mig.edn")
          (pr-str {:process/id :mig :process/version 1
                   :process/guard-vocab 1
                   :lint {:runbooks :off}
                   :probe "probes/mig.sh"
                   :process/facts {[:proof] [:string {:min 2}]}
                   :process/stages [{:stage/id :started :guards []}
                                    {:stage/id :done :after [:started]
                                     :stage/sticky? true
                                     :guards [[:fact [:proof]]]}]}))
    (.mkdirs (io/file top ".tik" "probes"))
    (spit (io/file top ".tik" "probes" "mig.sh")
          "if [ -f marker.txt ]; then echo \"proof=\\\"$(cat marker.txt)\\\"\"; fi\n")
    (tik-at top nil "rollout" "mig")
    (testing "no evidence in the world, no facts asserted"
      (is (re-find #"0 fact\(s\) derived" (:out (tik-at top nil "probe")))))
    (testing "evidence appears; the probe records it and the stage derives"
      (spit (io/file top "alpha" "marker.txt") "pr-77")
      (let [r (tik-at top nil "probe")]
        (is (re-find #"alpha: proof = \"pr-77\"" (:out r)))
        (is (re-find #"alpha -> .*done" (:out r)))
        (is (re-find #"1 fact\(s\) derived" (:out r)))))
    (testing "re-probing an unchanged world asserts nothing"
      (is (re-find #"0 fact\(s\) derived" (:out (tik-at top nil "probe")))))))

(deftest org_authoring_rules_feed_check_and_prompt_alike
  (let [top (tmpdir)]
    (tik-at top nil "init" "--hidden")
    (spit (io/file top ".tik" "authoring-rules.edn")
          (pr-str {:rules [{:id :metio-ban-foo :on :stage-name
                            :match "^foo" :level :warning
                            :msg "violates metio house style"
                            :teach "metio house style: never name a stage foo-anything."}]}))
    (spit (io/file top "answers.edn")
          (pr-str {:name "x"
                   :stages [{:name "foo-bar" :after [] :needs []}]}))
    (testing "check applies the org rule"
      (is (re-find #"metio house style"
                   (:out (tik-at top nil "author" "check" "answers.edn")))))
    (testing "the prompt teaches the very same rule"
      (is (re-find #"metio house style: never name a stage"
                   (:out (tik-at top nil "author" "prompt")))))))

(deftest rollout_reruns_converge_titles
  ;; a child created under an old naming scheme gets retitled by the
  ;; next rollout run — as a superseding fact, never by editing history
  (let [top (tmpdir)]
    (.mkdirs (io/file top "alpha" ".git"))
    (tik-at top nil "init" "--hidden")
    (.mkdirs (io/file top ".tik" "processes"))
    (spit (io/file top ".tik" "processes" "mig.edn")
          (pr-str {:process/id :mig :process/version 1
                   :process/guard-vocab 1 :lint {:runbooks :off}
                   :process/stages [{:stage/id :started :guards []}]}))
    (let [old (str/trim (:out (tik-at top nil "new" "mig" "--title" "alpha")))]
      (tik-at top nil "set" old "repo=:alpha")
      (tik-at top nil "rollout" "mig")
      (testing "adopted, not duplicated; renamed, not rewritten"
        (let [out (:out (tik-at top nil "ls"))]
          (is (re-find #"mig: alpha" out))
          (is (= 1 (count (re-seq #"alpha" out))))))
      (testing "the original title survives in the log"
        (is (re-find #"create .*:title \"alpha\""
                     (:out (tik-at top nil "log" old))))))))

(deftest link_section_orders_by_the_target_process_and_deduplicates
  (let [top (tmpdir)]
    (doseq [r ["alpha" "beta" "gamma"]]
      (.mkdirs (io/file top r ".git")))
    (tik-at top nil "init" "--hidden")
    (.mkdirs (io/file top ".tik" "processes"))
    (spit (io/file top ".tik" "processes" "mig.edn")
          (pr-str {:process/id :mig :process/version 1
                   :process/guard-vocab 1 :lint {:runbooks :off}
                   :process/facts {[:proof] [:string {:min 2}]
                                   [:ack] [:string {:min 2}]}
                   :process/stages [{:stage/id :started :guards []}
                                    {:stage/id :proven :after [:started]
                                     :guards [[:fact [:proof]]]}
                                    {:stage/id :acked :after [:proven]
                                     :stage/sticky? true
                                     :guards [[:fact [:ack]]]}]}))
    (tik-at top nil "rollout" "mig")
    (let [find-child (fn [r] (-> (tik-at top nil "ls" "--all" "--where"
                                         (str "fact:repo=:" r))
                                 :out str/split-lines first
                                 (str/split #"\s+") first))
          parent (-> (tik-at top nil "ls") :out str/split-lines
                     (->> (filter #(re-find #"rollout" %)))
                     first (str/split #"\s+") first)]
      (tik-at top nil "set" (find-child "gamma") "proof=\"pr-1\"" "ack=\"ok\"")
      (tik-at top nil "set" (find-child "alpha") "proof=\"pr-2\"")
      (let [links (->> (tik-at top nil "status" parent) :out
                       str/split-lines
                       (filter #(re-find #"^\s+\([a-z]" %))
                       vec)]
        (testing "least progressed first, per the process's own ordering"
          (is (re-find #"\(started\).*beta" (first links)) (pr-str links))
          (is (re-find #"\(proven\).*alpha" (second links)))
          (is (re-find #"\(acked\).*gamma" (nth links 2))))
        (testing "the rel is omitted when the title already says it"
          (is (not-any? #(re-find #"\[beta\]" %) links))))
      (testing "link facts stay out of the rendered facts table"
        (is (not (re-find #"\[:link"
                          (:out (tik-at top nil "status" parent)))))))))

(deftest link_section_summarizes_and_filters
  (let [top (tmpdir)]
    (doseq [r ["alpha" "beta" "gamma"]]
      (.mkdirs (io/file top r ".git")))
    (tik-at top nil "init" "--hidden")
    (.mkdirs (io/file top ".tik" "processes"))
    (spit (io/file top ".tik" "processes" "mig.edn")
          (pr-str {:process/id :mig :process/version 1
                   :process/guard-vocab 1 :lint {:runbooks :off}
                   :process/facts {[:proof] [:string {:min 2}]}
                   :process/stages [{:stage/id :started :guards []}
                                    {:stage/id :done :after [:started]
                                     :stage/sticky? true
                                     :guards [[:fact [:proof]]]}]}))
    (tik-at top nil "rollout" "mig")
    (let [child (-> (tik-at top nil "ls" "--all" "--where" (str "fact:repo=" ":beta"))
                    :out str/split-lines first (str/split #"\s+") first)
          parent (-> (tik-at top nil "ls") :out str/split-lines
                     (->> (filter #(re-find #"rollout" %)))
                     first (str/split #"\s+") first)]
      (tik-at top nil "set" child "proof=\"pr-9\"")
      (testing "the header counts per stage, in process order"
        (is (re-find #"links:  \(started 2 · done 1\)"
                     (:out (tik-at top nil "status" parent)))))
      (testing "--links narrows the rows; the header still counts all"
        (let [out (:out (tik-at top nil "status" parent "--links" ":done"))]
          (is (re-find #"links:  \(started 2 · done 1\)" out))
          (is (re-find #"mig: beta" out))
          (is (not (re-find #"mig: alpha" out))))))))

(deftest status_names_the_pinned_definition_after_migration
  (let [top (tmpdir)]
    (tik-at top nil "init" "--hidden")
    (.mkdirs (io/file top ".tik" "processes"))
    (spit (io/file top ".tik" "processes" "second.edn")
          (pr-str {:process/id :second :process/version 3
                   :process/guard-vocab 1 :lint {:runbooks :off}
                   :process/stages [{:stage/id :here :guards []}]}))
    (let [id (str/trim (:out (tik-at top nil "new" "track" "--title" "mover")))]
      (is (re-find #"rules:   :track v1" (:out (tik-at top nil "status" id))))
      (tik-at top nil "reprocess" id ".tik/processes/second.edn" "--apply"
              "--reason" "moving on")
      (testing "the pinned definition names the rules, not the create label"
        (is (re-find #"rules:   :second v3"
                     (:out (tik-at top nil "status" id))))))))

(deftest packing_changes_layout_never_truth
  (let [top (tmpdir)]
    (tik-at top nil "init" "--hidden")
    (let [id (str/trim (:out (tik-at top nil "new" "track" "--title" "packable")))
          evdir (io/file top ".tik" "tickets" id "events")]
      (tik-at top nil "set" id "note=\"gathering\"")
      (tik-at top nil "set" id "outcome=\"all wrapped up\"")
      (let [before (:out (tik-at top nil "status" id))
            loose-before (count (.listFiles evdir))]
        (testing "pack consolidates the settled ticket"
          (let [r (tik-at top nil "pack")]
            (is (re-find #"1 ticket\(s\) packed" (:out r)))
            (is (.isFile (io/file evdir "events.pack")))
            (is (< (count (.listFiles evdir)) loose-before))))
        (testing "derivation is byte-identical after packing"
          (is (= before (:out (tik-at top nil "status" id)))))
        (testing "verify checks every packed slice against its id"
          (let [r (tik-at top nil "verify" id)]
            (is (re-find #"packed slice hashes to id" (:out r)))
            (is (re-find #"verify: PASS" (:out r)))))
        (testing "appends after packing land loose and merge on read"
          (tik-at top nil "comment" id "postscript")
          (is (re-find #"attach .*comment/"
                       (:out (tik-at top nil "log" id))))
          (is (some #(str/ends-with? (.getName ^java.io.File %) ".edn")
                    (.listFiles evdir))))
        (testing "re-packing folds the loose newcomers in"
          (tik-at top nil "pack" id)
          (let [r (tik-at top nil "verify" id)]
            (is (re-find #"verify: PASS" (:out r)))))))))
