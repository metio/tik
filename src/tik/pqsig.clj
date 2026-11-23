;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pqsig
  "ML-DSA-65 (FIPS 204) event signatures and their registry (ADR 0036).

  An event's post-quantum signature sits beside its SSHSIG one as
  `<id>.pqsig.<fpr>`: the raw FIPS 204 signature over `tik-event-v1\\0`
  followed by the event's stored bytes. `pq-signers` maps each key to an
  actor and the namespaces it may sign in, one line per key:

    <actor> namespaces=\"<pattern>,…\" ml-dsa-65 <base64 SubjectPublicKeyInfo>

  `<fpr>` is the hex SHA-256 of that DER SubjectPublicKeyInfo.

  The algorithm set is closed: a key is accepted only when its DER is an
  ML-DSA-65 SubjectPublicKeyInfo, so neither a tag nor a key of another
  parameter set can choose a different scheme (ADR 0006 rule 4).

  Verification is fail-closed and pure: bytes in, a boolean out. A
  malformed registry line is refused with its line number, because a
  registry that is half read grants less than its author believes."
  (:require [clojure.string :as str]
            [tik.identity :as identity])
  (:import (java.security KeyFactory MessageDigest Signature)
           (java.security.spec X509EncodedKeySpec)
           (java.util Arrays Base64)))

(def algorithm
  "The one algorithm tag a `pq-signers` line may carry."
  "ml-dsa-65")

(def ^:private spki-header
  "The DER prefix of every ML-DSA-65 SubjectPublicKeyInfo: a SEQUENCE of
  the AlgorithmIdentifier for id-ml-dsa-65 (2.16.840.1.101.3.4.3.18) and a
  BIT STRING holding the 1952-byte public key."
  (byte-array (map unchecked-byte
                   [0x30 0x82 0x07 0xb2 0x30 0x0b 0x06 0x09 0x60 0x86 0x48
                    0x01 0x65 0x03 0x04 0x03 0x12 0x03 0x82 0x07 0xa1 0x00])))

(def ^:private spki-length 1974)

(def ^:private signature-length 3309)

(def ^:private event-domain
  (.getBytes "tik-event-v1\u0000" "UTF-8"))

(defn ml-dsa-65-key?
  "Whether `der` is an ML-DSA-65 SubjectPublicKeyInfo by its shape."
  [der]
  (and (bytes? der)
       (= spki-length (alength ^bytes der))
       (Arrays/equals ^bytes spki-header
                      (Arrays/copyOf ^bytes der (alength ^bytes spki-header)))))

(defn fingerprint
  "The hex SHA-256 of a DER SubjectPublicKeyInfo: the `<fpr>` of a sidecar."
  ^String [^bytes der]
  (str/join (map #(format "%02x" %)
                 (.digest (MessageDigest/getInstance "SHA-256") der))))

(defn event-message
  "The bytes an event signature covers: the domain prefix, then the
  event's stored bytes."
  ^bytes [^bytes event-bytes]
  (let [out (byte-array (+ (alength ^bytes event-domain) (alength event-bytes)))]
    (System/arraycopy event-domain 0 out 0 (alength ^bytes event-domain))
    (System/arraycopy event-bytes 0 out (alength ^bytes event-domain) (alength event-bytes))
    out))

(defn verify-event
  "Whether `signature` is an ML-DSA-65 signature by `public-key` (DER
  SubjectPublicKeyInfo) over `event-bytes`. False for anything malformed."
  [public-key signature event-bytes]
  (boolean
   (and (ml-dsa-65-key? public-key)
        (bytes? signature)
        (= signature-length (alength ^bytes signature))
        (bytes? event-bytes)
        (try
          (let [k (.generatePublic (KeyFactory/getInstance "ML-DSA")
                                   (X509EncodedKeySpec. ^bytes public-key))
                v (doto (Signature/getInstance "ML-DSA")
                    (.initVerify k)
                    (.update (event-message event-bytes)))]
            (.verify v ^bytes signature))
          ;; a key or signature the provider rejects is a failed
          ;; verification, never an escape
          (catch Exception _ false)))))

