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
  (:require [clojure.string :as str])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.util Base64)))

(def ^:private client (delay (HttpClient/newHttpClient)))

(defn http-get [url]
  (.body (.send ^HttpClient @client
                (.build (HttpRequest/newBuilder (URI/create url)))
                (HttpResponse$BodyHandlers/ofString))))

(defn http-post
  "Form-encoded POST; returns the body string regardless of status —
  OIDC endpoints speak JSON errors and the flows read them."
  [url params]
  (let [form (str/join "&" (for [[k v] params]
                             (str k "=" (URLEncoder/encode (str v) "UTF-8"))))]
    (.body (.send ^HttpClient @client
                  (-> (HttpRequest/newBuilder (URI/create url))
                      (.header "Content-Type" "application/x-www-form-urlencoded")
                      (.POST (HttpRequest$BodyPublishers/ofString form))
                      (.build))
                  (HttpResponse$BodyHandlers/ofString)))))

(defn- json-parse [s]
  ((requiring-resolve 'cheshire.core/parse-string) s true))

;; ---------------------------------------------------------------- pure

(defn discovery-url [issuer]
  (str (str/replace issuer #"/$" "") "/.well-known/openid-configuration"))

(defn decode-jwt-payload
  "The claims map of a JWT, no signature check (see the ns docstring
  for why that is honest here)."
  [token]
  (let [[_ payload _] (str/split token #"\.")]
    (json-parse (String. (.decode (Base64/getUrlDecoder) ^String payload)
                         "UTF-8"))))

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
     :token (:token_endpoint cfg)}))

(defn device-flow
  "Start device authorization; returns {:prompt … :poll (fn [] …)}.
  `poll` returns the token map once the human has approved, nil while
  pending. `sleep` is injected for the same reason http is."
  [post {:keys [device token]} client-id sleep]
  (let [start (json-parse (post device {"client_id" client-id
                                        "scope" "openid"}))
        interval (* 1000 (or (:interval start) 5))]
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
                 r)))}))

(defn password-flow
  "Resource-owner password grant — the headless path for tests and
  scripted onboarding against a directly-trusted IdP."
  [post {:keys [token]} client-id username password]
  (json-parse (post token {"grant_type" "password"
                           "client_id" client-id
                           "scope" "openid"
                           "username" username
                           "password" password})))

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
