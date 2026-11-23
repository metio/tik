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
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [tik.process :refer [eval-capable-schemas explain-process
                                 guard-operators guard-operators-v1
                                 schema-compiles? valid?]]))

(defn- all-guards [stage]
  (vec (:guards stage [])))

(defn- collect [pred guard]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when-some [hit (pred x)] (vswap! acc conj hit)) x)
                   guard)
    @acc))

(def ^:private guard-arity
  "How many arguments each operator takes after its head. :and and :or
  take any number. A guard short of one is not a weaker guard: derivation
  reads the missing argument as nil, so [:fact= path] demands that the
  fact equal nil."
  {:fact 1 :fact= 2 :artifact 1 :signed-by 2 :stage-reached 1
   :elapsed-since 2 :malli 1 :attested-within 2 :different-person 2 :not 1})

(defn- misshapen-guards
  "Every guard inside `guard` whose operator takes a fixed number of
  arguments and is given another, found by descending only into
  combinator argument positions."
  [guard]
  (when (and (vector? guard) (keyword? (first guard)))
    (let [op (first guard)
          want (guard-arity op)]
      (concat (when (and want (not= want (dec (count guard)))) [guard])
              (cond
                (#{:and :or} op) (mapcat misshapen-guards (rest guard))
                (= :not op) (misshapen-guards (second guard)))))))

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

(defn- artifact-prefixes [guard]
  (collect #(when (and (vector? %) (= :artifact (first %))) (nth % 1 nil))
           guard))

(defn- artifact-forms [guard]
  (collect #(when (and (vector? %) (= :artifact (first %))) %) guard))

(defn- stage-refs [guard]
  (collect #(when (and (vector? %) (= :stage-reached (first %)))
              (second %))
           guard))

(defn- malli-schemas
  "The schema argument of every `:malli` guard in a tree."
  [guard]
  (collect #(when (and (vector? %) (= :malli (first %))) [(nth % 1 nil)])
           guard))

(defn- stage-ref-polarities
  "Every [:stage-reached X] in a guard as [X negated?], negated? true when
  an odd number of :not wrap it. :and and :or keep the polarity, so
  [:not [:or [:stage-reached x] ...]] negates x and [:not [:not
  [:stage-reached x]]] reads x positively."
  [guard]
  (letfn [(walk [g negated?]
            (when (vector? g)
              (case (first g)
                :stage-reached [[(second g) negated?]]
                :not (walk (second g) (not negated?))
                (:and :or) (mapcat #(walk % negated?) (rest g))
                nil)))]
    (vec (walk guard false))))

(defn- negated-stage-refs [guard]
  (into [] (comp (filter second) (map first)) (stage-ref-polarities guard)))

(defn- positive-stage-refs [guard]
  (into [] (comp (remove second) (map first)) (stage-ref-polarities guard)))

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

(defn- entry-bounds
  "stage-id -> the latest sweep the stage can enter in: one more than the
  latest of the stages it waits for, through :after or through a positive
  [:stage-reached X] in its guards, since a guard reads the snapshot the
  sweep started from. A negated reference only ever blocks, so it does not
  delay entry. A stage on a cycle of such waits has no bound."
  [process]
  (let [waits (into {} (map (fn [s]
                              [(:stage/id s)
                               (into (set (:after s []))
                                     (mapcat positive-stage-refs)
                                     (all-guards s))]))
                    (:process/stages process))
        known (set (keys waits))
        waits (update-vals waits #(filter known %))]
    (loop [bound {} remaining known]
      (let [ready (filter (fn [id] (every? bound (waits id))) remaining)]
        (if (empty? ready)
          bound
          (recur (into bound
                       (map (fn [id]
                              [id (if (empty? (waits id))
                                    0
                                    (inc (apply max (map bound (waits id)))))]))
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
          depth (strata process)
          latest (entry-bounds process)]
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
        (for [s stages, g (all-guards s), bad (misshapen-guards g)]
          {:level :error
           :msg (str "stage " (:stage/id s) " uses " (pr-str bad) ", but "
                     (first bad) " takes " (guard-arity (first bad))
                     " argument(s); a missing one derives as nil.")})
        ;; schemas are DATA. malli's :fn and :multi compile a child
        ;; through m/eval, which on a runtime carrying sci runs a string
        ;; or s-expression straight out of the definition — and a
        ;; definition is read from evidence bundles a stranger produced.
        ;; Derivation refuses to compile one (tik.process/schema-registry);
        ;; saying so here is what turns that refusal into a diagnostic.
        (for [[where schema] (concat (for [[path schema] (:process/facts process)]
                                       [(str "fact " path) schema])
                                     (for [s stages, g (all-guards s)
                                           schema (apply concat (malli-schemas g))]
                                       [(str "stage " (:stage/id s)
                                             " :malli guard") schema]))
              :when (not (schema-compiles? schema))]
          {:level :error
           :msg (str where " carries a schema that is not data: "
                     (pr-str schema) ". Schemas naming "
                     (str/join " or " (sort (map pr-str eval-capable-schemas)))
                     " can call a function, so derivation never evaluates"
                     " one — express the constraint as data.")})
        ;; undeclared facts
        (for [s stages, g (all-guards s)
              path (apply concat (fact-refs g)) :when (not (declared path))]
          {:level :warning
           :msg (str "stage " (:stage/id s) " references fact " path
                     " not declared in :process/facts (no schema,"
                     " no generated form, no generator).")})
        ;; eval-artifact compares artifact names with str/starts-with?,
        ;; so any other prefix throws on every derivation of a ticket
        ;; holding an artifact
        (for [s stages, g (all-guards s)
              form (artifact-forms g)
              :when (not (and (= 2 (count form)) (string? (second form))))]
          {:level :error
           :msg (str "stage " (:stage/id s) " guard " (pr-str form)
                     " needs a string prefix, such as \"receipts/\" —"
                     " any other value fails every derivation once an"
                     " artifact is attached")})
        ;; :artifact matches a raw string prefix, so one that does not
        ;; end at a path boundary also accepts sibling names — a
        ;; "review" prefix is satisfied by "review-notes-fake.txt".
        ;; The operator cannot be tightened without changing what
        ;; already-pinned definitions derive, so the prefix carries the
        ;; boundary and lint is where the footgun gets named.
        (for [s stages, g (all-guards s)
              p (artifact-prefixes g)
              :when (and (string? p) (seq p) (not (str/ends-with? p "/")))]
          {:level :warning
           :msg (str "stage " (:stage/id s) " demands an artifact under "
                     (pr-str p) " — that is a raw string prefix, so it"
                     " also accepts " (pr-str (str p "-anything"))
                     ". End it at a path boundary: " (pr-str (str p "/")) ".")})
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
        ;; ADR 0005 and ADR 0033: stratified negation. A stage is first
        ;; judged in the sweep of its :after depth; every stage it negates
        ;; must have entered for good by then, so the target's latest
        ;; possible entry must come strictly earlier.
        (for [s stages, g (all-guards s)
              target (distinct (negated-stage-refs g))
              :let [ds (depth (:stage/id s))]
              :when (and ds (stage-ids target))
              :let [lt (latest target)]
              :when (or (nil? lt) (>= lt ds))]
          {:level :error
           :msg (str "stage " (:stage/id s) " negates stage " target
                     (cond
                       (nil? lt)
                       ", which waits on a cycle of stage references and has no stratum —"
                       (= lt (depth target))
                       " in the same or a later stratum —"
                       :else
                       (str ", which its stage references can hold back until sweep " lt
                            ", no earlier than " (:stage/id s) " is judged in sweep " ds " —"))
                     " stratified negation (ADR 0005) requires strictly"
                     " earlier targets, or the result depends on"
                     " fixpoint iteration order.")})
        ;; derivation reaches an id when ANY entry with it passes, while
        ;; explain and the strata read only the last, so a reviewer of
        ;; one entry would miss the gate another bypasses
        (for [[id n] (sort-by (comp str key) (frequencies (map :stage/id stages)))
              :when (< 1 n)]
          {:level :error
           :msg (str "stage " id " is defined more than once (" n " entries)")})
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
                                           (map (juxt second #(nth % 2 nil))
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

