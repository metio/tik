;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.oid4vci
  "Parse a Verifiable Credential (an OID4VCI issuance output) into the
  normalized shape tik mints as an ATTESTATION event — the porcelain
  half of a would-be `tik bridge oid4vci`. A verifiable credential IS,
  in tik's ontology, a signed attestation whose claim is an external
  fact and whose signer is a third-party issuer (see docs/IDEAS.md); so
  the kernel needs nothing — this ns only structures the credential, and
  the bridge mints `credential-claim` via the existing attestation event.

  Two wire formats:
  - a plain JWT-VC (`<header>.<payload>.<sig>`);
  - an SD-JWT-VC (`<jwt>~<disclosure>~…~[<kb-jwt>]`) whose selectively
    disclosed claims are digest-checked against the issuer JWT's `_sd`
    array before they are merged — a disclosure the issuer did not
    commit to is rejected, so selective disclosure cannot smuggle a
    claim the issuer never signed.

  Pure and TOTAL, the tik contract: a hostile or malformed credential
  fails well with an ex-info {:reason :oid4vci/…}, never a raw throw.
  Parsing NEVER trusts a claim — signature verification is a SEAM
  (`verify` takes an injected verifier), so parsing stays offline and
  testable; the bridge supplies a real JWKS-backed verifier. What
  `tik verify` ultimately checks is the bridge's OWN signature over the
  minted attestation, plus the issuer registered in `actors` — never the
  issuer online (offline-forever, like the OIDC/email bridges)."
  (:require [clojure.string :as str]
            [tik.oidc :as oidc])
  (:import (java.security MessageDigest)
           (java.util Base64)))

(defn- fail! [msg & {:as data}]
  (throw (ex-info msg (merge {:reason :oid4vci/malformed} data))))

(defn- b64url-decode ^bytes [^String s]
  (try (.decode (Base64/getUrlDecoder) s)
       (catch Exception e (throw (ex-info "not valid base64url"
                                          {:reason :oid4vci/malformed} e)))))

(defn- json-parse [s]
  (try ((requiring-resolve 'cheshire.core/parse-string) s true)
       (catch Exception e
         (throw (ex-info "credential contains invalid JSON"
                         {:reason :oid4vci/malformed} e)))))

(defn- jwt-payload
  "The issuer JWT's claims as a map — reusing the OIDC bridge's total
  decoder — re-tagged with an :oid4vci reason and required to be a JSON
  object (a top-level array/scalar is not a credential)."
  [jwt]
  (let [p (try (oidc/decode-jwt-payload jwt)
               (catch clojure.lang.ExceptionInfo e
                 (fail! (str "credential JWT: " (ex-message e)) :cause (ex-data e))))]
    (when-not (map? p) (fail! "credential payload is not a JSON object"))
    p))

(defn- sd-digest
  "An SD-JWT disclosure's digest: base64url(SHA-256(ascii(disclosure)))
  without padding — what the issuer JWT's `_sd` array holds."
  [^String disclosure]
  (let [d (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes disclosure "US-ASCII"))]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) d)))

(defn- parse-disclosure
  "A disclosure token -> {:digest :name :value}. Object-property form is
  `base64url([salt, name, value])`; the array-element form ([salt,
  value]) is out of scope for the spike and rejected."
  [^String d]
  (let [arr (json-parse (String. (b64url-decode d) "UTF-8"))]
    ;; cheshire yields a vector on the JVM and a lazy seq under babashka
    ;; for a JSON array — sequential? covers both runtimes
    (when-not (and (sequential? arr) (= 3 (count arr)))
      (fail! "malformed SD-JWT disclosure (expected [salt name value])"))
    {:digest (sd-digest d) :name (str (nth arr 1)) :value (nth arr 2)}))

