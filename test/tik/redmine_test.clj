;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.redmine-test
  "The Redmine bridge: the issue's opening values recovered from its
  journal, coordination-free ingest, stages following the mirrored
  status, and issues that leave the query staying mirrored."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.bridge-sync :as sync]
            [tik.cli-core :as core]
            [tik.harness :as h]
            [tik.redmine :as rm]
            [tik.stage :as stage]
            [tik.store.protocol :as store])
  (:import (java.time Instant)))

(def ^:private tables
  {:status {"1" {:name "New" :is_closed false}
            "2" {:name "In Progress" :is_closed false}
            "5" {:name "Closed" :is_closed true}}
   :priority {"2" "Normal" "3" "High"}
   :tracker {"1" "Bug"}
   :project {"9" "Ops"}
   :users {11 "seb"}})

(defn- journal [id at & {:keys [details notes user]}]
  {:id id :created_on at :updated_on at :notes (or notes "")
   :user (or user {:id 11 :name "Sebastian"}) :details (vec details)})

(defn- attr [n old new] {:property "attr" :name n :old_value old :new_value new})

(def ^:private issue
  {:id 4711
   :subject "Renew the certificate"
   :description "The cert expires."
   :created_on "2026-10-01T08:00:00Z"
   :updated_on "2026-10-03T12:00:00Z"
   :project {:id 9 :name "Ops"}
   :tracker {:id 1 :name "Bug"}
   :status {:id 5 :name "Closed"}
   :priority {:id 3 :name "High"}
   :author {:id 12 :name "Alex"}
   :assigned_to {:id 11 :name "Sebastian"}
   :journals [(journal 101 "2026-10-01T09:00:00Z"
                       :details [(attr "status_id" "1" "2") (attr "assigned_to_id" nil "11")]
                       :user {:id 12 :name "Alex"})
              (journal 102 "2026-10-02T10:00:00Z"
                       :details [(attr "subject" "Renew cert" "Renew the certificate")
                                 (attr "priority_id" "2" "3")])
              (journal 103 "2026-10-03T12:00:00Z"
                       :details [(attr "status_id" "2" "5")]
                       :notes "Renewed and deployed.")]})

(defn- ctx [nms]
  {:host "redmine.example" :url "https://redmine.example" :actor "redmine"
   :process :redmine-issue :version 1 :process-hash "sha256-x" :clamp identity
   :names nms})

(deftest the_opening_values_are_the_current_ones_with_the_journal_undone
  (is (= {"status_id" "1" "assigned_to_id" nil "priority_id" "2" "tracker_id" "1"
          "project_id" "9" "subject" "Renew cert" "due_date" nil
          "description" "The cert expires."}
         (rm/initial-values issue))))

(deftest names_render_through_the_tables_and_the_config_users
  (let [nms (rm/names tables issue)]
    (is (= "seb" (rm/render nms "assigned_to_id" "11")) "the config maps a Redmine user to an actor")
    (is (= "Alex" (rm/render nms "assigned_to_id" "12")) "else the name Redmine shows")
    (is (= "redmine-user-99" (rm/render nms "assigned_to_id" "99")))
    (is (= "In Progress" (rm/render nms "status_id" "2")))
    (is (nil? (rm/render nms "due_date" nil)))))

(deftest issue_events_are_a_pure_function_of_the_issue
  (let [a (rm/issue-events (ctx (rm/names tables issue)) issue)
        b (rm/issue-events (ctx (rm/names tables issue)) (update issue :journals reverse))]
    (is (= (map :event/id (:events a)) (map :event/id (:events b))))
    (is (= (rm/ticket-id "redmine.example" 4711) (:id a)))
    (is (= "#4711 Renew cert" (get-in (first (:events a)) [:event/body :ticket/title])))))

;; ---------------------------------------------------------------- sync

(defn- store-with-process! []
  (let [{:keys [root store]} (h/temp-store!)
        dest (io/file root "processes" "redmine-issue.edn")]
    (io/make-parents dest)
    (io/copy (io/file "processes/redmine-issue.edn") dest)
    {:root root :store store}))

(def ^:private t (Instant/parse "2030-01-01T00:00:00Z"))

