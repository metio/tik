;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pqsig-bundle-test
  "A bundle carries pq-signers beside the ticket's .pqsig. sidecars, and
  both of its verifiers check them (ADR 0036 point 6): `tik rederive` in
  process, and `verify.sh` with OpenSSL 3.5 or later. A verify.sh on a
  host whose OpenSSL has no ML-DSA fails the bundle rather than passing
  a signature it could not check."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.pqsig :as pqsig])
  (:import (java.security KeyPairGenerator Signature)
           (java.util Base64)))

(def ^:private repo (System/getProperty "user.dir"))

(def ^:private openssl-has-ml-dsa?
  (str/includes? (str/lower-case (:out (sh/sh "sh" "-c" "openssl list -signature-algorithms 2>/dev/null")))
                 "ml-dsa-65"))

(defn- keypair [] (.generateKeyPair (KeyPairGenerator/getInstance "ML-DSA-65")))
(defn- der ^bytes [kp] (.getEncoded (.getPublic ^java.security.KeyPair kp)))

(defn- bundled
  "A one-ticket store whose events carry SSHSIG and ML-DSA-65 sidecars,
  bundled and unpacked: {:root :id :dest}."
  []
  (let [root (h/temp-dir! "tik-pqsig-bundle")
        key (io/file root "id_test")
        _ (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "k" "-f" (str key))
        env {"TIK_KEY" (str key)}
        run (fn [& args] (:out (apply h/tik! {:root root :actor "seb" :env env} args)))
        _ (io/copy (io/file repo "processes/support-request.edn")
                   (io/file (doto (io/file root "processes") (.mkdirs)) "support-request.edn"))
        _ (run "actor" "add" "seb" (str key ".pub"))
        kp (keypair)
        _ (spit (io/file root "pq-signers")
                (str "seb namespaces=\"tik-*\" ml-dsa-65 "
                     (.encodeToString (Base64/getEncoder) (der kp)) "\n"))
        id (str/trim (run "new" "support-request" "--title" "pq bundle"))
        _ (run "set" id "summary=\"two\"")]
    (doseq [^java.io.File f (.listFiles (h/events-dir root id))
            :when (re-find #"^sha256-[0-9a-f]{64}\.edn$" (.getName f))
            :let [b (java.nio.file.Files/readAllBytes (.toPath f))
                  sig (.sign (doto (Signature/getInstance "ML-DSA")
                               (.initSign (.getPrivate ^java.security.KeyPair kp))
                               (.update (pqsig/event-message b))))]]
      (io/copy sig (io/file (.getParentFile f)
                            (str (str/replace (.getName f) #"\.edn$" "")
                                 ".pqsig." (pqsig/fingerprint (der kp))))))
    (let [out (io/file root "ev.tgz")
          dest (h/temp-dir! "tik-pqsig-bundle-dest")]
      (run "bundle" id "--out" (str out))
      (sh/sh "tar" "xzf" (str out) "-C" (str dest))
      {:root root :id id :dest dest})))

(defn- verify-sh [dest] (:out (sh/sh "sh" "./verify.sh" :dir (str dest))))

(defn- rederive [root dest]
  (let [r (h/run-tik! {:root root :actor "seb"} "rederive" (str dest))]
    (str (:out r) (:err r))))

(defn- tamper-one-pqsig! [dest]
  (let [^java.io.File f (first (filter #(str/includes? (.getName ^java.io.File %) ".pqsig.")
                                       (file-seq (io/file dest "tickets"))))
        b (java.nio.file.Files/readAllBytes (.toPath f))]
    (aset-byte b 7 (unchecked-byte (bit-xor (aget b 7) 1)))
    (io/copy b f)))

(deftest the-registry-travels-and-both-verifiers-check-the-sidecars
  (let [{:keys [root dest]} (bundled)]
    (is (.isFile (io/file dest "pq-signers")))
    (is (= 2 (count (filter #(str/includes? (.getName ^java.io.File %) ".pqsig.")
                            (file-seq (io/file dest "tickets"))))))
    (testing "tik rederive"
      (let [out (rederive root dest)]
        (is (= 2 (count (re-seq #"verifies post-quantum as seb" out))) out)
        (is (not (re-find #"(?m)^\s*FAIL" out)) out)))
    (testing "verify.sh"
      (let [out (verify-sh dest)]
        (if openssl-has-ml-dsa?
          (do (is (= 2 (count (re-seq #"verifies post-quantum as seb" out))) out)
              (is (str/includes? out "bundle: PASS") out))
          (do (is (str/includes? out "OpenSSL 3.5") out)
              (is (str/includes? out "bundle: FAIL") out)))))))

(deftest a-tampered-sidecar-fails-both-verifiers
  (let [{:keys [root dest]} (bundled)]
    (tamper-one-pqsig! dest)
    (is (re-find #"(?m)^\s*FAIL.*post-quantum" (rederive root dest)))
    (when openssl-has-ml-dsa?
      (is (re-find #"(?m)^FAIL.*post-quantum" (verify-sh dest))))))

(deftest a-sidecar-without-the-registry-fails-both-verifiers
  (let [{:keys [root dest]} (bundled)]
    (io/delete-file (io/file dest "pq-signers"))
    (is (re-find #"(?m)^\s*FAIL.*pq-signers" (rederive root dest)))
    (is (re-find #"(?m)^FAIL.*pq-signers" (verify-sh dest)))))

(deftest a-key-the-registry-gives-another-actor-fails-both-verifiers
  (let [{:keys [root dest]} (bundled)
        reg (io/file dest "pq-signers")]
    (spit reg (str/replace (slurp reg) #"^seb " "eve "))
    (is (re-find #"(?m)^\s*FAIL.*post-quantum" (rederive root dest)))
    (when openssl-has-ml-dsa?
      (is (re-find #"(?m)^FAIL.*not of its event's actor" (verify-sh dest))))
    (testing "a registry line that may not sign events"
      (spit reg (str/replace (slurp reg) #"^eve namespaces=\"tik-\*\"" "seb namespaces=\"tik-witness\""))
      (is (re-find #"(?m)^\s*FAIL.*post-quantum" (rederive root dest)))
      (when openssl-has-ml-dsa?
        (is (re-find #"(?m)^FAIL.*does not allow to sign events" (verify-sh dest)))))))
