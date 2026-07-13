;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.author-test
  (:require [clojure.edn]
            [clojure.test :refer [deftest is testing]]
            [tik.author :as author]
            [tik.lint :as lint]))

(def answers
  {:name "expense-approval"
   :purpose "reimburse employees without surprises"
   :stages [{:name "submitted"
             :purpose "the claim exists with its evidence"
             :after []
             :needs [{:kind :fact :path [:amount] :type :number}
                     {:kind :choice :path [:category]
                      :values [:travel :equipment]}
                     {:kind :file :prefix "receipts/"}]}
            {:name "approved"
             :purpose "a manager stands behind the spend"
             :after ["submitted"]
             :needs [{:kind :signature :role :manager}]}
            {:name "paid"
             :purpose "the money moved"
             :after ["approved"]
             :needs [{:kind :fact :path [:payment :ref]}
                     {:kind :waited :duration "PT24H"}]}]
   :roles {"manager" ["alice" "bob"]}})

(deftest built_definition_lints_clean
  (let [d (author/build-process answers)]
    (is (empty? (filter #(= :error (:level %)) (lint/lint d)))
        (pr-str (lint/lint d)))))

(deftest interview_answers_compile_to_the_closed_basis
  (let [d (author/build-process answers)
        guards (mapcat :guards (:process/stages d))]
    (testing "facts and choices declare their types"
      (is (= :int (get-in d [:process/facts [:amount]])))
      (is (= [:enum :travel :equipment]
             (get-in d [:process/facts [:category]]))))
    (testing "a bare signature compiles to a signed decision fact"
      (is (some #{[:signed-by :manager [:approval :manager]]} guards))
      (is (some #{[:fact= [:approval :manager] :approved]} guards))
      (is (= [:enum :approved :rejected]
             (get-in d [:process/facts [:approval :manager]]))))
    (testing "waiting compiles to :elapsed-since ticket creation"
      (is (some #{[:elapsed-since :ticket/create "PT24H"]} guards)))))

(deftest friendly_durations
  (is (= "PT30M" (author/parse-duration "30m")))
  (is (= "PT48H" (author/parse-duration "48h")))
  (is (= "P7D" (author/parse-duration "7d")))
  (is (= "PT2H30M" (author/parse-duration "pt2h30m")))
  (is (nil? (author/parse-duration "soonish")))
  (is (nil? (author/parse-duration "Pfoo"))
      "an invalid ISO-8601 P-form is rejected, not passed through to lint"))

(deftest tests_skeleton_covers_every_outcome
  (let [d (author/build-process answers)
        sk (author/tests-skeleton d "expense-approval.edn")]
    (is (= [:paid] (author/terminal-stages d)))
    (is (= 1 (count (:test/cases sk))))
    (is (= #{:paid} (get-in sk [:test/cases 0 :case/expect :includes])))))

(deftest runbook_stubs_speak_the_interview's_words
  (let [stubs (author/runbook-stubs answers)
        d (author/with-runbook-hints (author/build-process answers)
            "expense-approval")]
    (is (= 3 (count stubs)))
    (is (re-find #"a manager stands behind the spend"
                 (get stubs "kb/runbooks/expense-approval-approved.md")))
    (is (re-find #"role records and signs their approval"
                 (get stubs "kb/runbooks/expense-approval-approved.md")))
    (testing "every stage's :hint points at a generated stub"
      (is (every? #(contains? stubs (:hint %)) (:process/stages d))))))

(deftest every_template_builds_a_lintable_process
  (doseq [[tname answers] author/templates]
    (let [d (author/build-process answers)]
      (is (empty? (filter #(= :error (:level %)) (lint/lint d)))
          (str tname ": " (pr-str (lint/lint d))))
      (is (seq (author/terminal-stages d)) tname)
      (is (:purpose answers) tname))))

(deftest the_builtin_track_process_matches_the_shipped_file
  (is (= author/track-process
         (clojure.edn/read-string (slurp "processes/track.edn")))
      "one definition, two homes — they must never drift")
  (is (empty? (filter #(= :error (:level %))
                      (lint/lint author/track-process)))))

(deftest scripted_interview_produces_the_same_answers
  (let [lines (atom ["tiny" "just one stage"          ; name, purpose
                     "done" "it is done"              ; stage 1 name+purpose
                     "i" "note" "n"                   ; a text fact
                     ""                               ; done with needs
                     ""])                             ; done with stages
        in #(let [[l & more] @lines] (reset! lines more) l)
        out (fn [_])]
    (is (= {:name "tiny" :purpose "just one stage"
            :stages [{:name "done" :purpose "it is done" :after []
                      :needs [{:kind :fact :path [:note]}]}]
            :roles {}}
           (author/interview in out)))))

(def llm-answers
  "A real LLM draft: schema-valid, smelly in every classic way."
  {:name "dependabot-to-renovate-migration"
   :stages [{:name "plan" :after [] :needs []}
            {:name "in-progress" :after ["plan"]
             :needs [{:kind :fact :path [:renovate :json-created]}
                     {:kind :fact :path [:renovate :uses-org-wide-config]}]}
            {:name "empty-mid" :after ["plan"] :needs []}
            {:name "done" :after ["in-progress"]
             :needs [{:kind :signature :role :developer}]}]
   :roles {"developer" ["developer"]}})

(deftest check_finds_the_classic_llm_smells
  (let [msgs (map :msg (author/check llm-answers))]
    (testing "flag-shaped facts, suffix and prefix spellings"
      (is (some #(re-find #"json-created.*yes/no" %) msgs))
      (is (some #(re-find #"uses-org-wide-config" %) msgs)))
    (testing "activity-named stage"
      (is (some #(re-find #"in-progress.*activity" %) msgs)))
    (testing "needless mid-chain stage; the needless START stage is fine"
      (is (some #(re-find #"empty-mid.*no needs" %) msgs))
      (is (not-any? #(re-find #"'plan'" %) msgs)))
    (testing "placeholder role members"
      (is (some #(re-find #"placeholder members" %) msgs)))
    (testing "all of it is advisory, none of it fatal"
      (is (not-any? #(= :error (:level %)) (author/check llm-answers))))))

(deftest check_rejects_broken_answers_loudly
  (testing "schema violations are errors"
    (is (= :error (:level (first (author/check {:name "Bad Name"
                                                :stages []}))))))
  (testing "unknown :after targets are errors"
    (is (some #(and (= :error (:level %))
                    (re-find #"unknown stage 'ghost'" (:msg %)))
              (author/check {:name "x"
                             :stages [{:name "a" :after ["ghost"]}]}))))
  (testing "the clean templates stay clean apart from change-me"
    (doseq [[tname answers] author/templates]
      (is (not-any? #(= :error (:level %)) (author/check answers)) tname))))

(deftest rules_are_data_with_org_overrides
  (testing "built-ins apply by default"
    (is (= 2 (count author/default-rules))))
  (testing "an org adds a rule; it fires like a built-in"
    (let [rules (author/merge-rules
                 {:rules [{:id :no-temp :on :fact-name :match "^temp-"
                           :level :warning
                           :msg "is temporary by its own admission"}]})
          findings (author/check
                    {:name "x"
                     :stages [{:name "a" :after []
                               :needs [{:kind :fact :path [:temp-note]}]}]}
                    rules)]
      (is (some #(re-find #"temporary by its own admission" (:msg %))
                findings))))
  (testing "an org disables a built-in by id"
    (let [rules (author/merge-rules {:disable [:flag-facts]})
          findings (author/check
                    {:name "x"
                     :stages [{:name "a" :after []
                               :needs [{:kind :fact :path [:json-created]}]}]}
                    rules)]
      (is (not-any? #(re-find #"yes/no" (:msg %)) findings))))
  (testing "an org replaces a built-in wholesale, same id"
    (let [rules (author/merge-rules
                 {:rules [{:id :flag-facts :on :fact-name :match "^flagged-"
                           :level :error :msg "house style forbids this"}]})]
      (is (= 2 (count rules)))
      (is (some #(= :error (:level %))
                (author/check {:name "x"
                               :stages [{:name "a" :after []
                                         :needs [{:kind :fact
                                                  :path [:flagged-item]}]}]}
                              rules))))))
