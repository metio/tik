;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.odoo-mirrored-test
  "The records a sweep re-fetches because the store already mirrors them:
  each is found by its ticket's identity (host, model and record id), so
  no text in the server URL can hide one, and no ticket of another host or
  model is taken for one."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tik.bridge-sync :as sync]
            [tik.cli-core :as core]
            [tik.harness :as h]
            [tik.odoo :as od]
            [tik.store.protocol :as store])
  (:import (java.time Instant)))

(def ^:private t (Instant/parse "2030-01-01T00:00:00Z"))

(def ^:private task
  {:id 1249
   :name "login aktualisieren"
   :stage_id [12 "In Progress"]
   :user_id [45 "Sebastian Hoß"]
   :project_id [10 "Operations"]
   :create_date "2024-01-15 10:51:11"})

(def ^:private handed-on (assoc task :user_id [46 "Markus Otto"]))

(def ^:private hand-over
  "The chatter message that records the hand-over, so the new assignee is
  a later change rather than a different opening value."
  {:id 41070 :date "2024-02-06 08:00:00" :message_type "notification" :body ""
   :author_id [45 "Sebastian Hoß"]
   :tracking_value_ids [{:id 11 :changed_field "Assigned to" :field_type "many2one"
                         :old_value "Sebastian Hoß" :new_value "Markus Otto"}]})

(def ^:private other-model
  "project.task's profile under another model name, so two models share
  every field and differ only in identity."
  (assoc od/project-task :model "x.task"))

(defn- store-with-process! []
  (let [{:keys [root store]} (h/temp-store!)
        dest (io/file root "processes" "odoo-task.edn")]
    (io/make-parents dest)
    (io/copy (io/file "processes/odoo-task.edn") dest)
    {:root root :store store}))

(defn- by-id-search? [[_ method args]]
  (and (= "search_read" method) (= "id" (ffirst (first args)))))

