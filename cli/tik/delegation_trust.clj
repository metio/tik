;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.delegation-trust
  "Which delegations the audit trusts, and which signatures they credit
  (ADR 0037). tik.delegation judges a delegation's shape and scope from the
  events alone; this namespace adds the signatures.

  A delegation is trusted when its attestation carries a signature that
  verifies as the delegator through `actors` alone. A key that rung 2 or
  another delegation grants cannot delegate, so there are no chains. With
  `{:delegation {:require-touch true}}` in `oidc.edn` that signature must
  also come from a security key, whose touch tik.sshsig already requires.
  An `oidc.edn` that does not parse trusts no delegation, because a typo
  must not reopen what the setting closes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.delegation :as delegation]
            [tik.sign :as sign]
            [tik.sshsig :as sshsig]
            [tik.store.protocol :as store]))

(defn policy-of
  "`{:require-touch bool}` from a parsed `oidc.edn` map, or `::malformed`
  when it is not a map or its `:delegation` setting does not parse."
  [conf]
  (let [d (when (map? conf) (:delegation conf))]
    (cond
      (not (map? conf)) ::malformed
      (nil? d) {:require-touch false}
      (and (map? d) (contains? #{nil true false} (:require-touch d)))
      {:require-touch (true? (:require-touch d))}
      :else ::malformed)))

(defn policy
  "The store's delegation policy from `oidc.edn` (see policy-of); no file
  means no touch requirement."
  [root]
  (let [f (io/file root "oidc.edn")]
    (if-not (.exists f)
      {:require-touch false}
      (policy-of (try (edn/read-string (slurp f)) (catch Exception _ ::malformed))))))

(defn- sig-names [s ticket target]
  (filterv #(str/starts-with? % (str target ".sig.")) (store/sidecar-names s ticket)))

(defn- security-key? [sig]
  (some-> (sshsig/signer-key-type sig) (str/starts-with? "sk-")))

(defn judge
  "Whether the delegation `d` is trusted, given the bytes of its event, its
  detached signatures, the rung-1 allowed-signers `signers` text and the
  policy: {:trusted? bool :why reason-or-nil}. Rung 1 alone counts, so a
  caller passes only the lines a human curated, never a key a binding or
  another delegation grants."
  [d {:keys [bytes sigs signers policy]}]
  (let [rung-1 (filterv #(and % bytes (string? signers)
                              (true? (sign/verify-text signers bytes % (:actor d) sign/namespace-event)))
                        sigs)
        why (cond
              (seq (:problems d)) (str "malformed: " (str/join ", " (map name (:problems d))))
              (= ::malformed policy) "the delegation policy does not parse, so no delegation is trusted"
              (empty? rung-1) (str "not signed by a key actors grants " (:actor d))
              (and (:require-touch policy) (not-any? security-key? rung-1))
              "the store requires a security key, and a plain key signed it")]
    {:trusted? (nil? why) :why why}))

(defn assess
  "Every delegation on `registry-events`, each with `:trusted?` and, when
  it is not, a `:why` a reader can act on."
  [root s registry-events]
  (let [pol (policy root)
        actors (io/file root "actors")
        signers (when (.isFile actors) (slurp actors))]
    (mapv
     (fn [d]
       (let [bytes (when (:ticket d) (store/event-bytes s (:ticket d) (:event d)))
             sigs (when bytes (mapv #(store/read-sidecar s (:ticket d) %)
                                    (sig-names s (:ticket d) (:event d))))]
         (merge d (judge d {:bytes bytes :sigs sigs :signers signers :policy pol}))))
     (delegation/delegations registry-events))))

(defn credit
  "The trusted delegation that covers `event` and whose key verifies
  `sig` over `event-bytes`, or nil."
  [assessed event ^bytes event-bytes sig]
  (some (fn [d]
          (when (and (:trusted? d)
                     (delegation/covers? d event)
                     (true? (sign/verify-text (sign/allowed-signers-line (:actor d) (:public-key d))
                                              event-bytes sig (:actor d) sign/namespace-event)))
            d))
        assessed))