(defn- split-sd-jwt
  "`<jwt>~<disclosure>~…~[<kb-jwt>]` -> {:jwt :disclosures}. A trailing
  key-binding JWT (it alone contains dots) is not a disclosure and is
  dropped — the spike does not check holder key-binding proofs."
  [s]
  (let [parts (str/split s #"~")]
    {:jwt (first parts)
     :disclosures (->> (rest parts)
                       (remove str/blank?)
                       (remove #(str/includes? % ".")))}))

(defn- registered-claim? [k]
  (contains? #{:iss :sub :exp :iat :nbf :cnf :_sd :_sd_alg :vct :vc :jti :aud} k))

(defn parse-credential
  "A VC string -> a normalized map, or a fail-well ex-info:

    {:format     :jwt-vc | :sd-jwt-vc
     :issuer     iss
     :subject    sub
     :type       vct (IETF) or vc.type (W3C)
     :holder-key cnf.jwk — the holder key-binding, if any
     :claims     {keyword value} — top-level (IETF) or
                 vc.credentialSubject (W3C), with committed SD
                 disclosures merged in
     :raw        the credential as given}

  Total over hostile input: a non-string, a non-JWT, a non-object
  payload, a `_sd` that is not a list, a `vc.credentialSubject` that is
  not a map, or a disclosure whose digest the issuer did not commit all
  fail well."
  [s]
  (when-not (string? s) (fail! "credential must be a string"))
  (let [sd? (str/includes? s "~")
        {:keys [jwt disclosures]} (if sd? (split-sd-jwt s) {:jwt s :disclosures []})
        payload (jwt-payload jwt)
        sd-set (let [sd (:_sd payload)] (if (coll? sd) (set sd) #{}))
        discs (map parse-disclosure disclosures)
        _ (doseq [{:keys [digest name]} discs]
            (when-not (contains? sd-set digest)
              (fail! "SD-JWT disclosure not committed in the issuer's _sd"
                     :reason :oid4vci/disclosure-uncommitted :name name)))
        disclosed (into {} (map (fn [{:keys [name value]}] [(keyword name) value])) discs)
        w3c (let [cs (get-in payload [:vc :credentialSubject])]
              (if (map? cs) cs {}))
        top (into {} (remove (comp registered-claim? key)) payload)]
    {:format (if sd? :sd-jwt-vc :jwt-vc)
     :issuer (:iss payload)
     :subject (:sub payload)
     :type (or (:vct payload) (get-in payload [:vc :type]))
     :holder-key (let [c (:cnf payload)] (when (map? c) (:jwk c)))
     :claims (merge top w3c disclosed)
     :raw s}))

(defn credential-claim
  "The attestation body a parsed credential mints as — the analogue of
  `oidc/binding-claim`. `:credential/raw` carries the VC for re-audit;
  what `tik verify` checks is the BRIDGE actor's signature over this
  attestation (trust flows through the bridge, ADR 0019), never the
  issuer online — the issuer signature was checked once, at ingest."
  [{:keys [issuer subject type holder-key claims raw]} actor]
  {:claim :credential
   :credential/issuer issuer
   :credential/subject subject
   :credential/type type
   :credential/holder-key holder-key
   :credential/claims claims
   :credential/actor actor
   :credential/raw raw})

;; The JWS signature check against the issuer JWKS lives in `tik.jwks`
;; (native-only — full-JDK RSA/EC key specs babashka cannot load), kept
;; out of this ns's load graph so parsing stays bb-portable. The bridge
;; command lazy-requires tik.jwks and passes its `verifier` here.

(defn verify
  "Parse `s`, then check the issuer signature over the issuer JWT with an
  injected `verifier` — (signing-input-string, signature-bytes,
  header-map) -> boolean. Returns the parsed credential on success,
  throws {:reason :oid4vci/bad-signature} otherwise. A seam so parsing
  stays offline; the bridge supplies tik.jwks/verifier."
  [s verifier]
  (let [cred (parse-credential s)
        jwt (:jwt (split-sd-jwt s))
        [h p sig] (str/split (str jwt) #"\.")]
    (when (str/blank? sig) (fail! "issuer JWT has no signature segment"))
    (let [header (json-parse (String. (b64url-decode h) "UTF-8"))
          ok? (boolean (verifier (str h "." p) (b64url-decode sig) header))]
      (when-not ok?
        (throw (ex-info "credential issuer signature does not verify"
                        {:reason :oid4vci/bad-signature :issuer (:issuer cred)})))
      cred)))
