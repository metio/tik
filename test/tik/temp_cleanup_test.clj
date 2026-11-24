;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.temp-cleanup-test
  "A test run leaves its temp directory as it found it. /tmp is commonly a
  tmpfs whose inode table is fixed, so every directory a run leaves behind
  brings the machine closer to a full table, which byte counts never show.
  Each case gives the code under test a temp directory of its own and
  checks that it is empty once the code is done, after checking that the
  code really made something there."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h])
  (:import (java.io File)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- entries [^File dir] (vec (.list dir)))

(defn- run-failing-suite
  "Run a clojure.test namespace whose tests make temp directories, stores
  and files through the harness and then fail or throw, in a fresh JVM
  whose java.io.tmpdir is `tmp`, ending the way kaocha ends a failed run:
  System/exit with a non-zero code. Returns the sh/sh result."
  [^File tmp]
  (let [form '(do (require '[clojure.test :as t] '[tik.harness :as h])
                  (create-ns 'tik.doomed-test)
                  (binding [*ns* (the-ns 'tik.doomed-test)]
                    (refer-clojure)
                    (require '[clojure.java.io :as io]
                             '[clojure.test :refer [deftest is]]
                             '[tik.harness :as h])
                    (eval '(deftest fails-after-making-temp-state
                             (let [d (h/temp-dir! "tik-doomed")
                                   s (h/temp-store!)
                                   f (h/temp-file! "tik-doomed" ".tgz")]
                               (spit (io/file d "x") "x")
                               (println "MADE" (str d) (str (:root s)) (str f))
                               (is (= 1 2) "a deliberate failure"))))
                    (eval '(deftest throws-after-making-temp-state
                             (let [d (h/temp-dir! "tik-doomed-throw")]
                               (println "MADE" (str d))
                               (throw (ex-info "a deliberate error" {}))))))
                  (let [r (t/run-tests 'tik.doomed-test)]
                    (System/exit (if (t/successful? r) 0 1))))]
    (sh/sh (str (io/file (System/getProperty "java.home") "bin" "java"))
           (str "-Djava.io.tmpdir=" tmp)
           "-cp" (System/getProperty "java.class.path")
           "clojure.main" "-e" (pr-str form)
           :dir repo
           :env (assoc (h/subprocess-env) "TMPDIR" (str tmp)))))

(deftest a_failing_run_leaves_its_temp_directory_empty
  (let [tmp (h/temp-dir! "tik-failing-run")
        r (run-failing-suite tmp)
        made (mapcat #(rest (str/split % #" "))
                     (filter #(str/starts-with? % "MADE ") (str/split-lines (:out r))))]
    (testing "the suite failed and its tests made temp state below tmp"
      (is (= 1 (:exit r)) (str (:out r) (:err r)))
      (is (re-find #"a deliberate failure" (:out r)) (:out r))
      (is (re-find #"a deliberate error" (:out r)) (:out r))
      (is (= 4 (count made)) (:out r))
      (is (every? #(str/starts-with? % (str tmp "/")) made) (pr-str made)))
    (testing "and none of it outlived the JVM"
      (is (not-any? #(.exists (io/file %)) made) (pr-str made))
      (is (= [] (entries tmp))))))

(defn- store! [^File tmp]
  (let [root (h/temp-dir! "tik-cleanup-store")
        env {:root (str root) :dir root :actor "seb" :tmpdir tmp}]
    (h/tik! env "init")
    (let [proc (io/file root "processes" "support-request.edn")]
      (io/make-parents proc)
      (io/copy (io/file repo "processes/support-request.edn") proc))
    env))

(deftest bundle_and_import_remove_their_temp_directories
  (let [tmp (h/temp-dir! "tik-cleanup-cmds")
        src (store! tmp)
        id (second (re-find #"([0-9a-f]{8}-[0-9a-f-]+)"
                            (:out (h/tik! src "new" "support-request" "--title" "a report"))))
        bundle (io/file (:root src) "evidence.tgz")
        dest (store! tmp)]
    (testing "tik bundle"
      (let [r (h/run-tik! src "bundle" id "--out" (str bundle))]
        (is (zero? (:exit r)) (:err r))
        (is (.isFile bundle))
        (is (= [] (entries tmp)))))
    (testing "tik import of an archive"
      (let [r (h/run-tik! dest "import" (str bundle))]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"imported \d+ event" (:out r)) (:out r))
        (is (= [] (entries tmp)))))
    (testing "tik import of an archive it refuses, which ends the process through die"
      (let [junk (doto (io/file (:root dest) "junk.tgz") (spit "not a gzip stream"))
            r (h/run-tik! dest "import" (str junk))]
        (is (= 1 (:exit r)) (:out r))
        (is (re-find #"cannot unpack" (:err r)) (:err r))
        (is (= [] (entries tmp)))))))
