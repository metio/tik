;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.github-test
  "CI failure streaks through the GitHub adapter: streak partitioning over
  run histories, coordination-free ingest (two stores syncing the same
  history mint the same event ids), the stage path a streak walks,
  repository discovery, webhook authentication, and the stats lens the
  tickets feed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.bridge-sync :as sync]
            [tik.cli-core :as core]
            [tik.forge :as forge]
            [tik.github :as gh]
            [tik.harness :as h]
            [tik.stage :as stage]
            [tik.stats :as stats]
            [tik.store.protocol :as store])
  (:import (java.time Instant)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn- run
  "A completed run fixture; `n` doubles as id and run number."
  ([n conclusion] (run n conclusion 1))
  ([n conclusion attempt]
   {:id (+ 1000 n) :run_number n :run_attempt attempt :conclusion conclusion
    :name "verify" :event "push" :head_sha (format "%040d" n)
    :display_title (str "commit " n)
    :actor {:login "dev"}
    :repository {:id 42 :full_name "metio/tik"}
    :html_url (str "https://github.com/metio/tik/actions/runs/" (+ 1000 n))
    :created_at (str (Instant/ofEpochSecond (+ 1700000000 (* n 3600))))
    :run_started_at (str (Instant/ofEpochSecond (+ 1700000000 (* n 3600) (* (dec attempt) 60))))
    :updated_at (str (Instant/ofEpochSecond (+ 1800000000 (* n 3600))))}))

(defn- streaks [cfg runs complete?]
  (forge/streaks (merge gh/conclusions cfg) (map gh/run runs) complete?))

(defn- tid [branch start] (forge/ticket-id "github.com" 42 7 branch start))

