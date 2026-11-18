;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.odoo-test
  "The Odoo bridge: a task's opening values recovered from its chatter,
  tracking labels in any installed language, the closed stages the config
  names, coordination-free ingest, and tasks that leave the query staying
  mirrored."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.bridge-sync :as sync]
            [tik.cli-core :as core]
            [tik.harness :as h]
            [tik.odoo :as od]
            [tik.stage :as stage]
            [tik.store.protocol :as store])
  (:import (java.time Instant)))

(def ^:private fields-de
  {:name {:string "Titel"} :stage_id {:string "Stufe"} :user_id {:string "Zugewiesen an"}
   :project_id {:string "Projekt"} :kanban_state_label {:string "Kanban Statuslabel"}})

(def ^:private fields-en
  {:name {:string "Title"} :stage_id {:string "Stage"} :user_id {:string "Assigned to"}
   :project_id {:string "Project"} :manager_id {:string "Project Manager"}})

(def ^:private labels (od/label-table [fields-de fields-en]))

(defn- tv [id label old new]
  {:id id :changed_field label :old_value old :new_value new :field_type "many2one"})

(defn- msg [id date & {:keys [tracking type body]}]
  {:id id :date date :message_type (or type "notification") :body (or body "")
   :author_id [583 "Sebastian Hoß"] :tracking_value_ids (vec tracking)})

(def ^:private task
  {:id 1249
   :name "login.infra.run aktualisieren"
   :stage_id [23 "Done"]
   :user_id [45 "Sebastian Hoß"]
   :project_id [10 "Operations"]
   :create_date "2024-01-15 10:51:11"})

(def ^:private messages
  [(msg 41010 "2024-01-15 10:51:12"
        :tracking [(tv 1 "Titel" false "login aktualisieren")
                   (tv 2 "Stufe" "" "Backlog")
                   (tv 3 "Kanban Statuslabel" false "In Arbeit")
                   (tv 4 "Projekt" "" "Operations")
                   (tv 5 "Zugewiesen an" "" "Sebastian Hoß")])
   (msg 41020 "2024-01-15 10:52:35" :tracking [(tv 6 "Stage" "Backlog" "In Progress")])
   (msg 41030 "2024-01-22 11:57:29" :type "comment" :body "<p>Updated &amp; deployed</p>")
   (msg 41040 "2024-01-30 14:02:29" :tracking [(tv 7 "Stufe" "In Progress" "Waiting / Blocked")])
   (msg 41050 "2024-02-01 09:00:00"
        :tracking [(tv 8 "Stufe" "Waiting / Blocked" "Done")
                   (tv 9 "Titel" "login aktualisieren" "login.infra.run aktualisieren")])])

