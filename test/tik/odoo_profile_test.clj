;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.odoo-profile-test
  "Odoo profiles: the registry is sound, odoo.edn selects a profile or is
  refused with a precise message, and the mapping reads every model
  specific choice from the profile, shown with a profile that exists only
  in these tests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.bridge-sync :as sync]
            [tik.cli-core :as core]
            [tik.harness :as h]
            [tik.odoo :as od]
            [tik.store.protocol :as store])
  (:import (clojure.lang ExceptionInfo)
           (java.time Instant)))

(defn- refusal
  "The message and reason of the ex-info `f` throws, nil when it returns."
  [f]
  (try (f) nil
       (catch ExceptionInfo e {:message (ex-message e) :reason (:reason (ex-data e))})))

;; ---------------------------------------------------------------- registry

(deftest every_registered_profile_is_sound_and_keyed_by_its_model
  (doseq [[model p] od/profiles]
    (is (= [] (od/profile-problems p)) model)
    (is (= model (:model p)))))

(deftest the_default_model_is_project_task_and_registered
  (is (= "project.task" od/default-model))
  (is (identical? od/project-task (get od/profiles od/default-model))))

(deftest project_task_keeps_its_field_table_and_process
  (is (= :odoo-task (:process od/project-task)))
  (is (= ["create_date" "name" "stage_id" "user_id" "project_id"]
         (od/record-fields od/project-task))
      "the fields search_read asks for, in the order the bridge always asked"))

(def ^:private request-profile
  "A model that is not registered, to show the mapping takes every model
  specific choice from the profile."
  {:model "x.request"
   :process :odoo-task
   :created "opened_at"
   :fields {"subject" [:req :subject]
            "phase_id" [:req :phase]
            "owner_id" [:req :owner]}
   :actors #{}
   :state {:field "phase_id"
           :fact [:req :state]
           :lists [[:solved :solved-phases] [:cancelled :cancelled-phases]]
           :otherwise :waiting}
   :title {:rule :labelled-id :label "request"}
   :chatter nil
   :domain [["owner_id" "=" :uid] ["active" "=" true]]})

(deftest a_test_profile_is_sound
  (is (= [] (od/profile-problems request-profile))))

