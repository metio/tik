;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sign-test
  "Signature sidecars end to end with real ssh-keygen: sign the exact
  stored bytes, verify through the actors registry, and prove that
  tampering with either bytes or claimed authorship fails."
  (:require [tik.harness :as h]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [tik.canonical :as canonical]
            [tik.event :as event]
            [tik.sign :as sign])
  (:import
   (java.time Instant)))

(defn- tmp-dir []
  (h/temp-dir! "tik-sign"))

(defn- gen-key! [dir name]
  (let [f (io/file dir name)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" name
           "-f" (str f))
    f))

(deftest sign-and-verify-roundtrip
  (let [dir (tmp-dir)
        key (gen-key! dir "seb-key")
        eve-key (gen-key! dir "eve-key")
        signers (io/file dir "actors")
        e (event/create-ticket {:ticket (random-uuid) :actor "seb"
                                :at (Instant/parse "2026-07-08T10:00:00Z")
                                :title "signed" :process :support-request})
        f (io/file dir (str (:event/id e) ".edn"))]
    (spit f (canonical/emit (dissoc e :event/id)))
    (spit signers (str (sign/allowed-signers-line "seb" (sign/pubkey key)) "\n"))
    (let [sig (sign/sign! key f (:event/id e))]
      (testing "the sidecar name carries the key fingerprint"
        (is (= [(str (:event/id e) ".sig."
                     (sign/fingerprint (sign/pubkey key)))]
               (map #(.getName ^java.io.File %)
                    (sign/sidecars dir (:event/id e))))))
      (testing "the signature verifies as the claimed actor"
        (is (sign/verify signers f sig "seb")))
      (testing "authorship is not transferable to another principal"
        (is (not (sign/verify signers f sig "eve"))))
      (testing "tampered bytes fail"
        (let [tampered (io/file dir "tampered.edn")]
          (spit tampered (str (slurp f) " "))
          (is (not (sign/verify signers tampered sig "seb")))))
      (testing "a key outside the registry cannot claim the actor"
        (let [f2 (io/file dir "other.edn")
              _ (spit f2 (slurp f))
              sig2 (sign/sign! eve-key f2 "other")]
          (is (not (sign/verify signers f sig2 "seb")))
          (is (not (sign/verify signers f2 sig2 "seb"))))))))

(deftest an-allowed-signers-line-is-exactly-one-line-for-one-principal
  (is (= "ci namespaces=\"tik-*\" ssh-ed25519 AAAAC3ci comment here"
         (sign/allowed-signers-line "ci" "ssh-ed25519 AAAAC3ci comment here")))
  (testing "a key that would add a second line is refused"
    (doseq [k ["ssh-ed25519 AAAAC3ci\nseb ssh-ed25519 AAAAC3evil"
               "ssh-ed25519 AAAAC3ci\rseb ssh-ed25519 AAAAC3evil"
               "ssh-ed25519 AAAA\u0000C3"
               "ssh-ed25519"
               "ssh-ed25519 not*base64"
               "cert-authority ssh-ed25519 AAAAC3ci"
               "nonsense-type AAAAC3ci"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"public key"
                            (sign/allowed-signers-line "ci" k))
          (pr-str k))))
  (testing "an actor that is a principal pattern or carries options is refused"
    (doseq [a ["*" "s?b" "seb,ci" "!seb" "se b" "seb\nci" "\"seb\"" ""]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"actor"
                            (sign/allowed-signers-line a "ssh-ed25519 AAAAC3ci"))
          (pr-str a)))))