(def ^:private closed #{"Done" "Dismissed"})

(defn- ctx []
  {:host "odoo.example" :url "https://odoo.example" :actor "odoo"
   :process :odoo-task :version 1 :process-hash "sha256-x" :clamp identity
   :labels labels :users {"Sebastian Hoß" "seb"} :closed closed})

(deftest a_label_two_fields_share_is_left_out
  (is (= {"Titel" "name" "Title" "name" "Stufe" "stage_id" "Stage" "stage_id"
          "Zugewiesen an" "user_id" "Assigned to" "user_id" "Projekt" "project_id"
          "Project" "project_id"}
         labels))
  (is (not (contains? (od/label-table [fields-de {:x_area {:string "Projekt"}}]) "Projekt"))))

(deftest the_opening_values_are_the_current_ones_with_the_chatter_undone
  (is (= {"name" nil "stage_id" nil "user_id" nil "project_id" nil}
         (od/initial-values labels task messages))
      "the creation message tracked every field from nothing")
  (is (= {"name" "login aktualisieren" "stage_id" "Backlog" "user_id" "Sebastian Hoß"
          "project_id" "Operations"}
         (od/initial-values labels task (rest messages)))
      "without it, the opening values are the values before the first change"))

(deftest only_the_configured_stages_are_closed
  (is (= :closed (od/state-of closed "Done")))
  (is (= :open (od/state-of closed "Waiting / Blocked")))
  (is (= :open (od/state-of closed "Backlog")) "a folded stage is not a closed one")
  (is (nil? (od/state-of closed nil))))

(deftest task_events_are_a_pure_function_of_the_task
  (let [a (od/task-events (ctx) task messages)
        b (od/task-events (ctx) task (reverse messages))]
    (is (= (map :event/id (:events a)) (map :event/id (:events b))))
    (is (= (od/ticket-id "odoo.example" 1249) (:id a)))
    (is (= "#1249 login aktualisieren" (get-in (first (:events a)) [:event/body :ticket/title])))))

(deftest odoo_datetimes_are_utc
  (is (= (Instant/parse "2024-01-15T10:51:11Z") (od/odoo-instant "2024-01-15 10:51:11"))))

;; ---------------------------------------------------------------- sync

(defn- store-with-process! []
  (let [{:keys [root store]} (h/temp-store!)
        dest (io/file root "processes" "odoo-task.edn")]
    (io/make-parents dest)
    (io/copy (io/file "processes/odoo-task.edn") dest)
    {:root root :store store}))

(def ^:private t (Instant/parse "2030-01-01T00:00:00Z"))

(defn- executor
  "An Odoo stub: `assigned` are the task ids the domain selects, `tasks`
  and `chatter` hold each task and its messages by id."
  [assigned tasks chatter]
  (fn x
    ([model method args] (x model method args {}))
    ([model method args kw]
     (case [model method]
       ["res.lang" "search_read"] [{:code "de_DE"} {:code "en_US"}]
       ["project.task" "fields_get"] (if (= "de_DE" (get-in kw [:context :lang])) fields-de fields-en)
       ["project.task" "search_read"]
       (let [[[f op ids]] (first args)]
         (if (and (= "id" f) (= "in" op))
           (keep tasks ids)
           (keep tasks assigned)))
       ["mail.message" "search_read"]
       (let [[_ [_ _ id]] (first args)]
         (mapv #(select-keys % [:id]) (get chatter id)))
       ["mail.message" "message_format"]
       (let [want (set (first args))]
         (filter #(want (:id %)) (mapcat val chatter)))
       (throw (ex-info (str "unexpected call " model " " method) {}))))))

(defn- poll! [{:keys [root store]} assigned tasks chatter]
  (h/with-cli-root root
    (fn []
      (with-redefs [core/now (constantly t)]
        (let [c (assoc (sync/bridge-ctx {:process :odoo-task :actor "odoo"} {})
                       :host "odoo.example" :url "https://odoo.example" :uid 45
                       :closed closed :users {"Sebastian Hoß" "seb"})]
          (binding [*out* (java.io.StringWriter.)]
            (sync/odoo-poll! store (executor assigned tasks chatter)
                             {:url "https://odoo.example"} c {})))))))

(defn- event-ids [s]
  (set (for [id (store/ticket-ids s) e (store/events s id)] (:event/id e))))

(defn- view [{:keys [root store]} id]
  (h/with-cli-root root
    (fn []
      (let [{:keys [process events roles state]} (core/ticket-ctx store id)]
        {:stages (stage/current-stages process (stage/effective-reached process events t roles))
         :fact #(get-in state [:facts % :value])
         :artifacts (set (keys (:artifacts state)))}))))

(deftest a_task_syncs_with_its_history_and_its_stage_follows_the_stage
  (let [st (store-with-process!)
        id (od/ticket-id "odoo.example" 1249)]
    (is (= {:ticket 1} (poll! st [1249] {1249 task} {1249 messages})))
    (let [{:keys [stages fact artifacts]} (view st id)]
      (is (= #{:closed} stages))
      (is (= "Done" (fact [:odoo :stage])))
      (is (= "seb" (fact [:odoo :assignee])))
      (is (= "Operations" (fact [:odoo :project])))
      (is (= "login.infra.run aktualisieren" (fact [:odoo :title])))
      (is (= "https://odoo.example/web#id=1249&model=project.task" (fact [:odoo :url])))
      (is (= #{"odoo/message/41030"} artifacts)))
    (testing "the message's text, as text"
      (let [blob (some #(when (and (.isFile ^java.io.File %)
                                   (str/includes? (slurp %) "message 41030"))
                          (slurp %))
                       (file-seq (io/file (:root st) "tickets" (str id) "blobs")))]
        (is (str/includes? (str blob) "Updated & deployed") blob)))
    (testing "a re-poll writes nothing"
      (let [before (event-ids (:store st))]
        (is (= {} (poll! st [1249] {1249 task} {1249 messages})))
        (is (= before (event-ids (:store st))))))))

(deftest a_waiting_task_is_open
  (let [st (store-with-process!)
        id (od/ticket-id "odoo.example" 1249)
        waiting (assoc task :stage_id [47 "Waiting / Blocked"] :name "login aktualisieren")]
    (poll! st [1249] {1249 waiting} {1249 (vec (butlast messages))})
    (is (= #{:open} (:stages (view st id))))
    (is (= "Waiting / Blocked" ((:fact (view st id)) [:odoo :stage])))))

(deftest two_replicas_mint_identical_events
  (let [a (store-with-process!)
        b (store-with-process!)]
    (poll! a [1249] {1249 task} {1249 messages})
    (poll! b [1249] {1249 task} {1249 (vec (reverse messages))})
    (is (seq (event-ids (:store a))))
    (is (= (event-ids (:store a)) (event-ids (:store b))))))

(deftest a_task_moved_back_is_open_again_and_only_the_new_change_is_added
  (let [st (store-with-process!)
        id (od/ticket-id "odoo.example" 1249)
        reopened (assoc task :stage_id [12 "In Progress"])
        more (conj messages (msg 41060 "2024-02-05 08:00:00"
                                 :tracking [(tv 10 "Stufe" "Done" "In Progress")]))]
    (poll! st [1249] {1249 task} {1249 messages})
    (let [before (event-ids (:store st))]
      (poll! st [1249] {1249 reopened} {1249 more})
      (is (= #{:open} (:stages (view st id))))
      (is (every? (event-ids (:store st)) before) "nothing earlier is re-minted"))))

(deftest a_task_that_leaves_the_query_keeps_being_mirrored
  (let [st (store-with-process!)
        id (od/ticket-id "odoo.example" 1249)
        handed-on (assoc task :user_id [46 "Markus Otto"])
        more (conj messages (msg 41070 "2024-02-06 08:00:00"
                                 :tracking [(tv 11 "Zugewiesen an" "Sebastian Hoß" "Markus Otto")]))]
    (poll! st [1249] {1249 task} {1249 messages})
    (is (= "seb" ((:fact (view st id)) [:odoo :assignee])))
    (poll! st [] {1249 handed-on} {1249 more})
    (is (= "Markus Otto" ((:fact (view st id)) [:odoo :assignee])))))
