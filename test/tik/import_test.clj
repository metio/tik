;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.import-test
  "Import is how evidence produced somewhere else reaches a store: a
  release records itself where CI can write, and the store that cares
  reads it in. Merge is union by content address, so the cases that
  matter are the ones about believing nothing on arrival — bytes that
  do not hash to their own name, and importing the same thing twice."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]))

(def ^:private repo (System/getProperty "user.dir"))

(defn- store! [prefix]
  (let [root (h/temp-dir! prefix)]
    (io/copy (io/file repo "processes/support-request.edn")
             (io/file (doto (io/file root "processes") (.mkdirs))
                      "support-request.edn"))
    (h/run-tik! {:root (str root)} "init")
    (io/copy (io/file repo "processes/support-request.edn")
             (io/file root "processes" "support-request.edn"))
    root))

(defn- seeded!
  "A store holding one ticket with facts, plus its bundle."
  []
  (let [root (store! "tik-import-src")
        env {:root (str root) :actor "seb"}
        out (:out (h/run-tik! env "new" "support-request" "--title" "a report"))
        id (second (re-find #"([0-9a-f]{8}-[0-9a-f-]+)" out))
        _ (h/run-tik! env "set" id "category=:billing" "severity=:high")
        bundle (io/file root "evidence.tgz")]
    (h/run-tik! env "bundle" id "--out" (str bundle))
    {:root root :id id :bundle bundle}))

(deftest a_bundle_imports_into_a_store_that_never_saw_it
  (let [{:keys [id bundle]} (seeded!)
        dest (store! "tik-import-dest")
        env {:root (str dest) :actor "seb"}
        r (h/run-tik! env "import" (str bundle))]
    (is (zero? (:exit r)) (str (:err r) (:out r)))
    (is (re-find #"imported \d+ event" (:out r)) (:out r))
    (testing "and derives there exactly as it did at home"
      (let [st (:out (h/run-tik! env "status" id))]
        (is (str/includes? st "triaged") st)
        (is (str/includes? st ":billing") st)))))

(deftest importing_twice_changes_nothing
  (testing "union by content address: the second import is a no-op"
    (let [{:keys [bundle]} (seeded!)
          dest (store! "tik-import-twice")
          env {:root (str dest) :actor "seb"}
          first* (h/run-tik! env "import" (str bundle))
          second* (h/run-tik! env "import" (str bundle))]
      (is (re-find #"imported \d+ event" (:out first*)))
      (is (re-find #"imported 0 event" (:out second*)) (:out second*))
      (is (re-find #"already present" (:out second*)) (:out second*)))))

(deftest bytes_that_do_not_hash_to_their_name_are_refused
  (let [{:keys [bundle]} (seeded!)
        work (h/temp-dir! "tik-import-tamper")
        _ (sh/sh "tar" "xzf" (str bundle) "-C" (str work))
        victim (->> (file-seq work)
                    (filter #(and (.isFile ^java.io.File %)
                                  (str/ends-with? (str %) ".edn")
                                  (str/includes? (str %) "/events/")))
                    first)
        ;; any change at all: the check is on the bytes, so a trailing
        ;; space is as good as a rewritten fact and is guaranteed to land
        _ (spit victim (str (slurp victim) " "))
        dest (store! "tik-import-tampered")
        r (h/run-tik! {:root (str dest) :actor "seb"} "import" (str work))]
    (is (pos? (:exit r)) "an import must believe nothing on arrival")
    (is (str/includes? (str (:err r) (:out r)) "hash to")
        (str (:err r) (:out r)))))

(deftest a_missing_bundle_is_named_rather_than_ignored
  (let [dest (store! "tik-import-missing")
        r (h/run-tik! {:root (str dest) :actor "seb"} "import" "/nope/absent.tgz")]
    (is (pos? (:exit r)))
    (is (str/includes? (str (:err r) (:out r)) "no such bundle"))))
