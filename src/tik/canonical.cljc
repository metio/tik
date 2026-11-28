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
  (:require [briko.edn :as bedn]
            [clojure.string :as str]
            [tik.shape :as shape])
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

(declare max-depth too-deep)

(def max-text-bytes
  "The longest text parse reads: 64 MiB, the largest ticket tik.api
  admits. Callers that read external input bound it themselves first."
  (* 64 1024 1024))

(defn parse
  "The one guarded EDN read, through briko.edn: a linear nesting scan
  refuses text the recursive reader could overflow on, then the text is
  read with the canonical readers, or with `readers` (a map from tag
  symbol to function) for a file whose tags mean something else, over
  the #inst and #uuid readers clojure.edn falls back to. Exactly one form:
  text after it is refused, and text holding no form reads as nil. Fails
  WELL: text nesting deeper than max-depth is an ex-info {:reason
  :canonical/too-deep}, any other rejection an ex-info {:reason
  :edn/malformed}; callers wrap with their own context (which file,
  which row, which argument)."
  ([text] (parse text edn-readers))
  ([text readers]
   (when-not (and (shape/plain-map? readers)
                  (every? (fn [[k f]] (and (symbol? k) (fn? f))) readers))
     (shape/reject :readers :tag-readers readers))
   (when-not (or (nil? text) (string? text))
     (shape/reject :text :string text))
   (when text
     (try (bedn/read-string text {:max-bytes max-text-bytes
                                  :max-depth max-depth
                                  :readers (merge default-data-readers readers)})
          (catch clojure.lang.ExceptionInfo e
            (let [type (:type (ex-data e))]
              (cond
                (= type ::bedn/no-form) nil
                (= type ::bedn/too-deep) (throw (too-deep "nesting too deep to read safely"))
                (bedn/refusal? e) (throw (ex-info (str "malformed EDN — " (ex-message e))
                                                  {:reason :edn/malformed}
                                                  e))
                :else (throw e))))))))

(defn normalize-inst
  "An instant at the precision the canonical form commits to —
  milliseconds. Minting normalizes through this so an event's in-memory
  :event/at IS the value its bytes, and therefore its id, commit to;
  without it a nanosecond-precision clock (Instant/now) yields events
  that sort one way in memory and another after a store round trip,
  and (at, id) order stops being a property of the event set.

  Total: a non-Instant passes through untouched and meets its ordinary
  rejection downstream (the printer's, the schema's) rather than a type
  error here."
  [x]
  (if (instance? Instant x) (.truncatedTo ^Instant x ChronoUnit/MILLIS) x))

(defn- emit-inst ^String [^Instant i]
  (str "#inst \"" (str (normalize-inst i)) "\""))

(def max-depth
  "Bound on container nesting, write side and read side alike. The
  emitter and the EDN readers recurse per nesting level, so unbounded
  depth is a stack bomb (StackOverflowError — an Error no fail-well
  guard catches). No honest event approaches triple-digit nesting;
  values deeper than this are rejected with a clean ex-info."
  512)

(defn- too-deep
  "The refusal of text or a value that nests deeper than max-depth."
  [message]
  (ex-info message {:reason :canonical/too-deep :max-depth max-depth}))

(defn- emit*
  ^String [x depth]
  (when (> depth max-depth)
    (throw (too-deep "value nests too deeply for canonical EDN")))
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
    ;; carries a :reason so the porcelain can turn "the kernel refused
    ;; this type" into "here is what to type instead" — the kernel names
    ;; what it refused, a lens says what to do about it
    :else (throw (ex-info "unsupported type in canonical EDN"
                          {:reason :canonical/unsupported-type
                           :type (type x) :value x}))))

(defn emit
  "Return the canonical string form of `x`. Throws on unsupported types
  and on nesting deeper than max-depth (both as ex-info)."
  ^String [x]
  (emit* x 0))

(defn- bytes->hex ^String [^bytes b]
  (str/join (map #(format "%02x" %) b)))

(def implemented-algorithms
  "The digests this build can compute for a content address. A format
  version may name only one of these; SHA-512 is here so that a version
  switching to it is a table row, not new code."
  #{"SHA-256" "SHA-512"})

(def hash-of-version
  "The content hash of each format version this build implements, and the
  prefix its ids carry. ADR 0006 rule 2 is one row per version, rule 4 is
  that a verifier reads this table and never the prefix of an id it is
  handed."
  {1 {:prefix "sha256-" :algorithm "SHA-256"}})

(defn hash-spec
  "The row of `hash-of-version` for `version`, refusing a version this
  build does not implement."
  [version]
  (or (get hash-of-version version)
      (throw (ex-info (str "format version " (pr-str version)
                           " is not implemented by this build")
                      {:reason :canonical/unknown-format-version
                       :version version
                       :known (vec (sort (keys hash-of-version)))}))))

(defn- digest-hex ^String [^String algorithm ^bytes b]
  (when-not (contains? implemented-algorithms algorithm)
    (throw (ex-info (str "hash algorithm " (pr-str algorithm) " is not implemented")
                    {:reason :canonical/unknown-hash-algorithm
                     :algorithm algorithm})))
  (bytes->hex (.digest (MessageDigest/getInstance algorithm) b)))

(defn sha256-hex ^String [^String s]
  (shape/string :text s)
  (digest-hex "SHA-256" (.getBytes s "UTF-8")))

(defn sha256-hex-bytes
  "For blobs (artifacts). Hash policy — one algorithm per store per
  format-version, self-describing ids, additive migration — is ADR 0006."
  ^String [^bytes b]
  (when-not (bytes? b) (shape/reject :blob :bytes b))
  (digest-hex "SHA-256" b))

(defn address-with
  "The content address of `b` under the row `spec` of `hash-of-version`:
  its prefix and the hex digest of its algorithm."
  ^String [spec ^bytes b]
  (when-not (and (map? spec) (string? (:prefix spec)) (string? (:algorithm spec)))
    (shape/reject :hash-spec :map spec))
  (when-not (bytes? b) (shape/reject :blob :bytes b))
  (str (:prefix spec) (digest-hex (:algorithm spec) b)))

(def address-prefix
  "The prefix of every content address this build writes: the row of the
  current `format-version`."
  (:prefix (hash-spec format-version)))

(defn address-of-bytes
  "The content address of `b` under the current format version."
  ^String [^bytes b]
  (address-with (hash-spec format-version) b))

(defn address-of-text
  "The content address of the UTF-8 bytes of `s`."
  ^String [^String s]
  (shape/string :text s)
  (address-of-bytes (.getBytes s "UTF-8")))

(defn content-address
  "The content address of the canonical form of `value`."
  ^String [value]
  (address-of-text (emit value)))
