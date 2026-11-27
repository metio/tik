;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.lean-oracle
  "The differential oracle between the Clojure kernel and the Lean model in
  formal/lean. Every case is derived by `tik.stage` here and by the model's
  executable there, and any disagreement fails the run.

  A case comes in one of two modes. In derive mode the model reads the
  events themselves and runs its own reduction, fold and sticky carry
  (`tik.stage/effective-reached`); that needs every guard inside the
  modelled basis (:fact :fact= :signed-by :different-person :stage-reached
  :and :or :not) and a history of assertions and retractions free of
  disputes and conflicts. In fixpoint mode the kernel reduces the events,
  hands the model the facts as fact-status reports them and the sticky
  carry, and replaces every guard outside the basis by its verdict
  (`tik.stage/reached-set`).

  The cases are the conformance corpus, every definition under processes/,
  every scripted case under processes/*.tests.edn, and generated processes
  and histories, lint-clean or not, from a fixed seed.

  Run as `clojure -M:dev -m tik.lean-oracle --lean <tik-lean> [--generated
  N] [--seed S] [--mutant NAME --expect-disagreement] [--echo] [--out
  DIR]`."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [tik.canonical :as canonical]
            [tik.design :as design]
            [tik.event :as event]
            [tik.guard :as guard]
            [tik.lint :as lint]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.io File)
           (java.time Instant)))

;; ------------------------------------------------------------ encoding

(def modelled-leaves #{:fact :fact= :signed-by :different-person :stage-reached})

(defn s
  "A kernel value as the string the model compares: its canonical EDN."
  [x]
  (canonical/emit x))

(defn leaf? [g] (not (#{:and :or :not} (first g))))

(defn fully-modelled?
  "Whether every leaf of `g` is in the modelled basis."
  [g]
  (if (leaf? g)
    (contains? modelled-leaves (first g))
    (every? fully-modelled? (rest g))))

(defn encode-guard
  "A guard as the model's JSON array. A leaf outside the basis goes through
  `other`, which answers its replacement or throws."
  [g other]
  (case (first g)
    :fact ["fact" (s (second g))]
    :fact= ["fact=" (s (nth g 1)) (s (nth g 2))]
    :signed-by ["signed-by" (s (nth g 1)) (s (nth g 2))]
    :different-person ["different-person" (s (nth g 1)) (s (nth g 2))]
    :stage-reached ["stage-reached" (s (second g))]
    :not ["not" (encode-guard (second g) other)]
    :and (into ["and"] (map #(encode-guard % other)) (rest g))
    :or (into ["or"] (map #(encode-guard % other)) (rest g))
    (other g)))

(defn- unsupported [g]
  (throw (ex-info "guard outside the modelled basis" {:guard g})))

(defn encode-process [proc other]
  {"stages" (mapv (fn [st]
                    {"id" (s (:stage/id st))
                     "after" (mapv s (:after st []))
                     "sticky" (boolean (:stage/sticky? st))
                     "guards" (mapv #(encode-guard % other) (:guards st []))})
                  (:process/stages proc))})

(defn encode-roles [roles]
  (into (sorted-map)
        (map (fn [[r {:keys [members]}]] [(s r) (mapv s members)]))
        roles))

(defn- guard-paths [g]
  (case (first g)
    (:fact :signed-by) [(last g)]
    :fact= [(nth g 1)]
    :different-person [(nth g 1) (nth g 2)]
    (:and :or :not) (mapcat guard-paths (rest g))
    []))

(defn- process-paths [proc]
  (into #{} (comp (mapcat :guards) (mapcat guard-paths)) (:process/stages proc)))

(defn- inst->nanos [^Instant t]
  (+ (* (bigint (.getEpochSecond t)) 1000000000) (.getNano t)))

(defn- schema-valid?
  "Whether `value` satisfies the schema the definition declares for `path`,
  as tik.guard's :fact reads it."
  [proc path value]
  (let [schema (get-in proc [:process/facts path])]
    (or (nil? schema) (true? (process/schema-holds? schema value)))))

;; ------------------------------------------------------------ cases

(defn- lint-clean? [proc]
  (not-any? #(= :error (:level %)) (lint/lint proc)))

(defn fixpoint-case
  "A fixpoint-mode case: the facts of `state` as fact-status reports them,
  the carry `base`, and the kernel's reached set."
  [id proc state now roles base]
  (let [ctx {:state state :process proc :now now :roles roles :reached #{}}
        const (fn [g] ["const" (:satisfied? (guard/eval-guard g ctx))])
        paths (into (process-paths proc) (keys (:facts state)))]
    {:case {"id" id
            "mode" "fixpoint"
            "process" (encode-process proc const)
            "roles" (encode-roles roles)
            "facts" (vec (for [p (sort-by s paths)
                               :let [fs (red/fact-status state p)]
                               :when (= :present (:status fs))]
                           {"path" (s p) "value" (s (:value fs)) "by" (s (:by fs))
                            "valid" (:satisfied? (guard/eval-guard [:fact p] ctx))}))
            "base" (mapv s (sort-by s base))
            "events" []}
     :expected (stage/reached-set proc state now roles base)
     :lint-clean (lint-clean? proc)}))

(defn- conflict-free?
  "No prefix of the ordered history holds a disputed or conflicted fact."
  [events]
  (let [ordered (vec (red/ordered events))]
    (every? (fn [n]
              (let [st (red/ticket-state (subvec ordered 0 n))]
                (every? #(nil? (red/conflicting-claims st %)) (keys (:facts st)))))
            (range 1 (inc (count ordered))))))

(defn derive-eligible? [proc events]
  (and (every? fully-modelled? (mapcat :guards (:process/stages proc)))
       (not-any? #(= :fact/dispute (:event/type %)) events)
       (conflict-free? events)))

(defn encode-event [proc e]
  (let [base {"at" (inst->nanos (:event/at e)) "id" (:event/id e)}
        {:fact/keys [path value]} (:event/body e)]
    (case (:event/type e)
      :fact/assert (assoc base "kind" "assert" "path" (s path) "value" (s value)
                          "by" (s (:event/actor e)) "valid" (schema-valid? proc path value))
      :fact/retract (assoc base "kind" "retract" "path" (s path))
      (assoc base "kind" "other"))))

(defn derive-case
  "A derive-mode case over `events` exactly as given: their order and any
  duplicates are the model's to undo."
  [id proc events now roles]
  {:case {"id" id
          "mode" "derive"
          "process" (encode-process proc unsupported)
          "roles" (encode-roles roles)
          "facts" []
          "base" []
          "events" (mapv #(encode-event proc %) events)}
   :expected (stage/effective-reached proc events now roles)
   :lint-clean (lint-clean? proc)})

(defn cases-for
  "The cases one history yields: always fixpoint mode at its end with the
  fold's carry (and with `extra-base` added when given), and derive mode
  when eligible, over `arrival`, a reordering of the same events."
  ([id proc events now roles] (cases-for id proc events now roles events nil))
  ([id proc events now roles arrival extra-base]
   (let [{:keys [state sticky-ever]} (stage/evolve proc events roles now)]
     (cond-> [(fixpoint-case (str id "/fixpoint") proc state now roles sticky-ever)]
       (seq extra-base)
       (conj (fixpoint-case (str id "/fixpoint+base") proc state now roles
                            (into sticky-ever extra-base)))
       (derive-eligible? proc events)
       (conj (derive-case (str id "/derive") proc arrival now roles))))))

;; ------------------------------------------------------------ sources

(defn corpus-cases []
  (for [^File dir (sort-by #(.getName ^File %) (.listFiles (io/file "corpus")))
        :when (.isDirectory dir)
        :let [expected (edn/read-string {:readers canonical/edn-readers}
                                        (slurp (io/file dir "expected.edn")))
              st (fstore/file-store (.getPath dir))
              evs (store/events st (first (store/ticket-ids st)))
              proc (edn/read-string
                    (slurp (io/file dir "processes" (str (name (:process expected)) ".edn"))))
              roles (:process/roles proc {})]
        c (cases-for (str "corpus/" (.getName dir)) proc evs (:now expected) roles)]
    c))

(defn process-test-cases []
  (for [^File f (sort-by #(.getName ^File %) (.listFiles (io/file "processes")))
        :when (str/ends-with? (.getName f) ".tests.edn")
        :let [spec (edn/read-string {:readers canonical/edn-readers} (slurp f))
              proc (edn/read-string (slurp (io/file "processes" (:test/process spec))))
              roles (process/resolve-roles (:process/roles proc {}) (:test/roles spec))]
        [i {:case/keys [steps]}] (map-indexed vector (:test/cases spec))
        :let [create (event/create-ticket {:ticket #uuid "018f2f6e-7c1a-7000-8000-0000000000ab"
                                           :actor "test" :at design/test-epoch
                                           :title "oracle" :process (:process/id proc)})
              {:keys [events now]} (reduce design/apply-step
                                           {:events [create] :now design/test-epoch
                                            :actor "test"}
                                           steps)]
        c (cases-for (str "tests/" (.getName f) "/" i) proc events now roles)]
    c))

(defn definition-cases
  "Every definition under processes/, over a history of its creation alone."
  []
  (for [^File f (sort-by #(.getName ^File %) (.listFiles (io/file "processes")))
        :let [n (.getName f)]
        :when (and (str/ends-with? n ".edn") (not (str/ends-with? n ".tests.edn")))
        :let [proc (edn/read-string (slurp f))
              create (event/create-ticket {:ticket #uuid "018f2f6e-7c1a-7000-8000-0000000000ae"
                                           :actor "test" :at design/test-epoch
                                           :title "oracle" :process (:process/id proc)})]
        c (cases-for (str "definitions/" n) proc [create] design/test-epoch
                     (:process/roles proc {}))]
    c))

;; generated processes and histories

(def ^:private tid #uuid "018f2f6e-7c1a-7000-8000-0000000000ac")
(def ^:private ^Instant start (Instant/parse "2026-11-27T10:00:00Z"))
(def ^:private gen-now (Instant/parse "2026-11-27T12:00:00Z"))
(def ^:private paths [[:f0] [:f1] [:f2]])
(def ^:private roles-def {:r {:members ["seb"]} :q {:members ["claude" "rando"]}})

(defn- gen-guard [stage-ids modelled-only?]
  (let [leaves (cond-> [(gen/fmap (fn [p] [:fact p]) (gen/elements paths))
                        (gen/fmap (fn [[p v]] [:fact= p v])
                                  (gen/tuple (gen/elements paths) (gen/elements [:a :b "x"])))
                        (gen/fmap (fn [[r p]] [:signed-by r p])
                                  (gen/tuple (gen/elements [:r :q :nobody]) (gen/elements paths)))
                        (gen/fmap (fn [[p q]] [:different-person p q])
                                  (gen/tuple (gen/elements paths) (gen/elements paths)))
                        (gen/fmap (fn [sid] [:stage-reached sid]) (gen/elements stage-ids))
                        (gen/fmap (fn [sid] [:not [:stage-reached sid]]) (gen/elements stage-ids))]
                 (not modelled-only?)
                 (conj (gen/return [:artifact "doc"])
                       (gen/return [:elapsed-since :ticket/create "PT30M"])))
        leaf (gen/one-of leaves)]
    (gen/frequency
     [[5 leaf]
      [2 (gen/recursive-gen
          (fn [inner]
            (gen/one-of [(gen/fmap #(into [:or] %) (gen/vector inner 0 3))
                         (gen/fmap #(into [:and] %) (gen/vector inner 0 3))
                         (gen/fmap (fn [g] [:not g]) inner)]))
          leaf)]])))

(defn- gen-process
  "A process of 1 to 7 stages. `cyclic?` lets :after name any stage, and
  an unknown one, so cycles and dangling waits occur."
  [{:keys [cyclic? modelled-only?]}]
  (gen/let [n (gen/choose 1 7)
            stages (apply gen/tuple
                          (for [i (range n)
                                :let [ids (mapv #(keyword (str "s" %)) (range n))
                                      pool (if cyclic? (conj ids :ghost) (subvec ids 0 i))]]
                            (gen/let [after (if (seq pool)
                                              (gen/fmap (comp vec sort set)
                                                        (gen/vector (gen/elements pool) 0 2))
                                              (gen/return []))
                                      guards (gen/vector (gen-guard ids modelled-only?) 0 3)
                                      sticky? (gen/frequency [[3 (gen/return false)]
                                                              [1 (gen/return true)]])]
                              (cond-> {:stage/id (nth ids i) :guards guards}
                                (seq after) (assoc :after after)
                                sticky? (assoc :stage/sticky? true)))))
            order (gen/shuffle (range n))]
    {:process/id :generated :process/version 1 :process/guard-vocab 2
     :process/purpose "a generated process"
     :process/roles roles-def
     :process/facts (zipmap paths (repeat [:enum :a :b]))
     :process/stages (mapv #(nth stages %) order)}))

(defn- gen-history [disputes?]
  (gen/let [ops (gen/vector
                 (gen/tuple (gen/frequency (cond-> [[6 (gen/return :assert)]
                                                    [2 (gen/return :retract)]
                                                    [1 (gen/return :attach)]]
                                             disputes? (conj [2 (gen/return :dispute)])))
                            (gen/elements paths)
                            (gen/elements [:a :b "x"])
                            (gen/elements ["seb" "claude" "rando"])
                            (gen/choose 0 200))
                 0 10)]
    (apply event/chain
           (fn [_] (event/create-ticket {:ticket tid :actor "seb" :at start
                                         :title "generated" :process :generated}))
           (for [[op path value actor secs] ops
                 :let [at (.plusSeconds start (* 30 secs))
                       spec {:ticket tid :actor actor :at at :path path}]]
             (fn [parents]
               (let [spec (assoc spec :parents parents)]
                 (case op
                   :assert (event/assert-fact (assoc spec :value value))
                   :retract (event/retract-fact spec)
                   :dispute (event/dispute-fact (assoc spec :reason "no"))
                   :attach (event/attach-artifact {:ticket tid :actor actor :at at
                                                   :parents parents :path "doc"
                                                   :hash (canonical/address-of-text "doc")}))))))))

(def ^:private gen-case
  (gen/let [flavour (gen/elements [:clean :clean :modelled :any :cyclic])
            proc (gen-process {:cyclic? (= flavour :cyclic)
                               :modelled-only? (#{:clean :modelled} flavour)})
            events (gen-history (#{:any :cyclic} flavour))
            arrival (gen/fmap #(concat % (take 2 %)) (gen/shuffle events))
            extra (gen/fmap set (gen/vector (gen/elements [:s0 :s1 :s2 :s3 :ghost]) 0 2))]
    {:flavour flavour :proc proc :events events :arrival arrival :extra extra}))

(defn generated-cases [n seed]
  (let [r (java.util.Random. seed)]
    (for [i (range n)
          :let [{:keys [flavour proc events arrival extra]}
                (gen/generate gen-case 30 (.nextLong r))
                ;; :clean keeps only definitions tik lint accepts
                flavour (if (and (= flavour :clean) (not (lint-clean? proc))) :modelled flavour)
                roles (process/resolve-roles roles-def {})]
          c (cases-for (str "gen/" (name flavour) "/" i) proc events gen-now roles arrival extra)]
      c)))

;; ------------------------------------------------------------ running

(defn- write-jsonl [^File f cases]
  (with-open [w (io/writer f)]
    (doseq [c cases]
      (.write w (json/generate-string (:case c)))
      (.write w "\n"))))

(defn run-lean
  "Runs the model's executable over `file` and returns its stdout lines,
  failing on a non-zero exit."
  [lean & args]
  (let [{:keys [exit out err]} (apply sh/sh lean args)]
    (when-not (zero? exit)
      (throw (ex-info (str "tik-lean exited " exit ": " err) {:exit exit})))
    (remove str/blank? (str/split-lines out))))

(defn compare-results
  "Pairs each case with the model's answer; returns the disagreements."
  [cases lines]
  (when (not= (count cases) (count lines))
    (throw (ex-info "tik-lean answered a different number of cases"
                    {:cases (count cases) :answers (count lines)})))
  (keep (fn [[c line]]
          (let [answer (json/parse-string line)
                want (set (map s (:expected c)))
                got (set (get answer "reached"))]
            (when-not (and (= (get-in c [:case "id"]) (get answer "id")) (= want got))
              {:id (get-in c [:case "id"]) :kernel (sort want) :model (sort got)
               :missing (sort (set/difference want got))
               :extra (sort (set/difference got want))})))
        (map vector cases lines)))

(defn echo-mismatches
  "The cases whose parse by the model does not print back as written."
  [cases lines]
  (keep (fn [[c line]]
          (let [back (json/parse-string line)]
            (when-not (= (json/parse-string (json/generate-string (:case c))) back)
              {:id (get-in c [:case "id"]) :sent (:case c) :echoed back})))
        (map vector cases lines)))

(defn- parse-args [args]
  (loop [m {:generated 3000 :seed 20261127} [a b & more :as args] args]
    (case a
      nil m
      "--lean" (recur (assoc m :lean b) more)
      "--generated" (recur (assoc m :generated (parse-long b)) more)
      "--seed" (recur (assoc m :seed (parse-long b)) more)
      "--mutant" (recur (assoc m :mutant b) more)
      "--out" (recur (assoc m :out b) more)
      "--expect-disagreement" (recur (assoc m :expect-disagreement true) (rest args))
      "--echo" (recur (assoc m :echo true) (rest args))
      (throw (ex-info (str "unknown argument " a) {:arg a})))))

(defn all-cases [{:keys [generated seed]}]
  (vec (concat (corpus-cases) (definition-cases) (process-test-cases)
               (generated-cases generated seed))))

(defn- summary [cases answers]
  (let [modes (frequencies (map #(get-in % [:case "mode"]) cases))
        sources (frequencies (map #(first (str/split (get-in % [:case "id"]) #"/")) cases))
        strat (map #(true? (get (json/parse-string %) "stratified")) answers)
        ;; how the model's stratification certificate compares with tik
        ;; lint's verdict, per case: [lint-clean? stratified?]
        verdicts (frequencies (map (fn [c st] [(:lint-clean c) st]) cases strat))]
    {:cases (count cases) :modes modes :sources sources
     :lint-clean+stratified (get verdicts [true true] 0)
     :lint-clean-only (get verdicts [true false] 0)
     :stratified-only (get verdicts [false true] 0)
     :neither (get verdicts [false false] 0)}))

(defn -main [& args]
  (let [{:keys [lean mutant out expect-disagreement echo] :as opts} (parse-args args)
        _ (when-not lean (throw (ex-info "--lean <tik-lean> is required" {})))
        dir (io/file (or out (str (System/getProperty "java.io.tmpdir") "/tik-lean-oracle")))
        _ (.mkdirs dir)
        cases (all-cases opts)
        f (io/file dir "cases.jsonl")]
    (write-jsonl f cases)
    (if echo
      (let [bad (echo-mismatches cases (run-lean lean "echo" (str f)))]
        (println (str "lean oracle echo: " (count cases) " cases, " (count bad) " mismatches"))
        (doseq [b (take 3 bad)] (prn b))
        (shutdown-agents)
        (System/exit (if (empty? bad) 0 1)))
      (let [answers (vec (if mutant
                           (run-lean lean "run" "--mutant" mutant (str f))
                           (run-lean lean "run" (str f))))
            bad (compare-results cases answers)]
        (spit (io/file dir "disagreements.edn") (with-out-str (doseq [b bad] (prn b))))
        (println (str "lean oracle" (when mutant (str " (mutant " mutant ")")) ": "
                      (pr-str (summary cases answers)) ", "
                      (count bad) " disagreements"))
        (doseq [b (take 5 bad)] (prn b))
        (shutdown-agents)
        (System/exit
         (cond
           expect-disagreement (if (seq bad) 0 1)
           :else (if (empty? bad) 0 1)))))))
