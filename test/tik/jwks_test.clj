;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.jwks-test
  "The JWS signature verifier (native-only crypto): a real credential
  signed with each supported algorithm verifies against its issuer JWK, a
  wrong key rejects it, an unsupported alg is a clean error, and the
  verifier is total over hostile signature bytes. Runs on the JVM (this
  crypto does not load under babashka — the product is the native binary)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.jwks :as jwks]
            [tik.oid4vci :as vc])
  (:import (java.security KeyPairGenerator Signature)
           (java.util Arrays Base64)))

(defn- b64url [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- b64url-json [x]
  (b64url (.getBytes ^String ((requiring-resolve 'cheshire.core/generate-string) x)
                     "UTF-8")))

(defn- gen-ed25519-jwt
  "A real signed Ed25519 JWT and the public JWK a real issuer publishes."
  []
  (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        signing-input (str (b64url-json {:alg "EdDSA" :kid "k1"})
                           "." (b64url-json {:iss "https://issuer.example"
                                             :sub "s1" :vct "T" :name "Zed"}))
        sig (let [sg (Signature/getInstance "Ed25519")]
              (.initSign sg (.getPrivate kp))
              (.update sg (.getBytes signing-input "US-ASCII"))
              (.sign sg))]
    {:jwt (str signing-input "." (b64url sig))
     :jwk {:kid "k1" :alg "EdDSA" :kty "OKP" :crv "Ed25519"
           :x (b64url (Arrays/copyOfRange (.getEncoded (.getPublic kp)) 12 44))}}))

(deftest verifier_round_trips_ed25519
  (let [{:keys [jwt jwk]} (gen-ed25519-jwt)]
    (is (= "https://issuer.example"
           (:issuer (vc/verify jwt (jwks/verifier {:keys [jwk]}))))
        "an Ed25519 credential verifies against its issuer JWK")
    (testing "a different key rejects it"
      (let [wrong (jwks/verifier {:keys [(assoc (:jwk (gen-ed25519-jwt)) :kid "k1")]})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not verify"
                              (vc/verify jwt wrong)))))))

(deftest verifier_declines_unsupported_alg
  (testing "a non-Ed25519 kty (RS256/ES256 are a planned addition)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"unsupported"
         ((jwks/verifier {:keys [{:kid "k1" :kty "RSA" :n "x" :e "AQAB"}]})
          "a.b" (byte-array 8) {:alg "RS256" :kid "k1"})))))

(defspec verifier_is_total_over_hostile_signatures 200
  ;; the signature is untrusted input; garbage bytes are a verification
  ;; FAILURE (false), an invalid curve point / bad DER too — never a raw
  ;; SignatureException surfaced as a crash.
  (let [{:keys [jwk]} (gen-ed25519-jwt)
        verify (jwks/verifier {:keys [jwk]})]
    (prop/for-all [bytes gen/bytes]
      (let [out (try (verify "a.b" bytes {:alg "EdDSA" :kid "k1"})
                     (catch clojure.lang.ExceptionInfo _ :rejected)
                     (catch Throwable _ :crash))]
        (or (boolean? out) (= :rejected out))))))
