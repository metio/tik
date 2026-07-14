;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.render
  "The porcelain's output layer: color, id/hash display forms, and the
  one --format axis (human | edn | json) applied over any lens's DATA.

  Color is porcelain — tty-detected, NO_COLOR honored — and the kernel
  lens keeps emitting plain data; only this namespace paints. Every lens
  produces EDN; the format is one axis over it, not a branch each command
  re-implements: `(when-not (emit-data opts data) <human>)`."
  (:require [clojure.string :as str]
            [tik.text :refer [safe-name]]))

;; ---------------------------------------------------------------- color

(def ^:private use-color?
  (delay (and (nil? (System/getenv "NO_COLOR"))
              (some? (System/console)))))

(defn tint [code s]
  (if @use-color? (str "\u001b[" code "m" s "\u001b[0m") s))

;; java.time.Instant has no print-method, so pr-str renders it as
;; #object[…<identity-hash>…] — gibberish for humans and UNREADABLE as
;; EDN, which corrupts every --edn output carrying a time-typed fact.
;; Instants are the one canonical time type; print them as the #inst
;; literal the canonical readers parse right back. Porcelain-side on
;; purpose: the kernel never prints (it emits via canonical/emit).
(defmethod print-method java.time.Instant [^java.time.Instant i ^java.io.Writer w]
  (.write w (str "#inst \"" i "\"")))

;; ---------------------------------------------------------------- id forms

(defn sid
  "A ticket id's display form: the first 8 hex chars. Total over any
  input — real ids are long, but hostile FILENAMES (roots/, event and
  by-hash files) reach the display path too, so a short string returns
  whole rather than an out-of-bounds crash."
  [x]
  (let [s (str x)] (subs s 0 (min 8 (count s)))))

(defn shash
  "A content hash's display form: `sha256-` plus the first 12 hex chars.
  Total over any input (see sid): a hostile-short hash-shaped filename
  displays whole, never crashes."
  [x]
  (let [s (str x)] (subs s 0 (min 19 (count s)))))

(defn print-problems
  "Print lint problems as `[level] msg` lines; true when any is an
  error — the callers' gate condition."
  [problems]
  (doseq [{:keys [level msg]} problems]
    (println (str "[" (safe-name level) "] " msg)))
  (boolean (some #(= :error (:level %)) problems)))

;; ---------------------------------------------------------------- output
;; --format human|edn|json (with --edn as the short alias for edn). human
;; is the default and is unchanged.

(defn json-safe
  "Coerce a lens's EDN value into a shape cheshire can encode: Instants
  to ISO strings (the one time type has no JSON form), sets to arrays,
  and any non-name map key to its printed string (JSON keys are strings)."
  [x]
  (cond
    (instance? java.time.Instant x) (str x)
    (map? x) (into {} (map (fn [[k v]]
                             [(if (or (keyword? k) (string? k)) k (pr-str k))
                              (json-safe v)]))
                   x)
    (or (set? x) (sequential? x)) (mapv json-safe x)
    :else x))

(defn json-encode [x]
  ((requiring-resolve 'cheshire.core/generate-string) (json-safe x)))

(defn output-format
  "The requested output format keyword, or a fail-well throw for an
  unknown one (dispatch-guarded turns it into `tik: …` + exit 1)."
  [opts]
  (cond
    (:edn opts) :edn
    (nil? (:format opts)) :human
    (contains? #{"edn" "json" "human"} (:format opts)) (keyword (:format opts))
    :else (throw (ex-info (str "unknown --format " (:format opts)
                               " (edn | json | human)")
                          {:format (:format opts)}))))

(defn emit-data
  "Emit a lens's `data` in the requested machine format (edn | json) and
  return true; return false for :human so the caller renders for people.
  One dispatch replaces every command's `(if (:edn opts) (prn …) …)`:
  `(when-not (emit-data opts data) <human>)`."
  [opts data]
  (case (output-format opts)
    :edn (do (prn data) true)
    :json (do (println (json-encode data)) true)
    false))

(defn paint-stage [stage-str settled? parked?]
  (cond
    settled? (tint "32" stage-str)                 ; green: finished
    parked? (tint "33" stage-str)                  ; yellow: waiting on a decision
    :else (tint "36" stage-str)))                  ; cyan: live work

(defn paint-explain
  "Green checks, red crosses, dim hints — over the lens's plain text."
  [s]
  (->> (str/split-lines s)
       (map #(cond
               (str/starts-with? % "  ✓") (tint "32" %)
               (str/starts-with? % "  ✗") (tint "31" %)
               (str/starts-with? % "  (see:") (tint "2" %)
               (str/starts-with? % "To reach") (tint "1" %)
               :else %))
       (str/join "\n")))
