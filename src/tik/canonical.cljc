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
    uuids, instants (java.time.Instant — THE one time type; java.util.Date
    is rejected like any other unsupported type)
  - floats, ratios, bigdecimals and everything else are REJECTED: their
    printed forms are not reliably byte-stable across runtimes."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.security MessageDigest)
           (java.time Instant)
           (java.time.temporal ChronoUnit)))

(def format-version
  "Bump on ANY change to canonical output. Events may carry this so future
  verifiers know which rules produced a hash."
  1)

(def edn-readers
  "The read half of the format: #inst as java.time.Instant and #uuid as
  UUID, so values round-trip through the canonical printer unchanged.
  Every reader of canonical bytes — and every porcelain EDN read that
  may hold such values — uses these; the default #inst reader would
  yield a java.util.Date, which the printer rejects (one time type)."
  {'inst (fn [s] (Instant/parse s))
   'uuid (fn [s] (java.util.UUID/fromString s))})

(declare check-nesting)

(defn parse
  "The one guarded EDN read: nesting depth precheck (a 100k-deep vector
  overflows the recursive reader with an Error no fail-well guard
  catches), then edn/read-string with the canonical readers. Fails WELL
  — any rejection is an ex-info {:reason :edn/malformed}; callers wrap
  with their own context (which file, which row, which argument)."
  [text]
  (check-nesting text)
  (try (edn/read-string {:readers edn-readers} text)
       (catch #?(:clj Exception :cljs :default) e
         (throw (ex-info (str "malformed EDN — " (ex-message e))
                         {:reason :edn/malformed}
                         e)))))

(defn- emit-inst ^String [^Instant i]
  (str "#inst \"" (str (.truncatedTo i ChronoUnit/MILLIS)) "\""))

(def max-depth
  "Bound on container nesting, write side and read side alike. The
  emitter and the EDN readers recurse per nesting level, so unbounded
  depth is a stack bomb (StackOverflowError — an Error no fail-well
  guard catches). No honest event approaches triple-digit nesting;
  values deeper than this are rejected with a clean ex-info."
  512)

(defn check-nesting
  "Reject a raw EDN string whose bracket nesting exceeds max-depth
  BEFORE it reaches a recursive reader. Linear scan, aware of string
  literals and character escapes so brackets inside them don't count."
  [^String s]
  (loop [i 0 depth 0 in-string false]
    (when (< i (count s))
      (let [c (.charAt s i)]
        (cond
          in-string
          (case c
            \\ (recur (+ i 2) depth true)
            \" (recur (inc i) depth false)
            (recur (inc i) depth true))

          (= c \") (recur (inc i) depth true)
          (= c \\) (recur (+ i 2) depth false)   ; char literal: \[ \{ …

          (or (= c \[) (= c \{) (= c \())
          (let [d (inc depth)]
            (when (> d max-depth)
              (throw (ex-info "nesting too deep to read safely"
                              {:reason :canonical/too-deep
                               :max-depth max-depth})))
            (recur (inc i) d in-string))

          (or (= c \]) (= c \}) (= c \)))
          (recur (inc i) (dec depth) in-string)

          :else (recur (inc i) depth in-string))))))

(defn- emit*
  ^String [x depth]
  (when (> depth max-depth)
    (throw (ex-info "value nests too deeply for canonical EDN"
                    {:reason :canonical/too-deep :max-depth max-depth})))
  (cond
    (nil? x)     "nil"
    (boolean? x) (str x)
    (int? x)     (str (long x))
    (string? x)  (pr-str x)
    ;; keyword and symbol both canonicalize to their printed name
    (or (keyword? x) (symbol? x)) (str x)
    (uuid? x)    (str "#uuid \"" x "\"")
    (instance? Instant x)        (emit-inst x)
    ;; entries sort by the CANONICAL form of the key, never pr-str:
    ;; types without a print-method (java.time.Instant) pr-str to
    ;; #object[... <identity-hash> ...], which is unstable across runs.
    ;; For keyword/string/int/uuid keys the two orders coincide, so all
    ;; existing hashes are unaffected (the golden tests pin this).
    (map? x)    (let [d (inc depth)]
                  (str "{"
                       (str/join " "
                                 (map (fn [[k v]] (str (emit* k d) " "
                                                       (emit* v d)))
                                      (sort-by (fn [[k _]] (emit* k d)) x)))
                       "}"))
    (set? x)    (str "#{" (str/join " " (sort (map #(emit* % (inc depth)) x)))
                     "}")
    (vector? x) (str "[" (str/join " " (map #(emit* % (inc depth)) x)) "]")
    (seq? x)    (str "(" (str/join " " (map #(emit* % (inc depth)) x)) ")")
    :else (throw (ex-info "unsupported type in canonical EDN"
                          {:type (type x) :value x}))))

(defn emit
  "Return the canonical string form of `x`. Throws on unsupported types
  and on nesting deeper than max-depth (both as ex-info)."
  ^String [x]
  (emit* x 0))

(defn- bytes->hex ^String [^bytes b]
  (str/join (map #(format "%02x" %) b)))

(defn sha256-hex ^String [^String s]
  (bytes->hex (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes s "UTF-8"))))

(defn sha256-hex-bytes
  "For blobs (artifacts). Hash policy — one algorithm per store per
  format-version, self-describing ids, additive migration — is ADR 0006."
  ^String [^bytes b]
  (bytes->hex (.digest (MessageDigest/getInstance "SHA-256") b)))

(defn content-address
  "\"sha256-<hex>\" of the canonical form of `value`."
  ^String [value]
  (str "sha256-" (sha256-hex (emit value))))
