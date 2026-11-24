;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pqsig-test
  "ML-DSA-65 event signatures and the pq-signers registry (ADR 0036
  points 1 to 3), checked against signatures OpenSSL made."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.pq-fixture :as fx]
            [tik.pq-keys :as keys]
            [tik.pqsig :as pqsig]))

(defn- flip ^bytes [^bytes b i]
  (let [c (aclone b)] (aset-byte c i (unchecked-byte (bit-xor (aget c i) 1))) c))

(defn- line [actor key-b64]
  (str actor " namespaces=\"tik-*\" ml-dsa-65 " key-b64))

(deftest an-openssl-signature-over-the-domain-separated-event-verifies
  (is (= "tik-event-v1\u0000" (String. ^bytes (pqsig/event-message (byte-array 0)) "UTF-8")))
  (is (true? (pqsig/verify-event fx/public-key fx/signature fx/event-bytes))))

(deftest anything-else-does-not-verify
  (testing "one bit of the event"
    (is (false? (pqsig/verify-event fx/public-key fx/signature (flip fx/event-bytes 3)))))
  (testing "one bit of the signature"
    (is (false? (pqsig/verify-event fx/public-key (flip fx/signature 100) fx/event-bytes))))
  (testing "a truncated signature"
    (is (false? (pqsig/verify-event fx/public-key (java.util.Arrays/copyOf ^bytes fx/signature 3308)
                                    fx/event-bytes))))
  (testing "a signature over the event without the domain prefix"
    (is (false? (pqsig/verify-event fx/public-key fx/sig-no-domain fx/event-bytes))))
  (testing "a valid ML-DSA-44 signature: the parameter set is closed"
    (is (false? (pqsig/verify-event fx/public-key-44 fx/signature-44 fx/event-bytes))))
  (testing "garbage instead of a key or signature fails closed, without throwing"
    (is (false? (pqsig/verify-event (byte-array 1974) fx/signature fx/event-bytes)))
    (is (false? (pqsig/verify-event nil nil nil)))
    (is (false? (pqsig/verify-event "key" "sig" "event")))))

