;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.identity-trust
  "The porcelain half of rung 2: what makes a registry binding count, and
  the effective signer set that follows from the ones that do.

  `tik.identity` decides WHICH bindings a log claims and keeps the
  selection pure. Here lives the part that needs cryptography and files:
  checking a binding's id-token against the issuer's signing keys, and
  assembling an allowed-signers view that `tik.sign` can verify against.

  Three rules decide whether a binding counts, and each closes a way of
  claiming somebody else's name.

  **The signature must be the issuer's.** Checked against keys PINNED in
  the store (`<root>/jwks/<issuer>.json`), never fetched at verification
  time. That is what lets a binding be checked on a machine with no
  network, years later, after the IdP has been decommissioned — the
  promise the rest of the ladder makes. Fetching during verification
  would make an old conclusion depend on a live service, which is the
  thing tik refuses everywhere else.

  **The token must be about the subject the attestation names.** Without
  this, a valid token from any subject would endorse a body naming any
  other, and the binding would say something the IdP never asserted.

  **The token must have been live when the binding was written.** The
  attestation's own `:event/at` has to fall inside the token's validity
  window, so a binding means \"this actor presented a working token at
  that moment\" rather than \"this actor once held a token\". Without it a
  leaked, long-expired token would mint a fresh binding today. The window
  is judged on the CLAIMED clock (ADR 0012), so a backdated attestation
  is detectable rather than prevented — a witness countersignature over
  the head is what turns that into evidence.

  **Rotation is additive.** An issuer that rotates signing keys publishes
  the new one alongside the old for a while; re-pinning appends rather
  than replaces, so a token signed by a retired key still verifies. A
  pinned set that loses a key stops verifying the bindings that key
  signed — a real consequence, and the reason re-pinning merges."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.identity :as identity]
            [tik.jwks :as jwks]
            [tik.sign :as sign])
  (:import (java.time Instant)
           (java.util Base64)))

(def default-leeway-seconds
  "Clock skew allowed when judging a token's window against the moment
  the binding was written. Sixty seconds is the usual OIDC allowance."
  60)