(deftest unsound_profiles_are_named_precisely
  (let [base od/project-task
        cases
        {"a profile is a map" []
         ":model must name an Odoo model" (assoc base :model " ")
         ":process must be a process keyword" (assoc base :process "odoo-task")
         ":created must name the creation-date field" (dissoc base :created)
         ":fields must map field names to fact paths" (assoc base :fields {"name" "title"})
         ":fields asserts one fact path from two fields" (assoc-in base [:fields "project_id"] [:odoo :title])
         ":fields must not assert [:odoo :id] or [:odoo :url]" (assoc-in base [:fields "project_id"] [:odoo :url])
         ":actors must be a set of fields from :fields" (assoc base :actors #{"partner_id"})
         ":state needs a :field from :fields, an unused :fact path, :lists of [state config-key] and an :otherwise state"
         (assoc-in base [:state :fact] [:odoo :stage])
         ":title :id-and-opening needs a :field from :fields" (assoc-in base [:title :field] "display_name")
         ":title :labelled-id needs a :label" (assoc base :title {:rule :labelled-id})
         ":chatter needs a set of message :types and a :prefix ending in /" (assoc-in base [:chatter :prefix] "odoo/message")
         ":domain must be an Odoo domain vector" (assoc base :domain "[]")}]
    (doseq [[problem p] cases]
      (is (some #{problem} (od/profile-problems p)) problem)))
  (is (some #(str/starts-with? % ":title needs a :rule of :id-and-opening, :labelled-id")
            (od/profile-problems (assoc od/project-task :title {:rule :subject})))))

(deftest a_state_rule_takes_the_first_list_naming_the_stage
  (let [sets {:solved-phases #{"Solved" "Both"} :cancelled-phases #{"Cancelled" "Both"}}
        rule (:state request-profile)]
    (is (= :solved (od/state-of rule sets "Solved")))
    (is (= :cancelled (od/state-of rule sets "Cancelled")))
    (is (= :solved (od/state-of rule sets "Both")) "the order of :lists decides")
    (is (= :waiting (od/state-of rule sets "New")))
    (is (nil? (od/state-of rule sets nil)))))

;; ---------------------------------------------------------------- config

(def ^:private infra-like-config
  "The shape of an odoo.edn in use before profiles existed."
  {:url "https://odoo.example" :db "odoo" :user {:env "ODOO_USER"}
   :password {:env "ODOO_PASSWORD"} :closed-stages ["Done" "Dismissed"]
   :users {45 "seb"}})

(deftest a_config_without_profile_mirrors_project_tasks_unchanged
  (is (= {:profile od/project-task :stage-sets {:closed-stages #{"Done" "Dismissed"}}}
         (sync/odoo-config infra-like-config))))

(deftest a_profile_is_named_by_its_model_as_string_or_keyword
  (is (identical? od/project-task (od/resolve-profile {:profile "project.task"})))
  (is (identical? od/project-task (od/resolve-profile {:profile :project.task})))
  (is (identical? od/project-task (od/resolve-profile {}))))

(deftest an_unknown_profile_is_refused_at_config_load
  (is (= {:message "the odoo config names the unknown profile \"helpdesk.ticket\"; known profiles: project.task"
          :reason :odoo/unknown-profile}
         (refusal #(od/resolve-profile {:profile "helpdesk.ticket"}))))
  (is (= :odoo/unknown-profile
         (:reason (refusal #(od/resolve-profile {:profile :x/project.task}))))
      "a namespaced keyword names a different model")
  (is (= :odoo/unknown-profile
         (:reason (refusal #(sync/odoo-config (assoc infra-like-config :profile "sale.order")))))))

(deftest a_profile_that_is_not_a_name_is_refused
  (doseq [p [42 nil ["project.task"] {:model "project.task"}]]
    (is (= {:message (str "the odoo config's :profile must name a model such as \"project.task\", not "
                          (pr-str p))
            :reason :odoo/bad-profile}
           (refusal #(od/resolve-profile {:profile p})))
        (pr-str p))))

(deftest an_unsound_registered_profile_is_refused_at_config_load
  (with-redefs [od/profiles {"project.task" (dissoc od/project-task :created)}]
    (let [{:keys [message reason]} (refusal #(od/resolve-profile {}))]
      (is (= :odoo/unsound-profile reason))
      (is (str/includes? message ":created must name the creation-date field")))))

(deftest the_config_lists_a_profile_state_reads_are_required
  (is (= {:message "the odoo config needs :closed-stages" :reason :odoo/bad-config}
         (refusal #(sync/odoo-config (dissoc infra-like-config :closed-stages)))))
  (is (= {:message "the odoo config's :closed-stages must list stage names, not \"Done\""
          :reason :odoo/bad-config}
         (refusal #(sync/odoo-config (assoc infra-like-config :closed-stages "Done"))))
      "a string would otherwise become a set of characters")
  (is (= :odoo/bad-config
         (:reason (refusal #(sync/odoo-config (assoc infra-like-config :closed-stages [23]))))))
  (testing "any collection of names works, an empty one too"
    (doseq [v [[] '("Done") #{"Done"}]]
      (is (= (set v) (get-in (sync/odoo-config (assoc infra-like-config :closed-stages v))
                             [:stage-sets :closed-stages]))))))

(deftest the_connection_keys_are_required
  (doseq [k [:url :db :user]]
    (is (= {:message (str "the odoo config needs " k) :reason :odoo/bad-config}
           (refusal #(sync/odoo-config (dissoc infra-like-config k)))))))

(deftest stage_sets_follow_the_profile
  (is (= {:solved-phases #{"Solved"} :cancelled-phases #{}}
         (od/stage-sets request-profile {:solved-phases ["Solved"] :cancelled-phases []})))
  (is (= "the odoo config needs :cancelled-phases"
         (:message (refusal #(od/stage-sets request-profile {:solved-phases []}))))))

(deftest the_default_domain_carries_the_bridge_user
  (is (= [["user_id" "=" 45]] (od/default-domain od/project-task 45)))
  (is (= [["owner_id" "=" 7] ["active" "=" true]] (od/default-domain request-profile 7))))

;; ---------------------------------------------------------------- mapping

(def ^:private request
  {:id 1249 :subject "Rechnung 2024-0042 für Frau Muster" :phase_id [3 "Solved"]
   :owner_id [45 "Sebastian Hoß"] :opened_at "2024-03-01 08:00:00"})

(def ^:private request-labels
  (od/label-table request-profile [{:subject {:string "Subject"} :phase_id {:string "Phase"}
                                    :owner_id {:string "Owner"}}]))

(def ^:private request-messages
  [{:id 7001 :date "2024-03-01 08:00:01" :message_type "notification" :body ""
    :author_id [45 "Sebastian Hoß"]
    :tracking_value_ids [{:id 1 :changed_field "Phase" :old_value "" :new_value "New"}]}
   {:id 7002 :date "2024-03-01 09:00:00" :message_type "comment" :body "<p>Kundendaten anbei</p>"
    :author_id [45 "Sebastian Hoß"] :tracking_value_ids []}
   {:id 7003 :date "2024-03-02 10:00:00" :message_type "notification" :body ""
    :author_id [45 "Sebastian Hoß"]
    :tracking_value_ids [{:id 2 :changed_field "Phase" :old_value "New" :new_value "Solved"}]}])

(defn- request-ctx []
  {:profile request-profile :host "odoo.example" :url "https://odoo.example/" :actor "odoo"
   :process :odoo-task :version 1 :process-hash "sha256-x" :clamp identity
   :labels request-labels :users {"Sebastian Hoß" "seb"}
   :stage-sets {:solved-phases #{"Solved"} :cancelled-phases #{"Cancelled"}}})

(defn- facts [events]
  (for [e events :when (= :fact/assert (:event/type e))]
    [(get-in e [:event/body :fact/path]) (get-in e [:event/body :fact/value])]))

(deftest the_ticket_id_names_the_model
  (is (not= (od/ticket-id "odoo.example" "project.task" 1249)
            (od/ticket-id "odoo.example" "x.request" 1249)))
  (is (= (od/ticket-id "odoo.example" "x.request" 1249)
         (:id (od/record-events (request-ctx) request request-messages)))))

(deftest a_labelled_id_title_carries_no_text_of_the_record
  (let [{:keys [events]} (od/record-events (request-ctx) request request-messages)
        title (get-in (first events) [:event/body :ticket/title])]
    (is (= "request #1249" title))
    (is (not (str/includes? title "Muster")))))

(deftest the_mapping_asserts_the_profile_fact_paths_and_state
  (let [{:keys [events blobs]} (od/record-events (request-ctx) request request-messages)
        fs (facts events)]
    (is (= [[:odoo :id] 1249] (first fs)))
    (is (= [[:odoo :url] "https://odoo.example/web#id=1249&model=x.request"] (second fs)))
    (is (some #{[[:req :owner] "Sebastian Hoß"]} fs)
        "a field outside :actors keeps the name Odoo shows")
    (is (some #{[[:req :subject] "Rechnung 2024-0042 für Frau Muster"]} fs))
    (is (= [[[:req :phase] "New"] [[:req :state] :waiting]
            [[:req :phase] "Solved"] [[:req :state] :solved]]
           (filter #(#{[:req :phase] [:req :state]} (first %)) fs)))
    (is (not-any? #(= :odoo (ffirst %)) (drop 2 fs)) "no project.task path leaks in")
    (is (= {} blobs) "a profile without :chatter mirrors no messages")
    (is (not-any? #(= :artifact/attach (:event/type %)) events))))

(deftest the_record_is_dated_by_the_profile_creation_field
  (let [{:keys [events]} (od/record-events (request-ctx) request request-messages)]
    (is (= (Instant/parse "2024-03-01T08:00:00Z") (:event/at (first events))))))

(deftest a_record_mapping_is_a_pure_function_of_record_and_chatter
  (is (= (od/record-events (request-ctx) request request-messages)
         (od/record-events (request-ctx) request (vec (reverse request-messages))))))

(deftest chatter_types_come_from_the_profile
  (let [chatter {:types #{"email"} :prefix "req/msg/"}
        m {:id 9 :date "2024-03-01 09:00:00" :body "<p>Hallo</p>" :author_id [1 "A"]}]
    (is (nil? (od/message-text chatter (assoc m :message_type "comment"))))
    (is (str/includes? (od/message-text chatter (assoc m :message_type "email")) "Hallo"))
    (let [{:keys [blobs events]} (od/record-events
                                  (assoc-in (request-ctx) [:profile :chatter] chatter)
                                  request [(assoc m :message_type "email")])]
      (is (= 1 (count blobs)))
      (is (some #(= "req/msg/9" (get-in % [:event/body :artifact/path])) events)))))

;; ---------------------------------------------------------------- remote

(defn- recording-executor
  "An Odoo stub serving one `record` of `model`, recording every call. The
  domain selects the record only while `listed`; a search by id always
  finds it."
  ([calls model record] (recording-executor calls model record true))
  ([calls model record listed]
   (fn x
     ([m method args] (x m method args {}))
     ([m method args kw]
      (swap! calls conj [m method args kw])
      (case [m method]
        ["res.lang" "search_read"] [{:code "en_US"}]
        ["mail.message" "search_read"] []
        (cond
          (and (= model m) (= "fields_get" method)) {:subject {:string "Subject"}}
          (and (= model m) (= "search_read" method))
          (if (or listed (= "id" (ffirst (first args)))) [record] [])
          :else (throw (ex-info (str "unexpected call " m " " method) {}))))))))

(deftest every_call_names_the_profile_model
  (let [calls (atom [])
        x (recording-executor calls "x.request" request)]
    (is (= [request] (od/list-records x request-profile [["id" ">" 0]])))
    (od/records-by-id x request-profile [1249])
    (od/fetch-labels x request-profile)
    (is (= [] (od/record-messages x request-profile 1249)))
    (let [by-method (group-by second @calls)]
      (is (every? #(= "x.request" (first %)) (get by-method "fields_get")))
      (is (= ["x.request" "x.request"]
             (for [[m method] @calls
                   :when (and (= "search_read" method) (not (#{"res.lang" "mail.message"} m)))]
               m)))
      (is (= {:fields ["opened_at" "subject" "phase_id" "owner_id"] :order "id"}
             (nth (first @calls) 3)))
      (is (some #(= [[["model" "=" "x.request"] ["res_id" "=" 1249]]] (nth % 2))
                (get by-method "search_read"))))))

(defn- store-with-process! []
  (let [{:keys [root store]} (h/temp-store!)
        dest (io/file root "processes" "odoo-task.edn")]
    (io/make-parents dest)
    (io/copy (io/file "processes/odoo-task.edn") dest)
    {:root root :store store}))

(deftest a_poll_through_another_profile_mirrors_that_model_and_only_its_tickets
  (let [{:keys [root store]} (store-with-process!)
        t (Instant/parse "2030-01-01T00:00:00Z")
        poll (fn [profile record listed]
               (let [calls (atom [])]
                 (h/with-cli-root root
                   (fn []
                     (with-redefs [core/now (constantly t)]
                       (binding [*out* (java.io.StringWriter.)]
                         (sync/odoo-poll! store (recording-executor calls (:model profile) record listed)
                                          {:url "https://odoo.example"}
                                          (assoc (sync/bridge-ctx {:process :odoo-task :actor "odoo"} {})
                                                 :profile profile :host "odoo.example"
                                                 :url "https://odoo.example" :uid 45
                                                 :users {} :stage-sets {:solved-phases #{"Solved"}
                                                                        :cancelled-phases #{}})
                                          {})))))
                 @calls))]
    (poll request-profile request true)
    (is (= #{(od/ticket-id "odoo.example" "x.request" 1249)} (set (store/ticket-ids store))))
    (testing "the next sweep re-fetches the mirrored record by id, under its own model"
      (let [calls (poll request-profile request false)]
        (is (some #(and (= ["x.request" "search_read"] (take 2 %))
                        (= [[["id" "in" [1249]]]] (nth % 2)))
                  calls))))
    (testing "a project.task sweep does not take the x.request ticket for a task"
      (let [task {:id 5 :name "t" :stage_id false :user_id false :project_id false
                  :create_date "2024-01-01 00:00:00"}
            calls (poll od/project-task task true)]
        (is (not-any? #(= [[["id" "in" [1249]]]] (nth % 2)) calls))))))
