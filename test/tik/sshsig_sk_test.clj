;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sshsig-sk-test
  "Security-key signatures read in process. ssh-keygen is the oracle for
  whether the bytes are a valid signature; on top of that, a signature
  counts only when the authenticator reports that a user was present,
  which ssh-keygen -Y verify does not check, so here the in-process
  reader is the stricter of the two."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.sign :as sign]
            [tik.sk-fixture :as sk]
            [tik.sshsig :as sshsig]))

(def ^:private msg (.getBytes "the bytes that were endorsed" "UTF-8"))

(defn- line [signer] (str "alice " (sk/public-line signer)))

(defn- ssh-keygen-accepts?
  [allowed armor ^bytes message]
  (let [dir (h/temp-dir! "sk-oracle")
        a (io/file dir "allowed") s (io/file dir "sig") m (io/file dir "msg")]
    (spit a (str allowed "\n"))
    (spit s armor)
    (io/copy message m)
    (zero? (:exit (sh/sh "ssh-keygen" "-Y" "verify" "-f" (str a) "-I" "alice"
                         "-n" "tik-event" "-s" (str s) :in m)))))

(defn- flip-last-byte
  "The armored signature with the final byte of its blob changed: the low
  byte of the counter, which the signature covers."
  [armor]
  (let [blob (sshsig/unarmor armor)
        n (alength ^bytes blob)]
    (aset-byte blob (dec n) (unchecked-byte (bit-xor 1 (aget ^bytes blob (dec n)))))
    (str "-----BEGIN SSH SIGNATURE-----\n"
         (.encodeToString (java.util.Base64/getMimeEncoder) blob)
         "\n-----END SSH SIGNATURE-----\n")))

(deftest a-touched-security-key-signature-verifies-in-process
  (let [s (sk/signer)
        armor (sk/sign s "tik-event" msg)]
    (is (ssh-keygen-accepts? (line s) armor msg) "the fixture makes what ssh-keygen accepts")
    (is (true? (sshsig/verify (line s) msg armor "alice" "tik-event")))
    (is (= ["alice"] (sshsig/find-principals (line s) msg armor "tik-event")))))

(deftest a-security-key-signature-without-a-touch-counts-for-nothing
  (let [s (sk/signer)
        armor (sk/sign s "tik-event" msg :flags 0)]
    (is (ssh-keygen-accepts? (line s) armor msg)
        "ssh-keygen -Y verify does not look at the user-presence flag")
    (is (false? (sshsig/verify (line s) msg armor "alice" "tik-event")))
    (is (= [] (sshsig/find-principals (line s) msg armor "tik-event")))))

(deftest a-pin-verified-signature-is-also-a-touched-one
  (let [s (sk/signer)
        armor (sk/sign s "tik-event" msg
                       :flags (bit-or sk/flag-user-present sk/flag-user-verified))]
    (is (true? (sshsig/verify (line s) msg armor "alice" "tik-event")))))

(deftest every-way-a-security-key-signature-must-say-no
  (let [s (sk/signer)
        other (sk/signer)
        armor (sk/sign s "tik-event" msg)]
    (testing "a message that moved"
      (is (false? (sshsig/verify (line s) (.getBytes "other" "UTF-8") armor "alice" "tik-event"))))
    (testing "a counter changed after signing"
      (let [bent (flip-last-byte armor)]
        (is (not (ssh-keygen-accepts? (line s) bent msg)))
        (is (false? (sshsig/verify (line s) msg bent "alice" "tik-event")))))
    (testing "another key's line"
      (is (false? (sshsig/verify (line other) msg armor "alice" "tik-event"))))
    (testing "another namespace"
      (is (false? (sshsig/verify (line s) msg armor "alice" "tik-witness"))))
    (testing "the application is part of what was signed"
      (let [moved (assoc s :application "ssh:other"
                         :blob (:blob (sk/signer "ssh:other")))]
        (is (false? (sshsig/verify (line moved) msg armor "alice" "tik-event")))))))

(defn- signers-file [text]
  (doto (io/file (h/temp-dir! "sk-signers") "allowed") (spit (str text "\n"))))

(defn- every-reader
  "What each signature reader tik has says about `armor` over the message
  under the allowed-signers `allowed`."
  [allowed armor]
  (let [sig (.getBytes ^String armor "UTF-8")]
    {:verify-text (sign/verify-text allowed msg sig "alice" "tik-event")
     :verify-bytes (sign/verify-bytes (signers-file allowed) msg sig "alice" "tik-event")
     :find-principals (sign/find-principals-bytes (signers-file allowed) msg sig "tik-event")}))

(deftest an-ecdsa-security-key-signature-without-a-touch-counts-for-nothing
  (let [s (sk/ecdsa-signer)
        armor (sk/sign s "tik-event" msg :flags 0)]
    (is (ssh-keygen-accepts? (line s) armor msg)
        "the bytes are a valid signature, and ssh-keygen ignores the flag")
    (is (false? (sshsig/verify (line s) msg armor "alice" "tik-event")))
    (is (= {:verify-text false :verify-bytes false :find-principals []}
           (every-reader (line s) armor)))))

(deftest a-touched-ecdsa-security-key-signature-still-verifies-through-ssh-keygen
  (let [s (sk/ecdsa-signer)
        armor (sk/sign s "tik-event" msg)]
    (is (ssh-keygen-accepts? (line s) armor msg))
    (is (= {:verify-text true :verify-bytes true :find-principals ["alice"]}
           (every-reader (line s) armor)))))

(deftest a-signer-line-with-a-clock-option-does-not-reopen-the-touch
  (let [s (sk/signer)
        dated (str "alice valid-after=\"20200101\" " (sk/public-line s))]
    (is (= {:verify-text false :verify-bytes false :find-principals []}
           (every-reader dated (sk/sign s "tik-event" msg :flags 0))))
    (is (= {:verify-text true :verify-bytes true :find-principals ["alice"]}
           (every-reader dated (sk/sign s "tik-event" msg))))))

(defn- shell-fn
  "The `sk_untouched` function as the bundle's verify.sh defines it."
  []
  (let [sh-text @(requiring-resolve 'tik.audit/bundle-verify-sh)
        start (str/index-of sh-text "sk_untouched() {")
        end (str/index-of sh-text "\n}\n" start)]
    (subs sh-text start (+ end 2))))

(defn- untouched-by-script? [armor]
  (let [f (io/file (h/temp-dir! "sk-script") "sig")]
    (spit f armor)
    (zero? (:exit (sh/sh "sh" "-c" (str (shell-fn) "\nsk_untouched \"$1\"") "sh" (str f))))))

(deftest the-bundle-script-refuses-what-nobody-touched
  (let [s (sk/signer)]
    (is (untouched-by-script? (sk/sign s "tik-event" msg :flags 0)))
    (is (not (untouched-by-script? (sk/sign s "tik-event" msg))))
    (is (not (untouched-by-script? (sk/sign s "tik-event" msg :flags 5))))
    (testing "an ECDSA security key"
      (let [e (sk/ecdsa-signer)]
        (is (untouched-by-script? (sk/sign e "tik-event" msg :flags 0)))
        (is (not (untouched-by-script? (sk/sign e "tik-event" msg))))))
    (testing "a plain Ed25519 signature carries no flags to check"
      (let [dir (h/temp-dir! "sk-plain")
            key (io/file dir "k")
            m (io/file dir "m")]
        (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" (str key))
        (spit m "x")
        (sh/sh "ssh-keygen" "-Y" "sign" "-f" (str key) "-n" "tik-event" (str m))
        (is (not (untouched-by-script? (slurp (str m ".sig")))))))))
