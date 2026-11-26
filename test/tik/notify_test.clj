;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.notify-test
  "The notifier's decisions as pure functions: the stage diff, the cursor,
  coalescing, subscriptions, the rate limit and the hint payload."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.event :as event]
            [tik.notify :as n]
            [tik.process :as process]
            [tik.select :as select]
            [tik.stage :as stage])
  (:import (java.time Instant)))

;; ------------------------------------------------------------ a process to derive

(def ^:private proc
  "Branching and sticky stages over three facts, so a random log moves a
  ticket forwards, sideways and back."
  {:process/id :notify-test
   :process/version 1
   :process/guard-vocab 2
   :process/purpose "stages for the notifier's property tests"
   :process/roles {:r {:members ["seb"]}}
   :process/facts {[:f0] [:enum :a :b] [:f1] [:enum :a :b] [:f2] [:enum :a :b]}
   :process/stages [{:stage/id :open :guards []}
                    {:stage/id :triaged :after [:open] :guards [[:fact [:f0]]]}
                    {:stage/id :blocked :after [:open] :guards [[:fact= [:f1] :b]]}
                    {:stage/id :accepted :after [:triaged] :guards [[:fact= [:f0] :a]]}
                    {:stage/id :done :after [:accepted] :stage/sticky? true
                     :guards [[:fact [:f2]]]}]})

