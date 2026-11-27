;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns fakes
  "Loopback stand-ins for the systems the bridges talk to, so
  dev/native-smoke.sh can drive one sweep of every bridge against a built
  binary without contacting anything real: the GitHub, GitLab and Redmine
  REST APIs, Odoo's JSON-RPC endpoint and a plain-text IMAP mailbox. Each
  answers only the calls one sweep makes, from fixed fixtures, and checks
  the credential the bridge config resolves, so a bridge that sent no
  secret or the wrong one fails the sweep instead of passing it.

    bb dev/native-smoke/fakes.clj <ports-file> <requests-log>

  It writes {:http <port> :imap <port>} to <ports-file> once both listen,
  appends one line per request it served to <requests-log>, and runs until
  it is killed."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http])
  (:import (java.io BufferedReader InputStreamReader OutputStream)
           (java.net InetAddress ServerSocket Socket)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant)))

(def secrets
  "The credentials the smoke script's bridge configs resolve."
  {:github "gh-smoke-token" :gitlab "gl-smoke-token" :redmine "rm-smoke-key"
   :odoo-user "smoke@example.org" :odoo-password "odoo-smoke-pw"
   :imap-user "desk@example.org" :imap-password "imap-smoke-pw"
   :support-user "support@smoke.test" :support-password "support-smoke-pw"})

(def ^:private log-file (atom nil))

(defn- log! [& parts]
  (locking log-file
    (spit @log-file (str (str/join " " parts) "\n") :append true)))

(def ^:private started (Instant/now))

(defn- ago
  "An instant `minutes` before the fakes started. Fixed per process, so a
  second sweep sees the same history as the first."
  ^String [minutes]
  (str (.minus ^Instant started (Duration/ofMinutes minutes))))

(defn- ok [body] {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string body)})
(def ^:private unauthorized {:status 401 :body "{\"message\":\"Bad credentials\"}"})
(def ^:private not-found {:status 404 :body "{\"message\":\"Not Found\"}"})

