;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.spiffe-test
  "SPIFFE JWT-SVIDs as rung-2 bindings: ECDSA-signed, with no `iss` (the
  trust domain in `sub` names the issuer) and possibly no `kid`, checked
  against a pinned SPIFFE bundle whose x509-svid keys must not count.
  Generated EC keys stand in for SPIRE; the bindings, verify, the bundle
  and its verify.sh are the real thing."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.identity-trust :as trust]
            [tik.jwks :as jwks])
  (:import (java.security KeyPair KeyPairGenerator Signature)
           (java.security.interfaces ECPublicKey)
           (java.security.spec ECGenParameterSpec)
           (java.time Instant)
           (java.util Base64)))

(def ^:private trust-domain "spiffe://example.org")
(def ^:private svid "spiffe://example.org/ns/ci/sa/release")

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- fixed
  "A coordinate as exactly `len` big-endian bytes, as a JWK carries it."
  [^java.math.BigInteger bi len]
  (let [bs (.toByteArray bi)
        bs (if (> (count bs) len) (byte-array (take-last len bs)) bs)]
    (byte-array (concat (repeat (- len (count bs)) 0) bs))))

(def ^:private curve-of
  {"P-256" {:name "secp256r1" :len 32 :alg "ES256" :sig "SHA256withECDSAinP1363Format"}
   "P-384" {:name "secp384r1" :len 48 :alg "ES384" :sig "SHA384withECDSAinP1363Format"}})

(defn- ec-pair ^KeyPair [crv]
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "EC")
                      (.initialize (ECGenParameterSpec. (:name (curve-of crv)))))))

(defn- ec-jwk [^KeyPair pair crv & {:as extra}]
  (let [^ECPublicKey pub (.getPublic pair)
        len (:len (curve-of crv))]
    (merge {:kty "EC" :crv crv
            :x (b64url (fixed (.getAffineX (.getW pub)) len))
            :y (b64url (fixed (.getAffineY (.getW pub)) len))}
           extra)))

(defn- jws
  "A compact JWS over `payload`, signed by `pair` on `crv`, with `kid`
  in the header unless it is nil."
  [^KeyPair pair crv kid payload]
  (let [{:keys [alg sig]} (curve-of crv)
        header (str "{\"alg\":\"" alg "\",\"typ\":\"JWT\""
                    (when kid (str ",\"kid\":\"" kid "\"")) "}")
        si (str (b64url (.getBytes header "UTF-8")) "."
                (b64url (.getBytes ^String payload "UTF-8")))
        s (doto (Signature/getInstance ^String sig)
            (.initSign (.getPrivate pair))
            (.update (.getBytes si "US-ASCII")))]
    (str si "." (b64url (.sign s)))))

