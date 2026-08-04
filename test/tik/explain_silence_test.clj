;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.explain-silence-test
  "explain is the product surface — the lens you read before asking a
  human anything — so it must never answer with nothing. Its result
  covers the frontier, and an empty frontier means either a finished
  ticket or a definition whose remaining stages can never be enabled;
  printing neither leaves the reader unable to tell which."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]
            [tik.cli-core]
            [tik.harness :as h]
            [tik.inspect]))

(defn- in
  [root & argv]
  (with-redefs-fn {#'tik.cli-core/root (constantly (str root))}
    (fn [] (cli/run-argv (mapv str argv)))))

(defn- store-with-process!
  "A store whose `probe-subject` process is `stages`, holding one ticket."
  [prefix stages]
  (System/setProperty "user.name" "tester")
  (let [root (h/temp-dir! prefix)]
    (.mkdirs (io/file root "processes"))
    (spit (io/file root "processes" "probe-subject.edn")
          (pr-str {:process/id :probe-subject
                   :process/version 1
                   :process/stages stages}))
    (in root "new" "probe-subject" "--title" "silent explain")
    {:root root
     :id (->> (str/split (:out (in root "ls" "--edn")) #"[^0-9a-f-]")
              (filter #(= 36 (count %)))
              first)}))

(deftest a_fully_reached_ticket_says_so_instead_of_printing_nothing
  (let [{:keys [root id]} (store-with-process!
                           "tik-explain-done"
                           [{:stage/id :open :stage/sticky? true}])
        r (in root "explain" id)]
    (is (zero? (:exit r)))
    (is (re-find #"nothing is missing" (:out r)))
    (is (not (str/blank? (:out r))))))

(deftest an_unreachable_stage_names_itself_rather_than_going_quiet
  ;; A stage waiting on an id no stage carries can never join the
  ;; frontier, so explain covers nothing — forever, and silently.
  (let [{:keys [root id]} (store-with-process!
                           "tik-explain-dangling"
                           [{:stage/id :open :stage/sticky? true}
                            {:stage/id :done :after [:typoed]}])
        r (in root "explain" id)]
    (is (zero? (:exit r)))
    (is (re-find #"no stage is reachable" (:out r)))
    (is (re-find #"done waits on :typoed" (:out r))
        "the message names the stage and the id it waits on")
    (is (re-find #"tik lint" (:out r))
        "and points at the gate that would have caught it")))

(deftest a_cycle_reports_the_shape_it_cannot_enter
  (let [{:keys [root id]} (store-with-process!
                           "tik-explain-cycle"
                           [{:stage/id :open :stage/sticky? true}
                            {:stage/id :a :after [:b]}
                            {:stage/id :b :after [:a]}])
        r (in root "explain" id)]
    (is (zero? (:exit r)))
    (is (re-find #"no stage is reachable" (:out r)))
    (is (re-find #"unreached stage" (:out r)))))

(deftest an_actor_filter_still_shows_the_stage_it_narrowed
  ;; `for-actor` narrows a block's :missing and counts the remainder as
  ;; :hidden — it never drops a block. So --actor cannot empty the
  ;; frontier, and the empty case stays a statement about the ticket.
  (let [{:keys [root id]} (store-with-process!
                           "tik-explain-actor"
                           [{:stage/id :open :stage/sticky? true}
                            {:stage/id :done
                             :guards [[:signed-by :approver [:ok]]]}])
        r (in root "explain" id "--actor" "nobody")]
    (is (zero? (:exit r)))
    (is (re-find #"done" (:out r)))
    (is (not (re-find #"no stage is reachable" (:out r)))
        "an unreachable-frontier message here would misreport the cause")))

(deftest machine_readers_still_get_the_empty_vector
  ;; The prose is for a terminal; --edn must stay a clean [] so a caller
  ;; parsing explain is unaffected by the human-facing change.
  (let [{:keys [root id]} (store-with-process!
                           "tik-explain-edn"
                           [{:stage/id :open :stage/sticky? true}])
        r (in root "explain" id "--edn")]
    (is (zero? (:exit r)))
    (is (= "[]" (str/trim (:out r))))))

(deftest a_real_frontier_still_explains_itself
  (testing "the change touches only the empty case"
    (let [{:keys [root id]} (store-with-process!
                             "tik-explain-normal"
                             [{:stage/id :open :stage/sticky? true}
                              {:stage/id :done :guards [[:fact [:gate]]]}])
          r (in root "explain" id)]
      (is (zero? (:exit r)))
      (is (re-find #"done" (:out r)))
      (is (not (re-find #"no stage is reachable" (:out r)))))))
