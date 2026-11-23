;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.debug-agrees-test
  "tik debug <id> shows the fixpoint tik status reports: the same sticky
  carry and the same role register."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [tik.cli :as cli]
            [tik.harness :as h]))

(def lock-process
  {:process/id :lock :process/version 1 :process/guard-vocab 2
   :process/purpose "a sticky lock and a register-gated approval"
   :process/roles {:reviewers {:members []}}
   :process/facts {[:lock] :boolean [:ok] :boolean}
   :process/stages [{:stage/id :open :guards []}
                    {:stage/id :locked :after [:open] :stage/sticky? true
                     :guards [[:fact [:lock]]]}
                    {:stage/id :after-lock :after [:locked] :guards []}
                    {:stage/id :approved :after [:open]
                     :guards [[:signed-by :reviewers [:ok]]]}]})

(defn- run [root & argv]
  (h/with-cli-root root #(cli/run-argv (mapv str argv))))

(deftest debug-shows-the-fixpoint-status-reports
  (let [root (h/temp-dir! "tik-debug")]
    (.mkdirs (io/file root "tickets"))
    (.mkdirs (io/file root "processes"))
    (spit (io/file root "processes" "lock.edn") (pr-str lock-process))
    (let [id (re-find #"[0-9a-f]{8}-[0-9a-f-]{27}"
                      (:out (run root "new" "lock" "--title" "debug subject" "--actor" "seb")))]
      (run root "set" id "lock=true" "--actor" "seb")
      (run root "retract" id "lock" "--actor" "seb")
      (run root "roles" "add" "reviewers" "seb" "--actor" "seb")
      (run root "set" id "ok=true" "--actor" "seb")
      (let [status (edn/read-string (:out (run root "status" id "--edn")))
            debug (edn/read-string (:out (run root "debug" id "--edn")))]
        (is (= #{:open :locked :after-lock :approved} (set (:reached status))) (pr-str status))
        (is (= (set (:reached status)) (set (:reached debug))))))))
