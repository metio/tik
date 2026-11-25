;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.odoo
  "The Odoo bridge (ADR 0019 inbound): each Odoo record becomes a ticket
  whose facts mirror the record, signed by the bridge. Odoo stays a source
  of facts, never authoritative over the log.

  A profile describes one Odoo model as data: which fields become which
  facts, which field derives a state and from which config lists, how the
  ticket title is formed and which chatter messages become artifacts.
  `profiles` holds every model the bridge mirrors; the mapping below reads
  only the profile, so another model is another profile.

  Every event is a pure function of the record and its chatter (ADR 0021).
  The ticket id hashes the Odoo host, the model and the record id. Odoo
  records each change of a tracked field in the chatter as a tracking value
  with the old and new value as Odoo displays them. The values the record
  was created with are recovered by undoing those changes, newest first,
  from the current values; the create carries them at the record's creation
  time, and each message contributes its own changes at its own time.

  A tracking value names its field by the label the field had when the
  change was made, in the language of whoever made it. `label-table`
  therefore maps the label of every mirrored field in every installed
  language back to the field, and drops a label two fields share. A change
  whose label maps to no field is not mirrored, and the opening value of
  that field then comes out as the value after the change.

  Only fields Odoo tracks belong in a profile's field table. Odoo keeps no
  history for the others, so a value read today could only be asserted at a
  moment it was not yet true."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [tik.forge :as forge]
            [tik.mime :as mime]
            [tik.mirror :as mirror])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------- profiles

