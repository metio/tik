;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.review-test
  "tik review offers a ticket only when the reviewer alone completes its
  next stage: every missing reason is the reviewer's signature on a
  recorded fact or a fact the reviewer passes, and the dry run lands the
  stage without losing one. The end-to-end cases run the command against
  a store with a real key."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.harness :as h]
            [tik.review :as review])
  (:import (java.time Instant)))

(def ^:private t (Instant/parse "2026-11-19T12:00:00Z"))

(def ^:private change
  {:process/id :change :process/version 1
   :process/roles {:maintainer {:members ["seb"]}}
   :process/facts {[:merge :commit] [:string {:min 7}]
                   [:review :verdict] [:enum :approved :follow-up]
                   [:note] :string}
   :process/stages
   [{:stage/id :merged :guards [[:fact [:merge :commit]]]}
    {:stage/id :reviewed :after [:merged] :stage/sticky? true
     :guards [[:fact [:review :verdict]]
              [:signed-by :maintainer [:review :verdict]]
              [:signed-by :maintainer [:merge :commit]]]}]})

(def ^:private roles {:maintainer {:members ["seb"]}})

(def ^:private tid (java.util.UUID/fromString "018f2f6e-7c1a-7000-8000-000000000001"))

(defn- log
  "A ticket's events: the create, then each [actor op path value] in order."
  [& steps]
  (reduce (fn [evs [i [who op path value]]]
            (let [arg {:ticket tid :actor who :parents #{(:event/id (peek evs))}
                       :at (Instant/parse (format "2026-11-19T10:%02d:00Z" (inc i)))
                       :path path}]
              (conj evs (case op
                          :set (event/assert-fact (assoc arg :value value))
                          :dispute (event/dispute-fact (assoc arg :reason value))))))
          [(event/create-ticket {:ticket tid :actor "claude" :title "x"
                                 :at (Instant/parse "2026-11-19T10:00:00Z")
                                 :process :change})]
          (map-indexed vector steps)))

(def ^:private merged (log ["claude" :set [:merge :commit] "46db877"]))

(def ^:private verdict {[:review :verdict] :approved})

(deftest the_reviewer_re_signs_the_recorded_commit_and_adds_the_verdict
  (let [{:keys [stage steps]} (review/plan change merged t roles "seb" verdict)]
    (is (= :reviewed stage))
    (is (= #{[[:review :verdict] :approved] [[:merge :commit] "46db877"]}
           (set steps)))))

(deftest a_stage_missing_a_value_nobody_passed_is_not_offered
  (is (nil? (review/plan change merged t roles "seb" {}))))

(deftest someone_outside_the_role_is_offered_nothing
  (is (nil? (review/plan change merged t roles "claude" verdict))))

(deftest a_reason_beyond_the_reviewer_keeps_the_ticket_back
  (testing "an unmerged change misses a commit the reviewer cannot supply"
    (is (nil? (review/plan change (log) t roles "seb" verdict))))
  (testing "a disputed commit needs a judgment, not a re-signature"
    (is (nil? (review/plan change
                           (log ["claude" :set [:merge :commit] "46db877"]
                                ["mari" :dispute [:merge :commit] "wrong commit"])
                           t roles "seb" verdict)))))

(deftest a_plan_that_would_lose_a_stage_is_not_offered
  (let [proc (update change :process/stages conj
                     {:stage/id :awaiting :after [:merged]
                      :guards [[:not [:fact [:review :verdict]]]]})]
    (is (nil? (review/plan proc merged t roles "seb" verdict)))))

(deftest a_reviewed_ticket_waits_for_nothing
  (let [done (log ["claude" :set [:merge :commit] "46db877"]
                  ["seb" :set [:merge :commit] "46db877"]
                  ["seb" :set [:review :verdict] :approved])]
    (is (nil? (review/plan change done t roles "seb" verdict)))))

;; ------------------------------------------------------------ end to end

