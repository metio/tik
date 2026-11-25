;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.roles-test
  "Who is in a role is store state, not a rule frozen into the pinned
  definition: the register decides, and it decides for tickets that
  already exist."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli-core :as cli-core]
            [tik.harness :as h]
            [tik.process :as process]
            [tik.serve :as serve]))

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

(deftest the-board-follows-a-register-change
  (let [root (h/temp-dir! "tik-roles-board")
        env {:root (str root) :actor "ana"}
        run (fn [& args] (apply h/run-tik! env args))]
    (write-process! root)
    (run "init")
    (write-process! root)
    (let [id (second (re-find #"([0-9a-f]{8})" (:out (run "new" "approval" "--title" "board"))))
          approved-on-board? #(boolean (re-find #":approved" (:out (run "ls" "--all" "--edn"))))]
      (run "set" id "ok=true" "--actor" "ana")
      (is (not (approved-on-board?)) "the first listing caches the row")
      (run "roles" "add" "approver" "ana")
      (is (approved-on-board?) "the cached row does not outlive the register it was derived under")
      (run "roles" "remove" "approver" "ana")
      (is (not (approved-on-board?))))))

;; ---------------------------------------- a register that does not read

(def ^:private broken-registers
  "Ways a store's roles.edn can be present without being a register."
  {"EDN that does not parse" #(spit % "{:approver {:members [\"ana\"]")
   "EDN that is not a register" #(spit % "{:approver [\"ana\"]}")
   "an empty file" #(spit % "")
   "a directory" #(do (io/delete-file %) (.mkdirs ^java.io.File %))})

(defn- departed-approver-store!
  "A store whose definition declares seb the approver and whose register
  leaves seb out of the role, with seb's sign-off on one ticket. The
  intact register denies :approved; the definition's members alone
  would grant it."
  []
  (let [root (h/temp-dir! "tik-roles-departed")
        env {:root (str root) :actor "seb"}
        run (fn [& args] (apply h/run-tik! env args))]
    (write-process! root)
    (run "init")
    (write-process! root)
    (let [id (second (re-find #"([0-9a-f]{8})" (:out (run "new" "approval" "--title" "t"))))]
      (run "set" id "ok=true")
      (spit (io/file root "roles.edn") (pr-str {:approver {:members ["ana"]}}))
      {:root root :id id :run run})))

(defn- approved? [out] (boolean (re-find #"stage:[^\n]*approved" (str out))))

(deftest a-store-register-that-does-not-read-refuses-every-derivation
  (let [{:keys [root id run]} (departed-approver-store!)
        roles (io/file root "roles.edn")
        intact (slurp roles)]
    (testing "the intact register denies the stage"
      (let [r (run "status" id)]
        (is (zero? (:exit r)) (:err r))
        (is (not (approved? (:out r))))))
    (doseq [[how alter!] broken-registers]
      (testing how
        (alter! roles)
        (doseq [argv [["ls"] ["ls" "--all" "--edn"] ["status" id] ["explain" id]
                      ["next"] ["verify"]]]
          (let [{:keys [exit out err]} (apply run argv)]
            (is (pos? exit) (str argv " exited 0: " out))
            (is (str/includes? (str err out) "malformed role register")
                (str argv ": " err out))
            (is (not (re-find #"stage:.*approved" (str out)))
                (str argv " granted the stage the intact register denies"))))
        (when (.isDirectory roles) (.delete roles))
        (spit roles intact)))
    (testing "and an absent register leaves the definition's members standing"
      (.delete roles)
      (is (approved? (:out (run "status" id)))))))

(deftest the-board-server-grants-nothing-under-a-register-that-does-not-read
  (let [{:keys [root id]} (departed-approver-store!)
        roles (io/file root "roles.edn")
        handler (serve/request-handler {:host "127.0.0.1" :port 7777 :board? true})
        get* (fn [uri] (handler {:uri uri :headers {"host" "127.0.0.1:7777"}}))]
    (with-redefs [cli-core/root (constantly (str root))]
      (let [full (some #(when (str/starts-with? (.getName ^java.io.File %) id) (.getName ^java.io.File %))
                       (.listFiles (io/file root "tickets")))
            explain (str "/explain/" full ".edn")]
        (testing "the intact register"
          (is (= 200 (:status (get* explain))))
          (is (not (re-find #":approved" (:body (get* "/tickets.edn"))))))
        (doseq [[how alter!] (dissoc broken-registers "a directory")]
          (testing how
            (alter! roles)
            (let [r (get* explain)]
              (is (= 500 (:status r)) "the explanation is refused, not derived")
              (is (str/includes? (:body r) "tik exit 1")))
            (doseq [uri ["/" "/tickets.edn"]]
              (is (not (re-find #":approved|>approved<" (str (:body (get* uri)))))
                  (str uri " granted the stage the intact register denies")))
            (spit roles (pr-str {:approver {:members ["ana"]}}))))))))