(defn- b64url ^bytes [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn- json-parse [s]
  ((requiring-resolve 'cheshire.core/parse-string) s true))

(defn issuer-slug
  "A pinned-keys filename for an issuer URL. Every character that is not
  unreserved becomes '-', so a hostile issuer string cannot escape the
  jwks directory or collide with a path separator."
  [issuer]
  (-> (str issuer)
      (str/replace #"[^A-Za-z0-9._-]" "-")
      (str/replace #"-{2,}" "-")
      (str/replace #"^-+|-+$" "")))

(defn jwks-file
  "Where an issuer's pinned key set lives."
  ^java.io.File [root issuer]
  (io/file root "jwks" (str (issuer-slug issuer) ".json")))

(defn pinned-jwks
  "The parsed key set pinned for `issuer`, or nil when none is."
  [root issuer]
  (let [f (jwks-file root issuer)]
    (when (.exists f)
      (jwks/parse-jwks (slurp f)))))

(defn merge-jwks
  "Union two key sets by `kid`, the newer winning a collision. Re-pinning
  a rotated issuer must not retire the key that signed yesterday's
  binding, so this appends rather than replaces."
  [old new*]
  (let [by-kid (fn [j] (into {} (map (juxt :kid identity)) (:keys j)))]
    {:keys (vec (vals (merge (by-kid old) (by-kid new*))))}))

(defn token-claims
  "A token's claims without checking anything — for reporting only.
  Never a basis for trust; `binding-status` is."
  [id-token]
  (try (let [p (second (str/split (str id-token) #"\."))]
         (when-not (str/blank? (str p))
           (json-parse (String. (b64url p) "UTF-8"))))
       (catch Exception _ nil)))

(defn expected-audience
  "The audience this store expects a binding's token to carry, from
  `oidc.edn`, or nil when the store declares none.

  Audience is the anti-replay control that keeps a token issued for
  another service from becoming a binding here: same issuer, same
  subject, different intended recipient. A store that declares one gets
  the check; a store that does not is told so rather than silently
  going without."
  [root]
  (let [f (io/file root "oidc.edn")]
    (when (.exists f)
      (try (:audience (edn/read-string (slurp f)))
           (catch Exception _ nil)))))

(defn- audience-holds?
  "Does the token's `aud` name the expected audience? The claim is a
  string or an array of them (RFC 7519), so both shapes count."
  [aud expected]
  (or (nil? expected)
      (cond
        (string? aud) (= aud expected)
        (sequential? aud) (boolean (some #(= % expected) aud))
        :else false)))

(defn- epoch-seconds
  "The binding event's claimed instant as epoch seconds, or nil when the
  value is not a time at all (hostile or corrupt data)."
  [at]
  (when (instance? Instant at) (.getEpochSecond ^Instant at)))

(defn- window-holds?
  "Did the token's validity window contain `when-secs`?

  A token carrying neither `exp` nor `iat` is refused rather than
  accepted: there is no way to tie it to a moment, so it could be
  replayed into a binding at any time."
  [{:keys [exp nbf iat]} when-secs leeway]
  (let [n (fn [v] (when (number? v) (long v)))
        exp (n exp) nbf (n nbf) iat (n iat)]
    (cond
      ;; no instant to judge against, or no claim tying the token to one:
      ;; either way it could be replayed into a binding at any time
      (or (nil? when-secs) (and (nil? exp) (nil? iat))) false
      :else (and (or (nil? exp) (<= when-secs (+ exp leeway)))
                 (or (nil? nbf) (>= when-secs (- nbf leeway)))
                 (or (nil? iat) (>= when-secs (- iat leeway)))))))

(defn binding-status
  "Why a binding does or does not count: `:trusted`, or a map carrying a
  reason and what it was about.

  A reason rather than a boolean because `verify` has to SAY why a
  binding was refused — 'signature does not verify' would be a lie when
  the truth is that nobody ever pinned the issuer's keys."
  ([root binding] (binding-status root binding nil))
  ([root binding opts]
   (binding-status root binding (or (:leeway opts) default-leeway-seconds)
                   (if (contains? opts :audience)
                     (:audience opts)
                     (expected-audience root))))
  ([root {:keys [issuer subject id-token at] :as binding} leeway audience]
   (let [jwks (try (pinned-jwks root issuer)
                   (catch Exception _ ::unreadable))]
     (cond
       (= ::unreadable jwks)
       {:reason :identity/unreadable-jwks :issuer issuer :binding binding}

       (nil? jwks)
       {:reason :identity/no-pinned-jwks :issuer issuer :binding binding}

       :else
       (let [[h p sig] (str/split (str id-token) #"\.")]
         (if (or (str/blank? (str sig)) (str/blank? (str p)))
           {:reason :identity/malformed-token :issuer issuer :binding binding}
           (let [parsed (try {:header (json-parse (String. (b64url h) "UTF-8"))
                              :claims (json-parse (String. (b64url p) "UTF-8"))
                              :sig (b64url sig)}
                             (catch Exception _ nil))]
             (cond
               (nil? parsed)
               {:reason :identity/malformed-token :issuer issuer :binding binding}

               (not (try (boolean ((jwks/verifier jwks)
                                   (str h "." p) (:sig parsed) (:header parsed)))
                         (catch Exception _ false)))
               {:reason :identity/bad-signature :issuer issuer :binding binding}

               (not= (str subject) (str (:sub (:claims parsed))))
               {:reason :identity/subject-mismatch :issuer issuer :binding binding
                :claimed subject :token-subject (:sub (:claims parsed))}

               (not= (str issuer) (str (:iss (:claims parsed))))
               {:reason :identity/issuer-mismatch :issuer issuer :binding binding
                :token-issuer (:iss (:claims parsed))}

               (not (window-holds? (:claims parsed) (epoch-seconds at) leeway))
               {:reason :identity/token-not-live :issuer issuer :binding binding
                :at at}

               ;; a token minted for another service is a valid token being
               ;; replayed here, which is misuse rather than an unknown
               (not (audience-holds? (:aud (:claims parsed)) audience))
               {:reason :identity/audience-mismatch :issuer issuer
                :binding binding :expected audience
                :token-audience (:aud (:claims parsed))}

               :else :trusted))))))))

(defn binding-verifier
  "The `trusted?` predicate tik.identity injects, backed by pinned keys."
  ([root] (binding-verifier root nil))
  ([root opts]
   (fn [binding] (= :trusted (binding-status root binding opts)))))

(defn unchecked-audiences
  "Bindings carrying an `aud` the store never declared an expectation
  for. Not a refusal — the binding may be perfectly good — but worth
  saying, because the anti-replay control is switched off until
  `oidc.edn` names an :audience."
  [root registry-events]
  (when-not (expected-audience root)
    (seq (into [] (keep (fn [b]
                          (let [claims (token-claims (:id-token b))]
                            (when (:aud claims)
                              (assoc b :token-audience (:aud claims))))))
                (identity/bindings registry-events)))))

(defn verified-bindings
  "The bindings on `registry-events` whose tokens check out."
  [root registry-events]
  (identity/verified (identity/bindings registry-events)
                     (binding-verifier root)))

(defn refusals
  "Every binding that does NOT count, with the reason — what `verify`
  reports so a store never fails for an unexplained reason."
  [root registry-events]
  (into []
        (keep (fn [b] (let [st (binding-status root b)]
                        (when-not (= :trusted st) st))))
        (identity/bindings registry-events)))

(defn effective-signers
  "An allowed-signers file naming rung 1 AND every rung-2 key a verified
  binding grants, or the plain `actors` file when the registry adds
  nothing.

  Written to a temp file because `ssh-keygen -Y verify` reads a path. The
  store's own `actors` is never rewritten — rung 2 widens who may verify
  for one check, it does not edit the registry a human curates."
  [root registry-events]
  (let [actors (io/file root "actors")
        base (if (.exists actors) (slurp actors) "")
        granted (identity/signing-keys (verified-bindings root registry-events))
        lines (for [[actor keys*] (sort-by key granted)
                    k (sort keys*)]
                (sign/allowed-signers-line actor k))]
    (if (empty? lines)
      (when (.exists actors) actors)
      (let [f (java.io.File/createTempFile "tik-signers" ".allowed")]
        (.deleteOnExit f)
        (spit f (str base (when (seq base) "\n") (str/join "\n" lines) "\n"))
        f))))