(defn- check [verifier token]
  (let [[h p sig] (str/split token #"\.")]
    (verifier (str h "." p) (.decode (Base64/getUrlDecoder) ^String sig)
              (trust/token-header token))))

;; ------------------------------------------------------------------ units

(deftest ecdsa-tokens-verify-on-their-own-curve
  (doseq [crv ["P-256" "P-384"]]
    (testing crv
      (let [pair (ec-pair crv)
            v (jwks/verifier {:keys [(ec-jwk pair crv :kid "k1")]})]
        (is (true? (check v (jws pair crv "k1" "{\"sub\":\"x\"}"))))
        (is (false? (check v (jws (ec-pair crv) crv "k1" "{\"sub\":\"x\"}")))
            "another key on the same curve")))))

(deftest an-ecdsa-alg-is-bound-to-its-curve
  (let [p256 (ec-pair "P-256")
        token (jws p256 "P-256" "k1" "{\"sub\":\"x\"}")
        [h p sig] (str/split token #"\.")
        v (jwks/verifier {:keys [(ec-jwk p256 "P-256" :kid "k1")]})]
    (is (false? (v (str h "." p) (.decode (Base64/getUrlDecoder) ^String sig)
                   {:alg "ES384" :kid "k1"}))
        "a P-256 key does not answer for ES384")))

(deftest a-token-without-a-kid-tries-every-signing-key
  (let [a (ec-pair "P-256") b (ec-pair "P-256")
        v (jwks/verifier {:keys [(ec-jwk a "P-256" :kid "a") (ec-jwk b "P-256" :kid "b")]})]
    (is (true? (check v (jws b "P-256" nil "{\"sub\":\"x\"}"))))
    (is (false? (check v (jws (ec-pair "P-256") "P-256" nil "{\"sub\":\"x\"}"))))))

(deftest a-key-published-for-certificates-never-verifies-a-token
  (let [ca (ec-pair "P-256")
        v (jwks/verifier {:keys [(ec-jwk ca "P-256" :kid "ca" :use "x509-svid")
                                 (ec-jwk (ec-pair "P-256") "P-256" :kid "jwt" :use "jwt-svid")]})]
    (is (false? (check v (jws ca "P-256" nil "{\"sub\":\"x\"}"))))
    (is (false? (check v (jws ca "P-256" "ca" "{\"sub\":\"x\"}")))
        "naming its kid does not bring it back")))

(deftest an-ec-key-round-trips-through-pem
  (doseq [crv ["P-256" "P-384"]]
    (let [pair (ec-pair crv)
          pem (jwks/jwk->pem (ec-jwk pair crv :kid "k1"))
          v (jwks/pem-verifier {"k1" [pem]})]
      (is (str/starts-with? pem "-----BEGIN PUBLIC KEY-----"))
      (is (true? (check v (jws pair crv "k1" "{\"sub\":\"x\"}"))) crv))))

(deftest a-svid-names-its-trust-domain-as-issuer
  (is (= trust-domain (trust/token-issuer {:sub svid})))
  (is (= trust-domain (trust/token-issuer {:sub trust-domain})))
  (is (= "https://idp" (trust/token-issuer {:iss "https://idp" :sub svid}))
      "an iss that is present wins")
  (doseq [c [{:sub "repo:metio/tik"} {:sub "spiffe://"} {:sub 42} {} {:iss ""}]]
    (is (nil? (trust/token-issuer c)) (pr-str c))))

;; ------------------------------------------------------------ end to end

(defn- ssh-keygen! [dir name]
  (let [path (str (io/file dir name))]
    (sh/sh "ssh-keygen" "-t" "ed25519" "-N" "" "-C" name "-f" path)
    path))

(defn- ticket-id [out]
  (second (re-find #"([0-9a-f]{8}-[0-9a-f-]+)" out)))

(deftest a-jwt-svid-binds-a-pipeline-key
  (let [root (h/temp-dir! "tik-spiffe")
        repo (System/getProperty "user.dir")
        env {:root (str root) :actor "seb"}
        _ (io/make-parents (io/file root "processes" "x"))
        _ (h/run-tik! env "init")
        _ (io/copy (io/file repo "processes" "identity-registry.edn")
                   (io/file root "processes" "identity-registry.edn"))
        signer (ec-pair "P-256")
        ca (ec-pair "P-256")
        ;; a SPIFFE bundle: one CA key for X509-SVIDs, one key for JWT-SVIDs
        bundle (io/file root "bundle.json")
        _ (spit bundle (str "{\"spiffe_sequence\":1,\"keys\":["
                            (str/join "," (for [[pair use kid] [[ca "x509-svid" nil]
                                                                [signer "jwt-svid" "jwt-1"]]]
                                            (let [k (ec-jwk pair "P-256" :use use)
                                                  k (cond-> k kid (assoc :kid kid))]
                                              (str "{" (str/join "," (for [[f v] k]
                                                                       (str "\"" (name f) "\":\"" v "\"")))
                                                   "}"))))
                            "]}"))
        pin (h/run-tik! env "bridge" "jwks" "--issuer" trust-domain "--jwks" (str bundle))
        _ (spit (io/file root "oidc.edn")
                (pr-str {:audience "tik"
                         :bindings {"ci" [{:issuer trust-domain
                                           :subject "spiffe://example.org/ns/ci/*"}]}}))
        registry (ticket-id (:out (h/run-tik! env "new" "identity-registry"
                                              "--title" "identity registry")))
        ci-key (ssh-keygen! root "ci_key")
        ci-env (assoc env :actor "ci" :env {"TIK_KEY" ci-key})
        now (.getEpochSecond (Instant/now))
        bind! (fn [token]
                (let [tf (io/file root "svid.jwt")]
                  (spit tf token)
                  (h/run-tik! ci-env "bridge" "workload" "--registry" registry
                              "--token-file" (str tf) "--public-key" (str ci-key ".pub")
                              "--actor" "ci")))
        payload (str "{\"sub\":\"" svid "\",\"aud\":[\"tik#"
                     (trust/key-commitment (slurp (str ci-key ".pub")))
                     "\"],\"exp\":" (+ now 300) "}")]
    (testing "pinning keeps the JWT-SVID key and skips the CA key"
      (is (zero? (:exit pin)) (str (:out pin) (:err pin)))
      (is (str/includes? (:err pin) "skipped 1") (:err pin))
      (is (= 1 (count (:keys (trust/pinned-jwks root trust-domain))))))
    (testing "a token the CA key signed binds nothing"
      (let [r (bind! (jws ca "P-256" nil payload))]
        (is (not (zero? (:exit r))))
        (is (str/includes? (str (:out r) (:err r)) "bad-signature") (str (:out r) (:err r)))))
    (testing "a JWT-SVID with no iss and no kid binds"
      (let [r (bind! (jws signer "P-256" nil payload))]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (str/includes? (:out r) (str "bound " trust-domain " subject " svid)) (:out r))))
    (let [work (ticket-id (:out (h/run-tik! ci-env "new" "identity-registry"
                                            "--title" "signed by SPIRE's workload")))]
      (h/run-tik! ci-env "set" work "description=built" "--actor" "ci")
      (testing "verify credits the bound key"
        (let [v (h/run-tik! env "verify" work)]
          (is (zero? (:exit v)) (str (:out v) (:err v)))
          (is (re-find #"signed by ci\n" (:out v)) (:out v))))
      (testing "a bundle's verify.sh checks the ES256 token without tik"
        (let [tgz (io/file root "evidence.tgz")
              dest (h/temp-dir! "tik-spiffe-bundle")]
          (h/run-tik! env "bundle" work "--out" (str tgz))
          (sh/sh "tar" "xzf" (str tgz) "-C" (str dest))
          (let [r (sh/sh "sh" "./verify.sh" :dir (str dest))]
            (is (zero? (:exit r)) (str (:out r) (:err r)))
            (is (str/includes? (:out r) (str "ci is bound to its key by " svid)) (:out r)))
          (testing "and rederive under the reader's own policy and keys"
            (let [r (h/run-tik! env "rederive" (str tgz) "--expect-bindings"
                                (str (io/file root "oidc.edn")))]
              (is (zero? (:exit r)) (str (:out r) (:err r)))
              (is (str/includes? (:out r) "which your policy permits") (:out r)))))))))
