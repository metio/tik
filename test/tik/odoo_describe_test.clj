;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.odoo-describe-test
  "`tik bridge odoo describe`: the read-only call wrapper refuses every
  write before any I/O, the describe result and profile coverage are pure
  functions over fields_get answers, and the command end to end against a
  loopback JSON-RPC server never prints the password.

  Both fields_get fixtures are SYNTHETIC; the helpdesk.ticket one is also
  UNVERIFIED (Odoo 12 Helpdesk field names were never read from a
  server). Tests over it exercise the code, not claims about Odoo."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.bridge-sync :as sync]
            [tik.cli :as cli]
            [tik.harness :as h]
            [tik.odoo :as od]
            [tik.odoo-describe :as odd])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- fixture [file]
  (edn/read-string (slurp (io/file "test/tik" file))))

(def ^:private project-task-fixture
  (fixture "odoo_fields_get_project_task.synthetic.edn"))

(def ^:private helpdesk-synthetic-unverified-fixture
  (fixture "odoo_fields_get_helpdesk_ticket.synthetic-unverified.edn"))

(defn- described [model {:keys [version fields]}]
  (odd/describe-result {:model model :host "odoo.example" :version version :fields fields}))

(def ^:private task (described "project.task" project-task-fixture))

(def ^:private password "s3cr3t-Pa55word")

;; ---------------------------------------------------------------- read-only

(defn- recording-call []
  (let [calls (atom [])]
    [calls (fn [& args] (swap! calls conj (vec args)) :answered)]))

(defn- refused-before-io [service method & args]
  (let [[calls call] (recording-call)
        e (try (when (apply (odd/read-only-call call) service method args) nil)
               (catch clojure.lang.ExceptionInfo e e))]
    {:reason (:reason (ex-data e)) :message (some-> e ex-message) :calls @calls}))

(deftest write_model_methods_are_refused_before_any_io
  (doseq [m ["write" "create" "unlink" "message_post" "copy" "action_archive"
             "toggle_active" "search_read" "read" "message_format" "name_create" ""]]
    (testing m
      (let [{:keys [reason message calls]}
            (refused-before-io "object" "execute_kw" "db" 2 password "project.task" m [[1]] {})]
        (is (= :odoo/not-read-only reason))
        (is (empty? calls) "the underlying call never ran")
        (is (not (str/includes? (str message) password)))))))

(deftest services_and_methods_off_the_allowlist_are_refused_before_any_io
  (doseq [[service method] [["object" "execute"] ["object" "exec_workflow"] ["db" "drop"]
                            ["db" "create_database"] ["db" "list"] ["common" "authenticate"]
                            ["report" "render_report"] ["common" "set_loglevel"] [nil nil]]]
    (testing (str service "/" method)
      (let [{:keys [reason calls]} (refused-before-io service method "db" 2 password)]
        (is (= :odoo/not-read-only reason))
        (is (empty? calls))))))

(deftest an_execute_kw_without_a_model_method_is_refused
  (is (= :odoo/not-read-only (:reason (refused-before-io "object" "execute_kw" "db" 2 password))))
  (is (= :odoo/not-read-only (:reason (refused-before-io "object" "execute_kw")))))

(deftest a_model_method_in_another_position_does_not_pass
  (is (= :odoo/not-read-only
         (:reason (refused-before-io "object" "execute_kw" "db" 2 password "fields_get" "write" [] {})))
      "fields_get as the model name with write as the method is still a write"))

(deftest the_allowlisted_calls_pass_through_unchanged
  (let [[calls call] (recording-call)
        ro (odd/read-only-call call)]
    (is (= :answered (ro "common" "version")))
    (is (= :answered (ro "common" "login" "db" "u" password)))
    (is (= :answered (ro "object" "execute_kw" "db" 2 password "project.task" "fields_get" []
                         {:attributes ["type"]})))
    (is (= [["common" "version"]
            ["common" "login" "db" "u" password]
            ["object" "execute_kw" "db" 2 password "project.task" "fields_get" [] {:attributes ["type"]}]]
           @calls))))

(deftest any_method_off_the_allowlist_is_refused
  (is (:pass?
       (tc/quick-check
        200
        (prop/for-all [service (gen/elements ["common" "object" "db" "report" ""])
                       method gen/string-alphanumeric
                       model-method gen/string-alphanumeric]
          (let [{:keys [reason calls]} (refused-before-io service method "db" 2 password "m" model-method)
                allowed? (and (contains? (get odd/read-only-methods service) method)
                              (or (not= "execute_kw" method)
                                  (contains? odd/read-only-model-methods model-method)))]
            (if allowed?
              (nil? reason)
              (and (= :odoo/not-read-only reason) (empty? calls)))))))))

