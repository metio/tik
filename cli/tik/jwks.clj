;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.jwks
  "JWS signature verification against a JWKS — the credential-verifying
  half of the oid4vci bridge.

  Scope: EdDSA (Ed25519) only. Ed25519 is tik's native curve (its whole
  ecosystem is ssh-ed25519), and — crucially — it reconstructs a public
  key from raw bytes via `X509EncodedKeySpec`, the ONE key spec babashka
  exposes, so this namespace loads identically on bb, the JVM, and the
  GraalVM native binary with a plain static require. RS256/ES256 need
  `RSAPublicKeySpec`/`ECPublicKeySpec`, which bb's class allowlist lacks
  and which GraalVM would need reflect-config for — a deliberate
  follow-up; until then they are declined with a clear message, never a
  crash.

  The verifier is TOTAL over an untrusted signature: a malformed one is a
  verification FAILURE, not a throw."
  (:import (java.security KeyFactory Signature)
           (java.security.spec X509EncodedKeySpec)
           (java.util Base64)))

(defn- fail! [msg & {:as data}]
  (throw (ex-info msg (merge {:reason :oid4vci/malformed} data))))

(defn- b64url-decode ^bytes [^String s]
  (try (.decode (Base64/getUrlDecoder) s)
       (catch Exception e (throw (ex-info "not valid base64url"
                                          {:reason :oid4vci/malformed} e)))))

(defn parse-jwks
  "A JWKS JSON string -> `{:keys [{:kid :kty :crv :alg :x} …]}`.
  Fail-well on non-JSON."
  [json]
  (let [m (try ((requiring-resolve 'cheshire.core/parse-string) json true)
               (catch Exception e (throw (ex-info "JWKS is not valid JSON"
                                                  {:reason :oid4vci/bad-jwks} e))))]
    (when-not (and (map? m) (sequential? (:keys m)))
      (fail! "JWKS is not a JSON object with a \"keys\" array"
             :reason :oid4vci/bad-jwks))
    m))

(defn- ed25519-key
  "An Ed25519 public key from a JWK `x` (32 raw bytes, base64url): wrap in
  the fixed X.509 SubjectPublicKeyInfo prefix and let KeyFactory decode."
  [x-b64]
  (let [raw (b64url-decode x-b64)
        prefix (byte-array (map unchecked-byte
                                [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70
                                 0x03 0x21 0x00]))
        spki (byte-array (concat (seq prefix) (seq raw)))]
    (.generatePublic (KeyFactory/getInstance "Ed25519")
                     (X509EncodedKeySpec. spki))))

(defn- jwk->key
  "A JWK map -> a java.security.PublicKey, or a fail-well ex-info for an
  algorithm this build does not support."
  [{:keys [kty crv x] :as jwk}]
  (cond
    (and (= "OKP" kty) (= "Ed25519" crv))
    (try (ed25519-key x)
         (catch clojure.lang.ExceptionInfo e (throw e))
         (catch Throwable t (fail! "JWK key material is malformed"
                                   :cause (.getMessage t))))
    :else (fail! (str "unsupported JWK (kty=" kty " crv=" crv
                      "); this build verifies OKP/Ed25519 only (RS256/ES256"
                      " are a planned addition)")
                 :reason :oid4vci/unsupported-alg :jwk (dissoc jwk :d))))

(defn- jws-verify
  "Verify a JWS `signing-input`/`sig` against `pubkey` for `alg`. Returns
  a boolean. A MALFORMED signature — an invalid curve point, wrong length
  — is a verification FAILURE (false), not a crash: the signature is
  untrusted input, so any failure processing it means it does not verify.
  An unsupported alg is a clean ex-info."
  [alg pubkey ^String signing-input ^bytes sig]
  (when-not (= "EdDSA" alg)
    (fail! (str "unsupported JWS alg: " alg " (this build verifies EdDSA)")
           :reason :oid4vci/unsupported-alg))
  (try
    (let [v (Signature/getInstance "Ed25519")]
      (.initVerify v ^java.security.PublicKey pubkey)
      (.update v (.getBytes signing-input "US-ASCII"))
      (.verify v sig))
    (catch Exception _ false)))

(defn verifier
  "The verifier `tik.oid4vci/verify` injects, from a parsed JWKS map:
  pick the key by the JWT header's `kid` (or the sole key) and check the
  signature per its alg. Total — a missing/unsupported key fails well."
  [jwks]
  (let [keys* (:keys jwks)
        by-kid (into {} (map (juxt :kid identity)) keys*)]
    (fn [signing-input sig header]
      (let [jwk (or (get by-kid (:kid header))
                    (when (= 1 (count keys*)) (first keys*))
                    (fail! (str "no JWKS key matches kid " (:kid header))
                           :reason :oid4vci/no-key))
            alg (or (:alg header) (:alg jwk))]
        (jws-verify alg (jwk->key jwk) signing-input sig)))))
