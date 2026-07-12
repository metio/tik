;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.cross-runtime-test
  "Differential verification across the two runtimes the kernel must
  agree on forever: the JVM (this test process) and babashka (SCI, via
  a shelled `bb`). The content-addressing promise — every event hashes
  and verifies identically on every runtime, for all time — is only as
  strong as the weakest runtime's agreement with the others. We compute
  on the JVM, recompute the SAME inputs under babashka, and require
  byte-identical answers. A divergence is a format fork: the same event
  would get two different ids.

  Values cross the boundary as canonical/emit output — guaranteed-clean
  EDN (no pr-str quirks), which babashka reads with the default reader
  and must re-emit to the very same bytes. Events cross as a real file
  store both runtimes read identically."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [tik.canonical :as canonical]
            [tik.event :as event]
            [tik.reduce :as red]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(def ^:private repo (System/getProperty "user.dir"))

(def ^:private gen-safe-string
  "Strings over valid, boundary-stable code points: printable ASCII plus
  BMP characters, EXCLUDING two classes that are harness limits rather
  than kernel divergences. Lone surrogates (U+D800–U+DFFF) are invalid
  Unicode that cannot survive the UTF-8 stdin pipe. U+2028/U+2029 (line
  and paragraph separators) are line terminators some readers split on,
  which would desync this test's line-based batching. Neither can occur
  in a real event that survives mint's round-trip guard, so both are out
  of scope; the kernel itself emits them fine when they do appear."
  (gen/fmap str/join
            (gen/vector
             (gen/fmap char
                       (gen/one-of [(gen/choose 32 126)
                                    (gen/choose 161 0x2027)
                                    (gen/choose 0x202A 0xD7FF)
                                    (gen/choose 0xE000 0xFFFD)]))
             0 8)))

(def ^:private gen-canonical-scalar
  "Only the types canonical/emit supports; java.util.Date for the time
  type (canonical emits Date and Instant identically)."
  (gen/one-of
   [(gen/return nil)
    gen/boolean
    gen/large-integer
    gen-safe-string
    gen/keyword
    gen/symbol
    (gen/fmap (fn [_] (random-uuid)) gen/nat)
    ;; dates within the range the DEFAULT #inst reader accepts (epoch to
    ;; year 9999) — the same range mint's round-trip guard enforces, so
    ;; this is exactly the domain of dates a real event can carry. An
    ;; expanded-year Instant (far past/future) emits a #inst literal
    ;; clojure.instant cannot read, which is why mint refuses it.
    (gen/fmap #(java.util.Date. (long %))
              (gen/choose 0 253402300799999))]))

(def ^:private gen-canonical
  "Nested maps/vectors/sets over the supported scalars — the shape of an
  event body."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/set inner {:max-elements 4})
                  (gen/map inner inner {:max-elements 4})]))
   gen-canonical-scalar))

(def ^:private mark
  "A per-line result marker. babashka can emit incidental stdout (task
  override warnings, deprecation notices); prefixing every real result
  line lets the JVM side ignore any such noise, so this test measures
  runtime AGREEMENT, not babashka's chattiness."
  "\u0001")

(defn- bb-results
  "Run `form` under babashka in the repo (tik on the classpath), feeding
  `in` on stdin; return the marked result lines with the marker stripped,
  ignoring any unmarked stdout noise. Throws with stderr on failure."
  [form in]
  (let [r (sh/sh "bb" "--config" (str repo "/bb.edn") "-e" form
                 :in in :dir repo)]
    (when-not (zero? (:exit r))
      (throw (ex-info "bb eval failed" {:err (:err r)})))
    (into []
          (comp (filter #(str/starts-with? % mark))
                (map #(subs % (count mark))))
          (str/split-lines (:out r)))))

(deftest canonical_emit_is_reproduced_by_babashka
  ;; the un-migratable layer, differentially checked: the JVM emits the
  ;; canonical form of each generated value; babashka reads that text
  ;; and must re-emit the very same bytes. A single divergent line is a
  ;; format fork between the runtimes.
  (let [values (gen/sample gen-canonical 200)
        jvm (mapv canonical/emit values)
        bb (bb-results
            (str "(require '[clojure.edn :as edn] '[tik.canonical :as c]"
                 " '[clojure.string :as s])"
                 "(doseq [l (s/split-lines (slurp *in*))]"
                 "  (println (str \"" mark "\" (c/emit (edn/read-string l)))))")
            (str/join "\n" jvm))
        mismatch (first (filter (fn [[a b]] (not= a b)) (map vector jvm bb)))]
    (is (= (count jvm) (count bb)))
    (is (nil? mismatch)
        (str "canonical/emit diverged between JVM and babashka:\n"
             "  JVM: " (pr-str (first mismatch)) "\n"
             "  bb : " (pr-str (second mismatch))))))

(deftest golden_instant_and_uuid_literals_agree
  ;; the two tagged literals whose printing has historically diverged
  ;; across runtimes, pinned directly
  (let [text (str "[#inst \"2026-07-12T10:20:30.123Z\" "
                  "#uuid \"018f2f6e-7c1a-7000-8000-00000000beef\" "
                  ":k \"s\" 42 true nil {:a [1 2 #{:x :y}]}]")
        jvm (canonical/content-address (edn/read-string text))
        bb (bb-results
            (str "(require '[clojure.edn :as edn] '[tik.canonical :as c])"
                 "(println (str \"" mark "\""
                 " (c/content-address (edn/read-string (slurp *in*)))))")
            text)]
    (is (= [jvm] bb))))

(deftest derivation_agrees_across_runtimes
  ;; a step up from canonical: the same file store must reduce to the
  ;; same ticket-state on both runtimes. The events are minted and
  ;; written by the JVM; babashka reads the identical store and derives.
  (let [dir (.toFile (Files/createTempDirectory
                      "tik-xrt" (make-array FileAttribute 0)))
        s (fstore/file-store (str dir))
        t (random-uuid)
        i0 (Instant/parse "2026-01-01T00:00:00Z")
        evs (event/chain
             (fn [_] (event/create-ticket {:ticket t :actor "a" :at i0
                                           :title "x" :process :p}))
             #(event/assert-fact {:ticket t :actor "a"
                                  :at (.plusSeconds i0 86400) :parents %
                                  :path [:amount] :value 120}))
        _ (doseq [e evs] (store/append! s e))
        jvm (canonical/content-address (red/ticket-state (store/events s t)))
        bb (bb-results
            (str "(require '[tik.store.file :as fs] '[tik.store.protocol :as p]"
                 " '[tik.reduce :as r] '[tik.canonical :as c] '[clojure.string :as s])"
                 "(let [st (fs/file-store (s/trim (slurp *in*)))"
                 "      tid (first (p/ticket-ids st))]"
                 "  (println (str \"" mark "\""
                 " (c/content-address (r/ticket-state (p/events st tid))))))")
            (str dir))]
    (is (= [jvm] bb)
        "ticket-state diverged across runtimes over the same store")))
