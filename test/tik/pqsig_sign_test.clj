;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pqsig-sign-test
  "TIK_PQ_KEY makes every write carry an ML-DSA-65 sidecar beside its
  SSHSIG one (ADR 0036 point 5), and a key pq-signers does not grant the
  actor refuses the write before anything is stored."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.pq-keys :as keys]
            [tik.pqsig :as pqsig]))

(def ^:private repo (System/getProperty "user.dir"))

(defn- store []
  (let [root (h/temp-dir! "tik-pqsig-sign")
        key (io/file root "id_test")
        pq (io/file root "pq.pem")
        pq-key (keys/jdk-key)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "k" "-f" (str key))
    (spit pq (:pem pq-key))
    (io/copy (io/file repo "processes/support-request.edn")
             (io/file (doto (io/file root "processes") (.mkdirs)) "support-request.edn"))
    (h/tik! {:root root :actor "seb" :env {"TIK_KEY" (str key)}}
            "actor" "add" "seb" (str key ".pub"))
    {:root root :pq-key pq-key :env {"TIK_KEY" (str key) "TIK_PQ_KEY" (str pq)}}))

(defn- registry! [{:keys [root pq-key]} actor]
  (spit (io/file root "pq-signers")
        (str actor " namespaces=\"tik-*\" ml-dsa-65 " (:public-key-b64 pq-key) "\n")))

(defn- files [root id suffix]
  (filter #(str/includes? (.getName ^java.io.File %) suffix)
          (.listFiles (h/events-dir root id))))

(deftest every-write-carries-both-signatures-and-verifies
  (let [{:keys [root env] :as st} (store)
        _ (registry! st "seb")
        id (str/trim (:out (h/tik! {:root root :actor "seb" :env env}
                                   "new" "support-request" "--title" "pq")))
        _ (h/tik! {:root root :actor "seb" :env env} "set" id "summary=\"two words\"")
        events (count (filter #(re-find #"\.edn$" (.getName ^java.io.File %))
                              (.listFiles (h/events-dir root id))))
        out (:out (h/run-tik! {:root root :actor "seb"} "verify" id))]
    (is (= 2 events))
    (is (= events (count (files root id ".sig."))))
    (is (= events (count (files root id (str ".pqsig." (pqsig/fingerprint (:public-key (:pq-key st))))))))
    (is (str/includes? out "verify: PASS") out)
    (is (= events (count (re-seq #"post-quantum signed by seb" out))) out)))

(deftest a-key-the-registry-does-not-grant-writes-nothing
  (testing "no pq-signers at all"
    (let [{:keys [root env]} (store)
          r (h/run-tik! {:root root :actor "seb" :env env}
                        "new" "support-request" "--title" "pq")]
      (is (not (zero? (:exit r))))
      (is (str/includes? (:err r) "pq-signers") (:err r))
      (is (h/clean-output? (str (:out r) (:err r))))
      (is (not (.exists (io/file root "tickets"))))))
  (testing "the key is registered for another actor"
    (let [{:keys [root env] :as st} (store)
          _ (registry! st "eve")
          r (h/run-tik! {:root root :actor "seb" :env env}
                        "new" "support-request" "--title" "pq")]
      (is (not (zero? (:exit r))))
      (is (str/includes? (:err r) "seb") (:err r))
      (is (not (.exists (io/file root "tickets")))))))

(deftest a-broken-key-file-is-a-plain-error
  (let [{:keys [root env] :as st} (store)
        _ (registry! st "seb")
        _ (spit (io/file root "pq.pem") "garbage")
        r (h/run-tik! {:root root :actor "seb" :env env}
                      "new" "support-request" "--title" "pq")]
    (is (not (zero? (:exit r))))
    (is (str/includes? (:err r) "TIK_PQ_KEY") (:err r))
    (is (h/clean-output? (str (:out r) (:err r))))))

(deftest tik-sign-adds-post-quantum-signatures-to-existing-events
  (let [{:keys [root env] :as st} (store)
        classical (dissoc env "TIK_PQ_KEY")
        id (str/trim (:out (h/tik! {:root root :actor "seb" :env classical}
                                   "new" "support-request" "--title" "pq later")))
        _ (h/tik! {:root root :actor "seb" :env classical} "set" id "summary=\"before\"")
        _ (registry! st "seb")
        first-run (:out (h/tik! {:root root :actor "seb" :env env} "sign" id))
        again (:out (h/tik! {:root root :actor "seb" :env env} "sign" id))
        out (:out (h/run-tik! {:root root :actor "seb"} "verify" id))]
    (is (str/includes? first-run "2 event(s) post-quantum") first-run)
    (is (str/includes? again "0 event(s) post-quantum") again)
    (is (= 2 (count (files root id ".pqsig."))))
    (is (= 2 (count (re-seq #"post-quantum signed by seb" out))) out)
    (testing "only TIK_PQ_KEY is enough to run it"
      (let [r (h/run-tik! {:root root :actor "seb" :env {"TIK_PQ_KEY" (get env "TIK_PQ_KEY")
                                                         "TIK_KEY" ""}}
                          "sign" id)]
        (is (zero? (:exit r)) (:err r))))))
