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

(declare check-nesting too-deep)

(defn parse
  "The one guarded EDN read: a nesting scan (check-nesting) refuses text
  the recursive reader could overflow on, then edn/read-string reads it
  with the canonical readers, or with `readers` (a map from tag symbol
  to function) for a file whose tags mean something else. Fails WELL:
  text nesting too deep is an ex-info {:reason :canonical/too-deep}, any
  other rejection an ex-info {:reason :edn/malformed}; callers wrap with
  their own context (which file, which row, which argument). A
  StackOverflowError is an Error no `catch Exception` sees, so the read
  refuses one as too deep as well, for a thread whose stack is smaller
  than max-depth levels need."
  ([text] (parse text edn-readers))
  ([text readers]
   (when-not (and (shape/plain-map? readers)
                  (every? (fn [[k f]] (and (symbol? k) (fn? f))) readers))
     (shape/reject :readers :tag-readers readers))
   (check-nesting text)
   (try (edn/read-string {:readers readers} text)
        #?(:clj (catch StackOverflowError _
                  (throw (too-deep "nesting too deep to read safely"))))
        (catch #?(:clj Exception :cljs :default) e
          (throw (ex-info (str "malformed EDN — " (ex-message e))
                          {:reason :edn/malformed}
                          e))))))

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

;; The nesting scan
;;
;; clojure.edn's reader recurses once per level of nesting, and a level is
;; not only a collection: a prefix the reader handles by reading further
;; forms inside the same call recurses as well. The scan mirrors that
;; recursion, so the levels it counts bound the reader's call depth. It
;; may count more levels than the reader uses, never fewer, and has to be
;; exact only up to the reader's first syntax error, since the reader
;; reads nothing after it. The prefixes, from the reader's macro and
;; dispatch tables:
;;
;;   #tag x    reads x, and the tagged literal is a form itself
;;   ##x       reads x (##Inf), and the value is a form itself
;;   #_ x      reads x, and the discard yields no form
;;   ^m x      reads m, then x; also #^m x
;;   #:ns {}   reads ns, then the map, which is a collection of its own,
;;             so a namespaced map counts two levels
;;
;; Strings, `;` comments and character literals are skipped, so neither a
;; bracket nor a quote inside them changes the count.

(def ^:private level-collection 1)
(def ^:private level-form-prefix 2) ; #tag and ##: completing x completes a form
(def ^:private level-discard 3)     ; #_: completing x completes nothing
(def ^:private level-two-forms 4)   ; ^ and #: before their first form
(def ^:private level-one-form 5)    ; ^ and #: before their second form

(defn- edn-whitespace? [c]
  (or (Character/isWhitespace (char c)) (= c \,)))

(defn- terminating?
  "Whether c ends a token: whitespace or one of the reader's terminating
  macro characters (# and ' are constituents)."
  [c]
  (or (edn-whitespace? c)
      (case (char c)
        (\" \; \^ \( \) \[ \] \{ \} \\) true
        false)))

(defn- token-end ^long [^String s ^long i]
  (let [n (.length s)]
    (loop [j i]
      (if (and (< j n) (not (terminating? (.charAt s j))))
        (recur (inc j))
        j))))

(defn- string-end
  "The index after the string literal whose opening quote is at i, or the
  length of s when it is unterminated."
  ^long [^String s ^long i]
  (let [n (.length s)]
    (loop [j (inc i)]
      (if (< j n)
        (case (.charAt s j)
          \\ (recur (+ j 2))
          \" (inc j)
          (recur (inc j)))
        n))))

(defn- line-end ^long [^String s ^long i]
  (let [n (.length s)]
    (loop [j i]
      (if (and (< j n)
               (not= \newline (.charAt s j))
               (not= \return (.charAt s j)))
        (recur (inc j))
        j))))

(defn check-nesting
  "Reject a raw EDN string that nests deeper than max-depth BEFORE it
  reaches the recursive reader, as an ex-info {:reason
  :canonical/too-deep}. Counts collections and the prefixes the reader
  recurses on (tags, ##, discards, metadata, namespaced maps), and skips
  strings, comments and character literals. Linear in the length of the
  text, and never recursive. nil reads as empty text."
  [^String s]
  (when-not (or (nil? s) (string? s))
    (shape/reject :text :string s))
  (when s
    (let [limit (long max-depth)
          n (.length s)
          stack (int-array (inc (min limit n)))
          depth (volatile! 0)
          push! (fn [kind]
                  (let [d (long @depth)]
                    (when (>= d limit)
                      (throw (too-deep "nesting too deep to read safely")))
                    (aset stack d (int kind))
                    (vreset! depth (inc d))))
          ;; A form ended: pop the prefixes it completes.
          complete! (fn []
                      (let [d (long @depth)]
                        (when (pos? d)
                          (let [top (aget stack (dec d))]
                            (cond
                              (= top level-form-prefix) (do (vreset! depth (dec d)) (recur))
                              (= top level-discard) (vreset! depth (dec d))
                              (= top level-two-forms) (aset stack (dec d) (int level-one-form))
                              (= top level-one-form) (do (vreset! depth (dec d)) (recur))
                              :else nil)))))
          ;; A closing delimiter: drop the prefixes still waiting for a
          ;; form (the reader refuses those) and the collection it closes.
          close! (fn []
                   (let [d (long @depth)]
                     (when (pos? d)
                       (let [top (aget stack (dec d))]
                         (vreset! depth (dec d))
                         (if (= top level-collection)
                           (complete!)
                           (recur))))))]
      (loop [i 0]
        (when (< i n)
          (let [c (.charAt s i)]
            (cond
              (edn-whitespace? c) (recur (inc i))
              (= c \;) (recur (line-end s i))
              (= c \") (do (complete!) (recur (string-end s i)))
              ;; a character literal: the character after the backslash,
              ;; whatever it is, then the rest of the token
              (= c \\) (let [end (if (< (inc i) n) (token-end s (+ i 2)) n)]
                         (complete!)
                         (recur (long end)))
              (or (= c \() (= c \[) (= c \{))
              (do (push! level-collection) (recur (inc i)))
              (or (= c \)) (= c \]) (= c \}))
              (do (close!) (recur (inc i)))
              (= c \^) (do (push! level-two-forms) (recur (inc i)))
              (= c \#)
              (if (< (inc i) n)
                (let [d (.charAt s (inc i))]
                  (cond
                    (= d \{) (do (push! level-collection) (recur (+ i 2)))
                    (= d \_) (do (push! level-discard) (recur (+ i 2)))
                    (or (= d \^) (= d \:)) (do (push! level-two-forms) (recur (+ i 2)))
                    (= d \#) (do (push! level-form-prefix) (recur (+ i 2)))
                    (Character/isLetter d)
                    (do (push! level-form-prefix) (recur (token-end s (inc i))))
                    ;; the reader refuses any other dispatch character and
                    ;; reads nothing after it
                    :else (recur (+ i 2))))
                (recur (inc i)))
              :else (let [end (token-end s i)]
                      (complete!)
                      (recur end)))))))))

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
