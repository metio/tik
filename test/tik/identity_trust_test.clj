;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.identity-trust-test
  "Rung 2 against real key material: a generated RSA key stands in for the
  IdP, and every way a binding can fail to earn trust gets its own case.
  These are the ones that matter — a trust base that accepts too much is
  the whole risk of reading identity off a log a stranger can append to."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.harness :as h]
            [tik.identity :as identity]
            [tik.identity-trust :as trust])
  (:import (java.security KeyPairGenerator Signature)
           (java.security.interfaces RSAPublicKey)
           (java.time Instant)
           (java.util Base64)))

(def ^:private tid #uuid "018f2f6e-7c1a-7000-8000-00000000beef")
(def ^:private issuer "https://token.actions.githubusercontent.com")
(def ^:private subject "repo:metio/tik:ref:refs/heads/main")
(def ^:private t0 (Instant/parse "2026-07-01T10:00:00Z"))

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- unsigned [^java.math.BigInteger bi]
  (let [bs (.toByteArray bi)]
    (if (and (> (count bs) 1) (zero? (aget bs 0))) (byte-array (rest bs)) bs)))

(def ^:private idp (delay (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                              (.initialize 2048)))))

(defn- jwks-json [^java.security.KeyPair kp]
  (let [^RSAPublicKey pub (.getPublic kp)]
    (str "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"kid\":\"k1\",\"n\":\""
         (b64url (unsigned (.getModulus pub))) "\",\"e\":\""
         (b64url (unsigned (.getPublicExponent pub))) "\"}]}")))

(defn- jwt
  "A signed id-token. `claims` is the payload map as JSON-ish pairs."
  ([claims] (jwt @idp claims))
  ([^java.security.KeyPair kp claims]
   (let [h (b64url (.getBytes "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}" "UTF-8"))
         p (b64url (.getBytes ^String claims "UTF-8"))
         si (str h "." p)
         s (doto (Signature/getInstance "SHA256withRSA")
             (.initSign (.getPrivate kp))
             (.update (.getBytes si "US-ASCII")))]
     (str si "." (b64url (.sign s))))))

(defn- claims-json
  [{:keys [iss sub iat exp] :or {iss issuer sub subject
                                 iat 1782000000 exp 1782000900}}]
  (str "{\"iss\":\"" iss "\",\"sub\":\"" sub "\",\"iat\":" iat ",\"exp\":" exp "}"))

;; the binding is written inside the token's window by default
(def ^:private live-at (Instant/ofEpochSecond 1782000300))

(defn- store-with-pin!
  "A temp root with the IdP's keys pinned, unless :pin? is false."
  [{:keys [pin? kp] :or {pin? true kp @idp}}]
  (let [root (h/temp-dir! "tik-trust")]
    (when pin?
      (let [f (trust/jwks-file root issuer)]
        (io/make-parents f)
        (spit f (jwks-json kp))))
    root))

(defn- registry-events
  "A registry log carrying one binding for actor `ci`."
  [{:keys [token at key] :or {key "ssh-ed25519 AAAAC3-ci" at live-at}}]
  (let [create (event/create-ticket {:ticket tid :actor "seb" :at t0
                                     :title "identity registry"
                                     :process :identity-registry})]
    [create
     (event/add-attestation
      {:ticket tid :actor "ci" :at at :parents #{(:event/id create)}
       :claim {:claim :identity
               :identity/issuer issuer
               :identity/subject subject
               :identity/username "github-actions"
               :identity/actor "ci"
               :identity/public-key key
               :identity/id-token token}})]))

(defn- status [root evs]
  (trust/binding-status root (first (identity/bindings evs))))

(deftest a-binding-signed-by-the-pinned-issuer-is-trusted
  (let [root (store-with-pin! {})
        evs (registry-events {:token (jwt (claims-json {}))})]
    (is (= :trusted (status root evs)))
    (is (= {"ci" #{"ssh-ed25519 AAAAC3-ci"}}
           (identity/signing-keys (trust/verified-bindings root evs))))))

(deftest an-issuer-nobody-pinned-is-named-rather-than-called-a-bad-signature
  (let [root (store-with-pin! {:pin? false})
        evs (registry-events {:token (jwt (claims-json {}))})]
    (is (= :identity/no-pinned-jwks (:reason (status root evs)))
        "verify must say the keys are missing, not that the signature failed")
    (is (= 1 (count (trust/refusals root evs))))))

(deftest a-token-signed-by-another-key-is-refused
  (let [other (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                  (.initialize 2048)))
        root (store-with-pin! {})
        evs (registry-events {:token (jwt other (claims-json {}))})]
    (is (= :identity/bad-signature (:reason (status root evs))))))

