;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.canonical
  "Deterministic, byte-stable EDN serialization and content addressing.

  This is the un-migratable layer: every event ever written must hash and
  verify identically, forever, on every runtime (JVM and babashka). Treat
  any change here as a format version bump (see `format-version`).

  Rules:
  - map entries sorted by the canonical form of the key; single-space
    separated
  - sets sorted by the canonical form of their elements
  - vectors and seqs preserve order (vectors as [..], other seqs as (..))
  - instants normalized to millisecond precision, printed as #inst \"...\"
    (java.time.Instant ISO-8601, trailing zero fractions omitted by Instant)
  - supported scalars: nil, booleans, longs, strings, keywords, symbols,
    uuids, instants (java.time.Instant or java.util.Date)
  - floats, ratios, bigdecimals and everything else are REJECTED: their
    printed forms are not reliably byte-stable across runtimes."
  (:require [clojure.string :as str])
  (:import (java.security MessageDigest)
           (java.time Instant)
           (java.time.temporal ChronoUnit)))

(def format-version
  "Bump on ANY change to canonical output. Events may carry this so future
  verifiers know which rules produced a hash."
  1)

(defn- emit-inst ^String [^Instant i]
  (str "#inst \"" (str (.truncatedTo i ChronoUnit/MILLIS)) "\""))

(defn emit
  "Return the canonical string form of `x`. Throws on unsupported types."
  ^String [x]
  (cond
    (nil? x)     "nil"
    (boolean? x) (str x)
    (int? x)     (str (long x))
    (string? x)  (pr-str x)
    (keyword? x) (str x)
    (symbol? x)  (str x)
    (uuid? x)    (str "#uuid \"" x "\"")
    (instance? Instant x)        (emit-inst x)
    (instance? java.util.Date x) (emit-inst (.toInstant ^java.util.Date x))
    ;; entries sort by the CANONICAL form of the key, never pr-str:
    ;; types without a print-method (java.time.Instant) pr-str to
    ;; #object[... <identity-hash> ...], which is unstable across runs.
    ;; For keyword/string/int/uuid keys the two orders coincide, so all
    ;; existing hashes are unaffected (the golden tests pin this).
    (map? x)    (str "{"
                     (str/join " "
                               (map (fn [[k v]] (str (emit k) " " (emit v)))
                                    (sort-by (comp emit first) x)))
                     "}")
    (set? x)    (str "#{" (str/join " " (sort (map emit x))) "}")
    (vector? x) (str "[" (str/join " " (map emit x)) "]")
    (seq? x)    (str "(" (str/join " " (map emit x)) ")")
    :else (throw (ex-info "unsupported type in canonical EDN"
                          {:type (type x) :value x}))))

(defn sha256-hex ^String [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes s "UTF-8"))]
    (str/join (map #(format "%02x" %) d))))

(defn sha256-hex-bytes
  "For blobs (artifacts). Hash policy — one algorithm per store per
  format-version, self-describing ids, additive migration — is ADR 0006."
  ^String [^bytes b]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") b)]
    (str/join (map #(format "%02x" %) d))))

(defn content-address
  "\"sha256-<hex>\" of the canonical form of `value`."
  ^String [value]
  (str "sha256-" (sha256-hex (emit value))))
