;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.lint
  "The process-definition linter — what the kernel cannot enforce at
  derivation time, checked once when a definition is authored/adopted:
  the closed guard basis (error on unknown operators), facts over flags
  (warning, per-process opt-out), graph sanity (unknown refs, unreachable
  stages), stratified negation (ADR 0005), and the time/signature operator
  argument checks. Pure over a definition; a leaf over tik.process (its
  schema and guard vocabulary), never the reverse."
  (:require [clojure.walk :as walk]
            [tik.process :refer [explain-process guard-operators guard-operators-v1
                                 valid?]]))

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
                ;; a :signed-by references the fact it ranges over; the
                ;; pathless form is rejected by lint (a signature over
                ;; nothing can never be satisfied), so here we read the
                ;; over-path when present and contribute no ref otherwise
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

;; the clock anchors tik.guard/eval-elapsed knows how to resolve; any
;; other reference throws on every derivation (see the lint clause below)
(def ^:private elapsed-since-refs #{:ticket/create})

(defn- elapsed-refs [guard]
  (collect #(when (and (vector? %) (= :elapsed-since (first %)))
              (nth % 1 nil))
           guard))

(defn- signed-by-forms
  "The :signed-by guard vectors in a tree, verbatim — so lint can check
  each carries an over-path (a pathless form can never be satisfied)."
  [guard]
  (collect #(when (and (vector? %) (= :signed-by (first %))) %) guard))

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
        ;; :elapsed-since's reference must be a clock anchor the runtime
        ;; can resolve — an unknown one (a typo like :ticket/created)
        ;; throws on every derivation, poisoning the ticket exactly as a
        ;; bad duration does; validate the closed set at lint time too
        (for [s stages, g (all-guards s)
              ref (elapsed-refs g) :when (not (contains? elapsed-since-refs ref))]
          {:level :error
           :msg (str "stage " (:stage/id s) " :elapsed-since reference "
                     (pr-str ref) " is unknown (supported: :ticket/create)")})
        ;; a :signed-by over no fact ([:signed-by :role], or bare
        ;; [:signed-by]) can never be satisfied — fact-status of a nil
        ;; path is :absent — so a stage guarded by it is permanently
        ;; blocked, and negating it ([:not [:signed-by :role]]) is
        ;; vacuously true. Reject it rather than strand the ticket; a
        ;; signature must range over a specific fact.
        (for [s stages, g (all-guards s)
              sb (signed-by-forms g) :when (< (count sb) 3)]
          {:level :error
           :msg (str "stage " (:stage/id s) " has " (pr-str sb)
                     " — a :signed-by over no fact can never be satisfied;"
                     " give it an over-path, e.g. [:signed-by "
                     (pr-str (nth sb 1 :role)) " [:fact]].")})
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
                    ;; [:and ... [:fact= p v1] ... [:fact= p v2] ...] — the
                    ;; SAME path demanded to equal two different values can
                    ;; never hold. Group each [path value] pair by PATH (not
                    ;; value) and flag a path with >1 distinct demanded value.
                    (and (vector? g) (= :and (first g))
                         (let [eqs (filter #(and (vector? %)
                                                 (= :fact= (first %)))
                                           (rest g))]
                           (some (fn [[_path vs]]
                                   (< 1 (count (distinct (map second vs)))))
                                 (group-by first
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