(def ^:private roles (process/resolve-roles {:r {:members ["seb"]}} {}))
(def ^:private ^Instant start (Instant/parse "2026-11-27T10:00:00Z"))
(def ^:private tickets
  [#uuid "018f2f6e-7c1a-7000-8000-0000000000a1"
   #uuid "018f2f6e-7c1a-7000-8000-0000000000a2"
   #uuid "018f2f6e-7c1a-7000-8000-0000000000a3"])

(def ^:private gen-step
  (gen/tuple (gen/elements tickets)
             (gen/elements [:assert :assert :retract])
             (gen/elements [[:f0] [:f1] [:f2]])
             (gen/elements [:a :b])
             (gen/elements ["seb" "claude" "metio-claude"])))

(defn- logs-after
  "Every ticket's events after each step of `steps`: a vector of
  {ticket events} snapshots, the first holding only the create events."
  [steps]
  (let [creates (into {} (for [t tickets]
                           [t [(event/create-ticket {:ticket t :actor "seb" :at start
                                                     :title (str "ticket " t)
                                                     :process :notify-test})]]))]
    (reductions
     (fn [logs [i [t op path value actor]]]
       (let [prev (get logs t)
             at (.plusSeconds start (* 60 (inc i)))
             parents #{(:event/id (peek prev))}
             e (case op
                 :assert (event/assert-fact {:ticket t :actor actor :at at :parents parents
                                             :path path :value value})
                 :retract (event/retract-fact {:ticket t :actor actor :at at :parents parents
                                               :path path}))]
         (assoc logs t (conj prev e))))
     creates
     (map-indexed vector steps))))

(defn- view
  "The view tik.notify/stage-diffs reads, derived by the kernel."
  [logs]
  (into {} (for [[t evs] logs
                 :let [now (.plusSeconds start 86400)
                       reached (stage/effective-reached proc evs now roles)]]
             [t {:current (set (stage/current-stages proc reached))
                 :title (str "ticket " t)}])))

(defspec diff_reports_exactly_the_tickets_whose_derived_stages_moved 100
  (prop/for-all [steps (gen/vector gen-step 0 25)]
    (let [views (map view (logs-after steps))]
      (every? true?
              (for [[a b] (partition 2 1 views)
                    :let [diffs (n/stage-diffs a b)
                          by-id (into {} (map (juxt :ticket identity)) diffs)]
                    t tickets]
                (if (= (get-in a [t :current]) (get-in b [t :current]))
                  (not (contains? by-id t))
                  (= {:from (get-in a [t :current]) :to (get-in b [t :current])}
                     (select-keys (by-id t) [:from :to]))))))))

(defspec coalescing_a_run_of_diffs_equals_the_diff_across_the_run 100
  (prop/for-all [steps (gen/vector gen-step 0 25)]
    (let [views (vec (map view (logs-after steps)))
          co (reduce (fn [co [i [a b]]]
                       (reduce #(n/enqueue %1 (n/hint "s" %2) i) co (n/stage-diffs a b)))
                     {}
                     (map-indexed vector (partition 2 1 views)))
          [_ due] (n/drain co 1000000 1)
          expected (for [d (n/stage-diffs (first views) (peek views))] (n/hint "s" d))]
      (= (set (map #(dissoc % :actor) expected))
         (set (map #(dissoc % :actor) due))))))

(defspec a_hint_carries_only_its_keys_whatever_the_diff_holds 200
  (prop/for-all [extra (gen/map gen/keyword gen/any-equatable {:max-elements 4})
                 title gen/string
                 actor gen/string]
    (let [h (n/hint "metio" (merge extra {:ticket (first tickets) :title title
                                          :from #{:open} :to #{:done} :actor actor}))]
      (and (= n/hint-keys (set (keys h)))
           (every? #(or (string? %) (and (vector? %) (every? string? %))) (vals h))
           (not (re-find #"[\n\r‮]" (str (:title h) (:actor h))))))))

;; ------------------------------------------------------------ advance and the cursor

(defn- obs [fp current events & {:keys [verified? why] :or {verified? true}}]
  {:fp fp :current current :title "t" :verified? verified? :why why
   :events (mapv (fn [[id actor]] {:id id :actor actor}) events)})

(deftest a_baseline_records_and_reports_nothing
  (let [{:keys [cursor diffs refused]} (n/advance nil {"t1" (obs "f1" #{:open} [["e1" "seb"]])} true)]
    (is (empty? diffs))
    (is (empty? refused))
    (is (= {"t1" {:fp "f1" :events ["e1"] :current #{:open}}} cursor))))

(deftest a_verified_change_diffs_against_the_cursor_and_names_the_newest_actor
  (let [cursor {"t1" {:fp "f1" :events ["e1"] :current #{:open}}}
        {:keys [diffs]} (n/advance cursor {"t1" (obs "f2" #{:done}
                                                     [["e1" "seb"] ["e2" "claude"] ["e3" "metio-claude"]])}
                                   false)]
    (is (= [{:ticket "t1" :title "t" :from #{:open} :to #{:done} :actor "metio-claude"}] diffs))))

(deftest a_new_ticket_moves_from_no_stage
  (is (= [{:ticket "t2" :title "t" :from #{} :to #{:open} :actor "seb"}]
         (:diffs (n/advance {} {"t2" (obs "f" #{:open} [["e1" "seb"]])} false)))))

(deftest an_unverified_change_sends_nothing_and_keeps_the_last_verified_stages
  (let [cursor {"t1" {:fp "f1" :events ["e1"] :current #{:open}}}
        r (n/advance cursor {"t1" (obs "f2" #{:done} [["e1" "seb"] ["e2" "mallory"]]
                                       :verified? false :why "bad signature")}
                     false)]
    (is (empty? (:diffs r)))
    (is (= [{:ticket "t1" :why "bad signature"}] (:refused r)))
    (is (= {:fp "f2" :events ["e1"] :current #{:open}} (get-in r [:cursor "t1"]))
        "the fingerprint moves so the bytes are not judged again; the stages do not")
    (testing "the next verified change diffs against the verified stages"
      (is (= [{:ticket "t1" :title "t" :from #{:open} :to #{:triaged} :actor "seb"}]
             (:diffs (n/advance (:cursor r) {"t1" (obs "f3" #{:triaged}
                                                       [["e1" "seb"] ["e2" "mallory"] ["e3" "seb"]])}
                                false)))))))

(deftest only_changed_fingerprints_are_derived_again
  (is (= ["b" "c"] (n/changed-tickets {"a" {:fp "1"} "b" {:fp "2"}} {"a" "1" "b" "3" "c" "4"}))))

(deftest the_cursor_survives_a_round_trip_and_garbage_reads_as_none
  (let [c {"metio" {"t1" {:fp "f" :events ["e1" "e2"] :current #{:open :blocked}}}}]
    (is (= c (n/edn->cursor (read-string (pr-str (n/cursor->edn c)))))))
  (doseq [junk [nil 42 "x" {} {:tik/notify-cursor 2 :stores {}} {:tik/notify-cursor 1 :stores []}]]
    (is (= {} (n/edn->cursor junk)))))

;; ------------------------------------------------------------ coalescing

(defn- h [ticket from to & [actor]]
  {:store "s" :ticket ticket :title "t" :from from :to to :actor (or actor "seb")})

(deftest a_burst_gives_one_hint_per_ticket_per_window
  (let [co (-> {}
               (n/enqueue (h "t1" ["open"] ["triaged"]) 0)
               (n/enqueue (h "t1" ["triaged"] ["accepted"] "claude") 100)
               (n/enqueue (h "t2" [] ["open"]) 200)
               (n/enqueue (h "t1" ["accepted"] ["done"] "metio-claude") 300))]
    (testing "nothing leaves before its window"
      (is (empty? (second (n/drain co 999 1000)))))
    (testing "t1 leaves once, from where it started to where it ended"
      (let [[co' due] (n/drain co 1000 1000)]
        (is (= [(h "t1" ["open"] ["done"] "metio-claude")] due))
        (is (= [(h "t2" [] ["open"])] (second (n/drain co' 1200 1000))))))))

(deftest a_ticket_that_returns_where_it_started_sends_nothing
  (let [co (-> {} (n/enqueue (h "t1" ["open"] ["blocked"]) 0)
               (n/enqueue (h "t1" ["blocked"] ["open"]) 10))]
    (is (= [] (second (n/drain co 5000 1000))))
    (is (empty? (:pending (first (n/drain co 5000 1000)))))))

;; ------------------------------------------------------------ subscriptions

(deftest a_subscription_expires_after_its_time_to_live
  (let [[reg why] (n/subscribe {} "a" {:store "s" :selector "" :ttl-ms 1000} #{"s"} 0)]
    (is (nil? why))
    (is (= ["a"] (n/recipients reg (h "t" [] ["open"]) #{""} 999)))
    (is (= [] (n/recipients reg (h "t" [] ["open"]) #{""} 1000)))
    (let [[reg' gone] (n/expire reg 1000)]
      (is (= ["a"] gone))
      (is (empty? (:subs reg'))))))

(deftest the_time_to_live_is_bounded
  (let [[reg] (n/subscribe {} "a" {:store "s" :ttl-ms (* 10 n/max-ttl-ms)} #{"s"} 0)
        [reg2] (n/subscribe {} "b" {:store "s"} #{"s"} 0)]
    (is (= n/max-ttl-ms (get-in reg [:subs "a" :expires-ms])))
    (is (= n/default-ttl-ms (get-in reg2 [:subs "b" :expires-ms]))))
  (is (some? (second (n/subscribe {} "a" {:store "s" :ttl-ms -5} #{"s"} 0)))))

(deftest a_subscriber_never_hears_of_a_store_it_may_not_read
  (let [[reg why] (n/subscribe {} "a" {:store "infra.run" :selector ""} #{"metio"} 0)]
    (is (= {} reg))
    (is (str/includes? why "infra.run")))
  (let [[reg] (n/subscribe {} "a" {:store "metio" :selector ""} #{"metio"} 0)]
    (is (= [] (n/recipients reg (assoc (h "t" [] ["open"]) :store "infra.run") #{""} 1)))))

(deftest recipients_need_their_selector_to_match
  (let [[reg] (n/subscribe {} "a" {:store "s" :selector "stage=:done"} #{"s"} 0)
        [reg] (n/subscribe reg "b" {:store "s" :selector ""} #{"s"} 0)
        [reg] (n/subscribe reg "c" {:store "s" :selector "" :permanent? true} #{"s"} 0)]
    (is (= ["b" "c"] (n/recipients reg (h "t" [] ["open"]) #{""} 10)))
    (is (= ["a" "b" "c"] (n/recipients reg (h "t" [] ["done"]) #{"" "stage=:done"} 10)))
    (is (= ["c"] (n/recipients reg (h "t" [] ["open"]) #{""} (* 2 n/max-ttl-ms)))
        "a permanent subscription never expires")))

;; ------------------------------------------------------------ rate limit

(deftest the_rate_limit_spends_a_burst_then_refills
  (let [rate {:per-ms 1000 :burst 3}
        step (fn [[b acc] now] (let [[b' ok?] (n/take-token b "a" now rate)] [b' (conj acc ok?)]))
        [b allowed] (reduce step [{} []] [0 1 2 3 4])]
    (is (= [true true true false false] allowed))
    (is (= [true false] (second (reduce step [b []] [1000 1001]))))
    (is (true? (second (n/take-token b "other" 4 rate))) "every subscriber has its own bucket")))

;; ------------------------------------------------------------ renderings and requests

(deftest titles_cannot_forge_lines_or_reorder_text
  (is (= "a b" (n/clean-text "a\n\r\tb" 50)))
  (is (= "evilgood" (n/clean-text "evil‮good⁦" 50)))
  (is (= "" (n/clean-text {:not "a string"} 50)))
  (is (= 10 (count (n/clean-text (str/join (repeat 50 "x")) 10)))))

(deftest a_command_sink_argument_stays_one_argument
  (is (= ["notify-send" "tik" "t1 \"$(rm -rf ~)\": open -> done (by seb)" "$(rm -rf ~)"]
         (n/render-argv ["notify-send" "tik" "{line}" "{title}"]
                        {:store "s" :ticket "t1" :title "$(rm -rf ~)" :from ["open"]
                         :to ["done"] :actor "seb"}))))

(deftest the_channel_event_has_identifier_meta_keys_only
  (let [ev (n/channel-notification (h "t1" ["open"] ["done"]))]
    (is (= "notifications/claude/channel" (:method ev)))
    (is (every? #(re-matches #"[A-Za-z_][A-Za-z0-9_]*" (name %)) (keys (get-in ev [:params :meta]))))
    (is (every? string? (vals (get-in ev [:params :meta]))))))

(deftest subscribe_requests_are_checked_before_they_count
  (let [p #(n/parse-subscribe % select/compile)]
    (is (= {:request {:store "metio" :selector "stage=:done" :ttl-ms 60000}}
           (p {"op" "subscribe" "store" "metio" "selector" "stage=:done" "ttl_seconds" 60})))
    (is (:refused (p {"op" "publish" "store" "metio"})))
    (is (:refused (p {"op" "subscribe"})))
    (is (:refused (p {"op" "subscribe" "store" "m" "ttl_seconds" -1})))
    (is (:refused (p {"op" "subscribe" "store" "m" "selector" 7})))
    (is (:refused (p {"op" "subscribe" "store" "m" "selector" "bogus=1"})))
    (is (:refused (p ["not" "a" "map"])))))

(deftest the_socket_directory_must_be_private_and_ours
  (is (nil? (n/socket-dir-problem {:exists? true :owner "seb" :me "seb" :perms "rwx------"})))
  (is (nil? (n/socket-dir-problem {:exists? false})))
  (is (some? (n/socket-dir-problem {:exists? true :owner "eve" :me "seb" :perms "rwx------"})))
  (is (some? (n/socket-dir-problem {:exists? true :owner "seb" :me "seb" :perms "rwxr-x---"})))
  (is (some? (n/socket-dir-problem {:exists? true :owner "seb" :me "seb" :perms "rwx-----x"}))))
