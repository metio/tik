;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.backend-test
  "The delegated-agent server: the pipeline supervisor composes the
  porcelain verbs on a cadence, as a delegate, holding the coordination-
  free line — it acts by producing ordinary signed events, idempotently."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.backend :as backend]
            [tik.cli :as cli]
            [tik.cli-core]
            [tik.harness :as h]
            [tik.store.protocol :as store])
  (:import (java.time LocalDate)))

(deftest period_label_matches_the_forms_recur_parses
  (let [d (LocalDate/of 2026 7 14)]                        ; a Tuesday in ISO week 29
    (is (= "2026" (backend/period-label d :year)))
    (is (= "2026-Q3" (backend/period-label d :quarter)))
    (is (= "2026-07" (backend/period-label d :month)))
    (is (= "2026-07-14" (backend/period-label d :day)))
    (is (= "2026-W29" (backend/period-label d :iso-week)))))

(deftest tick_argv_attributes_to_the_delegate_and_injects_the_period
  (let [d (LocalDate/of 2026 7 14)]
    (is (= ["recur" "track" "--actor" "tik-backend" "--period" "2026-W29"]
           (backend/tick-argv d {:id :release :run ["recur" "track"]
                                 :as "tik-backend" :period :iso-week})))
    (is (= ["probe"] (backend/tick-argv d {:id :dash :run ["probe"]}))
        "no :as / :period → the bare verb")))

(defn- run-backend [root]
  (h/with-cli-root root
    (fn [] (cli/run-argv ["backend" "--config" (str (io/file root "pipelines.edn"))]))))

(deftest scheduler_fires_recur_as_a_delegate_and_dedups
  (let [{:keys [root store]} (h/temp-store!)]
    (System/setProperty "user.name" "tester")
    (spit (io/file root "pipelines.edn")
          (pr-str {:pipelines [{:id :weekly :every "PT0.05S"
                                :run ["recur" "track"] :period :iso-week
                                :as "tik-backend"}]}))
    (binding [backend/*backend-ticks* 2]                   ; two fires, same period
      (run-backend root))
    (testing "the backend minted the period ticket via the recur verb"
      (is (= 1 (count (store/ticket-ids store)))
          "two ticks of the same period → exactly one ticket (idempotent, leaderless)"))
    (testing "and attributed it to the delegate actor"
      (let [id (first (store/ticket-ids store))
            evs (store/events store id)
            create (first (filter #(= :ticket/create (:event/type %)) evs))]
        (is (= "tik-backend" (:event/actor create)))))))

(deftest one_failing_pipeline_never_aborts_the_others
  (let [{:keys [root store]} (h/temp-store!)]
    (System/setProperty "user.name" "tester")
    (spit (io/file root "pipelines.edn")
          (pr-str {:pipelines [{:id :broken :every "PT0.05S" :run ["recur" "no-such-process"] :as "tik-backend"}
                               {:id :good :every "PT0.05S" :run ["recur" "track"] :period :iso-week :as "tik-backend"}]}))
    (binding [backend/*backend-ticks* 2]                   ; each pipeline fires once
      (is (zero? (:exit (run-backend root))) "the backend itself does not crash"))
    (is (= 1 (count (store/ticket-ids store)))
        "the good pipeline minted despite the broken one failing every tick")))

(deftest malformed_pipeline_fails_closed
  (let [{:keys [root]} (h/temp-store!)]
    (testing "a pipeline with neither :every nor :watch is refused"
      (spit (io/file root "pipelines.edn")
            (pr-str {:pipelines [{:id :x :run ["probe"]}]}))
      (let [r (run-backend root)]
        (is (= 1 (:exit r)))
        (is (str/includes? (str (:out r) (:err r)) ":every"))))
    (testing "a bad :every duration is refused"
      (spit (io/file root "pipelines.edn")
            (pr-str {:pipelines [{:id :x :every "5 minutes" :run ["probe"]}]}))
      (let [r (run-backend root)]
        (is (= 1 (:exit r)))
        (is (str/includes? (str (:out r) (:err r)) "ISO-8601 duration"))))
    (testing "no config file at all is a clean error"
      (.delete (io/file root "pipelines.edn"))
      (is (= 1 (:exit (run-backend root)))))))
