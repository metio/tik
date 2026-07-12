;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.process
  "Process definitions: schema, hashing, linting.

  Definitions are pure EDN, reviewed in merge requests, shipped GitOps-
  style. Tickets pin the definition's CONTENT HASH (ADR 0002/0006), so
  `verify` never trusts file naming — process-hash is the identity, the
  version number is a human label.

  The linter enforces what the kernel cannot:
  - the closed guard basis (error on unknown operators)
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

#_{:splint/disable [naming/lisp-case]} ; schema names are PascalCase (Guard,
                                        ; Stage); `Process` itself would clash
                                        ; with java.lang.Process
(def ProcessDef
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

(def guard-operators-v1
  #{:fact :fact= :artifact :signed-by :stage-reached :elapsed-since
    :and :or :not :malli})

(def guard-operators
  "Guard vocabulary v2: twelve operators, additive over v1 (old
  definitions evaluate unchanged forever — the runtime is total over
  both; the LINT enforces that a definition uses only what its declared
  :process/guard-vocab admits). :attested-within and :different-person
  arrived in v2; :fact= was briefly v6 sugar and was restored by
  dogfood evidence (see the guard namespace docstring)."
  (into guard-operators-v1 #{:attested-within :different-person}))

(defn signing-roles
  "Roles whose signature a guard tree demands, via :signed-by."
  [guard]
  (if-not (vector? guard)
    []
    (case (first guard)
      :signed-by [(second guard)]
      (:and :or) (into [] (mapcat signing-roles) (rest guard))
      :not (signing-roles (second guard))
      [])))

(defn roles-gating
  "role -> {:members [...] :stages [stage-ids]} for one definition —
  who gates what: every role with the stages waiting on its signature.
  Roles declared but gating nothing still appear (they may satisfy
  :role/unsatisfied facts without a :signed-by spelling)."
  [{:process/keys [roles stages]}]
  (let [gated (reduce (fn [acc {:stage/keys [id] :keys [guards]}]
                        (reduce (fn [m role] (update m role (fnil conj []) id))
                                acc
                                (distinct (mapcat signing-roles guards))))
                      {}
                      stages)]
    (into {}
          (for [role (distinct (concat (keys roles) (keys gated)))]
            [role {:members (get-in roles [role :members] [])
                   :stages (get gated role [])}]))))

(defn- all-guards [stage]
  (vec (:guards stage [])))

(defn- collect [pred guard]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when-some [hit (pred x)] (vswap! acc conj hit)) x)
                   guard)
    @acc))

(defn- unknown-operators
  "Operators outside the closed vocabulary the process DECLARES
  (:process/guard-vocab, conservative default 1), found by descending
  only into combinator argument positions — fact paths are argument
  vectors with keyword heads and must not be mistaken for operators."
  [allowed guard]
  (if-not (and (vector? guard) (keyword? (first guard)))
    [guard]
    (let [op (first guard)]
      (cond
        (not (allowed op)) [op]
        (#{:and :or} op) (into [] (mapcat #(unknown-operators allowed %))
                               (rest guard))
        (= :not op) (unknown-operators allowed (second guard))
        :else []))))

(defn- fact-refs [guard]
  (collect #(when (vector? %)
              (case (first %)
                (:fact :fact=) [(second %)]
                ;; :signed-by's over-path is optional — [:signed-by :role]
                ;; (sign anything) is as valid as [:signed-by :role [:fact]]
                ;; (sign over a specific fact); no path means no fact ref
                :signed-by (if-let [p (nth % 2 nil)] [p] [])
                :different-person [(second %) (nth % 2 nil)]
                nil))
           guard))

(defn- duration-args
  "[op duration-string] pairs for the time operators in a guard tree."
  [guard]
  (collect #(when (vector? %)
              (case (first %)
                (:elapsed-since :attested-within)
                [(first %) (nth % 2 nil)]
                nil))
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
        ;; runbooks: every stage carries authored how-to knowledge
        ;; (:hint), or the process opts out. explain answers WHAT is
        ;; missing; the runbook answers HOW one produces it — and a
        ;; stage whose runbook is unwritable is either under-specified
        ;; or judgment work whose runbook should say exactly that.
        (when-not (= :off (get-in process [:lint :runbooks]))
          (for [s stages :when (not (:hint s))]
            {:level :warning
             :msg (str "stage " (:stage/id s) " has no :hint (runbook)."
                       " Link kb/runbooks/, or a judgment-stage runbook"
                       " saying whose judgment and how to record it, or"
                       " set {:lint {:runbooks :off}}.")}))
        ;; closed guard vocabulary, per the DECLARED version
        (let [vocab (case (:process/guard-vocab process 1)
                      1 guard-operators-v1
                      guard-operators)]
          (for [s stages, g (all-guards s), op (unknown-operators vocab g)]
            {:level :error
             :msg (str "stage " (:stage/id s) " uses guard operator "
                       (pr-str op) " not admitted by guard-vocab "
                       (:process/guard-vocab process 1)
                       " (v2 adds :attested-within and"
                       " :different-person — declare"
                       " :process/guard-vocab 2 to use them).")}))
        ;; undeclared facts
        (for [s stages, g (all-guards s)
              path (apply concat (fact-refs g)) :when (not (declared path))]
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
        ;; time operators must carry parseable ISO-8601 durations — an
        ;; unparseable one would poison every derivation of every
        ;; ticket pinned to this definition, forever
        (for [s stages, g (all-guards s)
              [op dur] (duration-args g)
              :when (or (not (string? dur))
                        #?(:clj (try (java.time.Duration/parse dur) false
                                     (catch Exception _ true))
                           :cljs false))]
          {:level :error
           :msg (str "stage " (:stage/id s) " " op " duration "
                     (pr-str dur) " is not ISO-8601 (PT48H, P7D, PT30M)")})
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
                     " is unreachable (graph shape alone)")})
        ;; complexity (PLAN §18: how BPMN died) — warnings, opt-out via
        ;; {:lint {:complexity :off}}
        (when-not (= :off (get-in process [:lint :complexity]))
          (letfn [(depth-of [g]
                    (if (and (vector? g) (#{:and :or :not} (first g)))
                      (inc (apply max 0 (map depth-of (rest g))))
                      0))
                  (contradiction? [g]
                    ;; [:and ... [:fact= p v1] ... [:fact= p v2] ...]
                    (and (vector? g) (= :and (first g))
                         (let [eqs (filter #(and (vector? %)
                                                 (= :fact= (first %)))
                                           (rest g))]
                           (some (fn [[_p vs]] (< 1 (count (distinct vs))))
                                 (group-by second
                                           (map (juxt second #(nth % 2))
                                                eqs))))))]
            (concat
             (for [s stages, g (all-guards s)
                   :when (< 4 (depth-of g))]
               {:level :warning
                :msg (str "stage " (:stage/id s) " nests combinators "
                          (depth-of g) " deep — nobody will read this;"
                          " name the pieces as separate stages or facts.")})
             (for [s stages
                   :when (< 6 (count (:guards s [])))]
               {:level :warning
                :msg (str "stage " (:stage/id s) " has "
                          (count (:guards s [])) " guards — checkbox"
                          " creep (PLAN §18); consider intermediate"
                          " stages.")})
             (for [s stages, g (all-guards s)
                   :when (contradiction? g)]
               {:level :error
                :msg (str "stage " (:stage/id s) " requires the same"
                          " fact to equal two different values — this"
                          " guard can NEVER be satisfied.")})))))))))
