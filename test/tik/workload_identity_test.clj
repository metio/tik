;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.workload-identity-test
  "The claim rung 2 exists to make good on, end to end: a pipeline holds
  no long-lived secret, generates a keypair for one run, binds it with
  the OIDC token its platform already issues, signs its work with it —
  and the signature still verifies afterwards, because the binding is on
  the log and the issuer's keys are pinned in the store.

  A generated RSA key stands in for the IdP. Everything else is the real
  thing: real ssh-ed25519 keys, real `tik bridge workload`, real
  `tik verify`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.identity-trust :as trust])
  (:import (java.security KeyPairGenerator Signature)
           (java.security.interfaces RSAPublicKey)
           (java.time Instant)
           (java.util Base64)))

(def ^:private issuer "https://token.actions.githubusercontent.com")
(def ^:private subject "repo:metio/tik:ref:refs/heads/main")

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- unsigned [^java.math.BigInteger bi]
  (let [bs (.toByteArray bi)]
    (if (and (> (count bs) 1) (zero? (aget bs 0))) (byte-array (rest bs)) bs)))

(def ^:private idp (delay (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                              (.initialize 2048)))))

(defn- idp-jwks []
  (let [^RSAPublicKey pub (.getPublic ^java.security.KeyPair @idp)]
    (str "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"kid\":\"k1\",\"n\":\""
         (b64url (unsigned (.getModulus pub))) "\",\"e\":\""
         (b64url (unsigned (.getPublicExponent pub))) "\"}]}")))

(defn- id-token
  "A token live right now, the way a real one is when a job presents it,
  unless the subject or window is named."
  ([] (id-token {}))
  ([{:keys [sub iat exp]}]
   (let [now (.getEpochSecond (Instant/now))
         payload (str "{\"iss\":\"" issuer "\",\"sub\":\"" (or sub subject) "\","
                      "\"aud\":\"tik\",\"iat\":" (or iat (- now 30))
                      ",\"exp\":" (or exp (+ now 300)) "}")
         h (b64url (.getBytes "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}" "UTF-8"))
         p (b64url (.getBytes payload "UTF-8"))
         si (str h "." p)
         s (doto (Signature/getInstance "SHA256withRSA")
             (.initSign (.getPrivate ^java.security.KeyPair @idp))
             (.update (.getBytes si "US-ASCII")))]
     (str si "." (b64url (.sign s))))))

(defn- ssh-keygen! [dir name]
  (let [path (str (io/file dir name))]
    (sh/sh "ssh-keygen" "-t" "ed25519" "-N" "" "-C" name "-f" path)
    path))

(defn- setup!
  "A store with the sample process, an identity-registry ticket, and the
  IdP's keys pinned — the one-off an operator does and commits."
  []
  (let [root (h/temp-dir! "tik-workload")
        repo (System/getProperty "user.dir")
        env {:root (str root) :actor "seb"}]
    (io/make-parents (io/file root "processes" "x"))
    (io/copy (io/file repo "processes" "identity-registry.edn")
             (io/file root "processes" "identity-registry.edn"))
    (h/run-tik! env "init")
    (io/copy (io/file repo "processes" "identity-registry.edn")
             (io/file root "processes" "identity-registry.edn"))
    (let [f (trust/jwks-file root issuer)]
      (io/make-parents f)
      (spit f (idp-jwks)))
    {:root root :env env}))