(deftest the-registry-names-each-key-by-the-sha-256-of-its-der
  (let [[entry :as reg] (pqsig/parse-registry
                         (str "# comment\n\n" (line "alice" fx/public-key-b64) "\n"))]
    (is (= 1 (count reg)))
    (is (= "alice" (:actor entry)))
    (is (= ["tik-*"] (:namespaces entry)))
    (is (= fx/fingerprint (:fpr entry) (pqsig/fingerprint fx/public-key)))
    (is (= entry (pqsig/entry-for reg fx/fingerprint)))
    (is (nil? (pqsig/entry-for reg (str/replace fx/fingerprint #"^." "0"))))))

(deftest a-malformed-registry-line-is-refused-with-its-number
  (doseq [[why text] {"another algorithm" (str "alice namespaces=\"tik-*\" ml-dsa-87 " fx/public-key-b64)
                      "an ML-DSA-44 key under the ml-dsa-65 tag" (line "alice" fx/public-key-44-b64)
                      "not base64" (line "alice" "!!!")
                      "a pattern as actor" (line "al*ce" fx/public-key-b64)
                      "no namespaces option" (str "alice ml-dsa-65 " fx/public-key-b64)
                      "a negated namespace pattern" (str "alice namespaces=\"tik-*,!tik-witness\" ml-dsa-65 "
                                                         fx/public-key-b64)
                      "a ? namespace pattern" (str "alice namespaces=\"tik-?vent\" ml-dsa-65 "
                                                   fx/public-key-b64)}]
    (testing why
      (let [e (try (pqsig/parse-registry (str "\n" text)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= 2 (:line (ex-data e))))))))

(deftest one-key-cannot-speak-for-two-actors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two actors"
                        (pqsig/parse-registry (str (line "alice" fx/public-key-b64) "\n"
                                                   (line "bob" fx/public-key-b64))))))

(deftest a-sidecar-counts-only-for-its-registered-actor-and-namespace
  (let [reg (pqsig/parse-registry (line "alice" fx/public-key-b64))
        ok {:actor "alice" :fpr fx/fingerprint :signature fx/signature
            :event-bytes fx/event-bytes :namespace "tik-event"}]
    (is (true? (pqsig/check-event reg ok)))
    (is (false? (pqsig/check-event reg (assoc ok :actor "bob"))))
    (is (false? (pqsig/check-event reg (assoc ok :fpr (str/join (repeat 64 "0"))))))
    (is (false? (pqsig/check-event reg (assoc ok :namespace "git"))))
    (is (false? (pqsig/check-event
                 (pqsig/parse-registry (str "alice namespaces=\"tik-witness\" ml-dsa-65 "
                                            fx/public-key-b64))
                 ok)))))

(deftest a-sidecar-name-carries-the-key-fingerprint
  (is (= fx/fingerprint (pqsig/sidecar-fpr (str "sha256-ab.pqsig." fx/fingerprint))))
  (is (nil? (pqsig/sidecar-fpr "sha256-ab.sig.abc")))
  (is (nil? (pqsig/sidecar-fpr "sha256-ab.pqsig.XYZ"))))

(deftest a-pkcs8-private-key-signs-what-the-verifier-accepts
  (doseq [[source key] [["the JDK" (keys/jdk-key)] ["OpenSSL" (keys/openssl-key)]]
          :when key]
    (testing source
      (let [sig (pqsig/sign-event (pqsig/read-private-key (:pem key)) fx/event-bytes)]
        (is (= 3309 (alength ^bytes sig)))
        (is (true? (pqsig/verify-event (:public-key key) sig fx/event-bytes)))))))

(deftest a-malformed-private-key-is-refused-plainly
  (doseq [pem ["" "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n" "not a pem" nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PKCS#8"
                          (pqsig/read-private-key pem))
        (pr-str pem))))

(deftest the-signer-is-the-registered-key-that-verifies
  (let [key (keys/jdk-key)
        reg (pqsig/parse-registry (str (line "alice" fx/public-key-b64) "\n"
                                       (line "alice" (:public-key-b64 key))))
        sig (pqsig/sign-event (pqsig/read-private-key (:pem key)) fx/event-bytes)]
    (is (= (pqsig/fingerprint (:public-key key))
           (:fpr (pqsig/signer-entry reg "alice" sig fx/event-bytes))))
    (is (nil? (pqsig/signer-entry reg "bob" sig fx/event-bytes)))
    (is (nil? (pqsig/signer-entry (pqsig/parse-registry "# no ML-DSA-65 key\n")
                                  "alice" sig fx/event-bytes)))))

(deftest a-witness-countersignature-is-its-own-domain
  (let [{:keys [pem public-key public-key-b64]} (keys/jdk-key)
        k (pqsig/read-private-key pem)
        bytes (.getBytes "{:event/actor \"seb\"}" "UTF-8")
        witness (pqsig/sign-witness k bytes)
        authorship (pqsig/sign-event k bytes)
        fpr (pqsig/fingerprint public-key)
        registry #(pqsig/parse-registry (str "wit namespaces=\"" % "\" ml-dsa-65 " public-key-b64))]
    (is (= "tik-witness-v1\u0000" (String. ^bytes (pqsig/witness-message (byte-array 0)) "UTF-8")))
    (is (true? (pqsig/verify-witness public-key witness bytes)))
    (testing "neither signature passes as the other"
      (is (false? (pqsig/verify-event public-key witness bytes)))
      (is (false? (pqsig/verify-witness public-key authorship bytes))))
    (testing "a witness counts through a key allowed to witness"
      (is (= "wit" (pqsig/check-witness (registry "tik-witness") {:fpr fpr :signature witness :event-bytes bytes})))
      (is (= "wit" (pqsig/check-witness (registry "tik-*") {:fpr fpr :signature witness :event-bytes bytes})))
      (is (nil? (pqsig/check-witness (registry "tik-event") {:fpr fpr :signature witness :event-bytes bytes})))
      (is (nil? (pqsig/check-witness (registry "tik-witness") {:fpr fpr :signature authorship :event-bytes bytes}))))
    (is (= fpr (:fpr (pqsig/entry-of-private-key (registry "tik-witness") k))))
    (is (= fpr (pqsig/sidecar-fpr (str "sha256-ab.pqwitness." fpr))))))
