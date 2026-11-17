;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.jwks-rotation-test
  "Pinned issuer keys are identified by kid and key material together.
  An issuer that publishes new material under a kid it used before
  leaves both keys pinned, and a token either one signed verifies."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.identity-trust :as trust]
            [tik.jwks :as jwks])
  (:import (java.security KeyPair KeyPairGenerator Signature)
           (java.security.interfaces RSAPublicKey)
           (java.util Base64)))

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- unsigned-bytes [^java.math.BigInteger bi]
  (let [bs (.toByteArray bi)]
    (if (and (> (count bs) 1) (zero? (aget bs 0))) (byte-array (rest bs)) bs)))

(defn- rsa-pair ^KeyPair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(defn- jwk [^KeyPair pair kid]
  (let [^RSAPublicKey pub (.getPublic pair)]
    {:kty "RSA" :alg "RS256" :kid kid
     :n (b64url (unsigned-bytes (.getModulus pub)))
     :e (b64url (unsigned-bytes (.getPublicExponent pub)))}))

(defn- signed [^KeyPair pair kid]
  (let [h (b64url (.getBytes (str "{\"alg\":\"RS256\",\"kid\":\"" kid "\"}") "UTF-8"))
        p (b64url (.getBytes "{\"sub\":\"x\"}" "UTF-8"))
        si (str h "." p)
        s (doto (Signature/getInstance "SHA256withRSA")
            (.initSign (.getPrivate pair))
            (.update (.getBytes si "US-ASCII")))]
    [si (.sign s) {:alg "RS256" :kid kid}]))

(deftest a-thumbprint-matches-the-rfc-8037-example
  ;; RFC 8037, appendix A.3
  (is (= "kPrK_qmxVWaYVA9wwBF6Iuo3vVzz7TxHCTwXBygrS4k"
         (jwks/thumbprint {:kty "OKP" :crv "Ed25519" :kid "ignored" :use "sig"
                           :x "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"}))))

(deftest a-thumbprint-needs-its-members
  (doseq [k [{:kty "RSA" :n "abc"} {:kty "EC" :crv "P-256" :x "a"}
             {:kty "oct" :k "secret"} {} {:kty "OKP" :crv "Ed25519" :x 42}]]
    (is (nil? (jwks/thumbprint k)) (pr-str k))))

(deftest every-key-under-a-kid-verifies-what-it-signed
  (let [old (rsa-pair) new* (rsa-pair) stranger (rsa-pair)
        verify (jwks/verifier {:keys [(jwk old "k1") (jwk new* "k1")]})]
    (testing "the key that signed yesterday's binding"
      (is (true? (apply verify (signed old "k1")))))
    (testing "the key published under the same kid today"
      (is (true? (apply verify (signed new* "k1")))))
    (testing "a key nobody pinned"
      (is (false? (apply verify (signed stranger "k1")))))
    (testing "a kid nobody pinned is refused rather than tried against everything"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no JWKS key matches kid"
                            (let [[si sig header] (signed old "k2")]
                              (verify si sig header)))))))

(deftest an-unusable-key-under-a-kid-does-not-hide-a-usable-one
  (let [pair (rsa-pair)
        verify (jwks/verifier {:keys [{:kty "EC" :crv "P-256" :kid "k1" :x "x" :y "y"}
                                      (jwk pair "k1")]})]
    (is (true? (apply verify (signed pair "k1"))))
    (is (false? (apply verify (signed (rsa-pair) "k1")))
        "a usable key that does not verify is a plain failure")))

(deftest the-pem-verifier-tries-every-pem-under-a-kid
  (let [old (rsa-pair) new* (rsa-pair)
        verify (jwks/pem-verifier {"k1" [(jwks/jwk->pem (jwk old "k1"))
                                         (jwks/jwk->pem (jwk new* "k1"))]})]
    (is (true? (apply verify (signed old "k1"))))
    (is (true? (apply verify (signed new* "k1"))))
    (is (false? (apply verify (signed (rsa-pair) "k1"))))))

(deftest re-pinning-keeps-old-material-under-a-reused-kid
  (let [old (jwk (rsa-pair) "k1")
        new* (jwk (rsa-pair) "k1")
        rotated (jwk (rsa-pair) "k2")]
    (testing "new material under a held kid joins the old"
      (is (= [old new* rotated]
             (:keys (trust/merge-jwks {:keys [old]} {:keys [new* rotated]}))))
      (is (= #{"k1"} (trust/reused-kids {:keys [old]} {:keys [new* rotated]}))))
    (testing "the same key spelled differently is the same key"
      (let [respelled (-> old (dissoc :alg) (assoc :use "sig"))]
        (is (= [old] (:keys (trust/merge-jwks {:keys [old]} {:keys [respelled]}))))
        (is (empty? (trust/reused-kids {:keys [old]} {:keys [respelled]})))))
    (testing "a fresh kid is a rotation, not a reuse"
      (is (empty? (trust/reused-kids {:keys [old]} {:keys [old rotated]})))
      (is (= [old rotated] (:keys (trust/merge-jwks {:keys [old]} {:keys [old rotated]})))))
    (testing "a key under the same kid twice in one fetch is kept once"
      (is (= [old] (:keys (trust/merge-jwks nil {:keys [old (assoc old :use "sig")]})))))))

(deftest a-pinned-file-stays-a-jwks
  ;; the file is what an operator commits and what other tools read, so
  ;; identity by thumbprint changes what counts as a duplicate, not the format
  (let [merged (trust/merge-jwks {:keys [(jwk (rsa-pair) "k1")]}
                                 {:keys [(jwk (rsa-pair) "k1")]})]
    (is (= #{:keys} (set (keys merged))))
    (is (every? #(str/blank? (str (:thumbprint %))) (:keys merged)))))
