;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pqsig-verify-test
  "`tik verify` checks every `.pqsig.` sidecar of a ticket against
  `pq-signers` (ADR 0036): a valid one is reported, and a tampered one, an
  unregistered key or a key registered for somebody else fails the audit."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.pqsig :as pqsig])
  (:import (java.security KeyPairGenerator Signature)
           (java.util Base64)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- keypair []
  (.generateKeyPair (KeyPairGenerator/getInstance "ML-DSA-65")))

(defn- der ^bytes [kp] (.getEncoded (.getPublic ^java.security.KeyPair kp)))

(defn- sign ^bytes [kp ^bytes msg]
  (.sign (doto (Signature/getInstance "ML-DSA")
           (.initSign (.getPrivate ^java.security.KeyPair kp))
           (.update msg))))

(defn- registry-line [actor kp]
  (str actor " namespaces=\"tik-*\" ml-dsa-65 "
       (.encodeToString (Base64/getEncoder) (der kp)) "\n"))

(defn- event-files [root id]
  (sort (filter #(re-find #"^sha256-[0-9a-f]{64}\.edn$" (.getName ^java.io.File %))
                (.listFiles (h/events-dir root id)))))

(defn- pq-sign-all! [root id kp]
  (doseq [^java.io.File f (event-files root id)
          :let [stem (str/replace (.getName f) #"\.edn$" "")
                bytes (java.nio.file.Files/readAllBytes (.toPath f))]]
    (io/copy (sign kp (pqsig/event-message bytes))
             (io/file (.getParentFile f) (str stem ".pqsig." (pqsig/fingerprint (der kp)))))))

(defn- signed-store []
  (let [root (h/temp-dir! "tik-pqsig-verify")
        key (io/file root "id_test")
        _ (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "k" "-f" (str key))
        env {"TIK_KEY" (str key)}
        _ (io/copy (io/file repo "processes/support-request.edn")
                   (io/file (doto (io/file root "processes") (.mkdirs)) "support-request.edn"))
        _ (h/tik! {:root root :actor "seb" :env env} "actor" "add" "seb" (str key ".pub"))
        id (str/trim (:out (h/tik! {:root root :actor "seb" :env env}
                                   "new" "support-request" "--title" "pq")))]
    {:root root :id id}))

(defn- verify [root id]
  (let [r (h/run-tik! {:root root :actor "seb"} "verify" id)]
    (str (:out r) (:err r))))

(deftest a-registered-post-quantum-signature-is-checked-and-reported
  (let [{:keys [root id]} (signed-store)
        kp (keypair)]
    (spit (io/file root "pq-signers") (registry-line "seb" kp))
    (pq-sign-all! root id kp)
    (let [out (verify root id)]
      (is (str/includes? out "verify: PASS") out)
      (is (str/includes? out "post-quantum signed by seb") out))))

(deftest a-bad-post-quantum-sidecar-fails-the-audit
  (testing "one flipped byte"
    (let [{:keys [root id]} (signed-store)
          kp (keypair)]
      (spit (io/file root "pq-signers") (registry-line "seb" kp))
      (pq-sign-all! root id kp)
      (let [^java.io.File sc (first (filter #(str/includes? (.getName ^java.io.File %) ".pqsig.")
                                            (.listFiles (h/events-dir root id))))
            b (java.nio.file.Files/readAllBytes (.toPath sc))]
        (aset-byte b 10 (unchecked-byte (bit-xor (aget b 10) 1)))
        (io/copy b sc))
      (is (str/includes? (verify root id) "verify: FAIL"))))
  (testing "a key nobody registered"
    (let [{:keys [root id]} (signed-store)]
      (spit (io/file root "pq-signers") (registry-line "seb" (keypair)))
      (pq-sign-all! root id (keypair))
      (is (str/includes? (verify root id) "verify: FAIL"))))
  (testing "a key registered for another actor"
    (let [{:keys [root id]} (signed-store)
          kp (keypair)]
      (spit (io/file root "pq-signers") (registry-line "eve" kp))
      (pq-sign-all! root id kp)
      (is (str/includes? (verify root id) "verify: FAIL"))))
  (testing "a malformed registry is named, not read in part"
    (let [{:keys [root id]} (signed-store)
          kp (keypair)]
      (spit (io/file root "pq-signers") (str (registry-line "seb" kp) "garbage\n"))
      (pq-sign-all! root id kp)
      (let [out (verify root id)]
        (is (str/includes? out "verify: FAIL") out)
        (is (str/includes? out "pq-signers line 2") out)))))

(deftest a-store-without-post-quantum-sidecars-verifies-as-before
  (let [{:keys [root id]} (signed-store)
        out (verify root id)]
    (is (str/includes? out "verify: PASS") out)
    (is (not (str/includes? out "post-quantum")) out)))
