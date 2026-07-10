;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.author-test
  (:require [clojure.edn]
            [clojure.test :refer [deftest is testing]]
            [tik.author :as author]
            [tik.process :as process]))

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
    (is (empty? (filter #(= :error (:level %)) (process/lint d)))
        (pr-str (process/lint d)))))

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
  (is (nil? (author/parse-duration "soonish"))))

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
      (is (empty? (filter #(= :error (:level %)) (process/lint d)))
          (str tname ": " (pr-str (process/lint d))))
      (is (seq (author/terminal-stages d)) tname)
      (is (:purpose answers) tname))))

(deftest the_builtin_track_process_matches_the_shipped_file
  (is (= author/track-process
         (clojure.edn/read-string (slurp "processes/track.edn")))
      "one definition, two homes — they must never drift")
  (is (empty? (filter #(= :error (:level %))
                      (process/lint author/track-process)))))

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
