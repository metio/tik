;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.roles-test
  "Who is in a role is store state, not a rule frozen into the pinned
  definition: the register decides, and it decides for tickets that
  already exist."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.process :as process]))

(deftest a-role-the-register-does-not-name-keeps-its-declared-members
  (let [pinned {:triager {:members ["seb"]} :billing {:members ["bill"]}}]
    (is (= pinned (process/resolve-roles pinned nil)))
    (is (= pinned (process/resolve-roles pinned {})))
    (is (= {:triager {:members ["ana"]} :billing {:members ["bill"]}}
           (process/resolve-roles pinned {:triager {:members ["ana"]}})))))

(deftest the-register-replaces-a-role-rather-than-adding-to-it
  (is (= {:triager {:members ["ana"]}}
         (process/resolve-roles {:triager {:members ["seb"]}}
                                {:triager {:members ["ana"]}}))
      "a departure has to be expressible, so membership is not a union")
  (is (= {:triager {:members []}}
         (process/resolve-roles {:triager {:members ["seb"]}}
                                {:triager {:members []}}))
      "emptying a role is a legitimate state, not a fallback to the pin"))

(def ^:private proc
  "A one-role process: :approved needs a signature from :approver."
  (str {:process/id :approval
        :process/version 1
        :process/guard-vocab 2
        :process/roles {:approver {:members ["seb"]}}
        :process/facts {[:ok] :boolean}
        :process/stages [{:stage/id :open}
                         {:stage/id :approved :after [:open]
                          :guards [[:signed-by :approver [:ok]]]}]}))

(defn- write-process! [root]
  (let [f (io/file root "processes" "approval.edn")]
    (io/make-parents f)
    (spit f proc)))

(deftest a-hire-gains-authority-over-a-ticket-that-already-exists
  (let [root (h/temp-dir! "tik-roles")
        env {:root (str root) :actor "ana"}
        run (fn [& args] (apply h/run-tik! env args))]
    (write-process! root)
    (run "init")
    (write-process! root)
    (let [out (:out (run "new" "approval" "--title" "needs a sign-off"))
          id (second (re-find #"([0-9a-f]{8})" out))
          reached? (fn []
                     (->> (str/split-lines (:out (run "status" id)))
                          (filter #(str/starts-with? % "stage:"))
                          first
                          (re-find #"approved")
                          boolean))]
      (is id (str "expected a ticket id in: " out))
      (run "set" id "ok=true" "--actor" "ana")
      (testing "the pinned definition does not have ana in :approver"
        (is (not (reached?))))
      (testing "adding her to the register moves the ticket without a re-pin"
        (run "roles" "add" "approver" "ana")
        (is (reached?)))
      (testing "and removing her takes the authority back"
        (run "roles" "remove" "approver" "ana")
        (is (not (reached?)))))))

(deftest a-malformed-register-is-refused-rather-than-ignored
  (let [root (h/temp-dir! "tik-roles-bad")
        env {:root (str root) :actor "ana"}]
    (write-process! root)
    (h/run-tik! env "init")
    (write-process! root)
    (h/run-tik! env "new" "approval" "--title" "t")
    (spit (io/file root "roles.edn") "{:approver [\"ana\"]}")
    (let [{:keys [exit err out]} (h/run-tik! env "ls")]
      (is (pos? exit) "silently falling back would widen authority")
      (is (str/includes? (str err out) "malformed role register")))))
