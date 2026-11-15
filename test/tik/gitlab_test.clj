;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.gitlab-test
  "CI failure streaks through the GitLab adapter: pipelines in the run
  shape, retried pipelines closing the ticket opened on them, project
  discovery, and the SSH forward's command and URL rewriting."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tik.bridge-sync :as sync]
            [tik.cli-core :as core]
            [tik.forge :as forge]
            [tik.gitlab :as gl]
            [tik.harness :as h]
            [tik.stage :as stage]
            [tik.store.protocol :as store])
  (:import (java.time Instant)))

(def ^:private target {:repo "infra.run/ansible/ansible-base" :project-id 77 :branches ["main"]})

(defn- pipeline
  ([n status] (pipeline n status "push"))
  ([n status source]
   {:id (+ 5000 n) :iid n :status status :source source :ref "main"
    :sha (format "%040d" n)
    :web_url (str "https://git.example/infra.run/ansible/ansible-base/-/pipelines/" (+ 5000 n))
    :created_at (str (Instant/ofEpochSecond (+ 1700000000 (* n 3600))))
    :updated_at (str (Instant/ofEpochSecond (+ 1800000000 (* n 3600))))
    :user {:username "dev"}}))

(defn- getter
  "A get-json stub serving project 77's `pipelines`, filtered by source,
  newest first."
  [pipelines]
  (fn [path {:keys [source page] :or {page 1}}]
    (case path
      "/projects/77/pipelines"
      (if (> page 1)
        []
        (->> pipelines (filter #(or (nil? source) (= source (:source %))))
             (sort-by :id >) vec))
      (throw (ex-info (str "unexpected path " path) {})))))

(defn- store-with-process! []
  (let [{:keys [root store]} (h/temp-store!)
        dest (io/file root "processes" "ci-failure.edn")]
    (io/make-parents dest)
    (io/copy (io/file "processes/ci-failure.edn") dest)
    {:root root :store store}))

(def ^:private t (Instant/parse "2030-01-01T00:00:00Z"))

(defn- sync! [{:keys [root store]} pipelines source]
  (h/with-cli-root root
    (fn []
      (with-redefs [core/now (constantly t)]
        (let [ctx (assoc (sync/bridge-ctx {:process :ci-failure :actor "gitlab"} {})
                         :host "git.example")]
          (sync/sync-gitlab-source! store (getter pipelines) {} ctx target "main" source
                                    (Instant/ofEpochSecond 0) {}))))))

(defn- current [{:keys [root store]} id]
  (h/with-cli-root root
    (fn []
      (let [{:keys [process events roles]} (core/ticket-ctx store id)]
        (stage/current-stages process (stage/effective-reached process events t roles))))))

(defn- fact [{:keys [root store]} id path]
  (get-in (h/with-cli-root root #(core/ticket-ctx store id)) [:state :facts path :value]))

(deftest a_pipeline_becomes_a_run_dated_by_its_creation
  (let [r (gl/pipeline target (pipeline 3 "failed"))]
    (is (= {:id 5003 :number 3 :conclusion "failed" :workflow-id "push"
            :branch "main" :repo-id 77 :repo "infra.run/ansible/ansible-base"}
           (select-keys r [:id :number :conclusion :workflow-id :branch :repo-id :repo])))
    (is (= (:created_at (pipeline 3 "failed")) (:started-at r)))))

(deftest a_failing_streak_opens_a_ticket_and_the_next_green_pipeline_recovers_it
  (let [st (store-with-process!)
        id (forge/ticket-id "git.example" 77 "push" "main" 5002)]
    (is (= [:ticket] (map :outcome (sync! st [(pipeline 1 "success") (pipeline 2 "failed")] "push"))))
    (is (= #{:failing} (current st id)))
    (is (= "git.example" (fact st id [:forge :host])))
    (is (= "infra.run/ansible" (fact st id [:forge :owner])))
    (is (= :ansible-base (fact st id [:repo])))
    (is (= [:recovered] (map :outcome (sync! st [(pipeline 1 "success") (pipeline 2 "failed")
                                                 (pipeline 3 "success")]
                                             "push"))))
    (is (= #{:recovered} (current st id)))
    (testing "a re-poll writes nothing"
      (is (empty? (sync! st [(pipeline 1 "success") (pipeline 2 "failed") (pipeline 3 "success")]
                         "push"))))))

(deftest a_pipeline_retried_to_green_recovers_the_ticket_opened_on_it
  (let [st (store-with-process!)
        id (forge/ticket-id "git.example" 77 "push" "main" 5002)]
    (sync! st [(pipeline 1 "success") (pipeline 2 "failed")] "push")
    (is (= [:recovered] (map :outcome (sync! st [(pipeline 1 "success") (pipeline 2 "success")]
                                             "push"))))
    (is (= #{:recovered} (current st id)))))

(deftest pipeline_sources_are_separate_streaks
  (let [st (store-with-process!)
        ps [(pipeline 1 "success" "push") (pipeline 2 "failed" "schedule")
            (pipeline 3 "success" "push")]]
    (is (empty? (sync! st ps "push")))
    (is (= [:ticket] (map :outcome (sync! st ps "schedule"))))))

(deftest canceled_and_skipped_pipelines_say_nothing
  (let [st (store-with-process!)]
    (sync! st [(pipeline 1 "success") (pipeline 2 "failed") (pipeline 3 "canceled")
               (pipeline 4 "skipped")]
           "push")
    (is (= 1 (count (store/ticket-ids (:store st)))))
    (is (= #{:failing} (current st (forge/ticket-id "git.example" 77 "push" "main" 5002))))))

(deftest targets_cover_membership_groups_and_projects
  (let [errors (atom [])
        get-json (fn [path _]
                   (case path
                     "/projects" [{:id 1 :path_with_namespace "infra.run/a" :default_branch "main"}
                                  {:id 2 :path_with_namespace "infra.run/old" :default_branch "main"
                                   :archived true}]
                     "/groups/infra.run%2Fansible/projects"
                     [{:id 3 :path_with_namespace "infra.run/ansible/b" :default_branch "master"}
                      {:id 4 :path_with_namespace "infra.run/ansible/noise" :default_branch "main"}]
                     "/projects/infra.run%2Fc" {:id 5 :path_with_namespace "infra.run/c"
                                                :default_branch "main"}
                     (throw (ex-info "403" {:status 403}))))]
    (is (= [{:repo "infra.run/a" :project-id 1 :branches ["main"]}
            {:repo "infra.run/ansible/b" :project-id 3 :branches ["master"]}
            {:repo "infra.run/c" :project-id 5 :branches ["main" "next"]}]
           (gl/targets get-json {:membership true :groups ["infra.run/ansible" "secret"]
                                 :projects [{:project "infra.run/c" :branches ["main" "next"]}]
                                 :exclude ["infra.run/ansible/noise"]}
                       (fn [what _] (swap! errors conj what)))))
    (is (= ["secret"] @errors))))

(deftest the_tunnel_forwards_a_free_local_port_and_keeps_the_api_path
  (is (= ["ssh" "-N" "-o" "ExitOnForwardFailure=yes" "-o" "BatchMode=yes"
          "-L" "127.0.0.1:4711:127.0.0.1:8080" "admin@git.example"]
         (forge/ssh-command {:ssh "admin@git.example" :target "127.0.0.1:8080"} 4711)))
  (is (= "http://127.0.0.1:4711/api/v4"
         (forge/tunneled-url "http://git.example/api/v4" 4711)))
  (is (= "https://git.example/api/v4"
         (forge/with-tunnel nil "https://git.example/api/v4" identity)))
  (is (= "git.example" (gl/host-of "http://git.example"))))

(deftest plain_http_is_refused_off_loopback
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-HTTPS"
                        (forge/require-tls! "http://git.example/api/v4")))
  (is (forge/require-tls! "http://127.0.0.1:4711/api/v4")))
