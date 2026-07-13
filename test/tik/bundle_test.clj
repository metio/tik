;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bundle-test
  "The evidence bundle end to end: bundle a signed ticket, extract it
  somewhere tik-less, and require verify.sh (coreutils + ssh-keygen
  only) to pass on honest bytes and fail on one flipped byte or an
  unregistered signer."
  (:require [tik.harness :as h]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- tmpdir [prefix]
  (h/temp-dir! prefix))

(defn- tik! [root env & args]
  (:out (apply h/tik! {:root root :actor "seb" :env env} args)))

(deftest bundle_verifies_without_tik_and_catches_tampering
  (let [root (tmpdir "tik-bundle-store")
        key (io/file root "id_test")
        _ (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "test key"
                 "-f" (str key))
        env {"TIK_KEY" (str key)}
        _ (io/copy (io/file repo "processes/support-request.edn")
                   (io/file (doto (io/file root "processes") (.mkdirs))
                            "support-request.edn"))
        _ (tik! root env "actor" "add" "seb" (str key ".pub"))
        id (str/trim (tik! root env "new" "support-request"
                           "--title" "bundled evidence"))
        _ (tik! root env "set" id "category=:billing" "severity=:low")
        out (io/file root "evidence.tgz")
        _ (tik! root env "bundle" id "--out" (str out))
        dest (tmpdir "tik-bundle-dest")
        _ (sh/sh "tar" "xzf" (str out) "-C" (str dest))
        verify #(sh/sh "sh" "./verify.sh" :dir (str dest))]
    (testing "honest bytes pass with coreutils + ssh-keygen alone"
      (let [r (verify)]
        (is (zero? (:exit r)) (:out r))
        (is (re-find #"bundle: PASS" (:out r)))
        (is (re-find #"verifies as seb" (:out r)))))
    (testing "one flipped byte fails the bundle"
      (let [f (->> (file-seq (io/file dest "tickets"))
                   (filter #(str/ends-with? (str %) ".edn"))
                   first)
            bytes (Files/readAllBytes (.toPath ^java.io.File f))]
        (aset-byte bytes 10 (unchecked-byte
                             (bit-xor (aget ^bytes bytes 10) 1)))
        (Files/write (.toPath ^java.io.File f) ^bytes bytes
                     ^"[Ljava.nio.file.OpenOption;"
                     (make-array java.nio.file.OpenOption 0))
        (let [r (verify)]
          (is (= 1 (:exit r)))
          (is (re-find #"bytes do not match their name" (:out r))))))
    (testing "an unregistered signer fails even with honest bytes"
      (sh/sh "tar" "xzf" (str out) "-C" (str dest))   ; restore
      (spit (io/file dest "actors") "")               ; empty registry
      (let [r (verify)]
        (is (= 1 (:exit r)))
        (is (re-find #"absent from the actors registry" (:out r)))))
    (testing "a registered actor cannot forge another actor's authorship"
      ;; the threat is INSIDE the trust boundary: mallory is a fully
      ;; registered principal, yet signs an event that names seb as its
      ;; author. Binding find-principals alone would accept it (mallory
      ;; is registered); the audit must instead verify the signature AS
      ;; the event's own :event/actor, matching in-process L1.
      (sh/sh "tar" "xzf" (str out) "-C" (str dest))   ; honest bytes + actors
      (let [mkey (io/file dest "id_mallory")
            _ (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" (str mkey))
            mpub (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str mkey))))
            _ (spit (io/file dest "actors")
                    (str "mallory namespaces=\"tik-*\" " mpub "\n") :append true)
            sig (->> (file-seq (io/file dest "tickets"))
                     (filter #(re-find #"\.sig\." (.getName ^java.io.File %)))
                     first)
            event (io/file (str/replace (str sig) #"\.sig\..*$" ".edn"))]
        ;; mallory re-signs seb's event (its bytes, hence its :event/actor
        ;; "seb", are untouched — L0 still passes) and overwrites the sidecar
        (sh/sh "ssh-keygen" "-Y" "sign" "-f" (str mkey) "-n" "tik-event"
               (str event))
        (io/copy (io/file (str event ".sig")) sig)
        (.delete (io/file (str event ".sig")))
        (let [r (verify)]
          (is (= 1 (:exit r)))
          (is (re-find #"does not verify as its event's actor" (:out r))
              (:out r)))))))