(deftest streaks_partition_on_green_runs
  (let [ss (streaks {} [(run 1 "success") (run 2 "failure") (run 3 "timed_out")
                        (run 4 "success") (run 5 "failure")]
                    false)]
    (is (= [[1002 1003] [1005]] (map #(map :id (:failed %)) ss)))
    (is (= [1004 nil] (map #(:id (:green %)) ss)))
    (is (every? :known-start? ss))))

(deftest cancelled_and_skipped_runs_neither_extend_nor_end_a_streak
  (let [[s & more] (streaks {} [(run 1 "success") (run 2 "failure") (run 3 "cancelled")
                                (run 4 "skipped") (run 5 "failure")]
                            false)]
    (is (empty? more))
    (is (= [1002 1005] (map :id (:failed s))))
    (is (nil? (:green s)))))

(deftest a_streak_reaching_past_the_fetched_history_has_no_known_start
  (testing "incomplete history: the first failure may not be the streak's start"
    (is (= [false] (map :known-start? (streaks {} [(run 7 "failure")] false)))))
  (testing "complete history: the workflow's first run starts the streak"
    (is (= [true] (map :known-start? (streaks {} [(run 7 "failure")] true))))))

(deftest runs_are_ordered_by_run_number_whatever_order_github_lists_them
  (is (= (streaks {} [(run 1 "success") (run 2 "failure") (run 3 "success")] false)
         (streaks {} [(run 3 "success") (run 1 "success") (run 2 "failure")] false))))

(deftest a_rerun_that_turned_green_is_reported
  (is (= [{:rerun (gh/run (run 2 "success" 2))}]
         (streaks {} [(run 1 "success") (run 2 "success" 2)] false))))

(deftest custom_conclusion_sets_apply
  (is (empty? (streaks {:failing #{"failure"}} [(run 1 "success") (run 2 "timed_out")] false))))

(deftest ticket_ids_are_a_function_of_the_streak_coordinates
  (is (= (tid "main" 1002) (tid "main" 1002)))
  (is (not= (tid "main" 1002) (tid "dev" 1002)))
  (is (not= (tid "main" 1002) (forge/ticket-id "git.example" 42 7 "main" 1002)))
  (is (= 8 (.version ^java.util.UUID (tid "main" 1002)))))

;; ---------------------------------------------------------------- sync

(defn- getter
  "A get-json stub serving `runs` (one workflow, id 7) newest first, paged."
  [runs]
  (fn [path {:keys [page per_page] :or {page 1 per_page 100}}]
    (case path
      "/repos/metio/tik/actions/workflows/7/runs"
      {:workflow_runs (->> runs (sort-by :run_number >)
                           (drop (* (dec page) per_page)) (take per_page) vec)}
      (throw (ex-info (str "unexpected path " path) {})))))

(defn- store-with-process! []
  (let [{:keys [root store]} (h/temp-store!)
        dest (io/file root "processes" "ci-failure.edn")]
    (io/make-parents dest)
    (io/copy (io/file "processes/ci-failure.edn") dest)
    {:root root :store store}))

(def ^:private since (Instant/ofEpochSecond 0))

(defn- sync! [{:keys [root store]} runs]
  (h/with-cli-root root
    (fn []
      (with-redefs [core/now (constantly (Instant/parse "2030-01-01T00:00:00Z"))]
        (sync/sync-github-workflow! store (getter runs) {} (sync/github-ctx {} {} {})
                                    {:repo "metio/tik" :branch "main" :workflow-id 7
                                     :since since}
                                    {})))))

(defn- event-ids [s]
  (set (for [id (store/ticket-ids s) e (store/events s id)] (:event/id e))))

(defn- current [{:keys [root store]} id]
  (h/with-cli-root root
    (fn []
      (let [{:keys [process events roles]} (core/ticket-ctx store id)
            t (Instant/parse "2030-01-01T00:00:00Z")]
        (stage/current-stages process (stage/effective-reached process events t roles))))))

(deftest two_replicas_syncing_the_same_history_mint_identical_events
  (let [history [(run 1 "success") (run 2 "failure") (run 3 "failure") (run 4 "success")
                 (run 5 "failure")]
        a (store-with-process!)
        b (store-with-process!)]
    (sync! a history)
    (sync! b (reverse history))
    (is (seq (event-ids (:store a))))
    (is (= (event-ids (:store a)) (event-ids (:store b))))
    (is (= 2 (count (store/ticket-ids (:store a)))))))

(deftest resyncing_is_a_no_op
  (let [st (store-with-process!)
        history [(run 1 "success") (run 2 "failure")]]
    (is (= [:ticket] (map :outcome (sync! st history))))
    (let [before (event-ids (:store st))]
      (is (empty? (sync! st history)))
      (is (= before (event-ids (:store st)))))))

(deftest github_rewriting_updated_at_mints_nothing_new
  (let [st (store-with-process!)
        history [(run 1 "success") (run 2 "failure") (run 3 "failure")]]
    (sync! st history)
    (let [before (event-ids (:store st))]
      (is (empty? (sync! st (map #(assoc % :updated_at "2031-06-01T00:00:00Z") history))))
      (is (= before (event-ids (:store st)))))))

(deftest a_ticket_is_dated_by_its_first_failed_run_and_recovers_at_the_green_attempt
  (let [st (store-with-process!)
        id (tid "main" 1002)]
    (sync! st [(run 1 "success") (run 2 "failure")])
    (sync! st [(run 1 "success") (run 2 "success" 2)])
    (let [es (store/events (:store st) id)
          at-of (fn [pred] (:event/at (first (filter pred es))))]
      (is (= (Instant/parse (:created_at (run 2 "failure")))
             (at-of #(= :ticket/create (:event/type %)))))
      (is (= (Instant/parse (:run_started_at (run 2 "success" 2)))
             (at-of #(= [:forge :green-run] (get-in % [:event/body :fact/path]))))))))

(deftest a_streak_grows_then_recovers
  (let [st (store-with-process!)
        id (tid "main" 1002)]
    (sync! st [(run 1 "success") (run 2 "failure")])
    (is (= #{:failing} (current st id)))
    (testing "a further failed run attaches to the same ticket"
      (is (= [:update] (map :outcome (sync! st [(run 1 "success") (run 2 "failure")
                                                (run 3 "failure")]))))
      (is (= 1 (count (store/ticket-ids (:store st))))))
    (testing "the next green run recovers it"
      (is (= [:recovered] (map :outcome (sync! st [(run 1 "success") (run 2 "failure")
                                                   (run 3 "failure") (run 4 "success")]))))
      (is (= #{:recovered} (current st id))))
    (testing "a later failure opens a new streak instead of reopening this one"
      (is (= [:ticket] (map :outcome (sync! st [(run 1 "success") (run 2 "failure")
                                                (run 3 "failure") (run 4 "success")
                                                (run 5 "failure")]))))
      (is (= 2 (count (store/ticket-ids (:store st))))))))

(deftest a_green_rerun_recovers_the_streak_it_opened
  (let [st (store-with-process!)
        id (tid "main" 1002)]
    (sync! st [(run 1 "success") (run 2 "failure")])
    (is (= [:recovered] (map :outcome (sync! st [(run 1 "success") (run 2 "success" 2)]))))
    (is (= #{:recovered} (current st id)))))

(deftest a_streak_with_an_unknown_start_opens_no_ticket
  (let [st (store-with-process!)
        page (mapv #(run % "failure") (range 1 101))]
    (is (= [:unknown-start]
           (map :outcome (h/with-cli-root (:root st)
                           (fn []
                             (sync/sync-github-workflow! (:store st)
                                                         (getter (conj page (run 0 "failure")))
                                                         {:max-pages 1}
                                                         (sync/github-ctx {} {} {})
                                                         {:repo "metio/tik" :branch "main"
                                                          :workflow-id 7 :since since}
                                                         {}))))))
    (is (empty? (store/ticket-ids (:store st))))))

(deftest streaks_older_than_the_lookback_are_left_alone
  (let [st (store-with-process!)]
    (is (empty? (h/with-cli-root (:root st)
                  (fn []
                    (sync/sync-github-workflow! (:store st)
                                                (getter [(run 1 "success") (run 2 "failure")
                                                         (run 3 "success")])
                                                {} (sync/github-ctx {} {} {})
                                                {:repo "metio/tik" :branch "main" :workflow-id 7
                                                 :since (Instant/parse "2029-01-01T00:00:00Z")}
                                                {})))))))

(deftest the_ticket_carries_the_metadata_stats_group_by
  (let [st (store-with-process!)
        id (tid "main" 1002)]
    (sync! st [(run 1 "success") (run 2 "failure") (run 3 "failure")])
    (let [{:keys [state]} (h/with-cli-root (:root st) #(core/ticket-ctx (:store st) id))
          fact #(get-in state [:facts % :value])]
      (is (= "github.com" (fact [:forge :host])))
      (is (= "metio/tik" (fact [:forge :repo])))
      (is (= "metio" (fact [:forge :owner])))
      (is (= :tik (fact [:repo])) "the same [:repo] a ticket made inside the checkout gets")
      (is (= "verify" (fact [:forge :workflow])))
      (is (= "main" (fact [:forge :branch])))
      (is (= "https://github.com/metio/tik/actions/runs/1002" (fact [:forge :first-run])))
      (is (= #{"ci/run/1002/1" "ci/run/1003/1"} (set (keys (:artifacts state))))))))

;; ---------------------------------------------------------------- webhook

(defn- sign [secret ^String body]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")))]
    (str "sha256=" (str/join (map #(format "%02x" (bit-and % 0xff))
                                  (.doFinal mac (.getBytes body "UTF-8")))))))

(deftest webhook_signatures_authenticate_the_body
  (let [body "{\"action\":\"completed\"}"
        bytes (.getBytes body "UTF-8")]
    (is (gh/valid-signature? "s3cret" bytes (sign "s3cret" body)))
    (is (not (gh/valid-signature? "s3cret" bytes (sign "other" body))))
    (is (not (gh/valid-signature? "s3cret" (.getBytes "{}" "UTF-8") (sign "s3cret" body))))
    (is (not (gh/valid-signature? "s3cret" bytes nil)))
    (is (not (gh/valid-signature? "" bytes (sign "x" body))) "an empty secret authenticates nothing")))

(deftest webhook_covers_configured_repositories_and_branches
  (let [watched? sync/watched?
        cfg {:repos ["metio/tik" {:repo "metio/jaas" :branches ["main" "next"]}]
             :orgs ["sebhoss"] :exclude ["sebhoss/scratch"]}]
    (is (watched? cfg "metio/tik" "main" "main"))
    (is (not (watched? cfg "metio/tik" "feature" "main")))
    (is (watched? cfg "metio/jaas" "next" "main"))
    (is (watched? cfg "sebhoss/anything" "main" "main"))
    (is (not (watched? cfg "sebhoss/scratch" "main" "main")))
    (is (not (watched? cfg "other/repo" "main" "main")))))

(deftest webhook_rejects_unsigned_deliveries_before_reading_them
  (let [handler (sync/github-webhook-handler nil (fn [& _] (throw (ex-info "no API" {})))
                                             {:repos ["metio/tik"]} {} {} "s3cret")
        req (fn [sig kind body]
              {:request-method :post
               :headers (cond-> {"x-github-event" kind}
                          sig (assoc "x-hub-signature-256" sig))
               :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))})]
    (is (= 401 (:status (handler (req nil "workflow_run" "{}")))))
    (is (= 401 (:status (handler (req (sign "wrong" "{}") "workflow_run" "{}")))))
    (is (= 200 (:status (handler (req (sign "s3cret" "{}") "ping" "{}")))))
    (is (= 204 (:status (handler (req (sign "s3cret" "{}") "push" "{}")))))))

;; ---------------------------------------------------------------- discovery

(defn- http-error [status path]
  (ex-info (str "GitHub API " status) {:reason :github/http :status status :path path}))

(defn- api
  "A get-json stub for `owner` answering from `routes` {path body}; any
  other path answers 404."
  [owner routes]
  (fn [path {:keys [page] :or {page 1}}]
    (cond
      (> page 1) []
      (contains? routes path) (get routes path)
      :else (throw (http-error 404 (str owner " " path))))))

(deftest targets_route_each_owner_through_its_own_getter
  (let [calls (atom [])
        getters {"metio" (api "metio" {"/orgs/metio/repos"
                                       [{:full_name "metio/tik" :default_branch "main"}
                                        {:full_name "metio/old" :default_branch "main" :archived true}]})
                 "sebhoss" (api "sebhoss" {"/users/sebhoss/repos"
                                           [{:full_name "sebhoss/public" :default_branch "main"
                                             :owner {:login "sebhoss"}}]
                                           "/user/repos"
                                           [{:full_name "sebhoss/private" :default_branch "trunk"
                                             :owner {:login "sebhoss"}}
                                            {:full_name "metio/tik" :default_branch "main"
                                             :owner {:login "metio"}}]})}
        get-json-for (fn [owner] (swap! calls conj owner) (getters owner))]
    (is (= [{:repo "metio/tik" :branches ["main"]}
            {:repo "sebhoss/private" :branches ["trunk"]}
            {:repo "sebhoss/public" :branches ["main"]}]
           (gh/targets get-json-for {:orgs ["metio" "sebhoss"]}
                       (fn [w e] (throw (ex-info "unexpected" {:w w} e))))))
    (is (= #{"metio" "sebhoss"} (set @calls)))))

(deftest a_user_owner_without_its_own_token_lists_public_repositories
  (let [get-json (fn [path q]
                   (if (= "/user/repos" path)
                     (throw (http-error 401 path))
                     ((api "sebhoss" {"/users/sebhoss/repos"
                                      [{:full_name "sebhoss/public" :default_branch "main"}]})
                      path q)))]
    (is (= [{:repo "sebhoss/public" :branches ["main"]}]
           (gh/targets (constantly get-json) {:orgs ["sebhoss"]} (fn [_ e] (throw e)))))))

(deftest one_failing_owner_skips_only_that_owner
  (let [errors (atom [])
        get-json-for {"metio" (api "metio" {"/orgs/metio/repos"
                                            [{:full_name "metio/tik" :default_branch "main"}]})
                      "broken" (fn [path _] (throw (http-error 403 path)))}]
    (is (= [{:repo "metio/tik" :branches ["main"]}]
           (gh/targets get-json-for {:orgs ["broken" "metio"]
                                     :repos ["broken/repo"]}
                       (fn [what _] (swap! errors conj what)))))
    (is (= ["broken" "broken/repo"] @errors))))

;; ---------------------------------------------------------------- stats

(deftest stats_group_count_and_time_to_stage
  (let [h 3600000
        rows [{:facts {[:repo] "a" [:cause] :flaky} :created-ms 0 :reached-ms {:resolved (* 2 h)}}
              {:facts {[:repo] "a" [:cause] :code} :created-ms 0 :reached-ms {:resolved (* 4 h)}}
              {:facts {[:repo] "a"} :created-ms 0 :reached-ms {}}
              {:facts {[:repo] "b" [:cause] :flaky} :created-ms h :reached-ms {:resolved (* 2 h)}}]
        [a b] (stats/group-stats rows {:by [:repo] :split [:cause] :until :resolved})]
    (is (= {:group "a" :tickets 3 :reached 2 :open 1
            :split {:flaky 1 :code 1 nil 1}
            :duration {:n 2 :mean-ms (* 3 h) :median-ms (* 2 h) :p90-ms (* 4 h) :max-ms (* 4 h)}}
           a))
    (is (= "b" (:group b)))
    (is (= h (get-in b [:duration :median-ms])))
    (testing "without :until only counts are reported"
      (is (= [{:group "a" :tickets 3} {:group "b" :tickets 1}]
             (stats/group-stats rows {:by [:repo]}))))))
