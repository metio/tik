;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.redmine
  "The Redmine bridge (ADR 0019 inbound): each Redmine issue becomes a
  redmine-issue ticket whose facts mirror the issue, signed by the bridge.
  Redmine stays a source of facts, never authoritative over the log.

  Every event is a pure function of the issue and its journal (ADR 0021).
  The ticket id hashes the Redmine host and issue id. The values the issue
  was opened with are recovered by undoing each journal entry, newest
  first, from the current values; the create carries those at the issue's
  creation time, and each journal entry contributes its own changes at its
  own time. A replica that syncs later only adds the journal entries that
  are new by then, and two replicas mint byte-identical events.

  Ids in the journal (status, priority, tracker, project, assignee) are
  rendered through name tables fetched at sync time. Renaming a status in
  Redmine therefore renders older entries differently on the next sync,
  which adds assertions under the new name beside the old ones; the latest
  entry still decides the current value."
  (:require [clojure.string :as str]
            [tik.forge :as forge]
            [tik.mirror :as mirror])
  (:import (java.time Instant)))

(def mirrored
  "Journal attribute -> [fact path, name table]. A nil table keeps the
  value as written."
  {"status_id" [[:redmine :status] :status]
   "assigned_to_id" [[:redmine :assignee] :user]
   "priority_id" [[:redmine :priority] :priority]
   "tracker_id" [[:redmine :tracker] :tracker]
   "project_id" [[:redmine :project] :project]
   "subject" [[:redmine :subject] nil]
   "due_date" [[:redmine :due] nil]})

(defn ticket-id [host issue-id]
  (forge/name-uuid (str "tik/redmine " host " " issue-id)))

(defn- id-str [x] (some-> x str))

(defn current-values
  "The issue's present values, keyed like the journal's attributes, ids as
  strings the way journal details carry them."
  [issue]
  {"status_id" (id-str (get-in issue [:status :id]))
   "assigned_to_id" (id-str (get-in issue [:assigned_to :id]))
   "priority_id" (id-str (get-in issue [:priority :id]))
   "tracker_id" (id-str (get-in issue [:tracker :id]))
   "project_id" (id-str (get-in issue [:project :id]))
   "subject" (:subject issue)
   "due_date" (:due_date issue)
   "description" (:description issue)})

(defn journal-order [j] [(str (:created_on j)) (long (:id j))])

