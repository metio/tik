;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bundle-test
  "The evidence bundle end to end: bundle a signed ticket, extract it
  somewhere tik-less, and require verify.sh (coreutils + ssh-keygen
  only) to pass on honest bytes and fail on one flipped byte or an
  unregistered signer."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- tmpdir [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- tik! [root env & args]
  (let [r (apply sh/sh (concat ["bb" "tik"] (map str args)
                               [:dir repo
                                :env (merge (into {} (System/getenv))
                                            {"TIK_ROOT" (str root)
                                             "TIK_ACTOR" "seb"}
                                            env)]))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "tik " (first args) " failed")
                      {:out (:out r) :err (:err r)})))
    (:out r)))

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
        (is (re-find #"absent from the actors registry" (:out r)))))))