(deftest the_executor_built_on_the_wrapper_cannot_write
  (let [[calls call] (recording-call)
        x (od/executor (odd/read-only-call call) "db" 2 password)]
    (doseq [m ["write" "create" "unlink" "message_post"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"describe only runs fields_get"
                            (x "project.task" m [[1] {"name" "x"}]))))
    (is (empty? @calls))))

;; ---------------------------------------------------------------- pure

(deftest model_names_are_checked_before_connecting
  (is (every? odd/model-name? ["project.task" "helpdesk.ticket" "res.users" "x_custom.model_2"]))
  (is (not-any? odd/model-name? [nil "" "Project.Task" "project..task" ".task" "task." "a b"
                                 "project.task;drop" true 42])))

(deftest a_project_task_describe_reports_types_selections_and_tracked_flags
  (is (= "12.0" (:server-version task)))
  (is (= "track_visibility" (:tracking-attribute task)))
  (is (= {:string "Stage" :type "many2one" :required false :readonly false :tracked true
          :relation "project.task.type" :selection nil}
         (get-in task [:fields "stage_id"])))
  (is (= [["normal" "Grey"] ["done" "Green"] ["blocked" "Red"]]
         (get-in task [:fields "kanban_state" :selection])))
  (is (true? (get-in task [:fields "name" :required])))
  (is (true? (get-in task [:fields "create_date" :readonly])))
  (testing "a field without the attribute is untracked once the server exports it"
    (is (false? (get-in task [:fields "priority" :tracked])))
    (is (false? (get-in task [:fields "create_date" :tracked])))))

(deftest message_post_message_id_is_reported_unknown
  (is (= :unknown (get-in task [:message-post :message-id])))
  (is (true? (get-in task [:message-post :mail-thread])))
  (is (false? (get-in (described "res.partner.category" {:fields {:name {:type "char"}}})
                      [:message-post :mail-thread]))))

