;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.migrate-test
  "ADR 0002 end to end through the CLI: migration is dry-run by default
  and shows consequences; --apply is a signed event; pinning is honored
  on READ — an unmigrated ticket keeps deriving under its archived
  definition after the named file moves on (reproducibility over
  freshness), and verify stays green for both."
  (:require [tik.harness :as h]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private repo (System/getProperty "user.dir"))

(defn- tik! [root & args]
  (:out (apply h/tik! {:root root :actor "seb"} args)))

(deftest migrate-dry-run-then-apply
  (let [root (h/temp-dir! "tik-migrate")
        _ (io/make-parents (io/file root "processes" "x"))
        _ (io/copy (io/file repo "processes/tik-dev.edn")
                   (io/file root "processes/tik-dev.edn"))
        v1 (edn/read-string (slurp (io/file root "processes/tik-dev.edn")))
        ;; v2 adds a review requirement to :landed
        v2 (-> v1
               (assoc :process/version 2)
               (assoc-in [:process/facts [:review]] [:string {:min 4}])
               (update :process/stages
                       (fn [stages]
                         (mapv #(if (= :landed (:stage/id %))
                                  (update % :guards conj [:fact [:review]])
                                  %)
                               stages))))
        v2-file (io/file root "v2.edn")
        _ (spit v2-file (pr-str v2))
        id (str/trim (tik! root "new" "tik-dev" "--title" "migration subject"))
        _ (tik! root "set" id "summary=\"exercise ADR 0002 end to end\""
                "kind=:feature" "commit=\"abc1234\"" "gate=:green")]

    (testing "the ticket landed under v1"
      (is (str/includes? (tik! root "status" id) "landed")))

    (testing "dry run names the regression and records nothing"
      (let [out (tik! root "reprocess" id (str v2-file))]
        (is (str/includes? out ":landed would REGRESS"))
        (is (str/includes? out "dry run — nothing recorded")))
      (is (str/includes? (tik! root "status" id) "v1")))

    (testing "apply is a signed event that re-pins and re-derives"
      (let [out (tik! root "reprocess" id (str v2-file)
                      "--reason" "review requirement introduced" "--apply")]
        (is (str/includes? out "migrated")))
      (let [status (tik! root "status" id)]
        (is (str/includes? status "v2"))
        ;; :landed is sticky and was reached under v1 — it survives the
        ;; migration; the new requirement shows for anything not yet won
        (is (str/includes? status "landed"))))

    (testing "grandfathering: an unmigrated ticket keeps its v1 pin
              after the NAMED definition file moves on"
      (let [other (str/trim (tik! root "new" "tik-dev" "--title" "unmigrated"))]
        (spit (io/file root "processes/tik-dev.edn") (pr-str v2))
        (let [status (tik! root "status" other)]
          (is (str/includes? status "v1")
              "still derives under the archived v1 definition"))
        (is (str/includes? (tik! root "verify" other) "verify: PASS")
            "pinned hash resolves through the archive")
        (is (str/includes? (tik! root "verify" id) "verify: PASS")
            "the migrated ticket verifies under v2")))))
