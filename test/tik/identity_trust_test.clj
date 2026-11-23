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
  [{:keys [iss sub iat exp aud] :or {iss issuer sub subject aud "tik"
                                     iat 1782000000 exp 1782000900}}]
  (str "{\"iss\":\"" iss "\",\"sub\":\"" sub "\",\"aud\":\"" aud "\","
       "\"iat\":" iat ",\"exp\":" exp "}"))

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
  "A registry log carrying one binding, for actor `ci` unless named."
  [{:keys [token at key actor sub]
    :or {key "ssh-ed25519 AAAAC3Kci" at live-at actor "ci" sub subject}}]
  (let [create (event/create-ticket {:ticket tid :actor "seb" :at t0
                                     :title "identity registry"
                                     :process :identity-registry})]
    [create
     (event/add-attestation
      {:ticket tid :actor actor :at at :parents #{(:event/id create)}
       :claim {:claim :identity
               :identity/issuer issuer
               :identity/subject sub
               :identity/username "github-actions"
               :identity/actor actor
               :identity/public-key key
               :identity/id-token token}})]))

(defn- status [root evs]
  (trust/binding-status root (first (identity/bindings evs))))

(deftest a-binding-signed-by-the-pinned-issuer-is-trusted
  (let [root (store-with-pin! {})
        evs (registry-events {:token (jwt (claims-json {}))})]
    (is (= :trusted (status root evs)))
    (is (= {"ci" #{"ssh-ed25519 AAAAC3Kci"}}
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

(deftest a-token-without-an-expiry-is-never-live
  (let [root (store-with-pin! {})
        evs (registry-events
             {:token (jwt (str "{\"iss\":\"" issuer "\",\"sub\":\"" subject
                               "\",\"aud\":\"tik\",\"iat\":1782000000}"))
              :at (Instant/ofEpochSecond 1782000030)})]
    (is (= :identity/token-not-live (:reason (status root evs)))
        "an iat alone bounds the token from below only, so its key would speak for ever")
    (is (not (tik.identity-trust/token-live-at? {:iat 1782000000}
                                                (Instant/ofEpochSecond 1782000030))))))

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

(deftest the-signer-view-adds-rung-two-without-editing-actors
  (let [root (store-with-pin! {})
        actors (io/file root "actors")
        _ (spit actors "seb namespaces=\"tik-*\" ssh-ed25519 AAAAC3seb\n")
        evs (registry-events {:token (jwt (claims-json {}))})
        f ((trust/signer-view root evs) live-at)
        text (slurp f)]
    (is (re-find #"seb namespaces=\"tik-\*\" ssh-ed25519 AAAAC3seb" text)
        "rung 1 keeps verifying")
    (is (re-find #"ci namespaces=\"tik-\*\" ssh-ed25519 AAAAC3Kci" text)
        "rung 2 is added for this check")
    (is (not= (.getCanonicalPath ^java.io.File f) (.getCanonicalPath actors))
        "the curated registry is never rewritten")
    (is (= "seb namespaces=\"tik-*\" ssh-ed25519 AAAAC3seb\n" (slurp actors)))))

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

(deftest a-token-minted-for-another-service-cannot-bind-here
  (let [root (store-with-pin! {})
        evs (registry-events {:token (jwt (claims-json {}))})]
    (testing "the store expects its own audience"
      (is (= :identity/audience-mismatch
             (:reason (trust/binding-status root (first (identity/bindings evs))
                                            {:audience "some-other-service"})))))
    (testing "and accepts the one the token names"
      (is (= :trusted (trust/binding-status root (first (identity/bindings evs))
                                            {:audience "tik"}))))))

(deftest a-token-with-no-audience-fails-a-store-that-expects-one
  (let [root (store-with-pin! {})
        payload (str "{\"iss\":\"" issuer "\",\"sub\":\"" subject "\","
                     "\"iat\":1782000000,\"exp\":1782000900}")
        evs (registry-events {:token (jwt payload)})]
    (is (= :identity/audience-mismatch
           (:reason (trust/binding-status root (first (identity/bindings evs))
                                          {:audience "tik"})))
        "expecting an audience means a token carrying none does not qualify")))

(deftest an-audience-array-counts-when-it-names-us
  (let [root (store-with-pin! {})
        payload (str "{\"iss\":\"" issuer "\",\"sub\":\"" subject "\","
                     "\"aud\":[\"other\",\"tik\"],\"iat\":1782000000,"
                     "\"exp\":1782000900}")
        evs (registry-events {:token (jwt payload)})]
    (is (= :trusted (trust/binding-status root (first (identity/bindings evs))
                                          {:audience "tik"}))
        "aud is a string OR an array of them (RFC 7519)")))

(deftest a-store-declaring-an-audience-gets-the-check
  (let [root (store-with-pin! {})
        _ (spit (io/file root "oidc.edn") (pr-str {:audience "elsewhere"}))
        evs (registry-events {:token (jwt (claims-json {}))})]
    (is (= "elsewhere" (trust/expected-audience root)))
    (is (= :identity/audience-mismatch
           (:reason (trust/binding-status root (first (identity/bindings evs)))))
        "the expectation is read from the store, not passed in")))

(deftest a-store-declaring-none-says-so-rather-than-going-quiet
  (let [root (store-with-pin! {})
        evs (registry-events {:token (jwt (claims-json {}))})]
    (is (nil? (trust/expected-audience root)))
    (is (= :trusted (trust/binding-status root (first (identity/bindings evs))))
        "no expectation means no check")
    (is (seq (trust/unchecked-audiences root evs))
        "but the operator is told the anti-replay control is off")))

(deftest an-untrusted-binding-grants-nothing
  (let [root (store-with-pin! {:pin? false})
        _ (spit (io/file root "actors") "seb namespaces=\"tik-*\" ssh-ed25519 AAAAC3seb\n")
        evs (registry-events {:token (jwt (claims-json {}))})
        f ((trust/signer-view root evs) live-at)]
    (is (= "actors" (.getName ^java.io.File f))
        "with nothing verified the plain actors registry is what verification uses")))

;; --- which subject may bind which actor ---------------------------------

(def ^:private policy
  {"ci" [{:issuer issuer :subject subject}]})

(defn- policy-store!
  "A pinned store whose oidc.edn declares `bindings` verbatim."
  [bindings-text]
  (let [root (store-with-pin! {})]
    (spit (io/file root "oidc.edn") (str "{:audience \"tik\" :bindings "
                                         bindings-text "}"))
    root))

(deftest a-policy-admits-the-subject-it-names
  (let [root (policy-store! (pr-str policy))
        evs (registry-events {:token (jwt (claims-json {}))})]
    (is (= policy (trust/binding-policy root)))
    (is (= :trusted (status root evs)))
    (is (nil? (trust/unconstrained-bindings root evs))
        "a store with a policy is not told it lacks one")))

(deftest another-repository-cannot-bind-this-actor
  ;; the attack the policy exists for: a genuine token, from the pinned
  ;; issuer, with the expected audience, about a subject that is not ours
  (let [root (policy-store! (pr-str policy))
        evil "repo:attacker/evil:ref:refs/heads/main"
        evs (registry-events {:token (jwt (claims-json {:sub evil})) :sub evil})
        st (status root evs)]
    (is (= :identity/actor-not-permitted (:reason st)))
    (is (= ["ci" evil] [(:actor st) (:subject st)]) "the refusal names both")
    (is (empty? (trust/verified-bindings root evs)) "and it grants nothing")
    (testing "without a policy the same binding counts, and verify says why"
      (let [open (store-with-pin! {})]
        (is (= :trusted (status open evs)))
        (is (seq (trust/unconstrained-bindings open evs)))))))

(deftest our-subject-cannot-bind-an-actor-the-policy-does-not-grant-it
  (let [root (policy-store! (pr-str policy))
        evs (registry-events {:token (jwt (claims-json {})) :actor "seb"})]
    (is (= :identity/actor-not-permitted (:reason (status root evs))))))

(deftest a-policy-rule-names-its-issuer
  (let [root (policy-store! (pr-str {"ci" [{:issuer "https://other.example"
                                            :subject subject}]}))
        evs (registry-events {:token (jwt (claims-json {}))})]
    (is (= :identity/actor-not-permitted (:reason (status root evs)))
        "the same subject string from a different issuer is somebody else")))

(deftest a-forged-token-is-reported-as-forged-under-a-policy
  (let [other (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                  (.initialize 2048)))
        root (policy-store! (pr-str policy))
        evil "repo:attacker/evil:ref:refs/heads/main"
        evs (registry-events {:token (jwt other (claims-json {:sub evil}))
                              :sub evil})]
    (is (= :identity/bad-signature (:reason (status root evs))))))

(deftest a-policy-that-does-not-parse-permits-nothing
  (doseq [[label text] [["a bare string" "\"repo:metio/tik:*\""]
                        ["rules not a vector" "{\"ci\" {:issuer \"x\" :subject \"y\"}}"]
                        ["a rule without a subject" (pr-str {"ci" [{:issuer issuer}]})]
                        ["a blank subject" (pr-str {"ci" [{:issuer issuer :subject " "}]})]
                        ["a keyword actor" (pr-str {:ci [{:issuer issuer :subject subject}]})]]]
    (testing label
      (let [root (policy-store! text)
            evs (registry-events {:token (jwt (claims-json {}))})]
        (is (= :identity/malformed-policy (:reason (status root evs)))))))
  (testing "an oidc.edn that is not EDN at all"
    (let [root (store-with-pin! {})
          _ (spit (io/file root "oidc.edn") "{:audience \"tik\" :bindings {")
          evs (registry-events {:token (jwt (claims-json {}))})]
      (is (= :identity/malformed-policy (:reason (status root evs)))))))

(deftest a-subject-pattern-is-exact-or-a-spelled-out-prefix
  (is (trust/subject-matches? "repo:metio/tik:ref:refs/heads/main"
                              "repo:metio/tik:ref:refs/heads/main"))
  (is (not (trust/subject-matches? "repo:metio/tik:ref:refs/heads/main"
                                   "repo:metio/tik:ref:refs/heads/main2")))
  (is (trust/subject-matches? "repo:metio/tik:*" "repo:metio/tik:environment:release"))
  (is (not (trust/subject-matches? "repo:metio/tik:*" "repo:metio/tik-fork:ref:x"))
      "the separator before the wildcard is what keeps a fork out")
  (is (trust/subject-matches? "repo:metio/tik*" "repo:metio/tik-fork:ref:x")
      "and leaving it off admits the fork, which is why patterns spell it")
  (is (trust/subject-matches? "spiffe://example.org/ns/release/*"
                              "spiffe://example.org/ns/release/sa/tik"))
  (is (not (trust/subject-matches? "*x" "ax")) "only a trailing wildcard")
  (is (not (trust/subject-matches? nil "x")))
  (is (not (trust/permitted? ::nonsense {:actor "ci" :issuer issuer :subject subject}))))

;; --- a bound key speaks only while its token was live --------------------

(deftest a-bound-key-counts-only-inside-its-tokens-window
  ;; claims-json: iat 1782000000, exp 1782000900; leeway 60s
  (let [root (store-with-pin! {})
        actors (io/file root "actors")
        _ (spit actors "seb namespaces=\"tik-*\" ssh-ed25519 AAAAC3seb\n")
        evs (registry-events {:token (jwt (claims-json {}))})
        view (trust/signer-view root evs)
        grants? (fn [secs]
                  (boolean (re-find #"AAAAC3Kci"
                                    (slurp (view (Instant/ofEpochSecond secs))))))]
    (is (grants? 1782000300) "inside the window")
    (is (grants? 1782000950) "inside the leeway after expiry")
    (is (not (grants? 1782001000)) "after the window the key is a stranger")
    (is (not (grants? 1790000000)) "years later, a leaked key signs nothing")
    (is (not (grants? 1781999900)) "before the token existed")
    (is (= "actors" (.getName ^java.io.File (view (Instant/ofEpochSecond 1790000000))))
        "outside the window verification falls back to rung 1 alone")
    (is (identical? (view (Instant/ofEpochSecond 1782000100))
                    (view (Instant/ofEpochSecond 1782000200)))
        "one file per distinct grant set, not one per event")))

(deftest a-store-with-nothing-to-verify-against-has-no-view
  (let [root (store-with-pin! {:pin? false})
        evs (registry-events {:token (jwt (claims-json {}))})]
    (is (nil? (trust/signer-view root evs)))))

(deftest a-binding-key-cannot-add-a-signer-line-for-another-actor
  (let [root (store-with-pin! {})
        evs (registry-events {:token (jwt (claims-json {}))
                              :key "ssh-ed25519 AAAAC3Kci\nseb ssh-ed25519 AAAAC3evil"})]
    (spit (io/file root "actors") "ci namespaces=\"tik-*\" ssh-ed25519 AAAAC3Kci\n")
    (is (empty? (trust/verified-bindings root evs)) "a malformed key is no binding")
    (is (not (re-find #"AAAAC3evil" (slurp ((trust/signer-view root evs) live-at))))
        "and grants nothing")))

(deftest a-binding-actor-cannot-be-a-principal-pattern
  (doseq [actor ["*" "ci,seb" "s?b"]]
    (let [root (store-with-pin! {})
          evs (registry-events {:token (jwt (claims-json {})) :actor actor})]
      (is (empty? (trust/verified-bindings root evs)) actor))))
