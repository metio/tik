;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.oidc-test
  "The bridge's flows against a scripted IdP — injected HTTP, no
  server. The live Keycloak run is a demo, not a test; these pin the
  protocol handling."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.loopback-http :as lh]
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

;; ------------------------------------------------------- the real HTTP

(deftest the_http_functions_return_the_body_whatever_the_status
  (lh/with-server
    (fn [{:keys [path]}]
      (if (= "/err" path)
        {:status 400 :body "{\"error\":\"invalid_grant\"}"}
        {:status 200 :body "{\"ok\":\"Größe\"}"}))
    (fn [base _]
      (is (= "{\"ok\":\"Größe\"}" (oidc/http-get (str base "/ok"))))
      (is (= "{\"error\":\"invalid_grant\"}" (oidc/http-post (str base "/err") {"a" "b"}))
          "OIDC endpoints answer errors as JSON, and the flows read them"))))

(deftest the_post_is_form_encoded_and_the_auth_get_sends_its_header
  (lh/with-server
    (constantly {:status 200 :body "{}"})
    (fn [base seen]
      (oidc/http-post (str base "/token") {"grant_type" "password" "username" "a b&c"})
      (oidc/http-get-with-auth (str base "/id?x=1") "Bearer rt")
      (let [[post get*] @seen]
        (is (= "POST" (:method post)))
        (is (= "application/x-www-form-urlencoded" (get-in post [:headers "content-type"])))
        (is (= "grant_type=password&username=a+b%26c" (:body post)))
        (is (= "GET" (:method get*)))
        (is (= "x=1" (:query get*)))
        (is (= "Bearer rt" (get-in get* [:headers "authorization"])))))))

(deftest a_plain_http_identity_fetch_is_refused_before_any_connection
  (doseq [f [#(oidc/http-get "http://idp.example/jwks")
             #(oidc/http-post "http://idp.example/token" {})
             #(oidc/http-get-with-auth "http://idp.example/id" "Bearer x")]]
    (let [e (try (f) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :oidc/insecure-url (:reason (ex-data e)))))))

(deftest an_unreachable_identity_provider_is_an_ex_info
  (let [port (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
               (.getLocalPort s))
        e (try (oidc/http-get (str "http://127.0.0.1:" port "/jwks")) nil
               (catch Exception e e))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= :oidc/unreachable (:reason (ex-data e))))
    (is (str/includes? (ex-message e) (str "127.0.0.1:" port)))))

(deftest an_identity_response_larger_than_the_limit_is_refused
  (doseq [chunked? [false true]]
    (testing (if chunked? "of unannounced length" "with a Content-Length")
      (lh/with-server
        (constantly {:status 200 :chunked? chunked?
                     :body (str "{\"k\":\"" (str/join (repeat (* 1024 1024) "a")) "\"}")})
        (fn [base _]
          (let [e (try (oidc/http-get (str base "/jwks")) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :oidc/too-large (:reason (ex-data e))))
            (is (= (* 1024 1024) (:limit (ex-data e))))))))))

(deftest a_real_jwks_document_fits_the_limits
  ;; the largest key set measured on 2026-11-28 (login.microsoftonline.com,
  ;; 12894 bytes) has this shape: about 10 RSA keys with x5c chains
  (let [k {:kty "RSA" :use "sig" :kid "k" :x5t "t" :n (str/join (repeat 342 "A"))
           :e "AQAB" :x5c [(str/join (repeat 900 "B"))] :issuer "https://login.example/{tenantid}/v2.0"}
        doc (json/generate-string {:keys (vec (repeat 12 k))})]
    (lh/with-server
      (constantly {:status 200 :body doc})
      (fn [base _]
        (is (< 12894 (count doc)))
        (is (= doc (oidc/http-get (str base "/keys"))))))))
