;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.oidc
  "The OIDC bridge (identity rung 2, PLAN §9): a device-flow login
  binds an IdP subject to a signing key as a signed ATTESTATION EVENT
  on the registry ticket. Verification never calls the IdP — the
  binding lives in the log, so offline-forever verification survives
  the IdP's death. Rotation and re-attestation are just newer
  attestations by the same bridge.

  Trust model: the bridge speaks to the token endpoint directly over
  the connection it opened, so it takes the endpoint's word for the
  ID token's payload; the durable, independently-checkable object is
  the bridge's OWN signature over the binding event, not the JWT.
  The raw ID token is carried in the claim for later re-audit.

  Composition: HTTP enters as an injected `http-post`/`http-get`
  function ((url form-params-or-nil) -> body string), so every flow
  is testable without a server; only the CLI passes the real one."
  (:require [briko.http :as http]
            [briko.json :as json]
            [clojure.string :as str])
  (:import (java.net URI URLEncoder)
           (java.util Base64)))

(def ^:private limits
  "Bounds of every identity request and of every JSON document an IdP
  sends. The largest discovery document and key set measured on
  2026-11-28 were 1.7 KB and 12.9 KB (login.microsoftonline.com), an ID
  token a few KB; 1 MiB leaves room for any real IdP and stops one that
  streams without end."
  {:connect-timeout-ms 10000
   :timeout-ms 60000
   :max-bytes (* 1024 1024)})

