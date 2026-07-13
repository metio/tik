;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.oidc-test
  "The bridge's flows against a scripted IdP — injected HTTP, no
  server. The live Keycloak run is a demo, not a test; these pin the
  protocol handling."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [tik.oidc :as oidc])
  (:import (java.util Base64)))

(defn- jwt [claims]
  (let [b64 #(.encodeToString (Base64/getUrlEncoder)
                              (.getBytes ^String % "UTF-8"))]
    (str (b64 "{\"alg\":\"RS256\"}") "."
         (b64 (json/generate-string claims)) "." (b64 "sig"))))

(deftest identity_fetches_require_tls
  ;; the JWKS/discovery/id_token fetch is the trust anchor: an http URL
  ;; lets an on-path attacker serve a forged key/token, so every real
  ;; fetch must be TLS. Loopback is excepted for local test IdPs.
  (testing "https passes; a non-loopback http URL is refused"
    (is (= "https" (.getScheme ^java.net.URI
                    (oidc/require-tls! "https://idp.example/token"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-HTTPS"
                          (oidc/require-tls! "http://idp.example/token")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-HTTPS"
                          (oidc/require-tls! "http://169.254.169.254/latest/meta-data"))))
  (testing "loopback may speak plain http for a local test IdP"
    (is (oidc/require-tls! "http://localhost:8080/realms/tik"))
    (is (oidc/require-tls! "http://127.0.0.1:8080/token"))))

(deftest discovery_and_jwt_decoding
  (is (= "https://idp.example/realms/tik/.well-known/openid-configuration"
         (oidc/discovery-url "https://idp.example/realms/tik/")))
  (is (= {:sub "abc" :iss "https://idp.example"}
         (oidc/decode-jwt-payload (jwt {:sub "abc" :iss "https://idp.example"})))))

(deftest password_flow_produces_a_binding
  (let [token (jwt {:iss "https://idp.example/realms/tik"
                    :sub "f81d4fae" :preferred_username "seb"})
        http (fn [url params]
               (is (= "https://idp.example/token" url))
               (is (= "password" (get params "grant_type")))
               (json/generate-string {:id_token token}))
        response (oidc/password-flow http {:token "https://idp.example/token"}
                                     "tik" "seb" "pw")
        claim (oidc/token->binding response {:actor "seb"
                                             :public-key "ssh-ed25519 AAAA"
                                             :issuer "https://idp.example/realms/tik"})]
    (is (= {:claim :identity
            :identity/issuer "https://idp.example/realms/tik"
            :identity/subject "f81d4fae"
            :identity/username "seb"
            :identity/actor "seb"
            :identity/public-key "ssh-ed25519 AAAA"
            :identity/id-token token}
           claim))))

(deftest device_flow_polls_until_approved
  (let [token (jwt {:iss "i" :sub "s"})
        approved (atom false)
        http (fn [url params]
               (case url
                 "d" (json/generate-string {:device_code "dc" :user_code "ABCD"
                                            :verification_uri "https://idp/device"
                                            :interval 0})
                 "t" (do (is (= "dc" (get params "device_code")))
                         (json/generate-string
                          (if @approved
                            {:id_token token}
                            {:error "authorization_pending"})))))
        {:keys [prompt poll]} (oidc/device-flow http {:device "d" :token "t"}
                                                "tik" (fn [_]))]
    (is (re-find #"ABCD" prompt))
    (is (nil? (poll)))
    (reset! approved true)
    (is (= token (:id_token (poll))))))

(deftest idp_errors_speak_the_idp's_words
  (testing "no id_token throws with the IdP's own description"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invalid user credentials"
         (oidc/token->binding {:error "invalid_grant"
                               :error_description "Invalid user credentials"}
                              {:actor "seb" :public-key "k" :issuer "i"})))))