(deftest a-token-about-somebody-else-cannot-endorse-this-actor
  (let [root (store-with-pin! {})
        evs (registry-events
             {:token (jwt (claims-json {:sub "repo:attacker/evil:ref:refs/heads/main"}))})]
    (is (= :identity/subject-mismatch (:reason (status root evs)))
        "a valid token must not endorse a body naming a different subject")))

(deftest a-token-from-another-issuer-is-refused
  (let [root (store-with-pin! {})
        evs (registry-events {:token (jwt (claims-json {:iss "https://evil.example"}))})]
    (is (= :identity/issuer-mismatch (:reason (status root evs))))))

(deftest a-leaked-expired-token-cannot-mint-a-binding-today
  (testing "the binding was written long after the token stopped working"
    (let [root (store-with-pin! {})
          evs (registry-events {:token (jwt (claims-json {}))
                                :at (Instant/ofEpochSecond 1790000000)})]
      (is (= :identity/token-not-live (:reason (status root evs)))))))

(deftest a-binding-written-before-its-token-existed-is-refused
  (let [root (store-with-pin! {})
        evs (registry-events {:token (jwt (claims-json {}))
                              :at (Instant/ofEpochSecond 1781000000)})]
    (is (= :identity/token-not-live (:reason (status root evs))))))

(deftest a-token-with-no-temporal-claims-cannot-be-tied-to-a-moment
  (let [root (store-with-pin! {})
        evs (registry-events
             {:token (jwt (str "{\"iss\":\"" issuer "\",\"sub\":\"" subject "\"}"))})]
    (is (= :identity/token-not-live (:reason (status root evs)))
        "with no iat or exp the token could be replayed into a binding at any time")))

(deftest a-mangled-token-fails-well
  (let [root (store-with-pin! {})]
    (doseq [[label t] [["not a jwt" "garbage"]
                       ["no signature" "aGVhZGVy.cGF5bG9hZA."]
                       ["not base64" "!!!.???.***"]]]
      (testing label
        (let [st (status root (registry-events {:token t}))]
          (is (contains? #{:identity/malformed-token :identity/bad-signature}
                         (:reason st))
              (str "expected a clean refusal, got " (pr-str st))))))))

(deftest clock-skew-inside-the-leeway-is-tolerated
  (let [root (store-with-pin! {})
        ;; thirty seconds past expiry, inside the sixty-second allowance
        evs (registry-events {:token (jwt (claims-json {}))
                              :at (Instant/ofEpochSecond 1782000930)})]
    (is (= :trusted (status root evs)))))

(deftest the-effective-signer-set-adds-rung-two-without-editing-actors
  (let [root (store-with-pin! {})
        actors (io/file root "actors")
        _ (spit actors "seb namespaces=\"tik-*\" ssh-ed25519 AAAAC3-seb\n")
        evs (registry-events {:token (jwt (claims-json {}))})
        f (trust/effective-signers root evs)
        text (slurp f)]
    (is (re-find #"seb namespaces=\"tik-\*\" ssh-ed25519 AAAAC3-seb" text)
        "rung 1 keeps verifying")
    (is (re-find #"ci namespaces=\"tik-\*\" ssh-ed25519 AAAAC3-ci" text)
        "rung 2 is added for this check")
    (is (not= (.getCanonicalPath ^java.io.File f) (.getCanonicalPath actors))
        "the curated registry is never rewritten")
    (is (= "seb namespaces=\"tik-*\" ssh-ed25519 AAAAC3-seb\n" (slurp actors)))))

(deftest inability-to-judge-is-distinguishable-from-evidence-of-forgery
  ;; verify fails on the second and only notes the first, so the reasons
  ;; have to stay tellable apart — an unpinned issuer grants nothing and
  ;; cannot be removed later (events are never deleted), while a bad
  ;; signature is somebody writing a binding the IdP never supported.
  (let [pinned (store-with-pin! {})
        unpinned (store-with-pin! {:pin? false})
        other (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                  (.initialize 2048)))
        good (registry-events {:token (jwt (claims-json {}))})
        forged (registry-events {:token (jwt other (claims-json {}))})]
    (is (= :identity/no-pinned-jwks (:reason (status unpinned good)))
        "cannot judge")
    (is (= :identity/bad-signature (:reason (status pinned forged)))
        "judged, and it failed")))

(deftest an-untrusted-binding-grants-nothing
  (let [root (store-with-pin! {:pin? false})
        _ (spit (io/file root "actors") "seb namespaces=\"tik-*\" ssh-ed25519 AAAAC3-seb\n")
        evs (registry-events {:token (jwt (claims-json {}))})
        f (trust/effective-signers root evs)]
    (is (= "actors" (.getName ^java.io.File f))
        "with nothing verified the plain actors registry is what verification uses")))
