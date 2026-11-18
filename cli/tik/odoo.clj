;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.odoo
  "The Odoo bridge (ADR 0019 inbound): each Odoo project task becomes an
  odoo-task ticket whose facts mirror the task, signed by the bridge. Odoo
  stays a source of facts, never authoritative over the log.

  Every event is a pure function of the task and its chatter (ADR 0021).
  The ticket id hashes the Odoo host and task id. Odoo records each change
  of a tracked field in the chatter as a tracking value with the old and
  new value as Odoo displays them. The values the task was created with are
  recovered by undoing those changes, newest first, from the current
  values; the create carries them at the task's creation time, and each
  message contributes its own changes at its own time.

  A tracking value names its field by the label the field had when the
  change was made, in the language of whoever made it. `label-table`
  therefore maps the label of every mirrored field in every installed
  language back to the field, and drops a label two fields share. A change
  whose label maps to no field is not mirrored, and the opening value of
  that field then comes out as the value after the change.

  Only fields Odoo tracks are mirrored: the title, stage, assignee and
  project. Odoo keeps no history for the others, so a value read today
  could only be asserted at a moment it was not yet true."
  (:require [clojure.string :as str]
            [tik.forge :as forge]
            [tik.mime :as mime]
            [tik.mirror :as mirror])
  (:import (java.time Instant)))

(def mirrored
  "Task field -> fact path."
  {"name" [:odoo :title]
   "stage_id" [:odoo :stage]
   "user_id" [:odoo :assignee]
   "project_id" [:odoo :project]})

(def task-fields (into ["create_date"] (keys mirrored)))

(defn ticket-id [host task-id]
  (forge/name-uuid (str "tik/odoo " host " project.task " task-id)))

(defn- display
  "The value as Odoo displays it, nil for none. A many2one reads as
  [id name], an unset field as false, and a tracking value of an unset
  many2one as the empty string."
  [v]
  (cond
    (sequential? v) (some-> (second v) str)
    (string? v) (when-not (str/blank? v) v)
    (or (nil? v) (false? v)) nil
    :else (str v)))

(defn current-values [task]
  (into {} (for [f (keys mirrored)] [f (display (get task (keyword f)))])))