(def project-task
  "Odoo project tasks as odoo-task tickets. Odoo 12 stages carry no closed
  flag (`fold` only hides a column), so the config names the closed ones
  in :closed-stages."
  {:model "project.task"
   :process :odoo-task
   :created "create_date"
   :fields {"name" [:odoo :title]
            "stage_id" [:odoo :stage]
            "user_id" [:odoo :assignee]
            "project_id" [:odoo :project]}
   :actors #{"user_id"}
   :state {:field "stage_id"
           :fact [:odoo :state]
           :lists [[:closed :closed-stages]]
           :otherwise :open}
   :title {:rule :id-and-opening :field "name"}
   :chatter {:types #{"comment" "email"} :prefix "odoo/message/"}
   :domain [["user_id" "=" :uid]]})

(def profiles
  "Model name -> profile, every model the bridge mirrors."
  {"project.task" project-task})

(def default-model
  "The model of an odoo.edn without :profile."
  "project.task")

(def title-rules
  "Title rule -> the keys its map needs besides :rule. :id-and-opening
  titles a ticket \"#<id> <opening value of :field>\", carrying the
  record's text into the log; :labelled-id titles it \"<label> #<id>\" and
  carries no text of the record."
  {:id-and-opening #{:field}
   :labelled-id #{:label}})

(def ^:private reserved-facts
  "The fact paths every profile asserts besides its field table."
  #{[:odoo :id] [:odoo :url]})

(defn- fact-path? [p]
  (and (vector? p) (seq p) (every? keyword? p)))

(defn- word? [s]
  (and (string? s) (not (str/blank? s))))

(defn profile-problems
  "Why `p` cannot serve as a profile, as messages; empty for a sound one."
  [p]
  (let [{:keys [model process created fields actors state title chatter domain]} p
        field-facts (vals fields)]
    (cond-> []
      (not (map? p)) (conj "a profile is a map")
      (not (word? model)) (conj ":model must name an Odoo model")
      (not (keyword? process)) (conj ":process must be a process keyword")
      (not (word? created)) (conj ":created must name the creation-date field")
      (not (and (map? fields) (seq fields) (every? word? (keys fields))
                (every? fact-path? field-facts)))
      (conj ":fields must map field names to fact paths")
      (not= (count field-facts) (count (set field-facts)))
      (conj ":fields asserts one fact path from two fields")
      (some reserved-facts field-facts)
      (conj ":fields must not assert [:odoo :id] or [:odoo :url]")
      (not (and (set? actors) (every? #(contains? fields %) actors)))
      (conj ":actors must be a set of fields from :fields")
      (and (some? state)
           (not (and (map? state)
                     (contains? fields (:field state))
                     (fact-path? (:fact state))
                     (not-any? #{(:fact state)} (concat field-facts reserved-facts))
                     (vector? (:lists state))
                     (every? #(and (vector? %) (= 2 (count %)) (every? keyword? %)) (:lists state))
                     (keyword? (:otherwise state)))))
      (conj ":state needs a :field from :fields, an unused :fact path, :lists of [state config-key] and an :otherwise state")
      (not (and (map? title) (contains? title-rules (:rule title))))
      (conj (str ":title needs a :rule of " (str/join ", " (sort (keys title-rules)))))
      (and (= :id-and-opening (:rule title)) (not (contains? fields (:field title))))
      (conj ":title :id-and-opening needs a :field from :fields")
      (and (= :labelled-id (:rule title)) (not (word? (:label title))))
      (conj ":title :labelled-id needs a :label")
      (and (some? chatter)
           (not (and (map? chatter) (set? (:types chatter)) (every? word? (:types chatter))
                     (word? (:prefix chatter)) (str/ends-with? (:prefix chatter) "/"))))
      (conj ":chatter needs a set of message :types and a :prefix ending in /")
      (not (vector? domain)) (conj ":domain must be an Odoo domain vector"))))

(defn resolve-profile
  "The profile odoo.edn's :profile names: a model name as a string or a
  keyword, `default-model` when absent. Throws on anything else, naming
  the profiles there are."
  [cfg]
  (let [p (get cfg :profile default-model)
        model (cond (string? p) p
                    (keyword? p) (subs (str p) 1))
        known (str/join ", " (sort (keys profiles)))]
    (when-not model
      (throw (ex-info (str "the odoo config's :profile must name a model such as \""
                           default-model "\", not " (pr-str p))
                      {:reason :odoo/bad-profile :profile p})))
    (let [profile (or (get profiles model)
                      (throw (ex-info (str "the odoo config names the unknown profile \"" model
                                           "\"; known profiles: " known)
                                      {:reason :odoo/unknown-profile :profile model})))
          problems (profile-problems profile)]
      (when (seq problems)
        (throw (ex-info (str "the odoo profile " model " is unsound: " (str/join "; " problems))
                        {:reason :odoo/unsound-profile :profile model :problems problems})))
      profile)))

(defn stage-sets
  "Config key -> set of stage names, for every list `profile`'s state
  reads from `cfg`. Throws when one is missing or is not a collection of
  stage names."
  [profile cfg]
  (into {}
        (for [[_ k] (get-in profile [:state :lists])
              :let [v (get cfg k)]]
          (if (and (or (sequential? v) (set? v)) (every? string? v))
            [k (set v)]
            (throw (ex-info (if (nil? v)
                              (str "the odoo config needs " k)
                              (str "the odoo config's " k " must list stage names, not " (pr-str v)))
                            {:reason :odoo/bad-config :key k}))))))

(defn default-domain
  "The profile's domain with :uid replaced by the bridge user's id."
  [profile uid]
  (walk/postwalk-replace {:uid uid} (:domain profile)))

;; ---------------------------------------------------------------- mapping

(defn ticket-id [host model record-id]
  (forge/name-uuid (str "tik/odoo " host " " model " " record-id)))

(defn mirrored-id
  "The record id `value`, a ticket's [:odoo :id] fact, when the ticket `id`
  is that record's ticket for `host` and `model`; nil otherwise. The ticket
  id is the record's identity, so a fact on any other ticket, or one naming
  another record, never claims the record."
  [host model id value]
  (when (and (integer? value) (= id (ticket-id host model value)))
    (long value)))

(defn record-fields
  "The fields `search_read` returns for a record of `profile`."
  [{:keys [created fields]}]
  (into [created] (keys fields)))

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

(defn current-values [{:keys [fields]} record]
  (into {} (for [f (keys fields)] [f (display (get record (keyword f)))])))

(defn label-table
  "Label -> mirrored field of `profile`, from `fields-gets`: one
  `fields_get` result per installed language. A label that names two
  fields, mirrored or not, is left out, so a change is never credited to
  the wrong field."
  [{:keys [fields]} fields-gets]
  (let [by-label (reduce (fn [m [label field]] (update m label (fnil conj #{}) field))
                         {}
                         (for [fg fields-gets
                               [f {label :string}] fg
                               :when (string? label)]
                           [label (name f)]))]
    (into {} (for [[label fs] by-label
                   :when (and (= 1 (count fs)) (contains? fields (first fs)))]
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
  "The values the record was created with: the current values with every
  tracked change undone, newest message first."
  [profile labels record messages]
  (reduce (fn [vals m]
            (reduce (fn [vs [f old]] (assoc vs f old)) vals (reverse (changes labels m))))
          (current-values profile record)
          (reverse (sort-by message-order messages))))

(defn- opening-value
  "The value `field` had when the record was created; a record whose
  creation tracked none has its first tracked one, else its current one."
  [profile labels record messages field]
  (or (get (initial-values profile labels record messages) field)
      (some (fn [m] (some (fn [[f _ new]] (when (= field f) new)) (changes labels m)))
            (sort-by message-order messages))
      (display (get record (keyword field)))))

(defn title-of
  "The ticket title `profile`'s title rule gives the record."
  [{:keys [title] :as profile} labels record messages]
  (case (:rule title)
    :id-and-opening (str "#" (:id record) " "
                         (opening-value profile labels record messages (:field title)))
    :labelled-id (str (:label title) " #" (:id record))))

(defn- render
  "The fact value a field's displayed value asserts, nil for none.
  `users` maps an Odoo user's name to a tik actor."
  [{:keys [actors]} users field v]
  (when v
    (if (contains? actors field) (get users v v) v)))

(defn state-of
  "The state `rule` (a profile's :state) derives from the stage named
  `stage`: the state of the first list in `sets` (config key -> stage
  names) holding it, else the rule's :otherwise; nil for no stage."
  [{:keys [lists otherwise]} sets stage]
  (when stage
    (or (some (fn [[state k]] (when (contains? (get sets k) stage) state)) lists)
        otherwise)))

(defn message-text
  "The message's body as text, nil for a message `chatter` does not
  mirror or one with no text."
  [chatter m]
  (when (contains? (:types chatter) (:message_type m))
    (let [text (mime/html->text (str (:body m)))]
      (when-not (str/blank? text)
        (str "message " (:id m) " by " (display (:author_id m)) " at " (:date m) "\n\n"
             text "\n")))))

(defn odoo-instant
  "An Odoo datetime (UTC, \"YYYY-MM-DD HH:MM:SS\") as an Instant."
  ^Instant [s]
  (Instant/parse (str (str/replace (str s) " " "T") "Z")))

(defn record-url [url model id]
  (str (str/replace (str url) #"/+$" "") "/web#id=" id "&model=" model))

(defn- record-steps
  "The record's history as ordered steps (see `tik.mirror`): opening
  values first, then each message in (date, id) order."
  [{:keys [profile labels users stage-sets url]} record messages at-of]
  (let [{:keys [model fields state chatter created]} profile
        at0 (at-of (get record (keyword created)))
        field-steps (fn [at f v]
                      (cond-> [{:fact (fields f) :value (render profile users f v) :at at}]
                        (and state (= (:field state) f))
                        (conj {:fact (:fact state) :value (state-of state stage-sets v) :at at})))
        init (initial-values profile labels record messages)]
    (concat
     [{:fact [:odoo :id] :value (long (:id record)) :at at0}
      {:fact [:odoo :url] :value (record-url url model (:id record)) :at at0}]
     (for [f (sort (keys fields))
           :when (some? (get init f))
           step (field-steps at0 f (get init f))]
       step)
     (for [m (sort-by message-order messages)
           :let [at (at-of (:date m))
                 text (when chatter (message-text chatter m))]
           step (concat
                 (for [[f _ new] (changes labels m)
                       step (field-steps at f new)]
                   step)
                 (when text
                   [{:attach (str (:prefix chatter) (:id m)) :text text :at at}]))]
       step))))

(defn record-events
  "The events and blobs one record contributes. `ctx` carries the
  :profile, the Odoo :host and :url, :labels (see `label-table`), :users
  (name -> actor), :stage-sets (see `stage-sets`), the bridge :actor, the
  pinned :process, :version and :process-hash, and a :clamp applied to
  every :at. `messages` are the record's chatter in `message_format`
  shape."
  [{:keys [profile host labels clamp] :as ctx} record messages]
  (let [at-of #(clamp (odoo-instant %))]
    (mirror/ticket-events ctx (ticket-id host (:model profile) (:id record))
                          (title-of profile labels record messages)
                          (at-of (get record (keyword (:created profile))))
                          (record-steps ctx record messages at-of))))

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

(defn fetch-labels [x {:keys [model] :as profile}]
  (label-table profile
               (for [{:keys [code]} (x "res.lang" "search_read" [[]] {:fields ["code"]})]
                 (x model "fields_get" [] {:attributes ["string"] :context {:lang code}}))))

(defn list-records
  "The records the `domain` selects, archived ones excluded as Odoo does."
  [x {:keys [model] :as profile} domain]
  (x model "search_read" [domain] {:fields (record-fields profile) :order "id"}))

(defn records-by-id
  "The records with `ids`, archived ones included."
  [x {:keys [model] :as profile} ids]
  (x model "search_read" [[["id" "in" (vec ids)]]]
     {:fields (record-fields profile) :order "id" :context {:active_test false}}))

(defn record-messages
  "The record's chatter in `message_format` shape, which carries the
  tracking values an ordinary user cannot read from mail.tracking.value."
  [x {:keys [model]} record-id]
  (let [ids (mapv :id (x "mail.message" "search_read"
                         [[["model" "=" model] ["res_id" "=" record-id]]]
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
