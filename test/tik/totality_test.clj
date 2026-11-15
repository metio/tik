;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.totality-test
  "The kernel's totality floor, enforced deterministically: every public
  function of every kernel namespace returns a value or throws ex-info,
  whatever it is handed. Never another Throwable, never a hang.

  The random sweep in tik.fuzz-test samples; this suite enumerates. A
  fixed corpus of hostile values meets every public kernel function on
  every run, so a bug like a map-destructured parameter receiving an
  odd-length list cannot hide behind the luck of a seed. The function
  set is DERIVED from src/tik/*.cljc and each namespace's publics, so a
  new kernel function is covered the moment it exists, with nothing to
  register.

  Four layers:
  - universal: every public fn, every arity, every argument the same
    hostile value;
  - per position: each registered boundary's valid base arguments with
    one position replaced by each hostile value;
  - deep mutation: each node inside those base arguments (map value,
    vector, list and set element) replaced by each hostile value;
  - per boundary: every registered boundary gets its own randomized run
    of `n` trials (TIK_FUZZ_N scales it)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.fuzz-test :as fuzz])
  (:import (java.time Instant)
           (java.util.concurrent TimeUnit TimeoutException)))

(def ^:private n
  (or (some-> (System/getenv "TIK_FUZZ_N") parse-long) 50))

(def hostile
  "Values shaped to break the usual assumptions: destructuring (odd and
  even lists, maps with odd keys), seq and count (scalars, chars, byte
  arrays, opaque objects), arithmetic and comparison (NaN, infinities,
  ratios, big numbers), string handling (empty, look-alike prefixes),
  and identity (uuids, instants)."
  [nil true false
   0 -1 1.5 ##NaN ##Inf ##-Inf 7/10 Long/MAX_VALUE Long/MIN_VALUE
   123456789012345678901234567890N 1.0M
   \a "" " " "x" "sha256-" "#uuid" "\u0000" (str/join (repeat 300 "é"))
   :k :a/b :event/id 'sym
   [] [nil] [0 0 0] [[]] [:a 1] [[0 0 0]]
   '() '(0) '(0 0 0) '(:a) (list :a 1 :b) (list :a 1) '(((0)))
   #{} #{0} #{nil} #{[0 0 0]}
   {} {nil nil} {:a 1} {0 0} {:a '(0 0 0)} {'(0 0 0) 1} {:ticket '(0 0 0)}
   (sorted-map) (sorted-set 1 2) (range 3) (lazy-seq [1 2 3])
   (java.util.UUID/fromString "018f2f6e-7c1a-7000-8000-00000000beef")
   Instant/EPOCH (Instant/parse "9999-12-31T23:59:59Z")
   (byte-array [1 2]) (Object.) (java.util.ArrayList. (java.util.List/of 1 2))])

(defn- kernel-namespaces []
  (->> (.listFiles (io/file (System/getProperty "user.dir") "src" "tik"))
       (keep (fn [^java.io.File f]
               (let [nm (.getName f)]
                 (when (str/ends-with? nm ".cljc")
                   (symbol (str "tik." (-> nm (str/replace #"\.cljc$" "")
                                           (str/replace "_" "-"))))))))
       sort))

(defn- public-fns
  "[qualified-sym fn arities] for every public fn of the kernel."
  []
  (for [ns-sym (kernel-namespaces)
        :let [_ (require ns-sym)]
        [nm v] (sort-by key (ns-publics ns-sym))
        :when (fn? @v)]
    [(symbol (str ns-sym) (str nm)) @v
     (distinct
      (mapcat (fn [al]
                (let [[fixed more] (split-with #(not= '& %) al)]
                  (if (seq more) [(count fixed) (inc (count fixed))] [(count fixed)])))
              (:arglists (meta v))))]))

(defn- force-all
  "Realize a result deeply enough that a lazy failure surfaces."
  [r]
  (when (seqable? r) (dorun (take 100000 (tree-seq seqable? seq r))))
  r)

(defn- outcome
  "nil when `f` on `args` returns a value or throws ex-info within the
  time limit; else a short description of what it did instead."
  [f args]
  (let [fut (future
              (try (force-all (apply f args)) nil
                   (catch clojure.lang.ExceptionInfo _ nil)
                   (catch Throwable t t)))]
    (try
      (when-let [t (.get ^java.util.concurrent.Future fut 10 TimeUnit/SECONDS)]
        (str (.getName (class t)) ": " (ex-message t)))
      (catch TimeoutException _
        (future-cancel fut)
        "no answer within 10s"))))

(defn- show [x]
  (let [s (binding [*print-length* 6 *print-level* 4] (pr-str x))]
    (if (> (count s) 160) (str (subs s 0 160) "…") s)))

(defn- report-all [failures]
  (doseq [[sym args what] (take 40 failures)]
    (println "NOT TOTAL:" sym "on" (show args) "->" what))
  (when (> (count failures) 40)
    (println "…and" (- (count failures) 40) "more")))

;; ---------------------------------------------------------------- universal

(deftest every_public_kernel_fn_is_total_over_the_hostile_corpus
  (let [failures (vec (for [[sym f arities] (public-fns)
                            k arities
                            h hostile
                            :let [args (vec (repeat k h))
                                  what (outcome f args)]
                            :when what]
                        [sym args what]))]
    (report-all failures)
    (is (empty? failures)
        (str (count failures) " hostile call(s) escaped as raw exceptions or hangs"))))

;; ---------------------------------------------------------------- registered

(def ^:private seed 20261115)

(defn- base-args
  "A valid argument vector for a registered boundary, the same on every
  run (fixed size and seed)."
  [{:keys [gen]}]
  (gen/generate gen 8 seed))

(defn- nodes
  "Paths to the nodes inside `x`, depth-first, at most `limit` of them.
  A path step is [:key k] for maps, [:idx i] for vectors and lists, and
  [:elem e] for sets."
  [x limit]
  (let [out (volatile! [])]
    (letfn [(walk [x path depth]
              (when (and (< (count @out) limit) (< depth 5))
                (let [children (cond
                                 (map? x) (for [[k v] x] [[:key k] v])
                                 (vector? x) (map-indexed (fn [i v] [[:idx i] v]) x)
                                 (seq? x) (map-indexed (fn [i v] [[:idx i] v]) (take 50 x))
                                 (set? x) (for [e x] [[:elem e] e])
                                 :else nil)]
                  (doseq [[step child] children
                          :while (< (count @out) limit)]
                    (let [p (conj path step)]
                      (vswap! out conj p)
                      (walk child p (inc depth)))))))]
      (walk x [] 0))
    @out))

(defn- replace-at [x [[kind k] & more] v]
  (let [inner (fn [old] (if more (replace-at old more v) v))]
    (case kind
      :key (update x k inner)
      :idx (if (vector? x)
             (assoc x k (inner (nth x k)))
             (apply list (assoc (vec x) k (inner (nth x k)))))
      :elem (conj (disj x k) (inner k)))))

(defn- arg-sets
  "Each argument vector a registered boundary meets beyond the universal
  layer: one position replaced, or one inner node replaced, by each
  hostile value."
  [entry]
  (let [base (base-args entry)]
    (concat
     (for [i (range (count base)) h hostile]
       (assoc base i h))
     (for [p (nodes base 40) h hostile]
       (replace-at base p h)))))

(deftest every_registered_boundary_survives_hostile_positions_and_nodes
  (let [failures (vec (for [{:keys [sym f] :as entry} fuzz/totality-registry
                            args (arg-sets entry)
                            :let [what (outcome f args)]
                            :when what]
                        [sym args what]))]
    (report-all failures)
    (is (empty? failures)
        (str (count failures) " hostile argument(s) escaped as raw exceptions or hangs"))))

(deftest every_registered_boundary_gets_its_own_random_run
  (doseq [{:keys [sym f gen]} fuzz/totality-registry]
    (let [r (tc/quick-check n (prop/for-all [args gen] (nil? (outcome f args)))
                            :max-size 30)]
      (when-not (:pass? r)
        (println "NOT TOTAL:" sym "seed" (:seed r) "smallest"
                 (show (get-in r [:shrunk :smallest]))))
      (is (:pass? r) (str sym " is not total; seed " (:seed r))))))

(deftest the_corpus_reaches_every_kernel_namespace
  (testing "the universal layer sees every kernel namespace's publics"
    (let [covered (set (map (comp symbol namespace first) (public-fns)))]
      (doseq [ns-sym (kernel-namespaces)]
        (is (or (covered ns-sym)
                (empty? (filter (comp fn? deref val) (ns-publics ns-sym))))
            (str ns-sym " has public fns the corpus does not reach"))))))