(defn label-table
  "Label -> mirrored field, from `fields-gets`: one `fields_get` result
  per installed language. A label that names two fields, mirrored or not,
  is left out, so a change is never credited to the wrong field."
  [fields-gets]
  (let [by-label (reduce (fn [m [label field]] (update m label (fnil conj #{}) field))
                         {}
                         (for [fg fields-gets
                               [f {label :string}] fg
                               :when (string? label)]
                           [label (name f)]))]
    (into {} (for [[label fs] by-label
                   :when (and (= 1 (count fs)) (contains? mirrored (first fs)))]
               [label (first fs)]))))

(defn message-order [m] [(str (:date m)) (long (:id m))])

(defn- changes
  "The message's changes to mirrored fields, as [field old new]."
  [labels m]
  (for [tv (sort-by :id (:tracking_value_ids m))
        :let [f (get labels (:changed_field tv))]
        :when f]
    [f (display (:old_value tv)) (display (:new_value tv))]))

(defn initial-values
  "The values the task was created with: the current values with every
  tracked change undone, newest message first."
  [labels task messages]
  (reduce (fn [vals m]
            (reduce (fn [vs [f old]] (assoc vs f old)) vals (reverse (changes labels m))))
          (current-values task)
          (reverse (sort-by message-order messages))))

(defn- opening-title
  "The title the task was created with; a task whose creation tracked no
  title has its first tracked one, else its current one."
  [labels task messages]
  (or (get (initial-values labels task messages) "name")
      (some (fn [m] (some (fn [[f _ new]] (when (= "name" f) new)) (changes labels m)))
            (sort-by message-order messages))
      (display (:name task))))

(defn- render
  "The fact value a field's displayed value asserts, nil for none.
  `users` maps an Odoo user's name to a tik actor."
  [users field v]
  (when v
    (if (= "user_id" field) (get users v v) v)))

(defn state-of
  "Whether the stage named `stage` counts as closed. Odoo 12 stages carry
  no such flag (`fold` only hides a column), so the config names the
  closed ones."
  [closed stage]
  (when stage
    (if (contains? closed stage) :closed :open)))

(def ^:private conversation #{"comment" "email"})

(defn message-text
  "The message's body as text, nil for a message with none."
  [m]
  (when (contains? conversation (:message_type m))
    (let [text (mime/html->text (str (:body m)))]
      (when-not (str/blank? text)
        (str "message " (:id m) " by " (display (:author_id m)) " at " (:date m) "\n\n"
             text "\n")))))

(defn odoo-instant
  "An Odoo datetime (UTC, \"YYYY-MM-DD HH:MM:SS\") as an Instant."
  ^Instant [s]
  (Instant/parse (str (str/replace (str s) " " "T") "Z")))

(defn task-url [url id]
  (str (str/replace (str url) #"/+$" "") "/web#id=" id "&model=project.task"))

(defn- task-steps
  "The task's history as ordered steps (see `tik.mirror`): opening values
  first, then each message in (date, id) order."
  [{:keys [labels users closed url]} task messages at-of]
  (let [at0 (at-of (:create_date task))
        field-steps (fn [at f v]
                      (cond-> [{:fact (mirrored f) :value (render users f v) :at at}]
                        (= "stage_id" f)
                        (conj {:fact [:odoo :state] :value (state-of closed v) :at at})))
        init (initial-values labels task messages)]
    (concat
     [{:fact [:odoo :id] :value (long (:id task)) :at at0}
      {:fact [:odoo :url] :value (task-url url (:id task)) :at at0}]
     (for [f (sort (keys mirrored))
           :when (some? (get init f))
           step (field-steps at0 f (get init f))]
       step)
     (for [m (sort-by message-order messages)
           :let [at (at-of (:date m))
                 text (message-text m)]
           step (concat
                 (for [[f _ new] (changes labels m)
                       step (field-steps at f new)]
                   step)
                 (when text
                   [{:attach (str "odoo/message/" (:id m)) :text text :at at}]))]
       step))))

(defn task-events
  "The events and blobs one task contributes. `ctx` carries the Odoo
  :host and :url, :labels (see `label-table`), :users (name -> actor),
  :closed (the closed stage names), the bridge :actor, the pinned
  :process, :version and :process-hash, and a :clamp applied to every
  :at. `messages` are the task's chatter in `message_format` shape."
  [{:keys [host labels clamp] :as ctx} task messages]
  (let [at-of #(clamp (odoo-instant %))]
    (mirror/ticket-events ctx (ticket-id host (:id task))
                          (str "#" (:id task) " " (opening-title labels task messages))
                          (at-of (:create_date task))
                          (task-steps ctx task messages at-of))))

;; ---------------------------------------------------------------- remote

(defn login
  "The uid Odoo answers for `user`, or nil when it refuses the login."
  [call db user password]
  (let [uid (call "common" "login" db user password)]
    (when (integer? uid) uid)))

(defn executor
  "An `x` ((model method args) or (model method args kw) -> result) that
  runs `execute_kw` as `uid`. Odoo 12 has no API keys, so every call
  carries the password."
  [call db uid password]
  (fn x
    ([model method args] (x model method args {}))
    ([model method args kw] (call "object" "execute_kw" db uid password model method args kw))))

(defn fetch-labels [x]
  (label-table (for [{:keys [code]} (x "res.lang" "search_read" [[]] {:fields ["code"]})]
                 (x "project.task" "fields_get" [] {:attributes ["string"] :context {:lang code}}))))

(defn list-tasks
  "The tasks the `domain` selects, archived ones excluded as Odoo does."
  [x domain]
  (x "project.task" "search_read" [domain] {:fields task-fields :order "id"}))

(defn tasks-by-id
  "The tasks with `ids`, archived ones included."
  [x ids]
  (x "project.task" "search_read" [[["id" "in" (vec ids)]]]
     {:fields task-fields :order "id" :context {:active_test false}}))

(defn task-messages
  "The task's chatter in `message_format` shape, which carries the
  tracking values an ordinary user cannot read from mail.tracking.value."
  [x task-id]
  (let [ids (mapv :id (x "mail.message" "search_read"
                         [[["model" "=" "project.task"] ["res_id" "=" task-id]]]
                         {:fields ["id"]}))]
    (if (seq ids) (x "mail.message" "message_format" [ids]) [])))

(defn user-names
  "User id -> the name Odoo shows for that user."
  [x ids]
  (if (seq ids)
    (into {} (for [{:keys [id name]} (x "res.users" "read" [(vec ids)] {:fields ["name"]})]
               [id name]))
    {}))

(defn host-of [url] (.getHost (java.net.URI/create (str url))))
