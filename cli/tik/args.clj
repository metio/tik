;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.args
  "The porcelain's input layer: flag parsing and the forgiving value
  grammar people actually type. Nobody should need to know EDN quoting to
  record a fact, so a bare word becomes a keyword, one complete EDN form
  is taken as EDN, and anything else is the literal string — informed,
  when a process declares a path's type, by that declaration.

  A leaf: it depends on the kernel's canonical reader, never on cli. File
  reads fail WELL (an ex-info dispatch-guarded turns into `tik: …` + exit
  1), so this namespace does no process control of its own."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical])
  (:import (java.io File PushbackReader StringReader)))

(defn parse-args [args]
  (loop [args args pos [] opts {}]
    (if (empty? args)
      {:pos pos :opts opts}
      (let [[a & more] args]
        (cond
          (not (str/starts-with? a "--"))
          (recur more (conj pos a) opts)
          ;; --flag=value: the value rides in the same token (the near-
          ;; universal CLI convention). Split on the first =, so
          ;; --actor=alice binds :actor, not the junk key :actor=alice —
          ;; parsing it wrong silently drops authorship (--actor), the
          ;; signing key (--key), or the derivation clock (--at).
          (str/includes? a "=")
          (let [i (str/index-of a "=")]
            (recur more pos
                   (assoc opts (keyword (subs a 2 i)) (subs a (inc i)))))
          ;; a flag with no value (end of args, or another option next)
          ;; is boolean true — e.g. --apply
          (or (empty? more) (str/starts-with? (first more) "--"))
          (recur more pos (assoc opts (keyword (subs a 2)) true))
          :else
          (recur (rest more) pos (assoc opts (keyword (subs a 2)) (first more))))))))

(defn actor [opts]
  (or (:actor opts) (System/getenv "TIK_ACTOR")
      (System/getProperty "user.name")))

(defn parse-key [s] (mapv keyword (str/split s #"\.")))

(defn parse-value
  "Forgiving: one complete EDN form is taken as EDN (bare words become
  keywords); anything else — multi-word prose, 31ffd53-style
  hex-with-letters, stray punctuation — is the literal string. Nobody
  should need to know EDN quoting to record a description."
  [s]
  (let [[v ok?] (try
                  ;; depth precheck: a recursion-bomb argument must land
                  ;; as a literal string, not overflow the reader
                  (canonical/check-nesting s)
                  (with-open [r (PushbackReader. (StringReader. s))]
                    (let [form (edn/read {:eof ::eof
                                          :readers canonical/edn-readers} r)
                          rest (edn/read {:eof ::eof} r)]
                      [form (= ::eof rest)]))
                  (catch Exception _ [nil false]))]
    (cond
      (not ok?) s
      ;; empty/whitespace-only input reads as the eof sentinel — that must
      ;; never land in a signed fact; it is the literal (empty) string
      (= ::eof v) s
      (symbol? v) (keyword (str v))
      :else v)))

(defn read-edn-file
  "EDN from a file, failing WELL: a malformed config names itself
  instead of exploding the reader. nil when the file is absent."
  [^File f]
  (when (.exists f)
    (try (canonical/parse (slurp f))
         (catch Exception e
           (throw (ex-info (str "malformed EDN in " f " — "
                                (ex-message e))
                           {:reason :config/malformed :file (str f)}
                           e))))))

(defn slurp-existing
  "Read a user-supplied file, failing cleanly if it is absent — never a
  raw FileNotFoundException surfaced as 'a bug in tik'. The ex-info
  dispatch-guarded turns into `tik: no such … file: …` + exit 1."
  [what path]
  (if (.exists (io/file path))
    (slurp path)
    (throw (ex-info (str "no such " what " file: " path)
                    {:reason :file/missing :what what :path (str path)}))))

(defn declared-string?
  "Does the process declare this fact path as a string type? Then a
  bare token the parser would keywordize is really the user's string."
  [proc path]
  (let [t (get-in proc [:process/facts path])]
    (boolean (or (= :string t)
                 (and (vector? t) (= :string (first t)))))))

(defn typed-value
  "parse-value informed by the DECLARED type: when the declaration
  says string and the raw text is a bare word (no explicit :colon),
  the raw text wins over keywordization — commit=a051932 lands as
  \"a051932\". Everything else parses exactly as before."
  [proc path raw]
  (let [parsed (parse-value raw)]
    (if (and (not (string? parsed))
             (or (declared-string? proc path)
                 ;; [:link <rel>] values are id REFERENCES — always
                 ;; strings. A uuid target starting with a letter would
                 ;; otherwise parse as a symbol and keywordize, breaking
                 ;; resolution (the leading ':' never matches a ticket id)
                 (= :link (first path))))
      ;; the declaration wants a string: bare hex, all-digit hashes,
      ;; words — the raw text IS the value (a quoted "..." already
      ;; parsed to a string and kept its EDN reading)
      (str/trim raw)
      parsed)))