(defn- store-with-key!
  "A store holding the change process, with seb's key registered in
  actors. Returns [root key-file]."
  []
  (let [root (h/temp-dir! "tik-review")
        key (io/file root "seb_key")]
    (.mkdirs (io/file root "tickets"))
    (io/make-parents (io/file root "processes" "x"))
    (spit (io/file root "processes" "change.edn")
          (pr-str (assoc change :lint {:runbooks :off}
                         :process/purpose "one change, merged and reviewed")))
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "seb" "-f" (str key))
    (spit (io/file root "actors")
          (str "seb namespaces=\"tik-*\" "
               (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str key)))) "\n"))
    [root key]))

(defn- run [root who & args]
  (apply h/run-tik! {:root root :dir root :actor who :env {"NO_COLOR" "1" "TIK_KEY" ""}} args))

(deftest review_lists_signs_and_then_finds_nothing
  (let [[root key] (store-with-key!)
        id (str/trim (first (str/split-lines (:out (run root "claude" "new" "change" "--title" "a change")))))
        _ (h/tik! {:root root :dir root :actor "claude"} "set" id "merge.commit=46db877")
        listed (:out (run root "seb" "review" "review.verdict=approved" "--list"))]
    (testing "the list names the definition and the ticket with every value it signs"
      (is (str/includes? listed "definition change"))
      (is (str/includes? listed (subs id 0 8)))
      (is (str/includes? listed "merge.commit = \"46db877\""))
      (is (str/includes? listed "review.verdict = :approved")))
    (testing "a filter for another ticket leaves this one out"
      (is (not (str/includes? (:out (run root "seb" "review" "review.verdict=approved"
                                         "ffffffff" "--list"))
                              (subs id 0 8)))))
    (testing "--list signs nothing"
      (is (str/includes? (:out (run root "seb" "status" id)) "stage:   merged")))
    (let [r (run root "seb" "review" "review.verdict=approved" "--yes" "--key" key)]
      (is (zero? (:exit r)) (:err r))
      (is (str/includes? (:out r) "published change"))
      (is (str/includes? (:out r) (str "approved change " (subs id 0 8) " → reviewed"))))
    (testing "the stage derives from seb's signatures, and the store verifies"
      (let [status (:out (run root "seb" "status" id))]
        (is (str/includes? status "stage:   reviewed"))
        (is (str/includes? status "[:merge :commit] = \"46db877\" (by seb)")))
      (is (zero? (:exit (run root "seb" "verify")))))
    (is (str/includes? (:out (run root "seb" "review" "review.verdict=approved" "--list"))
                       "nothing waits for seb"))))

(deftest yes_before_an_id_keeps_the_id_as_the_filter
  (let [[root key] (store-with-key!)
        _ (run root "seb" "review" "--yes" "--key" key)
        mint (fn [title]
               (let [id (str/trim (first (str/split-lines (:out (run root "claude" "new" "change" "--title" title)))))]
                 (h/tik! {:root root :dir root :actor "claude"} "set" id "merge.commit=46db877")
                 id))
        a (mint "first change")
        b (mint "second change")
        r (run root "seb" "review" "review.verdict=approved" "--yes" (subs a 0 8) "--key" key)]
    (is (zero? (:exit r)) (:err r))
    (is (str/includes? (:out r) (str "approved change " (subs a 0 8) " → reviewed")))
    (is (not (str/includes? (:out r) (subs b 0 8))))
    (is (str/includes? (:out (run root "seb" "status" a)) "stage:   reviewed"))
    (is (str/includes? (:out (run root "seb" "status" b)) "stage:   merged")
        "the ticket the filter left out stays unsigned")))

(deftest review_without_the_verdict_offers_only_the_definition
  (let [[root _] (store-with-key!)
        id (str/trim (first (str/split-lines (:out (run root "claude" "new" "change" "--title" "a change")))))
        _ (h/tik! {:root root :dir root :actor "claude"} "set" id "merge.commit=46db877")
        out (:out (run root "seb" "review" "--list"))]
    (is (str/includes? out "definition change"))
    (is (not (str/includes? out (subs id 0 8))))))

(deftest publishing_a_definition_needs_a_key
  (let [[root _] (store-with-key!)
        r (run root "seb" "review" "--yes")]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "needs a key"))))