(defn- getter
  "A Redmine stub: `assigned` lists for the query, `issues` by id."
  [assigned issues]
  (fn [path q]
    (case path
      "/issues.json"
      (let [xs (if-let [ids (:issue_id q)]
                 (let [want (set (map parse-long (str/split ids #",")))]
                   (filter #(want (:id %)) (vals issues)))
                 (map issues assigned))]
        {:issues (if (pos? (long (:offset q))) [] (vec xs)) :total_count (count xs)})
      "/issue_statuses.json"
      {:issue_statuses [{:id 1 :name "New"} {:id 2 :name "In Progress"}
                        {:id 5 :name "Closed" :is_closed true}]}
      "/enumerations/issue_priorities.json"
      {:issue_priorities [{:id 2 :name "Normal"} {:id 3 :name "High"}]}
      "/trackers.json" {:trackers [{:id 1 :name "Bug"}]}
      (if-let [[_ id] (re-matches #"/issues/(\d+)\.json" path)]
        {:issue (issues (parse-long id))}
        (throw (ex-info (str "unexpected path " path) {}))))))

(defn- poll! [{:keys [root store]} assigned issues]
  (h/with-cli-root root
    (fn []
      (with-redefs [core/now (constantly t)]
        (let [c (assoc (sync/bridge-ctx {:process :redmine-issue :actor "redmine"} {})
                       :host "redmine.example" :url "https://redmine.example")]
          (binding [*out* (java.io.StringWriter.)]
            (sync/redmine-poll! store (getter assigned issues)
                                {:url "https://redmine.example" :users {11 "seb"}} c {})))))))

(defn- event-ids [s]
  (set (for [id (store/ticket-ids s) e (store/events s id)] (:event/id e))))

(defn- view [{:keys [root store]} id]
  (h/with-cli-root root
    (fn []
      (let [{:keys [process events roles state]} (core/ticket-ctx store id)]
        {:stages (stage/current-stages process (stage/effective-reached process events t roles))
         :fact #(get-in state [:facts % :value])
         :artifacts (set (keys (:artifacts state)))}))))

(deftest an_issue_syncs_with_its_history_and_its_stage_follows_the_status
  (let [st (store-with-process!)
        id (rm/ticket-id "redmine.example" 4711)]
    (is (= {:ticket 1} (poll! st [4711] {4711 issue})))
    (let [{:keys [stages fact artifacts]} (view st id)]
      (is (= #{:closed} stages))
      (is (= "Closed" (fact [:redmine :status])))
      (is (= "seb" (fact [:redmine :assignee])))
      (is (= "High" (fact [:redmine :priority])))
      (is (= "Renew the certificate" (fact [:redmine :subject])))
      (is (= "https://redmine.example/issues/4711" (fact [:redmine :url])))
      (is (= #{"redmine/description" "redmine/journal/103"} artifacts)))
    (testing "a re-poll writes nothing"
      (let [before (event-ids (:store st))]
        (is (= {} (poll! st [4711] {4711 issue})))
        (is (= before (event-ids (:store st))))))))

(deftest two_replicas_mint_identical_events
  (let [a (store-with-process!)
        b (store-with-process!)]
    (poll! a [4711] {4711 issue})
    (poll! b [4711] {4711 (update issue :journals reverse)})
    (is (seq (event-ids (:store a))))
    (is (= (event-ids (:store a)) (event-ids (:store b))))))

(deftest a_reopened_issue_is_open_again_and_only_the_new_entry_is_added
  (let [st (store-with-process!)
        id (rm/ticket-id "redmine.example" 4711)
        reopened (-> issue
                     (assoc :status {:id 2 :name "In Progress"})
                     (update :journals conj (journal 104 "2026-10-05T09:00:00Z"
                                                     :details [(attr "status_id" "5" "2")])))]
    (poll! st [4711] {4711 issue})
    (let [before (event-ids (:store st))]
      (poll! st [4711] {4711 reopened})
      (is (= #{:open} (:stages (view st id))))
      (is (every? (event-ids (:store st)) before) "nothing earlier is re-minted"))))

(deftest an_issue_that_leaves_the_query_keeps_being_mirrored
  (let [st (store-with-process!)
        id (rm/ticket-id "redmine.example" 4711)
        open (assoc issue :status {:id 2 :name "In Progress"}
                    :journals (pop (:journals issue)))
        handed-on (-> open
                      (assoc :assigned_to {:id 12 :name "Alex"})
                      (update :journals conj (journal 105 "2026-10-04T09:00:00Z"
                                                      :details [(attr "assigned_to_id" "11" "12")])))]
    (poll! st [4711] {4711 open})
    (is (= "seb" ((:fact (view st id)) [:redmine :assignee])))
    (poll! st [] {4711 handed-on})
    (is (= "Alex" ((:fact (view st id)) [:redmine :assignee])))))
