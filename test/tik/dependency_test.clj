;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.dependency-test
  "Cross-ticket dependency links: a ticket that `[:link :depends-on]`s
  another is held back by `next` until that upstream ticket is settled.
  Pure porcelain over the store — dependency-readiness needs many logs,
  so it lives beside `next`, never in the per-ticket kernel. The link
  itself is an ordinary fact (an id reference); readiness is derived."
  (:require [tik.harness :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private task-process
  (str "{:process/id :task :process/version 1 :process/guard-vocab 1"
       " :lint {:runbooks :off}"
       " :process/facts {[:result] [:string {:min 1}]}"
       " :process/stages [{:stage/id :open :guards []}"
       "                  {:stage/id :done :after [:open] :stage/sticky? true"
       "                   :guards [[:fact [:result]]]}]}"))

(defn- tik [store & args]
  (apply h/run-tik! {:root store :dir store :actor "seb"
                     :env {"NO_COLOR" "1"}}
         args))

(deftest depends_on_holds_a_ticket_back_until_its_upstream_settles
  (let [store (.toFile (Files/createTempDirectory
                        "tik-dep" (make-array FileAttribute 0)))]
    (.mkdirs (io/file store "processes"))
    (spit (io/file store "processes" "task.edn") task-process)
    (let [a (str/trim (:out (tik store "new" "task" "--title" "upstream A")))
          b (str/trim (:out (tik store "new" "task" "--title" "downstream B")))]
      (tik store "set" b (str "link.depends-on=" a))

      (testing "while A is unsettled, B is blocked, not offered as work"
        (let [nxt (:out (tik store "next" "--actor" "seb"))]
          (is (re-find #"blocked: 1 ticket" nxt)
              "B is counted as blocked on an upstream ticket"))
        (let [st (:out (tik store "status" b))]
          (is (re-find #"blocked:" st) "status names the block")
          (is (re-find (re-pattern (subs a 0 8)) st)
              "and names the specific upstream ticket, resolved")))

      (testing "settling A unblocks B — it becomes actionable"
        (tik store "set" a "result=shipped")
        (let [nxt (:out (tik store "next" "--actor" "seb"))]
          (is (not (re-find #"blocked:" nxt)) "no dependency block remains")
          (is (re-find #"unlocks" nxt) "B's own work is now offered"))
        (is (not (re-find #"blocked:" (:out (tik store "status" b))))
            "status shows no blocker once the upstream is settled")))))

(deftest a_dangling_dependency_blocks_conservatively
  ;; an unresolvable :depends-on target counts as unmet — never call a
  ;; ticket ready on a dependency the store cannot even find
  (let [store (.toFile (Files/createTempDirectory
                        "tik-dep2" (make-array FileAttribute 0)))]
    (.mkdirs (io/file store "processes"))
    (spit (io/file store "processes" "task.edn") task-process)
    (let [b (str/trim (:out (tik store "new" "task" "--title" "orphan B")))]
      (tik store "set" b "link.depends-on=deadbeef-0000-0000-0000-000000000000")
      (is (re-find #"blocked: 1 ticket" (:out (tik store "next" "--actor" "seb"))))
      (is (re-find #"unresolved" (:out (tik store "status" b)))
          "status marks the dangling dependency as unresolved"))))
