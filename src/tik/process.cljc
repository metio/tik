;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.process
  "Process definitions: schema, hashing, linting.

  Definitions are pure EDN, reviewed in merge requests, shipped GitOps-
  style. Tickets pin the definition's CONTENT HASH (ADR 0002/0006), so
  `verify` never trusts file naming — process-hash is the identity, the
  version number is a human label.

  The linter enforces what the kernel cannot:
  - the closed guard basis (error on unknown operators; :fact= sugar is
    accepted and expands before evaluation)
  - facts over flags (warning, per-process opt-out via :lint config)
  - graph sanity (unknown refs, unreachable stages)
  - stratified negation (ADR 0005, error): [:not [:stage-reached ...]]
    may only reference strictly earlier strata."
  (:require [clojure.walk :as walk]
            [malli.core :as m]
            [tik.canonical :as canonical]))

(def Guard
  [:and vector? [:cat :keyword [:* :any]]])

(def Stage
  [:map
   [:stage/id :keyword]
   [:after {:optional true} [:vector :keyword]]
   [:guards {:optional true} [:vector Guard]]
   [:stage/sticky? {:optional true} :boolean]
   [:hint {:optional true} :string]
   [:effort {:optional true} :string]        ; optional ISO-8601, never inferred
   [:sla {:optional true} [:map-of :keyword :any]]])

(def ProcessDef
  ;; not `Process`: that name is java.lang.Process on the JVM
  [:map
   [:process/id :keyword]
   [:process/version pos-int?]
   [:process/guard-vocab {:optional true} pos-int?]
   [:lint {:optional true} [:map-of :keyword :keyword]]
   [:process/roles {:optional true}
    [:map-of :keyword [:map [:members [:vector :string]]]]]
   [:process/facts {:optional true} [:map-of [:vector :keyword] :any]]
   [:process/stages [:vector Stage]]])

(def valid? (m/validator ProcessDef))
(def explain-process (m/explainer ProcessDef))

(defn process-hash
  "Content address of the definition — the identity tickets pin."
  [process]
  (canonical/content-address process))

(def guard-operators
  "The nine-operator basis plus :fact= authoring sugar (expands to :malli
  before evaluation — tik.guard/expand)."
  #{:fact :fact= :artifact :signed-by :stage-reached :elapsed-since
    :and :or :not :malli})

(defn- all-guards [stage]
  (vec (:guards stage [])))

(defn- collect [pred guard]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when-some [hit (pred x)] (vswap! acc conj hit)) x)
                   guard)
    @acc))

(defn- unknown-operators
  "Operators outside the closed basis (+ sugar), found by descending only
  into combinator argument positions — fact paths are argument vectors
  with keyword heads and must not be mistaken for operators."
  [guard]
  (if-not (and (vector? guard) (keyword? (first guard)))
    [guard]
    (let [op (first guard)]
      (cond
        (not (guard-operators op)) [op]
        (#{:and :or} op) (into [] (mapcat unknown-operators) (rest guard))
        (= :not op) (unknown-operators (second guard))
        :else []))))

(defn- fact-refs [guard]
  (collect #(when (and (vector? %) (#{:fact :fact= :signed-by} (first %)))
              (case (first %) :signed-by (nth % 2) (second %)))
           guard))

(defn- stage-refs [guard]
  (collect #(when (and (vector? %) (= :stage-reached (first %)))
              (second %))
           guard))

(defn- negated-stage-refs
  "Targets of the direct [:not [:stage-reached X]] spelling. Arbitrary
  negation-parity nesting is a documented lint TODO (ADR 0005)."
  [guard]
  (collect #(when (and (vector? %) (= :not (first %))
                       (vector? (second %))
                       (= :stage-reached (first (second %))))
              (second (second %)))
           guard))

(defn- strata
  "stage-id -> longest :after path depth. Stages caught in cycles never get
  a depth; the reachability check reports them."
  [process]
  (let [afters (into {} (map (juxt :stage/id #(vec (:after % []))))
                     (:process/stages process))]
    (loop [depth {} remaining (set (keys afters))]
      (let [ready (filter (fn [id] (every? depth (afters id))) remaining)]
        (if (empty? ready)
          depth
          (recur (into depth
                       (map (fn [id]
                              [id (if (empty? (afters id))
                                    0
                                    (inc (apply max (map depth (afters id)))))]))
                       ready)
                 (reduce disj remaining ready)))))))

(defn- reachable-ids [process]
  (loop [reached (into #{}
                       (comp (filter #(empty? (:after % []))) (map :stage/id))
                       (:process/stages process))]
    (let [r' (into reached
                   (for [s (:process/stages process)
                         :when (and (not (reached (:stage/id s)))
                                    (every? reached (:after s [])))]
                     (:stage/id s)))]
      (if (= r' reached) reached (recur r')))))

(defn lint
  "Vector of {:level :error|:warning :msg}. Empty means clean."
  [process]
  (if-not (valid? process)
    [{:level :error
      :msg (str "invalid process definition: "
                (pr-str (explain-process process)))}]
    (let [stages (:process/stages process)
          declared (set (keys (:process/facts process {})))
          stage-ids (into #{} (map :stage/id) stages)
          reachable (reachable-ids process)
          depth (strata process)]
      (vec
       (concat
        ;; facts-over-flags: allowed but discouraged; opt out per process
        (when-not (= :off (get-in process [:lint :boolean-facts]))
          (for [[path schema] (:process/facts process)
                :when (or (= :boolean schema) (= boolean? schema))]
            {:level :warning
             :msg (str "fact " path " is a bare boolean — a checkbox"
                       " with extra steps. Prefer a fact useful"
                       " downstream, or set {:lint {:boolean-facts"
                       " :off}} to accept this explicitly.")}))
        ;; closed guard basis
        (for [s stages, g (all-guards s), op (unknown-operators g)]
          {:level :error
           :msg (str "stage " (:stage/id s) " uses unknown guard"
                     " operator " (pr-str op) " — the vocabulary"
                     " is closed (basis + :fact= sugar).")})
        ;; undeclared facts
        (for [s stages, g (all-guards s)
              path (fact-refs g) :when (not (declared path))]
          {:level :warning
           :msg (str "stage " (:stage/id s) " references fact " path
                     " not declared in :process/facts (no schema,"
                     " no generated form, no generator).")})
        ;; unknown stage refs
        (for [s stages, g (all-guards s)
              target (stage-refs g) :when (not (stage-ids target))]
          {:level :error
           :msg (str "stage " (:stage/id s)
                     " references unknown stage " target)})
        ;; ADR 0005: stratified negation
        (for [s stages, g (all-guards s)
              target (negated-stage-refs g)
              :let [ds (depth (:stage/id s)) dt (depth target)]
              :when (and ds dt (>= dt ds))]
          {:level :error
           :msg (str "stage " (:stage/id s) " negates stage " target
                     " in the same or a later stratum — stratified"
                     " negation (ADR 0005) requires strictly"
                     " earlier targets, or the result depends on"
                     " fixpoint iteration order.")})
        (for [s stages, a (:after s []) :when (not (stage-ids a))]
          {:level :error
           :msg (str "stage " (:stage/id s) " :after unknown stage " a)})
        (for [id stage-ids :when (not (reachable id))]
          {:level :error
           :msg (str "stage " id
                     " is unreachable (graph shape alone)")}))))))
