;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.lint-store-test
  "`tik lint` with no argument audits the store's hygiene — the layer
  between verify (integrity) and explain (derivation): unkempt, not
  wrong. Findings must name the fixing command; settled tickets are
  left in peace."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- tik* [root & args]
  (apply sh/sh (concat ["bb" "tik"] (map str args)
                       [:dir repo
                        :env (assoc (into {} (System/getenv))
                                    "TIK_ROOT" (str root)
                                    "TIK_ACTOR" "seb")])))

(deftest store_lint_names_the_fix_and_spares_the_settled
  (let [root (.toFile (Files/createTempDirectory
                       "tik-lint" (make-array FileAttribute 0)))
        _ (io/copy (io/file repo "processes/support-request.edn")
                   (io/file (doto (io/file root "processes")
                              (.mkdirs)) "support-request.edn"))
        id (str/trim (:out (tik* root "new" "support-request"
                                 "--title" "undescribed")))
        short (subs id 0 8)]
    (testing "an open ticket without a description is a finding"
      (let [r (tik* root "lint")]
        (is (= 1 (:exit r)))
        (is (re-find (re-pattern (str short " has no description")) (:out r)))
        (is (re-find #"unsigned event" (:out r))
            "unsigned events are findings too")
        (is (re-find (re-pattern (str "tik set " short " description="))
                     (:out r))
            "the finding names the fixing command")))
    (testing "description silences that finding"
      (tik* root "set" id "description=a one-liner")
      (is (not (re-find #"has no description" (:out (tik* root "lint"))))))
    (testing "a settled ticket is not nagged"
      (tik* root "set" id "category=:billing" "severity=:low"
            "resolution.ref=\"done-and-dusted\"" "customer.ack=true")
      (let [r (tik* root "lint")]
        (is (not (re-find (re-pattern short) (:out r)))
            (:out r))))))

(deftest prose_rot_heuristics_and_live_links
  (let [root (.toFile (Files/createTempDirectory
                       "tik-rot" (make-array FileAttribute 0)))
        _ (io/copy (io/file repo "processes/support-request.edn")
                   (io/file (doto (io/file root "processes")
                              (.mkdirs)) "support-request.edn"))
        a (str/trim (:out (tik* root "new" "support-request"
                                "--title" "the referenced work")))
        b (str/trim (:out (tik* root "new" "support-request"
                                "--title" "the referring ticket")))]
    (testing "a description reporting another ticket's status is flagged"
      (tik* root "set" b (str "description=blocked until " (subs a 0 8)
                              " is finished"))
      (is (re-find #"reports another ticket's status"
                   (:out (tik* root "lint")))))
    (testing "the lint's own advice silences it: a link fact instead"
      (tik* root "set" b "description=make the follow-up change"
            (str "link.blocked-by=\"" (subs a 0 8) "\""))
      (is (not (re-find #"reports another ticket" (:out (tik* root "lint"))))))
    (testing "the link renders the target's CURRENT derived stage"
      (is (re-find #"\(received\)  [0-9a-f]{8} the referenced work.*\[blocked-by\]"
                   (:out (tik* root "status" b)))))
    (testing "a description older than the latest landing is flagged"
      (tik* root "set" b "commit=\"abcdef0\"")
      (is (re-find #"description predates its latest landing"
                   (:out (tik* root "lint"))))
      (tik* root "set" b "description=make the follow-up change, still")
      (is (not (re-find #"predates" (:out (tik* root "lint"))))))
    (testing "an unresolvable link degrades, never crashes"
      (tik* root "set" b "link.see-also=\"ffffffff\"")
      (let [r (tik* root "status" b)]
        (is (zero? (:exit r)))
        (is (re-find #"unresolved" (:out r)))))))
