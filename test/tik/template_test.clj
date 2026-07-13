;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.template-test
  "The declarative expander, verified: substitution and conditional
  splicing produce the right definition, params are validated against
  the malli spec, and the result contains no markers. The closed,
  two-marker language is what keeps this a pure data transform rather
  than an evaluator."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [tik.process]
            [tik.template :as tmpl]))

(def ^:private expense
  {:tik/params [:map
                [:approvers [:vector :string]]
                [:with-legal {:optional true} :boolean]]
   :tik/template
   {:process/id :expense-approval :process/version 1 :process/guard-vocab 1
    :process/roles {:approver {:members [:tik/param :approvers]}
                    :legal {:members []}}
    :process/facts {[:amount] [:int]}
    :process/stages
    [{:stage/id :submitted :guards [[:fact [:amount]]]}
     {:stage/id :approved :after [:submitted] :guards [[:signed-by :approver]]}
     [:tik/when :with-legal
      {:stage/id :legal-cleared :after [:approved]
       :guards [[:signed-by :legal]]}]]}})

(defn- has-marker? [x]
  (let [found (atom false)]
    (walk/postwalk (fn [n] (when (and (vector? n) (#{:tik/param :tik/when} (first n)))
                             (reset! found true))
                     n)
                   x)
    @found))

(deftest substitution_fills_the_holes
  (let [out (tmpl/expand expense {:approvers ["alice" "bob"] :with-legal true})]
    (is (= ["alice" "bob"] (get-in out [:process/roles :approver :members])))
    (is (not (has-marker? out)) "the expanded definition contains no markers")))

(deftest marker-valued-param-cannot-survive-expansion
  ;; a parameter whose VALUE is itself a marker vector would be substituted
  ;; verbatim and lint as authoritative, violating marker-freedom. expand
  ;; rejects it rather than bake it in.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"marker"
       (tmpl/expand {:tik/params [:map [:default :any]]
                     :tik/template {:process/id :evil :process/version 1
                                    :process/facts {[:priority] [:tik/param :default]}
                                    :process/stages [{:stage/id :s :guards []}]}}
                    {:default [:tik/param :ghost]}))))

(deftest conditional_splice_toggles_a_stage
  (testing "flag on: the legal stage is present"
    (let [out (tmpl/expand expense {:approvers ["a"] :with-legal true})]
      (is (= [:submitted :approved :legal-cleared]
             (map :stage/id (:process/stages out))))))
  (testing "flag off (or absent): the legal stage is dropped"
    (is (= [:submitted :approved]
           (map :stage/id (:process/stages
                           (tmpl/expand expense {:approvers ["a"] :with-legal false})))))
    (is (= [:submitted :approved]
           (map :stage/id (:process/stages
                           (tmpl/expand expense {:approvers ["a"]})))))))

(deftest params_are_validated_against_the_spec
  (testing "a spec violation fails well, never a half-expanded definition"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not match"
                          (tmpl/expand expense {:approvers "not-a-vector"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not match"
                          (tmpl/expand expense {}))))       ; approvers required
  (testing "a template referencing an undeclared param fails well"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"undeclared parameter"
                          (tmpl/expand {:tik/template {:x [:tik/param :ghost]}} {}))))
  (testing "not a template at all"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a template"
                          (tmpl/expand {:process/id :x} {})))))

(deftest template_predicate
  (is (tmpl/template? expense))
  (is (not (tmpl/template? {:process/id :x})))
  (is (not (tmpl/template? 42))))

(deftest two_arg_signed_by_lints_rather_than_crashing
  ;; a template that expands to a pathless [:signed-by :role] guard must
  ;; LINT (produce a normal diagnostic), never throw IndexOutOfBounds
  ;; while inspecting its arity. The verdict is an :error: a signature
  ;; over no fact can never be satisfied (fact-status of a nil path is
  ;; :absent), so the stage would be permanently blocked — lint says so
  ;; loudly rather than let the ticket strand.
  (let [out (tmpl/expand
             {:tik/params [:map [:approvers [:vector :string]]]
              :tik/template
              {:process/id :t :process/version 1 :process/guard-vocab 1
               :lint {:runbooks :off}
               :process/roles {:approver {:members [:tik/param :approvers]}}
               :process/stages
               [{:stage/id :a :guards []}
                {:stage/id :b :after [:a] :guards [[:signed-by :approver]]}]}}
             {:approvers ["alice"]})
        errs (filter #(= :error (:level %)) (tik.process/lint out))]
    (is (seq errs) "a pathless :signed-by lints (does not crash)")
    (is (some #(re-find #":signed-by over no fact" (:msg %)) errs)
        "and the diagnostic names the unsatisfiable signature")))
