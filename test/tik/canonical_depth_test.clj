;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.canonical-depth-test
  "The nesting scan in front of the EDN reader. clojure.edn recurses once
  per collection and once per prefix it reads further forms for (tags,
  ##, discards, metadata, namespaced maps), and its StackOverflowError is
  an Error no `catch Exception` sees. Every chain of those beyond
  max-depth must come back as tik's own :canonical/too-deep, on the JVM
  here and under babashka through a subprocess."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.args :as args]
            [tik.canonical :as c]
            [tik.harness :as h]))

(defn- rep [n s] (str/join (repeat n s)))

(defn- outcome
  "What f does: :read, the :reason of an ex-info, or the class name of
  anything else thrown (which no test here accepts)."
  [f]
  (try
    (f)
    :read
    (catch clojure.lang.ExceptionInfo e
      (or (:reason (ex-data e)) :ex-info))
    #_{:splint/disable [lint/catch-throwable]}
    (catch Throwable t
      (.getName (class t)))))

(def ^:private levels
  "Text that nests exactly n levels, per kind of level."
  {:vector (fn [n] (str (rep n "[") "1" (rep n "]")))
   :list (fn [n] (str (rep n "(") "1" (rep n ")")))
   :map (fn [n] (str (rep n "{:a ") "1" (rep n "}")))
   :set (fn [n] (str (rep n "#{") "1" (rep n "}")))
   :tag (fn [n] (str (rep n "#inst ") "\"2026-01-01T00:00:00Z\""))
   :unknown-tag (fn [n] (str (rep n "#foo ") "1"))
   :symbolic (fn [n] (str (rep n "## ") "Inf"))
   :discard (fn [n] (str (rep n "#_ ") (rep (inc n) "1 ")))
   :meta (fn [n] (str (rep (dec n) "^:a ") "[]"))
   :hash-meta (fn [n] (str (rep (dec n) "#^:a ") "[]"))
   :meta-map (fn [n] (str (rep (dec n) "^{:a 1} ") "[]"))
   :namespaced-map (fn [n] (str (rep (quot n 2) "#:a{:b ") "1" (rep (quot n 2) "}")))
   :mixed (fn [n] (let [opens ["[" "#inst " "{:k " "#_ 0 " "^:m " "#:n{:k "]
                        ;; levels each fragment opens, and how it closes
                        cost {"[" 1 "#inst " 1 "{:k " 1 "#_ 0 " 0 "^:m " 1 "#:n{:k " 2}
                        close {"[" "]" "#inst " "" "{:k " "}" "#_ 0 " "" "^:m " "" "#:n{:k " "}"}
                        frags (loop [acc [] used 0 i 0]
                                (let [f (nth opens (mod i (count opens)))
                                      used' (+ used (cost f))]
                                  (if (> used' n)
                                    acc
                                    (recur (conj acc f) used' (inc i)))))
                        used (reduce + (map cost frags))
                        pad (- n used)]
                    (str (str/join frags) (rep pad "[") "1" (rep pad "]")
                         (str/join (map close (reverse frags))))))})

(defn- scan [text] (outcome #(c/check-nesting text)))

(deftest each_kind_of_level_is_refused_one_past_the_limit
  (doseq [[kind text-of] levels]
    (testing kind
      (is (= :read (scan (text-of c/max-depth))) "at the limit the scan passes")
      (is (not (#{:canonical/too-deep "java.lang.StackOverflowError"}
                (outcome #(c/parse (text-of c/max-depth)))))
          "at the limit the reader reads or refuses the syntax, never the depth")
      (is (= :canonical/too-deep (scan (text-of (+ 2 c/max-depth)))))
      (is (= :canonical/too-deep (outcome #(c/parse (text-of (+ 2 c/max-depth)))))))))

(deftest each_kind_of_level_at_the_limit_reads
  (testing "the plain forms read back as values"
    (is (= 1 (c/parse ((:discard levels) c/max-depth))))
    (is (= [] (c/parse ((:meta levels) c/max-depth))))
    (is (vector? (c/parse ((:vector levels) c/max-depth))))
    (is (map? (c/parse ((:namespaced-map levels) c/max-depth))))))

(deftest one_level_past_the_limit_is_refused
  (doseq [kind [:vector :tag :unknown-tag :symbolic :discard :meta :hash-meta]]
    (testing kind
      (is (= :canonical/too-deep (scan ((levels kind) (inc c/max-depth))))))))

(def ^:private bombs
  "200000-deep chains of every kind of level, the shape of the reported
  crash."
  (into {} (map (fn [[k f]] [k (f 200000)])) levels))

(deftest every_chain_bomb_is_refused_with_too_deep
  (doseq [[kind text] bombs]
    (testing kind
      (is (= :canonical/too-deep (outcome #(c/parse text)))))))

(deftest a_quote_in_a_comment_does_not_hide_the_nesting_after_it
  ;; a scan that read the quote as a string start would skip everything
  ;; to the next quote, and the bomb with it
  (doseq [prefix [";\"\n" "; a \"quote\" and another \"\n" ";\"\r" "[1 ;\"\n 2]"]]
    (testing (pr-str prefix)
      (is (= :canonical/too-deep (outcome #(c/parse (str prefix (:vector bombs))))))
      (is (= :canonical/too-deep (outcome #(c/parse (str prefix (:discard bombs)))))))))

(deftest brackets_and_prefixes_in_strings_chars_and_comments_do_not_count
  (let [noise (str "\"" (rep 2000 "[#_^#:a{##") "\"")]
    (is (= :read (scan (str "[" noise "]"))))
    (is (= [(subs noise 1 (dec (count noise)))] (c/parse (str "[" noise "]")))))
  (is (= :read (scan (str "[" (rep 2000 "\\[ \\{ \\( \\# \\^ \\_ ") "]"))))
  (is (= :read (scan (str "{:a 1 ; " (rep 2000 "[#_^ ") "\n :b 2}"))))
  (is (= {:a 1 :b 2} (c/parse (str "{:a 1 ; " (rep 2000 "[#_^ ") "\n :b 2}"))))
  (is (= :read (scan (str "[\"\\\"" (rep 2000 "[") "\"]")))
      "an escaped quote does not end the string"))

(deftest sibling_discards_and_metadata_do_not_accumulate
  ;; siblings are read in a loop, not by recursion, so they cost nothing
  (is (= :read (scan (str "[" (rep 100000 "#_ 1 ") "]"))))
  (is (= :read (scan (str "[" (rep 100000 "^:a [] ") "]"))))
  (is (= :read (scan (str "[" (rep 100000 "#inst \"2026-01-01T00:00:00Z\" ") "]")))))

(defn- on-small-stack
  "f's value, or the reason or class of what it threw, on a thread with a
  64 KiB stack, far too small for max-depth levels."
  [f]
  (let [result (promise)
        ^Runnable body #(deliver result (try (f)
                                             (catch clojure.lang.ExceptionInfo e
                                               (or (:reason (ex-data e)) :ex-info))
                                             #_{:splint/disable [lint/catch-throwable]}
                                             (catch Throwable t
                                               (.getName (class t)))))
        t (Thread. nil body "small-stack" 65536)]
    (.start t)
    (.join t)
    @result))

(deftest a_stack_overflow_inside_the_reader_is_refused_as_too_deep
  (let [text ((:vector levels) c/max-depth)]
    (testing "the input passes the scan, so the overflow is the reader's"
      (is (= :read (scan text))))
    (is (= :canonical/too-deep (on-small-stack #(c/parse text))))
    (testing "a CLI value lands as the literal string"
      (is (= text (on-small-stack #(args/parse-value text)))))))

(deftest cli_values_with_prefix_chains_land_as_literal_strings
  (doseq [[kind text] bombs]
    (testing kind
      (is (= text (args/parse-value text))))))

;; ---------------- properties

(defn- holds? [num-tests property]
  (let [result (tc/quick-check num-tests property)]
    (is (:pass? result) (pr-str (select-keys result [:seed :fail :shrunk])))))

(def ^:private syntax
  "Text built from collections and prefixes, with the number of levels the
  reader recurses through to read it."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/fmap (fn [xs] [(str "[" (str/join " " (map first xs)) "]")
                           (inc (reduce max 0 (map second xs)))])
                 (gen/vector inner 0 4))
       (gen/fmap (fn [xs] [(str "(" (str/join " " (map first xs)) ")")
                           (inc (reduce max 0 (map second xs)))])
                 (gen/vector inner 0 4))
       (gen/fmap (fn [xs] [(str "#{" (str/join " " (map-indexed (fn [i [t _]] (str "[:i" i " " t "]")) xs)) "}")
                           (inc (reduce max 0 (map #(inc (second %)) xs)))])
                 (gen/vector inner 0 3))
       (gen/fmap (fn [[t d]] [(str "#t " t) (inc d)]) inner)
       (gen/fmap (fn [[t d]] [(str "#t\n;c\n" t) (inc d)]) inner)
       ;; metadata needs a value that takes it, so it wraps a vector
       (gen/fmap (fn [[t d]] [(str "^:m [" t "]") (+ 2 d)]) inner)
       (gen/fmap (fn [[t d]] [(str "#^:m [" t "]") (+ 2 d)]) inner)
       (gen/fmap (fn [[[t d] [u e]]] [(str "^" t " [" u "]")
                                      (inc (max d (inc e)))])
                 (gen/tuple (gen/one-of [(gen/return [":k" 0])
                                         (gen/fmap (fn [[t d]] [(str "{:k " t "}") (inc d)]) inner)])
                            inner))
       (gen/fmap (fn [[[t d] [u e]]] [(str "[#_ " t " " u "]") (inc (max (inc d) e))])
                 (gen/tuple inner inner))
       (gen/fmap (fn [xs] [(str "#:ns{" (str/join " " (map-indexed (fn [i [t _]] (str ":k" i " " t)) xs)) "}")
                           (+ 2 (reduce max 0 (map second xs)))])
                 (gen/vector inner 0 3))]))
   (gen/elements [["1" 0] [":k" 0] ["\"[{(#_^\"" 0] ["\\[" 0] ["\\#" 0] ["\\;" 0]
                  ["sym" 0] ["nil" 0] ["##Inf" 1] ["[1 ; ]\"[\n]" 1]])))

(deftest the_scan_refuses_exactly_what_nests_past_the_limit
  ;; read with a tag reader the canonical readers lack, so a refusal can
  ;; only be the scan's
  (holds? 500
          (prop/for-all [[text depth] syntax
                         limit (gen/choose 1 8)]
            (with-redefs [c/max-depth limit]
              (let [scanned (scan text)]
                (if (<= depth limit)
                  (and (= :read scanned)
                       (= :read (outcome #(edn/read-string {:readers {'t identity}} text))))
                  (= :canonical/too-deep scanned)))))))

(def ^:private fragment
  "Short runs of the characters the scan and the reader treat specially."
  (gen/fmap str/join
            (gen/vector (gen/elements ["[" "]" "{" "}" "(" ")" "#{" "#_ " "^:m " "#^:m " "^" "#:a"
                                       "#:a{" "## " "#t " "#inst " "\"" "\\" "\\[" ";" "\n" " "
                                       "1" "a" ":k" "," "#"])
                        1 6)))

(deftest amplified_fragments_never_overflow_the_reader
  ;; The scan must never count fewer levels than the reader recurses
  ;; through. Repeating a fragment tens of thousands of times turns any
  ;; level the scan misses into a stack overflow, so whatever passes the
  ;; scan must read or be refused by the reader, never overflow it. The
  ;; read runs without parse's StackOverflowError backstop, which would
  ;; hide a miss.
  (holds? 200
          (prop/for-all [frag fragment
                         tail fragment
                         n (gen/choose 20000 60000)]
            (let [text (str (rep n frag) tail)]
              (case (scan text)
                :read (not= "java.lang.StackOverflowError"
                            (outcome #(edn/read-string {:readers {'t identity}} text)))
                :canonical/too-deep true
                false)))))

(deftest random_text_reads_or_is_refused
  (holds? 1000
          (prop/for-all [s (gen/fmap str/join (gen/vector fragment 0 40))]
            (let [o (outcome #(c/parse s))]
              (or (= :read o) (keyword? o))))))

;; ---------------- babashka

(deftest every_chain_bomb_is_refused_under_babashka
  (let [script (str "(require '[tik.canonical :as c] '[clojure.string :as str])"
                    "(defn rep [n s] (str/join (repeat n s)))"
                    "(prn (into (sorted-map) (for [[k t] {"
                    ":vector (str (rep 200000 \"[\") 1 (rep 200000 \"]\"))"
                    " :discard (str (rep 200000 \"#_ \") 1)"
                    " :meta (str (rep 200000 \"^:a \") \"[]\")"
                    " :hash-meta (str (rep 200000 \"#^:a \") \"[]\")"
                    " :tag (str (rep 200000 \"#foo \") 1)"
                    " :inst (str (rep 200000 \"#inst \") \"\\\"2026-01-01T00:00:00Z\\\"\")"
                    " :symbolic (str (rep 200000 \"## \") \"Inf\")"
                    " :namespaced-map (str (rep 200000 \"#:a{:b \") 1)"
                    " :comment-quote (str \";\\\"\\n\" (rep 200000 \"[\"))}]"
                    " [k (try (c/parse t) :read"
                    " (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))"
                    " (catch Throwable e (.getName (class e))))])))")
        repo (System/getProperty "user.dir")
        r (apply sh/sh (concat (h/bb-command "--config" (str repo "/bb.edn") "-e" script)
                               [:dir repo :env (h/subprocess-env)]))
        result (edn/read-string (last (str/split-lines (str/trim (:out r)))))]
    (is (zero? (:exit r)) (:err r))
    (is (= 9 (count result)))
    (is (every? #{:canonical/too-deep} (vals result)) (pr-str result))))

(deftest the_cli_refuses_a_chain_bomb_file_cleanly
  (let [f (h/temp-file! "bomb" ".edn")]
    (spit f (str (rep 200000 "#_ ") "{}"))
    (let [r (h/run-tik! {} "lint" (str f))]
      (is (not (zero? (:exit r))))
      (is (re-find #"nesting too deep" (:err r)) (:err r))
      (is (not (re-find #"StackOverflowError|bug in tik" (:err r))) (:err r)))))
