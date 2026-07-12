;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bridge-oid4vci-test
  "The oid4vci bridge end to end, exercised in-process on the JVM via
  run-argv (the product is the native binary; this tests the same code on
  JVM semantics, no bb subprocess): a real Ed25519 credential is verified
  against a local JWKS and lands as a bridge-signed attestation on the
  registry ticket, and a tampered one is refused."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli]
            [tik.harness :as h]
            [tik.store.protocol :as store])
  (:import (java.security KeyPairGenerator Signature)
           (java.util Arrays Base64)))

(defn- b64url [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- b64url-json [x]
  (b64url (.getBytes ^String ((requiring-resolve 'cheshire.core/generate-string) x)
                     "UTF-8")))

(defn- ed25519-vc+jwks
  "A real signed Ed25519 JWT-VC and the issuer JWKS that verifies it."
  []
  (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        x (b64url (Arrays/copyOfRange (.getEncoded (.getPublic kp)) 12 44))
        si (str (b64url-json {:alg "EdDSA" :kid "k1"})
                "." (b64url-json {:iss "https://issuer.test" :sub "did:ex:1"
                                  :vct "KYC" :name "Ada"}))
        sig (let [s (Signature/getInstance "Ed25519")]
              (.initSign s (.getPrivate kp))
              (.update s (.getBytes si "US-ASCII"))
              (.sign s))]
    {:vc (str si "." (b64url sig))
     :jwks {:keys [{:kid "k1" :kty "OKP" :crv "Ed25519" :alg "EdDSA" :x x}]}}))

(defn- registry-id [root]
  (h/with-cli-root root
    (fn []
      (tik.cli/run-argv ["new" "track" "--title" "identity registry"])
      (->> (:out (tik.cli/run-argv ["ls" "--edn"]))
           (re-seq #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
           first))))

(deftest bridge_oid4vci_ingests_a_verified_credential
  (let [{:keys [root store]} (h/temp-store!)
        {:keys [vc jwks]} (ed25519-vc+jwks)
        reg (registry-id root)]
    (spit (io/file root "vc.jwt") vc)
    (spit (io/file root "jwks.json")
          ((requiring-resolve 'cheshire.core/generate-string) jwks))
    (h/with-cli-root root
      (fn []
        (let [r (tik.cli/run-argv ["bridge" "oid4vci"
                                   "--credential" (str (io/file root "vc.jwt"))
                                   "--registry" reg
                                   "--jwks" (str (io/file root "jwks.json"))
                                   "--actor" "bridge-bot"])]
          (testing "a validly-signed credential is ingested, exit 0"
            (is (zero? (:exit r)) (:err r))
            (is (re-find #"ingested credential from https://issuer.test" (:out r))))
          (testing "it lands as a :credential attestation on the registry ticket"
            (let [evs (store/events store (java.util.UUID/fromString reg))
                  att (first (filter #(= :credential (get-in % [:event/body :claim]))
                                     evs))]
              (is (some? att) "an :attestation/add carrying the credential")
              (is (= "https://issuer.test" (get-in att [:event/body :credential/issuer])))
              (is (= "Ada" (get-in att [:event/body :credential/claims :name])))
              (is (string? (get-in att [:event/body :credential/raw]))))))))))

(deftest bridge_oid4vci_refuses_a_tampered_credential
  (let [{:keys [root]} (h/temp-store!)
        {:keys [vc jwks]} (ed25519-vc+jwks)
        ;; flip a char INSIDE the signature segment (not the final base64
        ;; char, whose spare bits a decode ignores) — same claims, a
        ;; signature that no longer verifies
        i (+ (str/last-index-of vc ".") 6)
        tampered (str (subs vc 0 i)
                      (if (= \A (.charAt ^String vc i)) \B \A)
                      (subs vc (inc i)))
        reg (registry-id root)]
    (spit (io/file root "vc.jwt") tampered)
    (spit (io/file root "jwks.json")
          ((requiring-resolve 'cheshire.core/generate-string) jwks))
    (h/with-cli-root root
      (fn []
        (let [r (tik.cli/run-argv ["bridge" "oid4vci"
                                   "--credential" (str (io/file root "vc.jwt"))
                                   "--registry" reg
                                   "--jwks" (str (io/file root "jwks.json"))])]
          (is (= 1 (:exit r)))
          (is (re-find #"does not verify" (:err r)))
          (is (h/clean-output? (str (:out r) (:err r)))))))))