(defn- attr-details [j]
  (filter #(= "attr" (:property %)) (:details j)))

(defn initial-values
  "The values the issue was created with: the current values with every
  attribute change undone, newest journal entry first."
  [issue]
  (reduce (fn [vals j]
            (reduce (fn [vs d] (assoc vs (:name d) (:old_value d))) vals
                    (reverse (attr-details j))))
          (current-values issue)
          (reverse (sort-by journal-order (:journals issue)))))

(defn names
  "Name tables for rendering journal ids. `tables` holds the fetched
  :status ({id {:name :is_closed}}), :priority, :tracker and :project
  ({id name}) tables and the config's :users ({id actor}); the issue's own
  people fill in user names the config does not map."
  [tables issue]
  (let [people (concat [(:author issue) (:assigned_to issue)] (map :user (:journals issue)))
        seen (into {} (for [p people :when (:id p)] [(str (:id p)) (:name p)]))
        mapped (into {} (for [[k v] (:users tables)] [(str k) v]))]
    (assoc tables :user (merge seen mapped))))

(defn render
  "The fact value an attribute's raw value asserts, nil for none."
  [nms attr raw]
  (when (and (some? raw) (not= "" raw))
    (let [[_ table] (mirrored attr)]
      (case table
        nil raw
        :status (or (get-in nms [:status raw :name]) (str "status-" raw))
        :user (or (get-in nms [:user raw]) (str "redmine-user-" raw))
        (or (get-in nms [table raw]) (str (name table) "-" raw))))))

(defn- state-of
  "Whether the status with raw id `raw` counts as closed in Redmine."
  [nms raw]
  (when raw
    (if (get-in nms [:status raw :is_closed]) :closed :open)))

(defn note-text [j]
  (str "journal " (:id j) " by " (get-in j [:user :name]) " at " (:created_on j) "\n\n"
       (:notes j) "\n"))

(defn- issue-steps
  "The issue's history as ordered steps: {:fact path :value v :at t} (nil
  :value retracts) and {:attach path :text s :at t}, opening values first,
  then each journal entry in (created_on, id) order."
  [nms url issue at-of]
  (let [at0 (at-of (:created_on issue))
        init (initial-values issue)
        attr-steps (fn [at attr raw]
                     (let [[path] (mirrored attr)]
                       (cond-> [{:fact path :value (render nms attr raw) :at at}]
                         (= "status_id" attr)
                         (conj {:fact [:redmine :state] :value (state-of nms raw) :at at}))))
        desc-step (fn [at text]
                    (when-not (str/blank? text)
                      [{:attach "redmine/description" :text text :at at}]))]
    (concat
     [{:fact [:redmine :id] :value (long (:id issue)) :at at0}
      {:fact [:redmine :url] :value (str (str/replace (str url) #"/+$" "") "/issues/" (:id issue))
       :at at0}]
     (for [attr (sort (keys mirrored))
           :let [raw (get init attr)]
           :when (some? (render nms attr raw))
           step (attr-steps at0 attr raw)]
       step)
     (desc-step at0 (get init "description"))
     (for [j (sort-by journal-order (:journals issue))
           :let [at (at-of (:created_on j))]
           step (concat
                 (for [d (attr-details j)
                       :when (contains? mirrored (:name d))
                       step (attr-steps at (:name d) (:new_value d))]
                   step)
                 (for [d (attr-details j)
                       :when (= "description" (:name d))
                       step (desc-step at (:new_value d))]
                   step)
                 (when-not (str/blank? (:notes j))
                   [{:attach (str "redmine/journal/" (:id j)) :text (note-text j)
                     :at (at-of (or (:updated_on j) (:created_on j)))}]))]
       step))))

(defn issue-events
  "The events and blobs one issue contributes (see `tik.mirror`). `ctx`
  carries the Redmine :host and :url, the bridge :actor, the pinned
  :process, :version and :process-hash, :names (see `names`), and a
  :clamp applied to every :at."
  [{:keys [host url clamp] :as ctx} issue]
  (let [at-of #(clamp (Instant/parse (str %)))]
    (mirror/ticket-events ctx (ticket-id host (:id issue))
                          (str "#" (:id issue) " " (get (initial-values issue) "subject"))
                          (at-of (:created_on issue))
                          (issue-steps (:names ctx) url issue at-of))))

(defn json-getter
  "A `get-json` over the Redmine REST API at `url` with API key `key`."
  [url key]
  (forge/json-getter "Redmine API" url {"X-Redmine-API-Key" key}))

(defn- offset-paged
  "Every item of a Redmine list endpoint (offset/limit paging)."
  [get-json path q k]
  (loop [offset 0 acc []]
    (let [body (get-json path (assoc q :offset offset :limit 100))
          xs (get body k)
          acc (into acc xs)]
      (if (and (seq xs) (< (count acc) (long (or (:total_count body) 0))))
        (recur (+ offset (count xs)) acc)
        acc))))

(defn fetch-tables
  "The name tables `names` renders journal ids with. Projects come from
  the issues themselves; the other tables from their endpoints."
  [get-json issues]
  {:status (into {} (for [s (:issue_statuses (get-json "/issue_statuses.json" {}))]
                      [(str (:id s)) {:name (:name s) :is_closed (boolean (:is_closed s))}]))
   :priority (into {} (for [p (:issue_priorities (get-json "/enumerations/issue_priorities.json" {}))]
                        [(str (:id p)) (:name p)]))
   :tracker (into {} (for [t (:trackers (get-json "/trackers.json" {}))]
                       [(str (:id t)) (:name t)]))
   :project (into {} (for [i issues :let [p (:project i)] :when (:id p)]
                       [(str (:id p)) (:name p)]))})

(defn list-issues
  "Issue summaries, all statuses, for the `query` filter map (Redmine's
  issues.json parameters, e.g. {:assigned_to_id \"me\"})."
  [get-json query]
  (offset-paged get-json "/issues.json" (assoc query :status_id "*") :issues))

(defn issues-by-id
  "Summaries of the issues with `ids`, all statuses, in batches."
  [get-json ids]
  (into [] (mapcat #(list-issues get-json {:issue_id (str/join "," %)}))
        (partition-all 50 ids)))

(defn issue-with-journal [get-json id]
  (:issue (get-json (str "/issues/" id ".json") {:include "journals"})))

(defn host-of [url] (.getHost (java.net.URI/create (str url))))
