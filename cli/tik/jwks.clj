;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.jwks
  "JWS signature verification against a JWKS — the credential-verifying
  half of the oid4vci bridge.

  Scope: EdDSA (Ed25519) and RS256 (RSA). Both reconstruct their public
  key through `X509EncodedKeySpec`, the ONE key spec babashka exposes, so
  this namespace loads identically on bb, the JVM, and the GraalVM native
  binary with a plain static require. Ed25519 wraps 32 raw bytes in a
  fixed SubjectPublicKeyInfo prefix; RSA has no fixed prefix because the
  modulus length varies, so `rsa-spki` encodes the DER by hand for the
  same reason — `RSAPublicKeySpec` is not on bb's allowlist and would
  need GraalVM reflect-config besides.

  RS256 is not an optional extra: it is what GitHub Actions and a stock
  Keycloak sign ID tokens with, so rung-2 bindings from either are
  uncheckable without it. ES256 still needs `ECPublicKeySpec` and is
  declined with a clear message, never a crash.

  The verifier is TOTAL over an untrusted signature: a malformed one is a
  verification FAILURE, not a throw."
  (:require [clojure.string :as str])
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

(defn- der-len
  "DER length octets: short form below 128, else long form."
  [n]
  (if (< n 128)
    [n]
    (let [bs (loop [v n acc ()] (if (zero? v) acc (recur (quot v 256) (conj acc (mod v 256)))))]
      (cons (bit-or 0x80 (count bs)) bs))))

(defn- der-tlv [tag content]
  (byte-array (map unchecked-byte (concat [tag] (der-len (count content)) content))))

(defn- der-int
  "An ASN.1 INTEGER from unsigned big-endian bytes. A leading zero is
  prepended when the high bit is set, because DER integers are signed and
  a modulus is not."
  [bytes*]
  (let [bs (seq bytes*)
        bs (drop-while zero? bs)
        bs (if (empty? bs) [0] bs)]
    (der-tlv 0x02 (if (>= (bit-and (first bs) 0xff) 0x80) (cons 0 bs) bs))))

(def ^:private rsa-oid
  "SEQUENCE { OID 1.2.840.113549.1.1.1 (rsaEncryption), NULL }"
  [0x30 0x0d 0x06 0x09 0x2a 0x86 0x48 0x86 0xf7 0x0d 0x01 0x01 0x01 0x05 0x00])

(defn- rsa-spki
  "A SubjectPublicKeyInfo for an RSA key from JWK `n`/`e`, encoded by
  hand so the key still arrives through X509EncodedKeySpec."
  [n-b64 e-b64]
  (let [n (map #(bit-and % 0xff) (seq (b64url-decode n-b64)))
        e (map #(bit-and % 0xff) (seq (b64url-decode e-b64)))
        pkcs1 (der-tlv 0x30 (concat (seq (der-int n)) (seq (der-int e))))
        bit-string (der-tlv 0x03 (cons 0 (map #(bit-and % 0xff) (seq pkcs1))))]
    (der-tlv 0x30 (concat rsa-oid (map #(bit-and % 0xff) (seq bit-string))))))

(defn jwk->pem
  "An RSA JWK as a PEM SubjectPublicKeyInfo — what an evidence bundle
  carries so a recipient can check a binding's token with openssl and
  nothing of ours. nil for a key type no shell verifier could use
  anyway, which the bundle reports rather than hides."
  [{:keys [kty n e]}]
  (when (and (= "RSA" kty) n e)
    (let [der (rsa-spki n e)
          b64 (.encodeToString (Base64/getEncoder) der)]
      (str "-----BEGIN PUBLIC KEY-----\n"
           (str/join "\n" (map str/join (partition-all 64 b64)))
           "\n-----END PUBLIC KEY-----\n"))))

(defn- rsa-key [n e]
  (.generatePublic (KeyFactory/getInstance "RSA")
                   (X509EncodedKeySpec. (rsa-spki n e))))

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
  [{:keys [kty crv x n e] :as jwk}]
  (cond
    (and (= "OKP" kty) (= "Ed25519" crv))
    (try (ed25519-key x)
         (catch clojure.lang.ExceptionInfo e (throw e))
         (catch Throwable t (fail! "JWK key material is malformed"
                                   :cause (.getMessage t))))

    (and (= "RSA" kty) n e)
    (try (rsa-key n e)
         (catch clojure.lang.ExceptionInfo ex (throw ex))
         (catch Throwable t (fail! "JWK key material is malformed"
                                   :cause (.getMessage t))))

    :else (fail! (str "unsupported JWK (kty=" kty " crv=" crv
                      "); this build verifies OKP/Ed25519 and RSA (ES256"
                      " is a planned addition)")
                 :reason :oid4vci/unsupported-alg :jwk (dissoc jwk :d))))

(defn- jws-verify
  "Verify a JWS `signing-input`/`sig` against `pubkey` for `alg`. Returns
  a boolean. A MALFORMED signature — an invalid curve point, wrong length
  — is a verification FAILURE (false), not a crash: the signature is
  untrusted input, so any failure processing it means it does not verify.
  An unsupported alg is a clean ex-info."
  [alg pubkey ^String signing-input ^bytes sig]
  (when-not (#{"EdDSA" "RS256"} alg)
    (fail! (str "unsupported JWS alg: " alg
                " (this build verifies EdDSA and RS256)")
           :reason :oid4vci/unsupported-alg))
  (try
    (let [v (Signature/getInstance (if (= "RS256" alg) "SHA256withRSA" "Ed25519"))]
      (.initVerify v ^java.security.PublicKey pubkey)
      (.update v (.getBytes signing-input "US-ASCII"))
      (.verify v sig))
    (catch Exception _ false)))

(defn b64url-bytes
  "base64url text as raw bytes — a JWS signature, decoded."
  ^bytes [^String s]
  (b64url-decode s))

(defn- pem->key
  "A PEM SubjectPublicKeyInfo as a PublicKey for `alg`. The JWS header
  names the algorithm and `jws-verify` pins the Signature to the same
  one, so a header claiming an algorithm the key is not simply fails to
  verify."
  [alg ^String pem]
  (let [der (.decode (Base64/getMimeDecoder)
                     (-> pem
                         (str/replace #"-----[A-Z ]+-----" "")
                         (str/replace #"\s" "")))]
    (.generatePublic (KeyFactory/getInstance
                      (case alg "RS256" "RSA" "EdDSA" "Ed25519"
                            (fail! (str "unsupported JWS alg: " alg)
                                   :reason :oid4vci/unsupported-alg)))
                     (X509EncodedKeySpec. der))))

(defn pem-verifier
  "The same verification an evidence bundle's `verify.sh` does with
  openssl, in process: pick the issuer key by the JWT header's `kid` from
  a {kid PEM} map and check the signature. A bundle carries PEMs rather
  than a JWKS because openssl reads one and nothing of ours is needed."
  [kid->pem]
  (fn [signing-input sig header]
    (let [kid (str (:kid header))
          pem (or (get kid->pem kid)
                  (when (= 1 (count kid->pem)) (val (first kid->pem)))
                  (fail! (str "no pinned issuer key matches kid " kid)
                         :reason :oid4vci/no-key))
          alg (:alg header)]
      (jws-verify alg (pem->key alg pem) signing-input sig))))

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