(defn- ticket-id [out]
  (second (re-find #"([0-9a-f]{8}-[0-9a-f-]+)" out)))

(deftest a-pipeline-key-bound-by-its-token-signs-events-that-verify
  (let [{:keys [root env]} (setup!)
        registry (ticket-id (:out (h/run-tik! env "new" "identity-registry"
                                              "--title" "identity registry")))
        _ (is registry "the registry ticket should mint")
        ;; the job's ephemeral key: generated here, never stored anywhere
        ci-key (ssh-keygen! root "ci_key")
        token-file (str (io/file root "token.jwt"))
        _ (spit token-file (id-token))
        ci-env (assoc env :actor "ci" :env {"TIK_KEY" ci-key})
        bound (h/run-tik! ci-env "bridge" "workload"
                          "--registry" registry
                          "--token-file" token-file
                          "--public-key" (str ci-key ".pub")
                          "--actor" "ci")]
    (is (zero? (:exit bound)) (str "binding failed: " (:err bound) (:out bound)))
    (is (str/includes? (:out bound) "bound") (:out bound))

    (testing "the bound key signs a ticket's facts"
      (let [work (ticket-id (:out (h/run-tik! ci-env "new" "identity-registry"
                                              "--title" "signed by the pipeline")))
            set* (h/run-tik! ci-env "set" work "description=built by CI" "--actor" "ci")]
        (is (zero? (:exit set*)) (str (:err set*) (:out set*)))

        (testing "and verify accepts them through the binding alone"
          (let [v (h/run-tik! env "verify" work)]
            (is (zero? (:exit v))
                (str "verify should accept a rung-2 key:\n" (:out v) (:err v)))
            (is (str/includes? (:out v) "signed by ci")
                (str "expected an L1 line crediting ci:\n" (:out v)))))))

    (testing "without the pinned keys the same signature is unverifiable"
      (let [pinned (trust/jwks-file root issuer)
            saved (slurp pinned)]
        (.delete pinned)
        (let [work (ticket-id (:out (h/run-tik! ci-env "new" "identity-registry"
                                                "--title" "unpinnable")))]
          (h/run-tik! ci-env "set" work "description=x" "--actor" "ci")
          (let [v (h/run-tik! env "verify" work)]
            (is (not (str/includes? (:out v) "signed by ci"))
                "an unverifiable binding must grant nothing")))
        (spit pinned saved)))))

(deftest a-binding-verify-would-refuse-is-never-written
  (testing "an unpinned issuer is refused at mint time, not months later"
    (let [{:keys [root env]} (setup!)
          registry (ticket-id (:out (h/run-tik! env "new" "identity-registry"
                                                "--title" "identity registry")))
          _ (.delete (trust/jwks-file root issuer))
          ci-key (ssh-keygen! root "ci_key")
          token-file (str (io/file root "token.jwt"))
          _ (spit token-file (id-token))
          r (h/run-tik! (assoc env :actor "ci" :env {"TIK_KEY" ci-key})
                        "bridge" "workload" "--registry" registry
                        "--token-file" token-file
                        "--public-key" (str ci-key ".pub") "--actor" "ci")]
      (is (pos? (:exit r)))
      (is (str/includes? (str (:err r) (:out r)) "no-pinned-jwks")
          (str (:err r) (:out r)))
      (is (str/includes? (str (:err r) (:out r)) "tik bridge jwks")
          "the refusal should say how to fix it"))))

(deftest an-expired-token-cannot-bind
  (let [{:keys [root env]} (setup!)
        registry (ticket-id (:out (h/run-tik! env "new" "identity-registry"
                                              "--title" "identity registry")))
        ci-key (ssh-keygen! root "ci_key")
        token-file (str (io/file root "token.jwt"))
        old (let [t (- (.getEpochSecond (Instant/now)) 100000)
                  payload (str "{\"iss\":\"" issuer "\",\"sub\":\"" subject "\","
                               "\"iat\":" t ",\"exp\":" (+ t 300) "}")
                  h (b64url (.getBytes "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}" "UTF-8"))
                  p (b64url (.getBytes payload "UTF-8"))
                  si (str h "." p)
                  s (doto (Signature/getInstance "SHA256withRSA")
                      (.initSign (.getPrivate ^java.security.KeyPair @idp))
                      (.update (.getBytes si "US-ASCII")))]
              (str si "." (b64url (.sign s))))
        _ (spit token-file old)
        r (h/run-tik! (assoc env :actor "ci" :env {"TIK_KEY" ci-key})
                      "bridge" "workload" "--registry" registry
                      "--token-file" token-file
                      "--public-key" (str ci-key ".pub") "--actor" "ci")]
    (is (pos? (:exit r)))
    (is (str/includes? (str (:err r) (:out r)) "token-not-live")
        (str (:err r) (:out r)))))

(defn- bind!
  "Run `tik bridge workload` for `actor` with `token`; returns the result
  and the key it bound."
  [{:keys [root env]} registry actor token]
  (let [ci-key (ssh-keygen! root (str actor "_key"))
        token-file (str (io/file root (str actor ".jwt")))]
    (spit token-file token)
    {:key ci-key
     :env (assoc env :actor actor :env {"TIK_KEY" ci-key})
     :result (h/run-tik! (assoc env :actor actor :env {"TIK_KEY" ci-key})
                         "bridge" "workload" "--registry" registry
                         "--token-file" token-file
                         "--public-key" (str ci-key ".pub") "--actor" actor)}))

(deftest a-token-from-another-repository-cannot-bind-ci-under-a-policy
  (let [{:keys [root env] :as st} (setup!)
        _ (spit (io/file root "oidc.edn")
                (pr-str {:audience "tik"
                         :bindings {"ci" [{:issuer issuer :subject subject}]}}))
        registry (ticket-id (:out (h/run-tik! env "new" "identity-registry"
                                              "--title" "identity registry")))]
    (testing "a genuine token from the same issuer, about somebody else"
      (let [{r :result} (bind! st registry "ci"
                               (id-token {:sub "repo:attacker/evil:ref:refs/heads/main"}))
            out (str (:err r) (:out r))]
        (is (pos? (:exit r)) out)
        (is (str/includes? out "actor-not-permitted") out)
        (is (str/includes? out ":bindings") "the refusal says where the policy lives")))
    (testing "the subject the policy names binds, and says nothing about a policy"
      (let [{r :result} (bind! st registry "ci" (id-token))]
        (is (zero? (:exit r)) (str (:err r) (:out r)))
        (is (not (str/includes? (str (:err r)) "no :bindings policy")))))))

(deftest a-store-without-a-policy-is-told-at-bind-time
  (let [{:keys [env] :as st} (setup!)
        registry (ticket-id (:out (h/run-tik! env "new" "identity-registry"
                                              "--title" "identity registry")))
        {r :result} (bind! st registry "ci" (id-token))]
    (is (zero? (:exit r)) (str (:err r) (:out r)))
    (is (str/includes? (str (:err r)) "no :bindings policy") (:err r))))

(deftest a-bound-key-signing-after-its-token-expired-fails-verify
  ;; The token expired 45s ago: inside the 60s leeway, so the binding is
  ;; written, and the window closes 15s from now. An event signed after
  ;; that is a signature by a key nobody vouches for any more.
  (let [{:keys [env] :as st} (setup!)
        registry (ticket-id (:out (h/run-tik! env "new" "identity-registry"
                                              "--title" "identity registry")))
        now (.getEpochSecond (Instant/now))
        closes (+ now 15)
        {r :result ci-env :env} (bind! st registry "ci"
                                       (id-token {:iat (- now 400) :exp (- now 45)}))]
    (is (zero? (:exit r)) (str (:err r) (:out r)))
    (let [inside (ticket-id (:out (h/run-tik! ci-env "new" "identity-registry"
                                              "--title" "inside the window")))]
      (Thread/sleep (long (* 1000 (+ 2 (- closes (.getEpochSecond (Instant/now)))))))
      (let [late (ticket-id (:out (h/run-tik! ci-env "new" "identity-registry"
                                              "--title" "after the window")))]
        (testing "the event signed while the token was live verifies"
          (let [v (h/run-tik! env "verify" inside)]
            (is (zero? (:exit v)) (str (:out v) (:err v)))))
        (testing "the event signed after it does not"
          (let [v (h/run-tik! env "verify" late)]
            (is (pos? (:exit v)) (str (:out v) (:err v)))
            (is (str/includes? (:out v) "FAIL") (:out v))))))))