(defn- executor
  "An Odoo stub serving `record` under every model, recording each call.
  The domain selects the record only while `listed`; a search by id finds
  it whenever it asks for the record's id. A record handed on to another
  user carries the message that tracks the hand-over."
  [calls record listed]
  (fn x
    ([model method args] (x model method args {}))
    ([model method args kw]
     (swap! calls conj [model method args kw])
     (case method
       "fields_get" {:name {:string "Title"} :stage_id {:string "Stage"}
                     :user_id {:string "Assigned to"} :project_id {:string "Project"}}
       "message_format" (if (= handed-on record) [hand-over] [])
       "search_read"
       (cond
         (= "res.lang" model) [{:code "en_US"}]
         (= "mail.message" model) (if (= handed-on record) [{:id (:id hand-over)}] [])
         (by-id-search? [model method args])
         (let [[[_ _ ids]] (first args)] (if (some #{(:id record)} ids) [record] []))
         listed [record]
         :else [])
       (throw (ex-info (str "unexpected call " model " " method) {}))))))

(defn- poll!
  "One sweep of `profile` against the Odoo at `url`, whose host is `host`
  (the host of `url` unless given); returns the calls it made."
  ([st profile url record listed]
   (poll! st profile url (od/host-of url) record listed))
  ([{:keys [root store]} profile url host record listed]
   (let [calls (atom [])]
     (h/with-cli-root root
       (fn []
         (with-redefs [core/now (constantly t)]
           (binding [*out* (java.io.StringWriter.)]
             (sync/odoo-poll! store (executor calls record listed)
                              {:url url}
                              (assoc (sync/bridge-ctx {:process :odoo-task :actor "odoo"} {})
                                     :profile profile :host host :url url :uid 45
                                     :users {"Sebastian Hoß" "seb"}
                                     :stage-sets {:closed-stages #{"Done"}})
                              {})))))
     @calls)))

(defn- assignee [{:keys [root store]} id]
  (h/with-cli-root root
    (fn [] (get-in (core/ticket-ctx store id) [:state :facts [:odoo :assignee] :value]))))

(defn- refetched? [calls]
  (some #(and (by-id-search? %) (= [[["id" "in" [1249]]]] (nth % 2))) calls))

(def ^:private urls
  "Server URLs carrying the text ID, or id, in each part of the URL."
  ["https://odoo.example/ID"
   "https://odoo.example/ID/"
   "https://odoo-ID.example"
   "https://ID.example"
   "https://odoo.example/odoo?db=ID"
   "https://odoo.example/ID/web?ID=ID"
   "https://odoo.example/id"
   "https://id.example"
   "https://odoo.example/odoo?db=id"
   "https://odoo.example/Id/iD"
   "https://odoo.example:8069/IDID"])

(deftest a_record_that_leaves_the_domain_stays_mirrored_whatever_the_url_holds
  (doseq [url urls]
    (testing url
      (let [st (store-with-process!)
            id (od/ticket-id (od/host-of url) "project.task" 1249)]
        (poll! st od/project-task url task true)
        (is (= "seb" (assignee st id)))
        (let [calls (poll! st od/project-task url handed-on false)]
          (is (refetched? calls) "the mirrored record is fetched by id")
          (is (= "Markus Otto" (assignee st id))))))))

(deftest a_record_that_leaves_the_domain_stays_mirrored_after_the_url_changes_on_the_same_host
  (let [st (store-with-process!)
        id (od/ticket-id "odoo.example" "project.task" 1249)]
    (poll! st od/project-task "http://odoo.example" task true)
    (is (refetched? (poll! st od/project-task "https://odoo.example/" handed-on false)))
    (is (= "Markus Otto" (assignee st id)))))

(deftest another_hosts_record_with_the_same_id_is_never_fetched
  (let [st (store-with-process!)]
    (poll! st od/project-task "https://a.example" task true)
    (is (not (refetched? (poll! st od/project-task "https://b.example" task false))))
    (is (= #{(od/ticket-id "a.example" "project.task" 1249)}
           (set (store/ticket-ids (:store st)))))))

(deftest another_models_record_with_the_same_id_is_never_fetched
  (let [st (store-with-process!)]
    (poll! st other-model "https://odoo.example" task true)
    (is (not (refetched? (poll! st od/project-task "https://odoo.example" task false))))
    (testing "and the other way round"
      (let [st (store-with-process!)]
        (poll! st od/project-task "https://odoo.example" task true)
        (is (not (refetched? (poll! st other-model "https://odoo.example" task false))))))))

(deftest a_url_fact_naming_another_host_does_not_make_a_ticket_that_hosts_record
  ;; The ticket below is a.example's record 1249, yet its [:odoo :url] fact
  ;; names b.example. A URL fact is free text any writer can assert; the
  ;; ticket id is what binds a ticket to one host, model and record.
  (let [st (store-with-process!)]
    (poll! st od/project-task "https://b.example" "a.example" task true)
    (is (= #{(od/ticket-id "a.example" "project.task" 1249)}
           (set (store/ticket-ids (:store st)))))
    (is (not (refetched? (poll! st od/project-task "https://b.example" task false))))))

(deftest a_sweep_of_one_host_and_model_refetches_exactly_its_own_records
  (let [st (store-with-process!)]
    (poll! st od/project-task "https://ID.example" task true)
    (poll! st other-model "https://ID.example" task true)
    (poll! st od/project-task "https://b.example" task true)
    (is (= 3 (count (store/ticket-ids (:store st)))))
    (doseq [[profile url] [[od/project-task "https://ID.example"]
                           [other-model "https://ID.example"]
                           [od/project-task "https://b.example"]]]
      (testing [(:model profile) url]
        (let [calls (poll! st profile url task false)]
          (is (= 1 (count (filter #(and (by-id-search? %) (= (:model profile) (first %))) calls)))))))))

(deftest mirrored-id-names-the-record-only-of-its-own-ticket
  (let [id (od/ticket-id "odoo.example" "project.task" 1249)]
    (is (= 1249 (od/mirrored-id "odoo.example" "project.task" id 1249)))
    (is (= 1249 (od/mirrored-id "odoo.example" "project.task" id (int 1249))))
    (is (nil? (od/mirrored-id "odoo.example" "project.task" id 1250)) "another record")
    (is (nil? (od/mirrored-id "other.example" "project.task" id 1249)) "another host")
    (is (nil? (od/mirrored-id "odoo.example" "x.task" id 1249)) "another model")
    (is (nil? (od/mirrored-id "odoo.example" "project.task"
                              (od/ticket-id "odoo.example" "project.task" 1250) 1249))
        "another ticket")
    (doseq [v ["1249" 1249.0 1249M nil :1249 [1249]]]
      (is (nil? (od/mirrored-id "odoo.example" "project.task" id v)) (pr-str v)))))
