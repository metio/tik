;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.odoo-describe
  "`tik bridge odoo describe`: what an Odoo server says about one model's
  fields, read through `fields_get` only, and how a bridge profile fits it.

  The command can reach no write: every call goes through `read-only-call`,
  which refuses any service, method or model method outside
  `read-only-methods` before the call leaves the process. `fetch` is the
  one impure step; `describe-result`, `profile-coverage` and the
  renderers are pure functions over what it fetched, so they are tested
  over fixtures and a later describe output can be checked against a
  profile without a server.

  `fields_get` describes fields, not method signatures, so whether
  `mail.thread`'s `message_post` accepts a `message_id` keyword cannot be
  learned read-only. The result says `:unknown` for it rather than
  guessing from the server version."
  (:require [clojure.string :as str]
            [tik.odoo :as od]
            [tik.render :as render]))

;; ---------------------------------------------------------------- read-only

(def read-only-methods
  "Service -> the methods describe may call. `execute_kw` runs a model
  method, so it is admitted only for the model methods in
  `read-only-model-methods`."
  {"common" #{"version" "login"}
   "object" #{"execute_kw"}})

(def read-only-model-methods
  "The model methods describe may run through `execute_kw`."
  #{"fields_get"})

(defn refusal
  "Why describe must not make the call (`service` `method` & `args`), as
  a message; nil for a call on the allowlist. An `execute_kw` names its
  model method fifth, after the database, uid, password and model."
  [service method args]
  (let [allowed (get read-only-methods service)
        model-method (when (= ["object" "execute_kw"] [service method]) (nth (vec args) 4 nil))]
    (cond
      (not (contains? allowed method))
      (str "refusing the Odoo call " service "/" method ": describe only reads")
      (and (= "execute_kw" method) (not (contains? read-only-model-methods model-method)))
      (str "refusing the Odoo model method " (pr-str model-method) ": describe only runs "
           (str/join ", " (sort read-only-model-methods))))))

(defn read-only-call
  "A `call` ((service method & args) -> result) that passes on only the
  calls `refusal` admits and throws, without calling `call`, on any
  other."
  [call]
  (fn [service method & args]
    (when-let [why (refusal service method args)]
      (throw (ex-info why {:reason :odoo/not-read-only :service service :method method})))
    (apply call service method args)))

;; ---------------------------------------------------------------- pure

(def field-attributes
  "The `fields_get` attributes describe asks for. Odoo 12 names the
  tracking flag `track_visibility` (a string such as \"onchange\"), Odoo
  13 and later `tracking`; describe asks for both."
  ["string" "type" "selection" "relation" "required" "readonly" "tracking" "track_visibility"])

(defn model-name?
  "Whether `s` reads as an Odoo model name such as \"project.task\"."
  [s]
  (boolean (and (string? s) (re-matches #"[a-z][a-z0-9_]*(\.[a-z0-9_]+)*" s))))

(defn tracking-attribute
  "The tracking attribute the server reports in `fields`, a `fields_get`
  result: \"tracking\", \"track_visibility\", or nil when no field
  carries either and the server does not export one."
  [fields]
  (let [ks (set (mapcat keys (vals fields)))]
    (cond (contains? ks :tracking) "tracking"
          (contains? ks :track_visibility) "track_visibility")))

(defn- tracked
  "Whether a field with `attrs` is tracked under the server's tracking
  `attribute`: :unknown when the server exports none. A server that
  exports the attribute only on tracked fields leaves it off the others,
  so its absence there reads as untracked."
  [attribute attrs]
  (let [v (get attrs (keyword (str attribute)))]
    (cond
      (nil? attribute) :unknown
      (or (nil? v) (false? v)) false
      (string? v) (not (str/blank? v))
      :else true)))

(defn- selection-of [v]
  (when (sequential? v)
    (vec (for [entry v :when (and (sequential? entry) (= 2 (count entry)))]
           [(first entry) (second entry)]))))

(defn- relation-of [v]
  (when (and (string? v) (not (str/blank? v))) v))

(defn describe-result
  "The describe result for `model` on `host` from the `version` answer
  (Odoo's `common/version` map) and `fields` (the model's `fields_get`
  result, keyword field names as JSON-RPC parses them)."
  [{:keys [model host version fields]}]
  (let [attribute (tracking-attribute fields)]
    {:model model
     :host host
     :server-version (some-> (:server_version version) str)
     :tracking-attribute attribute
     :fields (into (sorted-map)
                   (for [[f attrs] fields]
                     [(name f)
                      {:string (some-> (:string attrs) str)
                       :type (some-> (:type attrs) str)
                       :required (true? (:required attrs))
                       :readonly (true? (:readonly attrs))
                       :tracked (tracked attribute attrs)
                       :relation (relation-of (:relation attrs))
                       :selection (selection-of (:selection attrs))}]))
     :message-post {:mail-thread (contains? fields :message_ids)
                    :message-id :unknown
                    :reason (str "fields_get describes fields, not method signatures;"
                                 " Odoo offers no read-only call that reports"
                                 " message_post's keyword arguments")}}))

(defn- profile-field-names
  "Every field `profile` reads from a record."
  [{:keys [created fields]}]
  (into (sorted-set) (cons created (keys fields))))

(defn- state-coverage
  "How the profile's state rule covers its field's values: the selection
  entries no config list names (they fall to :otherwise), and list names
  no selection entry carries. A selection value counts as named when a
  list holds its key or its label, since search_read reports the key and
  a tracking value the label."
  [{:keys [field lists otherwise]} described stage-sets]
  (let [{:keys [type selection]} (get-in described [:fields field])]
    (cond
      (nil? type) {:field field :checkable false :reason "the server has no such field"}
      (not= "selection" type)
      {:field field :checkable false
       :reason (str "a " type " field's values are records, not a selection fields_get reports")}
      :else
      (let [named (into #{} (mapcat #(get stage-sets (second %))) lists)
            offered (into #{} (mapcat (fn [[k label]] [(str k) (str label)])) selection)]
        {:field field
         :checkable true
         :otherwise otherwise
         :uncovered (vec (for [[k label] selection
                               :when (not (or (named (str k)) (named (str label))))]
                           [k label]))
         :unknown-names (vec (sort (remove offered named)))}))))

(defn profile-coverage
  "How `profile` fits `described`, a `describe-result` of its model:
  the fields it reads that the server lacks, declared :types the server
  contradicts, actor fields that do not point at res.users, mirrored
  fields the server reports untracked or cannot say about, and the
  state rule's coverage given `stage-sets` (config key -> names, see
  `od/stage-sets`). :fits is true when nothing is missing, mismatched or
  untracked."
  ([profile described] (profile-coverage profile described {}))
  ([{:keys [model fields actors state types] :as profile} described stage-sets]
   (let [server (:fields described)
         missing (vec (remove #(contains? server %) (profile-field-names profile)))
         type-mismatches (vec (for [[f expected] (sort types)
                                    :let [actual (get-in server [f :type])]
                                    :when (and actual (not= expected actual))]
                                {:field f :expected expected :actual actual}))
         relation-mismatches (vec (for [f (sort actors)
                                        :let [actual (get-in server [f :relation])]
                                        :when (and (contains? server f) (not= "res.users" actual))]
                                    {:field f :expected "res.users" :actual actual}))
         mirrored (filter #(contains? server %) (sort (keys fields)))
         untracked (filterv #(false? (get-in server [% :tracked])) mirrored)
         unknown (filterv #(= :unknown (get-in server [% :tracked])) mirrored)]
     (cond-> {:model model
              :described (:model described)
              :missing missing
              :type-mismatches type-mismatches
              :relation-mismatches relation-mismatches
              :untracked untracked
              :tracking-unknown unknown
              :types-undeclared (vec (remove #(contains? types %) (profile-field-names profile)))
              :fits (and (= model (:model described))
                         (empty? missing) (empty? type-mismatches)
                         (empty? relation-mismatches) (empty? untracked))}
       state (assoc :state (state-coverage state described stage-sets))))))

(defn config-stage-sets
  "Config key -> set of names for every list `profile`'s state reads that
  `cfg` holds as a collection of strings. Describe does not need the
  lists, so an absent one is left out where `od/stage-sets` would throw."
  [profile cfg]
  (into {} (for [[_ k] (get-in profile [:state :lists])
                 :let [v (get cfg k)]
                 :when (and (or (sequential? v) (set? v)) (every? string? v))]
             [k (set v)])))

(defn- cell [v]
  (cond (nil? v) "-"
        (keyword? v) (name v)
        :else (str v)))

(defn- detail [{:keys [relation selection]}]
  (cond relation relation
        (seq selection) (str/join ", " (for [[k label] selection] (str k "=" label)))
        :else "-"))

(defn- coverage-lines [{:keys [model missing type-mismatches relation-mismatches untracked
                               tracking-unknown types-undeclared fits state]}]
  (let [line (fn [label xs] (when (seq xs) (str "  " label ": " (str/join ", " xs))))]
    (remove nil?
            [(str "profile " model ": " (if fits "fits" "does not fit"))
             (line "missing" missing)
             (line "type mismatch" (for [{:keys [field expected actual]} type-mismatches]
                                     (str field " (profile " expected ", server " actual ")")))
             (line "relation mismatch" (for [{:keys [field expected actual]} relation-mismatches]
                                         (str field " (profile " expected ", server " (cell actual) ")")))
             (line "untracked" untracked)
             (line "tracking unknown" tracking-unknown)
             (line "types undeclared" types-undeclared)
             (when state
               (if (:checkable state)
                 (str "  state " (:field state) ": "
                      (if (seq (:uncovered state))
                        (str (str/join ", " (for [[k label] (:uncovered state)] (str k "=" label)))
                             " fall to " (cell (:otherwise state)))
                        "every value named")
                      (when (seq (:unknown-names state))
                        (str "; unknown names " (str/join ", " (:unknown-names state)))))
                 (str "  state " (:field state) ": not checkable, " (:reason state))))])))

(defn format-human
  "`result` as text for people: a header, one line per field, the
  message_post finding, and the profile coverage when there is one."
  [{:keys [model host server-version tracking-attribute fields message-post coverage]}]
  (let [rows (for [[f a] fields]
               [f (cell (:type a)) (cell (:tracked a)) (if (:required a) "yes" "no")
                (if (:readonly a) "yes" "no") (detail a) (cell (:string a))])
        header ["FIELD" "TYPE" "TRACKED" "REQUIRED" "READONLY" "RELATION/SELECTION" "LABEL"]
        widths (apply map (fn [& col] (apply max (map count col))) header rows)
        fmt-row (fn [r] (str/trimr (str/join "  " (map #(str %2 (str/join (repeat (- %1 (count %2)) " ")))
                                                       widths r))))]
    (str/join "\n"
              (concat
               [(str "model " model " on " host " (Odoo " (cell server-version) ")")
                (str "tracking attribute: " (or tracking-attribute "none reported, tracked flags unknown"))
                ""
                (fmt-row header)]
               (map fmt-row rows)
               [""
                (str "inherits mail.thread: " (cell (:mail-thread message-post)))
                (str "message_post accepts message_id: " (cell (:message-id message-post))
                     " (" (:reason message-post) ")")]
               (when coverage (cons "" (coverage-lines coverage)))))))

(defn render-result
  "`result` as the text `fmt` (:human, :edn or :json) asks for."
  [fmt result]
  (case fmt
    :edn (pr-str result)
    :json (render/json-encode result)
    (format-human result)))

(defn redact
  "`s` with every occurrence of `secret` replaced, so no message or
  output carries the password even when a server echoes it."
  [secret s]
  (if (and (string? secret) (seq secret))
    (str/replace (str s) secret "[redacted]")
    (str s)))

;; ---------------------------------------------------------------- remote

(defn fetch
  "The describe result for `model`, read with `call` through
  `read-only-call`: the server version, a login, and the model's
  `fields_get`. Throws when Odoo refuses the login."
  [call {:keys [db user password model host]}]
  (let [ro (read-only-call call)
        version (ro "common" "version")
        uid (or (od/login ro db user password)
                (throw (ex-info (str "Odoo refused the login of " user " to database " db)
                                {:reason :odoo/login-refused})))
        x (od/executor ro db uid password)]
    (describe-result {:model model :host host :version version
                      :fields (x model "fields_get" [] {:attributes field-attributes})})))