(defn- query [q]
  (into {} (for [kv (str/split (str q) #"&") :when (seq kv)
                 :let [[k v] (str/split kv #"=" 2)]]
             [(keyword k) (java.net.URLDecoder/decode (str v) "UTF-8")])))

;; ------------------------------------------------------------- github

(defn- gh-run [id n conclusion minutes]
  {:id id :run_number n :run_attempt 1 :conclusion conclusion :workflow_id 7
   :name "CI" :head_branch "main" :head_sha (format "%040d" id)
   :html_url (str "https://github.com/acme/widgets/actions/runs/" id)
   :created_at (ago minutes) :run_started_at (ago minutes)
   :repository {:id 1 :full_name "acme/widgets"} :event "push"
   :actor {:login "dev"} :display_title "a commit"})

(defn- github [{:keys [uri headers query-string]}]
  (let [runs [(gh-run 1002 2 "failure" 30) (gh-run 1001 1 "success" 90)]
        page (parse-long (or (:page (query query-string)) "1"))]
    (cond
      (not= (get headers "authorization") (str "Bearer " (:github secrets))) unauthorized
      (= uri "/github/repos/acme/widgets") (ok {:full_name "acme/widgets" :default_branch "main"})
      (= uri "/github/repos/acme/widgets/actions/runs") (ok {:workflow_runs runs})
      (= uri "/github/repos/acme/widgets/actions/workflows/7/runs")
      (ok {:workflow_runs (if (= 1 page) runs [])})
      :else not-found)))

;; ------------------------------------------------------------- gitlab

(defn- gl-pipeline [id status minutes]
  {:id id :iid id :status status :source "push" :ref "main" :sha (format "%040d" id)
   :web_url (str "https://gitlab.example/acme/widgets/-/pipelines/" id)
   :created_at (ago minutes) :user {:username "dev"}})

(defn- gitlab [{:keys [uri headers query-string]}]
  (let [page (parse-long (or (:page (query query-string)) "1"))]
    (cond
      (not= (get headers "private-token") (:gitlab secrets)) unauthorized
      (#{"/gitlab/api/v4/projects/acme%2Fwidgets" "/gitlab/api/v4/projects/acme/widgets"} uri)
      (ok {:id 3 :path_with_namespace "acme/widgets" :default_branch "main"})
      (= uri "/gitlab/api/v4/projects/3/pipelines")
      (ok (if (= 1 page) [(gl-pipeline 502 "failed" 30) (gl-pipeline 501 "success" 90)] []))
      :else not-found)))

;; ------------------------------------------------------------- redmine

(def ^:private rm-issue
  {:id 4711 :subject "Renew the certificate" :description "The cert expires."
   :created_on "2026-10-01T08:00:00Z" :updated_on "2026-10-02T12:00:00Z"
   :project {:id 9 :name "Ops"} :tracker {:id 1 :name "Bug"}
   :status {:id 2 :name "In Progress"} :priority {:id 3 :name "High"}
   :author {:id 12 :name "Alex"} :assigned_to {:id 11 :name "Sebastian"}
   :journals [{:id 101 :created_on "2026-10-01T09:00:00Z" :notes "Looking into it."
               :user {:id 11 :name "Sebastian"}
               :details [{:property "attr" :name "status_id" :old_value "1" :new_value "2"}]}]})

(defn- redmine [{:keys [uri headers query-string]}]
  (let [q (query query-string)]
    (cond
      (not= (get headers "x-redmine-api-key") (:redmine secrets)) unauthorized
      (= uri "/redmine/issues.json")
      (ok (if (pos? (parse-long (or (:offset q) "0")))
            {:issues [] :total_count 1}
            {:issues [(dissoc rm-issue :journals)] :total_count 1}))
      (= uri "/redmine/issue_statuses.json")
      (ok {:issue_statuses [{:id 1 :name "New"} {:id 2 :name "In Progress"}
                            {:id 5 :name "Closed" :is_closed true}]})
      (= uri "/redmine/enumerations/issue_priorities.json")
      (ok {:issue_priorities [{:id 2 :name "Normal"} {:id 3 :name "High"}]})
      (= uri "/redmine/trackers.json") (ok {:trackers [{:id 1 :name "Bug"}]})
      (= uri "/redmine/issues/4711.json") (ok {:issue rm-issue})
      :else not-found)))

;; ------------------------------------------------------------- odoo

(def ^:private odoo-task
  {:id 1249 :name "Update the login page" :stage_id [23 "In Progress"]
   :user_id [45 "Sebastian"] :project_id [10 "Operations"]
   :create_date "2026-10-15 10:51:11"})

(def ^:private odoo-messages
  [{:id 41010 :date "2026-10-15 10:51:12" :message_type "notification" :body ""
    :author_id [45 "Sebastian"]
    :tracking_value_ids [{:id 1 :changed_field "Stage" :old_value "" :new_value "Backlog"
                          :field_type "many2one"}]}
   {:id 41020 :date "2026-10-15 10:52:35" :message_type "notification" :body ""
    :author_id [45 "Sebastian"]
    :tracking_value_ids [{:id 2 :changed_field "Stage" :old_value "Backlog" :new_value "In Progress"
                          :field_type "many2one"}]}
   {:id 41030 :date "2026-10-16 11:57:29" :message_type "comment" :body "<p>Deployed</p>"
    :author_id [45 "Sebastian"] :tracking_value_ids []}])

(defn- odoo-object [[_db uid password model method _args _kw]]
  (when (and (= 45 uid) (= password (:odoo-password secrets)))
    (case [model method]
      ["res.lang" "search_read"] [{:code "en_US"}]
      ["project.task" "fields_get"] {:name {:string "Title"} :stage_id {:string "Stage"}
                                     :user_id {:string "Assigned to"} :project_id {:string "Project"}}
      ["project.task" "search_read"] [odoo-task]
      ["mail.message" "search_read"] (mapv #(select-keys % [:id]) odoo-messages)
      ["mail.message" "message_format"] odoo-messages
      ["res.users" "read"] [{:id 45 :name "Sebastian"}]
      ::unknown)))

(defn- odoo [{:keys [uri body]}]
  (if-not (= uri "/odoo/jsonrpc")
    not-found
    (let [{{:keys [service method args]} :params id :id} (json/parse-string (slurp body) true)
          result (case [service method]
                   ["common" "login"] (if (= (rest args) [(:odoo-user secrets) (:odoo-password secrets)]) 45 false)
                   ["object" "execute_kw"] (odoo-object args)
                   ::unknown)]
      (ok (cond
            (= ::unknown result) {:jsonrpc "2.0" :id id :error {:message (str "unexpected " service " " method " " (pr-str (take 5 (drop 3 args))))}}
            (nil? result) {:jsonrpc "2.0" :id id :error {:message "Access Denied"}}
            :else {:jsonrpc "2.0" :id id :result result})))))

;; ------------------------------------------------------------- http

(def ^:private inflight (atom 0))

(defn- slow-redmine
  "Redmine again, below /slow-redmine/, with the first issue listing of a
  sweep taking 2.5 s. It logs how many requests are in flight at once,
  so two runs of one pipeline overlapping would show as inflight=2."
  [{:keys [uri query-string] :as req}]
  (let [n (swap! inflight inc)]
    (try
      (log! "slow-redmine" (str "inflight=" n))
      (when (and (= uri "/slow-redmine/issues.json")
                 (zero? (parse-long (or (:offset (query query-string)) "0"))))
        (Thread/sleep 2500))
      (redmine (assoc req :uri (str/replace-first uri "/slow-redmine/" "/redmine/")))
      (finally (swap! inflight dec)))))

(defn- handler [{:keys [request-method uri] :as req}]
  (let [resp (try
               (cond
                 (str/starts-with? uri "/slow-redmine/") (slow-redmine req)
                 ;; never answers in time: the pipeline's timeout must stop it
                 (str/starts-with? uri "/hang-github/") (do (Thread/sleep 30000) not-found)
                 (str/starts-with? uri "/github/") (github req)
                 (str/starts-with? uri "/gitlab/") (gitlab req)
                 (str/starts-with? uri "/redmine/") (redmine req)
                 (str/starts-with? uri "/odoo/") (odoo req)
                 :else not-found)
               (catch Exception e {:status 500 :body (str "fake: " (ex-message e))}))]
    (log! "http" (str/upper-case (name request-method)) uri (:status resp))
    resp))

;; ------------------------------------------------------------- imap

(defn- message [{:keys [from subject id authserv]}]
  (str (when authserv (str "Authentication-Results: " authserv "; dkim=pass header.d=example.org\r\n"))
       "From: " from "\r\n"
       "To: desk@example.org\r\n"
       "Subject: " subject "\r\n"
       "Date: Thu, 26 Nov 2026 10:00:00 +0000\r\n"
       "Message-ID: <" id "@example.org>\r\n"
       "Content-Type: text/plain; charset=utf-8\r\n"
       "\r\n"
       "Please send help.\r\n"))

(def ^:private mailboxes
  "Each user's messages. desk@ holds one plain message for a single
  sweep. support@ is the watched mailbox: a message DKIM-signed as seen
  by the pinned verifier mx.smoke.test, one whose verdict another
  verifier stamped, and a third that arrives while the watch idles."
  (atom {(:imap-user secrets) [(message {:from "Alice <alice@example.org>" :subject "The printer is on fire"
                                         :id "smoke-1"})]
         (:support-user secrets) [(message {:from "Alice <alice@example.org>" :subject "Signed and welcome"
                                            :id "dkim-1" :authserv "mx.smoke.test"})
                                  (message {:from "Boss <boss@example.org>" :subject "Wire the money"
                                            :id "forged-2" :authserv "mx.elsewhere.test"})]}))

(def ^:private pushed
  (message {:from "Carol <carol@example.org>" :subject "Pushed while idling" :id "pushed-3"
            :authserv "mx.smoke.test"}))

(defn- literal [n ^String raw]
  [(str "* " n " FETCH (UID " n " BODY[] {" (count (.getBytes raw StandardCharsets/ISO_8859_1)) "}")
   (str raw ")")])

(defn- imap-session [^Socket sock]
  (with-open [sock sock]
    (let [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/ISO_8859_1))
          ^OutputStream out (.getOutputStream sock)
          send! (fn [& lines]
                  (doseq [l lines] (.write out (.getBytes (str l "\r\n") StandardCharsets/ISO_8859_1)))
                  (.flush out))
          logins {(pr-str (:imap-user secrets)) [(:imap-user secrets) (:imap-password secrets)]
                  (pr-str (:support-user secrets)) [(:support-user secrets) (:support-password secrets)]}]
      (send! "* OK fake IMAP ready")
      (loop [user nil]
        (when-let [line (.readLine in)]
          (let [[tag verb & more] (str/split line #" ")
                rest-line (str/join " " more)
                box (get @mailboxes user [])]
            (log! "imap" (str/upper-case (str verb)) (if (= "LOGIN" (str/upper-case (str verb))) "<credentials>" rest-line))
            (case (str/upper-case (str verb))
              "LOGIN" (let [[u p] (get logins (first more))]
                        (if (and u (= (second more) (pr-str p)))
                          (do (send! (str tag " OK LOGIN completed")) (recur u))
                          (do (send! (str tag " NO LOGIN failed")) (recur nil))))
              "SELECT" (do (send! (str "* " (count box) " EXISTS")
                                  (str tag (if user " OK [READ-WRITE] SELECT completed" " NO not authenticated")))
                           (recur user))
              "UID" (let [[sub & args] more]
                      (case (str/upper-case sub)
                        "SEARCH" (send! (str "* SEARCH " (str/join " " (range 1 (inc (count box)))))
                                        (str tag " OK SEARCH completed"))
                        "FETCH" (let [n (parse-long (first args))]
                                  (apply send! (concat (when (<= 1 n (count box)) (literal n (nth box (dec n))))
                                                       [(str tag " OK FETCH completed")])))
                        (send! (str tag " BAD unknown UID command")))
                      (recur user))
              "FETCH" (let [[lo hi] (map parse-long (str/split (first more) #":"))]
                        (apply send! (concat (mapcat #(literal % (nth box (dec %)))
                                                     (range lo (inc (min hi (count box)))))
                                             [(str tag " OK FETCH completed")]))
                        (recur user))
              ;; the first IDLE of the watched mailbox delivers the third
              ;; message after a second, as a push
              "IDLE" (do (send! "+ idling")
                         (when (and (= user (:support-user secrets)) (= 2 (count box)))
                           (Thread/sleep 1000)
                           (swap! mailboxes update user conj pushed)
                           (send! (str "* " (count (get @mailboxes user)) " EXISTS")))
                         (let [done (.readLine in)]
                           (log! "imap" (str done)))
                         (send! (str tag " OK IDLE terminated"))
                         (recur user))
              "LOGOUT" (send! "* BYE" (str tag " OK LOGOUT completed"))
              (do (send! (str tag " BAD unknown command")) (recur user)))))))))

(defn- serve-imap! ^ServerSocket []
  (let [ss (ServerSocket. 0 50 (InetAddress/getLoopbackAddress))]
    (future
      (loop []
        (let [s (.accept ss)]
          (future (try (imap-session s) (catch Exception e (log! "imap" "error" (ex-message e))))))
        (recur)))
    ss))

(defn -main [ports-file requests-log]
  (reset! log-file requests-log)
  (spit requests-log "")
  (let [server (http/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        imap (serve-imap!)]
    (spit ports-file (pr-str {:http (http/server-port server) :imap (.getLocalPort imap)}))
    @(promise)))

(apply -main *command-line-args*)