(defn require-tls!
  "Every security-critical OIDC/JWKS fetch must run over TLS: an http URL
  lets an on-path attacker serve the discovery doc, JWKS, or id_token and
  forge a key-binding/credential that then verifies. Loopback is excepted
  so a local test IdP can speak plain http. Returns the URI on success."
  [url]
  (let [u (URI/create (str url))
        scheme (some-> (.getScheme u) str/lower-case)
        host (some-> (.getHost u) str/lower-case)]
    (when-not (or (= "https" scheme)
                  (contains? #{"localhost" "127.0.0.1" "::1"} host))
      (throw (ex-info (str "refusing a non-HTTPS identity fetch: " url
                           " — TLS is required (loopback excepted)")
                      {:reason :oidc/insecure-url :url (str url)})))
    u))

(defn- request!
  "The body of the answer to one identity request, whatever its status.
  A refusal of the HTTP layer becomes an ex-info naming the IdP's
  address (scheme, host, port and path; never the query, which may carry
  a token): :oidc/too-large for a body over the limit, :oidc/unreachable
  for everything else."
  [method url opts]
  (require-tls! url)
  (try
    (:body (http/request method url (merge limits {:as :text} opts)))
    (catch clojure.lang.ExceptionInfo e
      (if-not (http/refusal? e)
        (throw e)
        (let [{:keys [type limit] u :url} (ex-data e)]
          (throw (ex-info (str "the identity provider at " u " "
                               (if (= ::http/too-large type)
                                 (str "answered with more than " limit " bytes")
                                 (str "could not be reached: " (ex-message e))))
                          (cond-> {:reason (if (= ::http/too-large type) :oidc/too-large :oidc/unreachable)
                                   :url u}
                            limit (assoc :limit limit))
                          e)))))))

(defn http-get [url]
  (request! :get url nil))

(defn http-get-with-auth
  "GET with an Authorization header — what a platform's own token
  endpoint wants (GitHub Actions hands the job a short-lived request
  token to exchange for an ID token)."
  [url auth]
  (request! :get url {:headers {"Authorization" auth}}))

(defn http-post
  "Form-encoded POST; returns the body string regardless of status —
  OIDC endpoints speak JSON errors and the flows read them."
  [url params]
  (let [form (str/join "&" (for [[k v] params]
                             (str k "=" (URLEncoder/encode (str v) "UTF-8"))))]
    (request! :post url {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                         :body form})))

(defn- json-parse
  "Parse an IdP-sourced JSON body, keys as keywords. A broken or hostile
  IdP that returns non-JSON is THE IdP's fault, not a tik bug — fail well
  with a data-carrying error so the message names the IdP, never a raw
  parser exception reworded as a this-is-a-bug line."
  [s]
  (try
    (json/parse-string s {:keys :keyword :max-bytes (:max-bytes limits)})
    (catch Exception e
      (throw (ex-info "the identity provider returned a body that is not valid JSON"
                      {:reason :oidc/bad-response
                       :body (when (string? s) (subs s 0 (min 200 (count s))))}
                      e)))))

;; ---------------------------------------------------------------- pure

(defn discovery-url [issuer]
  (str (str/replace issuer #"/$" "") "/.well-known/openid-configuration"))

(defn decode-jwt-payload
  "The claims map of a JWT, no signature check (see the ns docstring
  for why that is honest here). Total over a hostile id_token: a
  missing payload segment, non-base64url bytes, or non-JSON content is
  the IdP's fault and fails well, never a raw NPE or decoder throw."
  [token]
  (let [payload (second (str/split (str token) #"\."))]
    (when (str/blank? payload)
      (throw (ex-info "the id_token is not a JWT (no payload segment)"
                      {:reason :oidc/bad-token})))
    (let [bytes (try (.decode (Base64/getUrlDecoder) ^String payload)
                     (catch Exception e
                       (throw (ex-info "the id_token payload is not valid base64url"
                                       {:reason :oidc/bad-token} e))))]
      (json-parse (String. ^bytes bytes "UTF-8")))))

(defn binding-claim
  "The attestation body binding an IdP subject to an actor's key —
  what the registry ticket accumulates. `id-token` rides along for
  re-audit; the binding itself is the bridge-signed event."
  [{:keys [claims actor public-key id-token issuer]}]
  {:claim :identity
   :identity/issuer (or (:iss claims) issuer)
   :identity/subject (:sub claims)
   :identity/username (or (:preferred_username claims) (:email claims))
   :identity/actor actor
   :identity/public-key public-key
   :identity/id-token id-token})

;; ---------------------------------------------------------------- flows

(defn discover [fetch issuer]
  (let [cfg (json-parse (fetch (discovery-url issuer)))]
    {:device (:device_authorization_endpoint cfg)
     :token (:token_endpoint cfg)
     ;; where the issuer publishes its signing keys — fetched ONCE when
     ;; an operator pins them, never at verification time (ADR 0023)
     :jwks (:jwks_uri cfg)}))

(defn device-flow
  "Start device authorization; returns {:prompt … :poll (fn [] …)}.
  `poll` returns the token map once the human has approved, nil while
  pending. `sleep` is injected for the same reason http is. `nonce`, when
  given, asks the IdP to put it in the id-token, which is how a binding's
  token commits to the key it binds."
  ([post endpoints client-id sleep] (device-flow post endpoints client-id sleep nil))
  ([post {:keys [device token]} client-id sleep nonce]
  (let [start (json-parse (post device (cond-> {"client_id" client-id
                                                "scope" "openid"}
                                         nonce (assoc "nonce" nonce))))
        ;; the poll interval is the IdP's word (RFC 8628: seconds, default
        ;; 5); a broken or hostile provider can send a non-number or a
        ;; negative, so fall back to the default rather than throwing on
        ;; the multiply or handing Thread/sleep a negative timeout.
        secs (:interval start)
        interval (* 1000 (if (and (number? secs) (not (neg? secs))) secs 5))]
    {:prompt (str "visit " (or (:verification_uri_complete start)
                               (:verification_uri start))
                  " and enter code " (:user_code start))
     :poll (fn []
             (sleep interval)
             (let [r (json-parse
                      (post token
                            {"grant_type" "urn:ietf:params:oauth:grant-type:device_code"
                             "device_code" (:device_code start)
                             "client_id" client-id}))]
               (when-not (= "authorization_pending" (:error r))
                 r)))})))

(defn password-flow
  "Resource-owner password grant — the headless path for tests and
  scripted onboarding against a directly-trusted IdP. `nonce` as in
  `device-flow`."
  ([post endpoints client-id username password]
   (password-flow post endpoints client-id username password nil))
  ([post {:keys [token]} client-id username password nonce]
   (json-parse (post token (cond-> {"grant_type" "password"
                                    "client_id" client-id
                                    "scope" "openid"
                                    "username" username
                                    "password" password}
                             nonce (assoc "nonce" nonce))))))

(defn token->binding
  "Token response -> the attestation body, or a thrown error carrying
  the IdP's own words."
  [response {:keys [actor public-key issuer]}]
  (let [id-token (:id_token response)]
    (when-not id-token
      (throw (ex-info (str "the IdP returned no id_token: "
                           (or (:error_description response)
                               (:error response)
                               "unknown error"))
                      {:response (dissoc response :access_token)})))
    (binding-claim {:claims (decode-jwt-payload id-token)
                    :actor actor
                    :public-key public-key
                    :id-token id-token
                    :issuer issuer})))
