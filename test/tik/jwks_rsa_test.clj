;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.jwks-rsa-test
  "RS256 against a real generated key. GitHub Actions and a stock Keycloak
  both sign ID tokens with RS256, so a rung-2 binding from either is
  uncheckable without this path — and the key has to arrive through
  X509EncodedKeySpec, the only spec babashka exposes, which is why the
  SubjectPublicKeyInfo is encoded by hand rather than via RSAPublicKeySpec."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.jwks :as jwks])
  (:import (java.security KeyPairGenerator Signature)
           (java.security.interfaces RSAPublicKey)
           (java.util Base64)))

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- unsigned-bytes
  "Big-endian magnitude, without the sign byte BigInteger prepends."
  [^java.math.BigInteger bi]
  (let [bs (.toByteArray bi)]
    (if (and (> (count bs) 1) (zero? (aget bs 0))) (byte-array (rest bs)) bs)))

(def ^:private pair
  (delay (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                             (.initialize 2048)))))

(defn- jwks-json [kid]
  (let [^RSAPublicKey pub (.getPublic @pair)]
    (str "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"kid\":\"" kid "\","
         "\"n\":\"" (b64url (unsigned-bytes (.getModulus pub))) "\","
         "\"e\":\"" (b64url (unsigned-bytes (.getPublicExponent pub))) "\"}]}")))

(defn- sign-jwt [kid payload]
  (let [header (str "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" kid "\"}")
        h (b64url (.getBytes header "UTF-8"))
        p (b64url (.getBytes ^String payload "UTF-8"))
        signing-input (str h "." p)
        s (doto (Signature/getInstance "SHA256withRSA")
            (.initSign (.getPrivate @pair))
            (.update (.getBytes signing-input "US-ASCII")))]
    (str signing-input "." (b64url (.sign s)))))

(defn- verify [jwks-str jwt]
  (let [[h p sig] (str/split jwt #"\.")
        header {:alg "RS256" :kid "k1"}]
    ((jwks/verifier (jwks/parse-jwks jwks-str))
     (str h "." p)
     (.decode (Base64/getUrlDecoder) ^String sig)
     header)))

(deftest an-rs256-token-verifies-against-its-jwks
  (let [jwt (sign-jwt "k1" "{\"iss\":\"https://idp.example\",\"sub\":\"repo:metio/tik\"}")]
    (is (true? (boolean (verify (jwks-json "k1") jwt)))
        "the modulus survives the hand-encoded SubjectPublicKeyInfo")))

(deftest a-tampered-payload-does-not-verify
  (let [jwt (sign-jwt "k1" "{\"sub\":\"repo:metio/tik\"}")
        [h _ sig] (str/split jwt #"\.")
        forged (str h "." (b64url (.getBytes "{\"sub\":\"repo:attacker/evil\"}" "UTF-8"))
                    "." sig)]
    (is (false? (boolean (verify (jwks-json "k1") forged)))
        "swapping the subject must not keep the signature valid")))

(deftest a-signature-from-another-key-does-not-verify
  (let [jwt (sign-jwt "k1" "{\"sub\":\"repo:metio/tik\"}")
        other (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                  (.initialize 2048)))
        ^RSAPublicKey pub (.getPublic other)
        foreign (str "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"kid\":\"k1\","
                     "\"n\":\"" (b64url (unsigned-bytes (.getModulus pub))) "\","
                     "\"e\":\"" (b64url (unsigned-bytes (.getPublicExponent pub))) "\"}]}")]
    (is (false? (boolean (verify foreign jwt))))))

(deftest a-malformed-signature-fails-rather-than-crashes
  (testing "an untrusted signature is input, so any failure means false"
    (let [jwt (sign-jwt "k1" "{\"sub\":\"x\"}")
          [h p _] (str/split jwt #"\.")]
      (is (false? (boolean (verify (jwks-json "k1") (str h "." p "." "AAAA"))))))))

(deftest an-unsupported-algorithm-is-declined-cleanly
  (let [jwt (sign-jwt "k1" "{\"sub\":\"x\"}")
        [h p sig] (str/split jwt #"\.")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"unsupported JWS alg"
         ((jwks/verifier (jwks/parse-jwks (jwks-json "k1")))
          (str h "." p)
          (.decode (Base64/getUrlDecoder) ^String sig)
          {:alg "ES256" :kid "k1"})))))