(def ^:private line-pattern
  #"^(\S+)\s+namespaces=\"([^\"]*)\"\s+(\S+)\s+(\S+)\s*$")

(defn- refuse [n why]
  (throw (ex-info (str "pq-signers line " n ": " why)
                  {:reason :pqsig/bad-line :line n})))

(defn- parse-line [n text]
  (let [[_ actor namespaces tag key-b64] (re-matches line-pattern text)]
    (when-not actor
      (refuse n "expected <actor> namespaces=\"…\" ml-dsa-65 <base64 key>"))
    (when-not (identity/principal? actor)
      (refuse n (str "an actor is letters, digits and ._@+- only: " (pr-str actor))))
    (when-not (= algorithm tag)
      (refuse n (str "the only algorithm is " algorithm ", not " (pr-str tag))))
    (let [der (try (.decode (Base64/getDecoder) ^String key-b64)
                   (catch IllegalArgumentException _ (refuse n "the key is not base64")))
          patterns (vec (remove str/blank? (str/split namespaces #",")))]
      (when-not (ml-dsa-65-key? der)
        (refuse n "the key is not an ML-DSA-65 SubjectPublicKeyInfo"))
      (when (empty? patterns)
        (refuse n "namespaces names no pattern"))
      ;; `*` is the one wildcard; `?` and `!` negation, which an
      ;; allowed-signers file knows, would be misread rather than honoured
      (when-let [p (some #(when (re-find #"[?!\s]" %) %) patterns)]
        (refuse n (str "a namespace pattern may use * only, not " (pr-str p))))
      {:actor actor :namespaces patterns :public-key der :fpr (fingerprint der)})))

(defn parse-registry
  "The entries of a `pq-signers` text, in order. Blank lines and lines
  starting with `#` are skipped; anything else malformed is refused with
  its line number, and so is one key registered for two actors."
  [text]
  (when-not (string? text)
    (throw (ex-info "pq-signers is text" {:reason :pqsig/bad-registry})))
  (let [entries (into []
                      (keep-indexed
                       (fn [i raw]
                         (let [l (str/trim raw)]
                           (when-not (or (str/blank? l) (str/starts-with? l "#"))
                             (parse-line (inc i) l)))))
                      (str/split-lines text))]
    (doseq [[fpr es] (group-by :fpr entries)
            :when (< 1 (count (distinct (map :actor es))))]
      (throw (ex-info (str "pq-signers registers the key " fpr " for two actors: "
                           (str/join ", " (distinct (map :actor es))))
                      {:reason :pqsig/key-for-two-actors :fpr fpr})))
    entries))

(defn entry-for
  "The registry entry of the key with fingerprint `fpr`."
  [registry fpr]
  (some #(when (= fpr (:fpr %)) %) registry))

(defn- glob-matches? [pattern s]
  (boolean
   (re-matches (re-pattern (str/join ".*" (map #(java.util.regex.Pattern/quote %)
                                               (str/split pattern #"\*" -1))))
               s)))

(defn namespace-allowed?
  "Whether a namespace matches one of an entry's patterns (`*` matches
  any run of characters, as in an allowed-signers file)."
  [patterns ns]
  (boolean (and (string? ns) (some #(glob-matches? % ns) patterns))))

(def ^:private sidecar-pattern #"\.pqsig\.([0-9a-f]{64})$")

(defn sidecar-fpr
  "The key fingerprint a `<id>.pqsig.<fpr>` sidecar name carries, or nil."
  [name]
  (when (string? name) (second (re-find sidecar-pattern name))))

(defn check-event
  "Whether a post-quantum sidecar counts for `actor`: its key is in the
  registry under that actor, may sign in `namespace`, and verifies over
  the event's bytes."
  [registry {:keys [actor fpr signature event-bytes namespace]}]
  (let [entry (entry-for registry fpr)]
    (boolean (and entry
                  (= actor (:actor entry))
                  (namespace-allowed? (:namespaces entry) namespace)
                  (verify-event (:public-key entry) signature event-bytes)))))