(deftest a_server_exporting_no_tracking_attribute_leaves_every_flag_unknown
  (let [stripped (update project-task-fixture :fields
                         update-vals #(dissoc % :track_visibility :tracking))
        r (described "project.task" stripped)]
    (is (nil? (:tracking-attribute r)))
    (is (every? #(= :unknown (:tracked %)) (vals (:fields r))))))

(deftest odoo_13_tracking_reads_true_for_a_flag_or_a_sequence
  (let [r (described "x.y" {:fields {:a {:type "char" :tracking true}
                                     :b {:type "char" :tracking 10}
                                     :c {:type "char" :tracking false}
                                     :d {:type "char"}}})]
    (is (= "tracking" (:tracking-attribute r)))
    (is (= {"a" true "b" true "c" false "d" false}
           (update-vals (:fields r) :tracked)))))

(deftest an_empty_track_visibility_is_untracked
  (let [r (described "x.y" {:fields {:a {:type "char" :track_visibility ""}
                                     :b {:type "char" :track_visibility false}
                                     :c {:type "char" :track_visibility "onchange"}}})]
    (is (= {"a" false "b" false "c" true} (update-vals (:fields r) :tracked)))))

(deftest odd_shapes_in_a_fields_get_answer_do_not_break_the_result
  (let [r (described "x.y" {:version nil
                            :fields {:a {:type "selection" :selection "not a list"}
                                     :b {:type "selection" :selection [["k"] ["k" "v"] "junk"]}
                                     :c {:relation ""}}})]
    (is (nil? (:server-version r)))
    (is (nil? (get-in r [:fields "a" :selection])))
    (is (= [["k" "v"]] (get-in r [:fields "b" :selection])))
    (is (nil? (get-in r [:fields "c" :relation])))))

(deftest the_project_task_profile_fits_the_synthetic_project_task_describe
  (let [c (odd/profile-coverage od/project-task task)]
    (is (true? (:fits c)))
    (is (= [] (:missing c) (:type-mismatches c) (:relation-mismatches c) (:untracked c)
           (:tracking-unknown c) (:types-undeclared c))))
  (testing "stage_id is a many2one, so its stages are records fields_get cannot list"
    (is (= {:field "stage_id" :checkable false}
           (select-keys (:state (odd/profile-coverage od/project-task task)) [:field :checkable])))))

(deftest coverage_names_missing_fields_type_and_relation_mismatches_and_untracked_fields
  (let [broken (-> project-task-fixture
                   (update :fields dissoc :project_id)
                   (assoc-in [:fields :name :type] "text")
                   (assoc-in [:fields :user_id :relation] "res.partner")
                   (update-in [:fields :stage_id] dissoc :track_visibility))
        c (odd/profile-coverage od/project-task (described "project.task" broken))]
    (is (false? (:fits c)))
    (is (= ["project_id"] (:missing c)))
    (is (= [{:field "name" :expected "char" :actual "text"}] (:type-mismatches c)))
    (is (= [{:field "user_id" :expected "res.users" :actual "res.partner"}] (:relation-mismatches c)))
    (is (= ["stage_id"] (:untracked c)))))

(deftest coverage_without_tracking_information_names_the_fields_but_still_fits
  (let [stripped (update project-task-fixture :fields update-vals #(dissoc % :track_visibility))
        c (odd/profile-coverage od/project-task (described "project.task" stripped))]
    (is (= ["name" "project_id" "stage_id" "user_id"] (:tracking-unknown c)))
    (is (true? (:fits c)) "unknown is not untracked")))

(deftest coverage_of_another_model_does_not_fit
  (let [c (odd/profile-coverage od/project-task
                                (described "helpdesk.ticket" helpdesk-synthetic-unverified-fixture))]
    (is (false? (:fits c)))
    (is (= ["project_id"] (:missing c)))))

(def ^:private priority-profile
  "A test-only profile whose state reads a selection field, over the
  SYNTHETIC, UNVERIFIED helpdesk.ticket fixture."
  {:model "helpdesk.ticket"
   :process :odoo-task
   :created "create_date"
   :fields {"priority" [:odoo :priority] "stage_id" [:odoo :stage] "user_id" [:odoo :assignee]}
   :types {"priority" "selection" "stage_id" "many2one" "user_id" "many2one"}
   :actors #{"user_id"}
   :state {:field "priority" :fact [:odoo :urgency]
           :lists [[:urgent :urgent-priorities] [:low :low-priorities]]
           :otherwise :normal}
   :title {:rule :labelled-id :label "ticket"}
   :chatter nil
   :domain []})

(deftest synthetic_unverified_helpdesk_selection_values_no_list_names_fall_to_otherwise
  (is (= [] (od/profile-problems priority-profile)))
  (let [helpdesk (described "helpdesk.ticket" helpdesk-synthetic-unverified-fixture)
        c (odd/profile-coverage priority-profile helpdesk
                                {:urgent-priorities #{"3"} :low-priorities #{"Low priority" "Lowest"}})]
    (is (true? (:fits c)))
    (is (= {:field "priority" :checkable true :otherwise :normal
            :uncovered [["0" "All"] ["2" "High priority"]]
            :unknown-names ["Lowest"]}
           (:state c))
        "a key or a label names a value; a name no value carries is reported")
    (testing "without the config lists every value falls to :otherwise"
      (is (= 4 (count (get-in (odd/profile-coverage priority-profile helpdesk) [:state :uncovered])))))))

(deftest a_state_field_the_server_lacks_is_not_checkable
  (let [c (odd/profile-coverage priority-profile (described "helpdesk.ticket" {:fields {}}))]
    (is (= {:field "priority" :checkable false :reason "the server has no such field"} (:state c)))
    (is (false? (:fits c)))))

(deftest a_profile_without_types_lists_them_as_undeclared
  (let [c (odd/profile-coverage (dissoc od/project-task :types) task)]
    (is (= ["create_date" "name" "project_id" "stage_id" "user_id"] (:types-undeclared c)))
    (is (true? (:fits c)))))

(deftest describe_reads_config_lists_leniently
  (is (= {:closed-stages #{"Done"}}
         (odd/config-stage-sets od/project-task {:closed-stages ["Done"]})))
  (is (= {} (odd/config-stage-sets od/project-task {})))
  (is (= {} (odd/config-stage-sets od/project-task {:closed-stages "Done"}))))

(deftest a_profile_types_map_is_validated
  (is (= [] (od/profile-problems od/project-task)))
  (doseq [bad [{"nope" "char"} {"name" ""} {"name" :char} ["name" "char"]]]
    (is (some #(str/starts-with? % ":types") (od/profile-problems (assoc od/project-task :types bad)))
        (pr-str bad))))

(deftest the_result_renders_as_edn_json_and_text
  (let [r (assoc task :coverage (odd/profile-coverage od/project-task task))]
    (is (= r (edn/read-string (odd/render-result :edn r))))
    (let [j (json/parse-string (odd/render-result :json r))]
      (is (= "unknown" (get-in j ["message-post" "message-id"])))
      (is (= "selection" (get-in j ["fields" "kanban_state" "type"])))
      (is (true? (get-in j ["coverage" "fits"]))))
    (let [text (odd/render-result :human r)]
      (is (str/includes? text "model project.task on odoo.example (Odoo 12.0)"))
      (is (re-find #"(?m)^kanban_state\s+selection\s+true\s+yes\s+no\s+normal=Grey, done=Green, blocked=Red\s+Kanban State$" text))
      (is (re-find #"(?m)^stage_id\s+many2one\s+true\s+no\s+no\s+project.task.type\s+Stage$" text))
      (is (str/includes? text "message_post accepts message_id: unknown"))
      (is (str/includes? text "profile project.task: fits"))
      (is (str/includes? text "state stage_id: not checkable")))))

(deftest a_coverage_that_does_not_fit_renders_its_reasons
  (let [broken (-> project-task-fixture
                   (update :fields dissoc :project_id)
                   (update-in [:fields :stage_id] dissoc :track_visibility))
        d (described "project.task" broken)
        text (odd/format-human (assoc d :coverage (odd/profile-coverage od/project-task d)))]
    (is (str/includes? text "profile project.task: does not fit"))
    (is (str/includes? text "missing: project_id"))
    (is (str/includes? text "untracked: stage_id"))))

(deftest redact_removes_every_occurrence_of_the_secret
  (is (= "a [redacted] b [redacted]" (odd/redact "pw" "a pw b pw")))
  (is (= "unchanged" (odd/redact nil "unchanged")))
  (is (= "unchanged" (odd/redact "" "unchanged"))))

(deftest fetch_runs_version_login_and_one_fields_get
  (let [calls (atom [])
        call (fn [& args]
               (swap! calls conj (vec args))
               (case (second args)
                 "version" (:version project-task-fixture)
                 "login" 7
                 "execute_kw" (:fields project-task-fixture)))
        r (odd/fetch call {:db "odoo" :user "u" :password password :model "project.task"
                           :host "odoo.example"})]
    (is (= task r))
    (is (= [["common" "version"]
            ["common" "login" "odoo" "u" password]
            ["object" "execute_kw" "odoo" 7 password "project.task" "fields_get" []
             {:attributes odd/field-attributes}]]
           @calls))))

(deftest fetch_throws_when_the_login_is_refused
  (let [call (fn [& args] (case (second args) "version" {} "login" false))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused the login"
                          (odd/fetch call {:db "odoo" :user "u" :password password :model "x.y"})))))

;; ---------------------------------------------------------------- end to end

(defn- with-fake-odoo
  "Calls `f` with the URL of a loopback JSON-RPC server answering
  version, login and fields_get from `fx`, or an RPC error carrying
  `error` for every call when given; returns [result requests], each
  request the parsed params."
  [fx error f]
  (let [seen (atom [])
        srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [params (:params (json/parse-string (slurp (.getRequestBody ^HttpExchange ex)) true))
                              _ (swap! seen conj params)
                              result (case (:method params)
                                       "version" (:version fx)
                                       "login" 7
                                       "execute_kw" (when (= "fields_get" (nth (:args params) 4 nil))
                                                      (:fields fx))
                                       nil)
                              reply (json/generate-string
                                     (if error
                                       {:jsonrpc "2.0" :id 1 :error {:message "Odoo Server Error"
                                                                     :data {:message error}}}
                                       {:jsonrpc "2.0" :id 1 :result result}))
                              body (.getBytes ^String reply StandardCharsets/UTF_8)]
                          (.sendResponseHeaders ^HttpExchange ex 200 (long (alength body)))
                          (with-open [o (.getResponseBody ^HttpExchange ex)]
                            (.write o body))))))
    (.start srv)
    (try
      [(f (str "http://127.0.0.1:" (.getPort (.getAddress srv)))) @seen]
      (finally (.stop srv 0)))))

(defn- config! [cfg]
  (let [dir (h/temp-dir! "odoo-describe")
        f (io/file dir "odoo.edn")]
    (spit f (pr-str cfg))
    (str f)))

(defn- password-file! []
  (let [f (h/temp-file! "odoo-pw" ".txt")]
    (spit f (str password "\n"))
    (str f)))

(defn- describe! [cfg & flags]
  (let [root (h/temp-dir! "odoo-describe-root")]
    (h/with-cli-root root
      #(cli/run-argv (into ["bridge" "odoo" "describe" "--config" (config! cfg)] flags)))))

(deftest describe_end_to_end_prints_the_fields_and_never_the_password
  (let [[r seen] (with-fake-odoo project-task-fixture nil
                   (fn [url]
                     (describe! {:url url :db "odoo" :user "bridge@example.org"
                                 :password {:file (password-file!)} :closed-stages ["Done"]}
                                "--model" "project.task")))]
    (is (zero? (:exit r)) (:err r))
    (is (str/includes? (:out r) "model project.task on 127.0.0.1 (Odoo 12.0)"))
    (is (str/includes? (:out r) "profile project.task: fits"))
    (is (not (str/includes? (str (:out r) (:err r)) password)))
    (is (h/clean-output? (str (:out r) (:err r))))
    (is (= [["version" []] ["login" ["odoo" "bridge@example.org" password]]]
           (map (juxt :method :args) (take 2 seen))))
    (is (= ["version" "login" "execute_kw"] (map :method seen)))
    (is (= "fields_get" (nth (:args (last seen)) 4)))))

(deftest describe_end_to_end_emits_edn_and_json
  (let [[edn-r] (with-fake-odoo helpdesk-synthetic-unverified-fixture nil
                  (fn [url]
                    (describe! {:url url :db "odoo" :user "u" :password {:file (password-file!)}}
                               "--model" "helpdesk.ticket" "--edn")))
        [json-r] (with-fake-odoo helpdesk-synthetic-unverified-fixture nil
                   (fn [url]
                     (describe! {:url url :db "odoo" :user "u" :password {:file (password-file!)}}
                                "--model" "helpdesk.ticket" "--format" "json")))
        e (edn/read-string (:out edn-r))
        j (json/parse-string (:out json-r))]
    (is (= 0 (:exit edn-r) (:exit json-r)))
    (is (= "helpdesk.ticket" (:model e) (get j "model")))
    (is (nil? (:coverage e)) "helpdesk.ticket has no registered profile")
    (is (= [["0" "All"] ["1" "Low priority"] ["2" "High priority"] ["3" "Urgent"]]
           (get-in e [:fields "priority" :selection])))
    (is (= "unknown" (get-in j ["message-post" "message-id"])))))

(deftest a_server_error_echoing_the_password_is_redacted
  (let [[r] (with-fake-odoo project-task-fixture (str "Access denied for password " password)
              (fn [url]
                (describe! {:url url :db "odoo" :user "u" :password {:file (password-file!)}}
                           "--model" "project.task")))]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "[redacted]"))
    (is (not (str/includes? (str (:out r) (:err r)) password)))))

(deftest a_literal_password_or_a_bad_model_is_refused_before_any_request
  (let [[r seen] (with-fake-odoo project-task-fixture nil
                   (fn [url]
                     (describe! {:url url :db "odoo" :user "u" :password password}
                                "--model" "project.task")))]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "must be a secret spec"))
    (is (not (str/includes? (str (:out r) (:err r)) password)))
    (is (empty? seen)))
  (doseq [flags [[] ["--model" "Project Task"] ["--model" "project.task;drop"]]]
    (let [[r seen] (with-fake-odoo project-task-fixture nil
                     (fn [url]
                       (apply describe! {:url url :db "odoo" :user "u" :password {:file (password-file!)}}
                              flags)))]
      (is (= 1 (:exit r)) (pr-str flags))
      (is (str/includes? (:err r) "usage: tik bridge odoo describe"))
      (is (empty? seen))))
  (let [[r seen] (with-fake-odoo project-task-fixture nil
                   (fn [url]
                     (describe! {:url url :db "odoo" :user "u" :password {:file (password-file!)}}
                                "--model")))]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "--model needs a value"))
    (is (empty? seen)))
  (let [[r seen] (with-fake-odoo project-task-fixture nil
                   (fn [url]
                     (describe! {:url url :db "odoo" :user "u" :password {:file (password-file!)}}
                                "--model" "project.task" "--format" "yaml")))]
    (is (= 1 (:exit r)))
    (is (empty? seen))))

(deftest the_connection_keys_are_required_for_describe
  (doseq [k [:url :db :user]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo (re-pattern (str "needs " k))
                          (sync/odoo-describe-settings (dissoc {:url "u" :db "d" :user "x"} k)))))
  (is (= {:env "ODOO_PASSWORD"} (:password (sync/odoo-describe-settings {:url "u" :db "d" :user "x"})))))

(deftest describe_needs_no_stage_lists
  (let [[r] (with-fake-odoo project-task-fixture nil
              (fn [url]
                (describe! {:url url :db "odoo" :user "u" :password {:file (password-file!)}}
                           "--model" "project.task")))]
    (is (zero? (:exit r)) (:err r))))
