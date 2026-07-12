;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.select-test
  "The selection grammar: each atom matches the right rows, `not` and
  conjunction compose, and compile is total over hostile expressions."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tik.select :as select]))

(def ^:private row
  {:current #{:triaged :blocked}
   :facts {[:severity] :high [:customer :ack] "yes"}
   :actors #{"seb" "alice"}
   :derived-from #{"sha256-abc"}
   :haystack "login fails on firefox"
   :disputed? true
   :conflicted? false
   :unsigned? false})

(defn- match? [expr r] ((select/compile expr) r))

(deftest atoms_match_their_dimension
  (is (match? "stage=:blocked" row))
  (is (match? "stage=blocked" row))                 ; bare name spelling
  (is (not (match? "stage=:closed" row)))
  (is (match? "fact:severity" row))
  (is (match? "fact:severity=:high" row))
  (is (match? "fact:severity=high" row))            ; keyword/string tolerant
  (is (not (match? "fact:severity=:low" row)))
  (is (not (match? "fact:missing" row)))
  (is (match? "fact:customer.ack=yes" row))         ; dotted path
  (is (match? "actor=seb" row))
  (is (not (match? "actor=carol" row)))
  (is (match? "derived-from=sha256-abc" row))
  (is (match? "disputed" row))
  (is (not (match? "conflicted" row)))
  (is (match? "~firefox" row))
  (is (match? "firefox" row))                        ; bare word = haystack
  (is (not (match? "~safari" row))))

(deftest not_and_conjunction_compose
  (is (match? "not conflicted" row))
  (is (not (match? "not disputed" row)))
  (is (match? "stage=:blocked and fact:severity=:high" row))
  (is (match? "stage=:blocked fact:severity=:high not conflicted" row))  ; `and` optional
  (is (not (match? "stage=:blocked and conflicted" row)))
  (is (match? "" row) "the empty selector matches everything"))

(deftest bad_terms_fail_well
  (is (thrown? clojure.lang.ExceptionInfo (select/compile "bogus=nope")))
  (is (thrown? clojure.lang.ExceptionInfo (select/compile "stage=:blocked and not"))))

(defspec compile_is_total_over_hostile_expressions 200
  ;; a selector comes from the command line; whatever a user types, it
  ;; either yields a predicate or a clean ex-info — never another throw.
  (prop/for-all [expr (gen/one-of
                       [gen/string
                        gen/string-alphanumeric
                        (gen/fmap #(str/join " " %)
                                  (gen/vector
                                   (gen/elements
                                    ["stage=:x" "fact:a" "fact:a=b" "actor=x"
                                     "disputed" "not" "and" "~z" "bogus=1"
                                     "=" ":" "~" "fact:" "stage="])
                                   0 6))])]
    (let [outcome (try {:pred (select/compile expr)}
                       (catch clojure.lang.ExceptionInfo _ :rejected)
                       (catch Throwable t {:crash t}))]
      (cond
        (= :rejected outcome) true
        (:crash outcome) false
        ;; a compiled predicate must itself be total over the row
        :else (try (boolean? ((:pred outcome) row))
                   (catch Throwable _ false))))))
