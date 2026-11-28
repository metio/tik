;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.signing-contract-test
  "The bytes tik.sign produces and the verdicts it gives, pinned against a
  signature ssh-keygen made and against ssh-keygen itself. Sidecars in
  every store were written by these functions, so a change in what they
  return is a format change."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.canonical :as canonical]
            [tik.harness :as h]
            [tik.sign :as sign])
  (:import (java.io File)
           (java.nio.file Files)))

(def ^:private golden
  (canonical/parse (slurp (io/resource "tik/signing_golden.edn"))))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(defn- registry! ^File [dir text]
  (let [f (io/file dir "actors")]
    (spit f text)
    f))

(defn- golden-line []
  (sign/allowed-signers-line "golden" (:public-key golden)))

(deftest the-fingerprint-of-a-known-key-is-pinned
  (is (= (:fingerprint golden) (sign/fingerprint (:public-key golden))))
  (is (= 16 (count (sign/fingerprint (:public-key golden))))))

(deftest a-signature-ssh-keygen-made-verifies-in-process
  (let [dir (h/temp-dir! "signing-golden")
        actors (registry! dir (str (golden-line) "\n"))
        msg (utf8 (:message golden))
        sig (utf8 (:signature golden))
        ns* (:namespace golden)]
    (doseq [fallback? [true false]]
      (binding [sign/*ssh-keygen-fallback* fallback?]
        (testing (str "fallback " fallback?)
          (is (true? (sign/verify-bytes actors msg sig "golden" ns*)))
          (is (true? (sign/verify-text (golden-line) msg sig "golden" ns*)))
          (is (= ["golden"] (sign/find-principals-bytes actors msg sig ns*)))
          (is (false? (sign/verify-bytes actors msg sig "other" ns*)))
          (is (false? (sign/verify-bytes actors msg sig "golden" "tik-process")))
          (is (false? (sign/verify-bytes actors (utf8 (str (:message golden) " "))
                                         sig "golden" ns*)))
          (is (false? (sign/verify-bytes actors msg sig nil ns*)))
          (is (false? (sign/verify-bytes actors msg sig "" ns*)))
          (is (false? (sign/verify-text (golden-line) msg sig nil ns*))))))))

(defn- gen-key! ^File [dir name type]
  (let [f (io/file dir name)]
    (sh/sh "ssh-keygen" "-q" "-t" type "-N" "" "-C" name "-f" (str f))
    f))

(defn- ssh-keygen-signature
  "The signature `ssh-keygen -Y sign` writes beside a file holding `msg`."
  ^bytes [dir key namespace ^bytes msg]
  (let [f (File/createTempFile "signing-msg" ".bin" (io/file dir))]
    (Files/write (.toPath f) msg (make-array java.nio.file.OpenOption 0))
    (sh/sh "ssh-keygen" "-Y" "sign" "-f" (str key) "-n" namespace (str f))
    (Files/readAllBytes (.toPath (io/file (str f ".sig"))))))

(deftest sign-bytes-returns-exactly-what-ssh-keygen-writes
  ;; Ed25519 signatures are deterministic, so the same key over the same
  ;; bytes gives the same SSHSIG armor however ssh-keygen was fed.
  (let [dir (h/temp-dir! "signing-bytes")
        key (gen-key! dir "k" "ed25519")
        msg (utf8 (:message golden))]
    (doseq [ns* [sign/namespace-event sign/namespace-process sign/namespace-witness]]
      (is (= (seq (ssh-keygen-signature dir key ns* msg))
             (seq (sign/sign-bytes key msg ns*)))
          ns*))
    (testing "the two-argument arity signs as an event"
      (is (= (seq (ssh-keygen-signature dir key "tik-event" msg))
             (seq (sign/sign-bytes key msg)))))
    (testing "the public key line is ssh-keygen's"
      (is (= (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str key))))
             (sign/pubkey key))))))

(deftest sign!-writes-the-sidecar-beside-the-file
  (let [dir (h/temp-dir! "signing-sidecar")
        key (gen-key! dir "k" "ed25519")
        f (io/file dir "abc.edn")
        _ (spit f (:message golden))
        side (sign/sign! key f "abc")]
    (is (= (str "abc.sig." (sign/fingerprint (sign/pubkey key))) (.getName side)))
    (is (= (seq (ssh-keygen-signature dir key "tik-event" (utf8 (:message golden))))
           (seq (Files/readAllBytes (.toPath side)))))
    (is (= [side] (sign/sidecars dir "abc")))))

(deftest a-key-type-read-only-by-ssh-keygen-falls-back-unless-forbidden
  (let [dir (h/temp-dir! "signing-rsa")
        key (gen-key! dir "r" "rsa")
        pub (sign/pubkey key)
        actors (registry! dir (str (sign/allowed-signers-line "rsa" pub) "\n"))
        msg (utf8 "rsa-signed bytes")
        sig (sign/sign-bytes key msg)]
    (testing "the CLI asks ssh-keygen"
      (is (true? (sign/verify-bytes actors msg sig "rsa" "tik-event")))
      (is (false? (sign/verify-bytes actors (utf8 "other") sig "rsa" "tik-event")))
      (is (= ["rsa"] (sign/find-principals-bytes actors msg sig "tik-event"))))
    (testing "a library call starts no process and leaves it unchecked"
      (binding [sign/*ssh-keygen-fallback* false]
        (is (nil? (sign/verify-bytes actors msg sig "rsa" "tik-event")))
        (is (nil? (sign/verify-text (slurp actors) msg sig "rsa" "tik-event")))
        (is (nil? (sign/find-principals-bytes actors msg sig "tik-event")))))))
