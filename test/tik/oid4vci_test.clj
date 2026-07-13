;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.oid4vci-test
  "The OID4VCI credential parser: both wire formats normalize to the same
  shape, selective-disclosure digests are enforced, the verifier seam
  gates on the issuer signature, and parsing is total over hostile input."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.oid4vci :as vc])
  (:import (java.security MessageDigest)
           (java.util Base64)))

;; --------------------------------------------------------- fixtures

(defn- b64url [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- b64url-json [x]
  (b64url (.getBytes ^String ((requiring-resolve 'cheshire.core/generate-string) x)
                     "UTF-8")))

(defn- jwt
  "A JWT with a fixed dummy signature (parsing does not check it)."
  [payload]
  (str (b64url-json {:alg "EdDSA" :typ "vc+jwt"}) "." (b64url-json payload) ".sig"))

(defn- disclosure
  "A base64url([salt name value]) object-property disclosure."
  [nm value]
  (b64url-json ["seedsalt" nm value]))

(defn- sd-digest [^String disclosure]
  (b64url (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes disclosure "US-ASCII"))))

;; --------------------------------------------------------- tests

(deftest jwt_vc_normalizes_w3c_shape
  (let [cred (vc/parse-credential
              (jwt {:iss "https://issuer.example" :sub "did:example:42"
                    :cnf {:jwk {:kty "OKP" :x "holderkey"}}
                    :vc {:type "KYCCredential"
                         :credentialSubject {:name "Alice" :over18 true}}}))]
    (is (= :jwt-vc (:format cred)))
    (is (= "https://issuer.example" (:issuer cred)))
    (is (= "did:example:42" (:subject cred)))
    (is (= "KYCCredential" (:type cred)))
    (is (= {:kty "OKP" :x "holderkey"} (:holder-key cred)))
    (is (= {:name "Alice" :over18 true} (:claims cred)))))

(deftest sd_jwt_vc_merges_only_committed_disclosures
  (let [d-name (disclosure "given_name" "Alice")
        d-age  (disclosure "age_over_18" true)
        payload {:iss "https://kyc.example" :sub "u1" :vct "IdentityCredential"
                 :_sd [(sd-digest d-name) (sd-digest d-age)]}
        sdjwt (str (jwt payload) "~" d-name "~" d-age "~")]
    (testing "committed disclosures merge into the top-level claims"
      (let [cred (vc/parse-credential sdjwt)]
        (is (= :sd-jwt-vc (:format cred)))
        (is (= "IdentityCredential" (:type cred)))
        (is (= "Alice" (get-in cred [:claims :given_name])))
        (is (true? (get-in cred [:claims :age_over_18])))))
    (testing "a disclosure the issuer did NOT commit in _sd is rejected"
      (let [forged (disclosure "is_admin" true)
            tampered (str (jwt payload) "~" d-name "~" forged "~")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not committed"
                              (vc/parse-credential tampered)))))))

(deftest credential_claim_is_the_attestation_body
  (let [cred (vc/parse-credential
              (jwt {:iss "iss" :sub "sub" :vct "T"
                    :name "Bob"}))
        body (vc/credential-claim cred "bridge-bot")]
    (is (= :credential (:claim body)))
    (is (= "iss" (:credential/issuer body)))
    (is (= "sub" (:credential/subject body)))
    (is (= "bridge-bot" (:credential/actor body)))
    (is (= {:name "Bob"} (:credential/claims body)))
    (is (string? (:credential/raw body)))))

(deftest verify_gates_on_the_injected_verifier
  (let [cred-str (jwt {:iss "iss" :sub "sub"})]
    (testing "a passing verifier returns the parsed credential"
      (is (= "iss" (:issuer (vc/verify cred-str (constantly true))))))
    (testing "the verifier receives signing-input, sig bytes, and header"
      (let [seen (atom nil)]
        (vc/verify cred-str (fn [si sig hdr] (reset! seen [si (class sig) hdr]) true))
        (is (str/includes? (first @seen) "."))
        (is (= (Class/forName "[B") (second @seen)))
        (is (= "EdDSA" (:alg (nth @seen 2))))))
    (testing "a failing verifier is a clean bad-signature rejection"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not verify"
                            (vc/verify cred-str (constantly false)))))))

(deftest verify_enforces_the_validity_window_at_ingest
  ;; a signature-valid but EXPIRED (or not-yet-valid, or wrong-audience)
  ;; credential must NOT be accepted — else a genuinely-issuer-signed
  ;; past credential replays into a fresh, current bridge attestation.
  ;; exp/nbf are NumericDates (epoch seconds); the bridge passes :now.
  (let [ok (constantly true)]
    (testing "expired: now at/after exp is rejected"
      (let [c (jwt {:iss "iss" :sub "sub" :exp 1000})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expired"
                              (vc/verify c ok {:now 2000})))
        (is (= "iss" (:issuer (vc/verify c ok {:now 500})))
            "before exp it verifies")
        (is (= "iss" (:issuer (vc/verify c ok)))
            "without :now the offline seam skips the temporal check")))
    (testing "leeway absorbs small clock skew at the boundary"
      (let [c (jwt {:iss "iss" :exp 1000})]
        (is (= "iss" (:issuer (vc/verify c ok {:now 1005 :leeway 60}))))))
    (testing "not-yet-valid: now before nbf is rejected"
      (let [c (jwt {:iss "iss" :nbf 5000})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not yet valid"
                              (vc/verify c ok {:now 1000})))))
    (testing "audience: enforced only when an expected audience is configured"
      (let [c (jwt {:iss "iss" :aud ["rp-a" "rp-b"]})]
        (is (= "iss" (:issuer (vc/verify c ok {:now 1 :audience "rp-a"}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"audience"
                              (vc/verify c ok {:now 1 :audience "rp-c"})))
        (is (= "iss" (:issuer (vc/verify c ok {:now 1})))
            "no configured audience -> no aud constraint")))
    (testing "a non-numeric exp cannot be enforced and does not throw"
      (let [c (jwt {:iss "iss" :exp "whenever"})]
        (is (= "iss" (:issuer (vc/verify c ok {:now 9999}))))))))

(deftest malformed_credentials_fail_well
  (doseq [bad ["" "not-a-jwt" "a.b" "~~~"
               (jwt [1 2 3])                          ; payload is an array
               (str (jwt {:iss "i" :_sd 5}) "~" (disclosure "x" 1) "~")  ; _sd not a list
               (str (jwt {:iss "i"}) "~not-base64!@#~")]]         ; junk disclosure
    (is (thrown? clojure.lang.ExceptionInfo (vc/parse-credential bad))
        (str "should fail well: " (pr-str bad)))))

(defspec parse_credential_is_total_over_hostile_strings 300
  ;; a credential arrives from an untrusted issuer over the wire; any
  ;; string must yield a normalized map or a clean ex-info — never
  ;; another Throwable.
  (prop/for-all [s (gen/one-of
                    [gen/string
                     gen/string-alphanumeric
                     (gen/fmap #(str/join "~" %) (gen/vector gen/string 0 5))
                     (gen/fmap #(str/join "." %) (gen/vector gen/string 1 4))])]
    (try (map? (vc/parse-credential s))
         (catch clojure.lang.ExceptionInfo _ true)
         (catch Throwable _ false))))
