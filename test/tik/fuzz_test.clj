;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.fuzz-test
  "The adversarial layer of the test stack: the other four oracles
  feed tik VALID inputs and check the answers; this one feeds garbage
  and checks the manner of failure. The contract everywhere is `fail
  well`: a clean, data-carrying rejection (ex-info or a schema error)
  — never an unexpected exception class, and above all never a silent
  pass. TIK_FUZZ_N scales the iteration counts (default keeps the
  gate fast; crank it for a soak run)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.walk]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.author]
            [tik.causal]
            [tik.dag]
            [tik.args]
            [tik.canonical :as canonical]
            [tik.cli]
            [tik.dupe]
            [tik.event :as event]
            [tik.explain]
            [tik.next]
            [tik.work :as work]
            [tik.gen-events :as ge]
            [tik.harness :as h]
            [tik.guard :as guard]
            [tik.lint]
            [tik.oidc :as oidc]
            [tik.plan]
            [tik.process]
            [tik.template]
            [tik.reduce :as red]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite])
  (:import (java.nio.file Files)
           (java.time Instant)))

(def ^:private n
  (or (some-> (System/getenv "TIK_FUZZ_N") parse-long) 50))

(defn- fails-well?
  "Run the thunk; true when it returns normally or throws ex-info.
  Any other throwable is a fuzz finding."
  [thunk]
  (try (thunk) true
       (catch clojure.lang.ExceptionInfo _ true)
       (catch Throwable _ false)))

;; --------------------------------------------------- malformed events

(def gen-garbage
  "Structural garbage: scalars, maps, and vectors that are shaped
  nothing like (or deceptively like) an event."
  (gen/one-of
   [gen/any-equatable
    (gen/map gen/keyword gen/any-equatable {:max-elements 6})
    ;; deceptive: a real event with one key nuked or one value mangled
    (gen/let [e (gen/elements
                 [{:event/ticket (random-uuid) :event/type :fact/assert
                   :event/actor "seb" :event/at (Instant/now)
                   :event/parents #{"sha256-x"}
                   :event/body {:fact/path [:x] :fact/value 1}}])
              k (gen/elements [:event/ticket :event/type :event/actor
                               :event/at :event/parents])
              v gen/any-equatable]
      (gen/one-of [(gen/return (dissoc e k))
                   (gen/return (assoc e k v))]))]))

(defspec malformed_events_are_rejected_never_crash_never_pass n
  (prop/for-all [garbage gen-garbage]
    (and (fails-well? #(event/mint garbage))
         ;; mint must never bless garbage: either it throws, or the
         ;; input happened to be a fully valid event map
         (try (event/valid? (event/mint garbage))
              (catch clojure.lang.ExceptionInfo _ true)))))

;; --------------------------------------------------- malformed guards

(def gen-garbage-guard
  "A guard vector with a real-or-nonsense operator and arbitrary args —
  the domain generator for guard/eval-guard and the fixpoint in the
  totality registry below."
  (gen/one-of
   [gen/any-equatable
    (gen/vector gen/keyword 0 4)
    (gen/let [op (gen/elements [:fact :fact= :artifact :signed-by
                                :stage-reached :elapsed-since :and :or
                                :not :nonsense])
              args (gen/vector gen/any-equatable 0 3)]
      (gen/return (into [op] args)))]))

;; ------------------------------------------------- corrupted stores

(deftest any_single_byte_flip_is_caught_by_the_stored_identity
  ;; ADR 0007's whole point, fuzzed: filename = sha256(bytes), so no
  ;; corruption of any event file can go unnoticed. We flip one byte
  ;; at every position of every event in a real store and require the
  ;; identity check to catch each one.
  (let [dir (h/temp-dir! "tik-fuzz")
        s (fstore/file-store (str dir))
        ticket (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")
        evs (event/chain
             (fn [_] (event/create-ticket {:ticket ticket :actor "seb"
                                           :at t :title "fuzz"
                                           :process :p}))
             #(event/assert-fact {:ticket ticket :actor "seb"
                                  :at (.plusSeconds t 1) :parents %
                                  :path [:x] :value 1}))
        _ (doseq [e evs] (store/append! s e))
        files (filter #(and (.isFile ^java.io.File %)
                            (str/ends-with? (.getName ^java.io.File %)
                                            ".edn"))
                      (file-seq (io/file dir "tickets")))
        caught (atom 0) total (atom 0)]
    (is (= 2 (count files)))
    (doseq [^java.io.File f files
            :let [bytes (Files/readAllBytes (.toPath f))
                  id (str/replace (.getName f) #"\.edn$" "")]
            i (range (alength bytes))]
      (let [mutated (aclone ^bytes bytes)]
        (aset-byte mutated i (unchecked-byte (bit-xor (aget mutated i) 1)))
        (swap! total inc)
        (when (not= id (str "sha256-"
                            (canonical/sha256-hex-bytes mutated)))
          (swap! caught inc))))
    (is (= @total @caught)
        "every single-byte flip must change the content address")))

;; ------------------------------------------------ the porcelain parsers

(def ^:private cli-parse-value tik.args/parse-value)
(def ^:private cli-parse-key tik.args/parse-key)

(defspec parse-key-is-total-over-plausible-keys n
  (prop/for-all [s (gen/such-that seq gen/string-alphanumeric)]
    (vector? (cli-parse-key s))))

(defspec similarity-is-total-and-bounded n
  (prop/for-all [a (gen/one-of [gen/string (gen/return nil)])
                 b (gen/one-of [gen/string (gen/return nil)])]
    (let [score (tik.dupe/similarity a b)]
      (and (<= 0.0 score 1.0)
           (= score (tik.dupe/similarity b a))))))

;; ------------------------------------- the totality registry

(def ^:private gen-hostile-events
  "Events whose parent references may form cycles or dangle —
  impossible to MINT (hashes), trivial for a hostile store to write."
  (gen/fmap
   (fn [edges]
     (mapv (fn [[id parents]]
             {:event/id id :event/parents (set parents)
              :event/at (Instant/parse "2026-01-01T00:00:00Z")
              :event/type :fact/assert
              :event/ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef"
              :event/actor "x" :event/body {:fact/path [:x] :fact/value 1}})
           edges))
   (gen/map (gen/fmap #(str "id" %) gen/small-integer)
            (gen/set (gen/fmap #(str "id" %) gen/small-integer)
                     {:max-elements 3})
            {:max-elements 8})))

(def ^:private one (partial gen/fmap vector))   ; a one-argument arg-vector

(def ^:private gen-dep-graph
  "A cross-ticket dependency graph, hostile by construction — small node
  set so cycles and dangling prerequisites arise; a `:depends-on` fact
  can be hand-set to anything, so the plan lens must stay total over
  these."
  (gen/map (gen/elements [:a :b :c :d :e])
           (gen/set (gen/elements [:a :b :c :d :e :ghost]) {:max-elements 3})
           {:max-elements 5}))

(def ^:private gen-settled (gen/set (gen/elements [:a :b :c :d :e])
                                    {:max-elements 5}))

(def ^:private gen-proc-events-now-roles
  "The (process events now roles) argument vector shared by every
  derivation lens — effective-reached, explain, causal, and (with a
  ticket-id prepended) next/contributions. A process whose stages carry
  garbage guards, over real events, so both the compute and reject
  sides are hit."
  (gen/let [stages (gen/vector
                    (gen/let [id gen/keyword
                              guards (gen/vector gen-garbage-guard 0 2)]
                      {:stage/id id :guards guards})
                    0 4)
            events ge/gen-events]
    [{:process/id :fuzz :process/version 1 :process/stages stages}
     events (Instant/now) ge/roles]))

(def ^:private gen-marker
  "A template marker — a well-formed one, or a deceptively malformed one
  (wrong arity, a non-keyword param key). The expander must expand the
  first and reject the second, never crash on either."
  (gen/one-of
   [(gen/fmap (fn [k] [:tik/param k]) gen/keyword)
    (gen/fmap (fn [k] [:tik/when k [:tik/param k]]) gen/keyword)
    ;; malformed: right head, wrong shape
    (gen/return [:tik/param])
    (gen/fmap (fn [k] [:tik/param k :extra]) gen/keyword)
    (gen/return [:tik/when])
    (gen/fmap (fn [k] [:tik/when k]) gen/keyword)]))

(def ^:private gen-template-body
  "A template body salted with real and malformed markers, nested inside
  vectors/maps so both the splice path and the substitute path are hit."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/vector (gen/one-of [inner gen-marker]) 0 4)
       (gen/map gen/keyword (gen/one-of [inner gen-marker]) {:max-elements 3})]))
   (gen/one-of [gen/small-integer gen/keyword gen/string gen-marker])))

(def ^:private gen-template
  "A whole template — sometimes structural garbage, sometimes a real
  :tik/template body with a plausible or hostile :tik/params schema,
  paired with a param map that may be missing keys or carry wrong types."
  (gen/let [body gen-template-body
            schema (gen/one-of
                    [(gen/return [:map [:x :int] [:y {:optional true} :string]])
                     (gen/return [:map])
                     (gen/return :garbage-schema)   ; a non-schema :tik/params
                     gen/any-equatable])
            with-schema? gen/boolean
            params (gen/map (gen/elements [:x :y :z :ghost])
                            gen/any-equatable {:max-elements 4})]
    [(cond-> {:tik/template body}
       with-schema? (assoc :tik/params schema))
     params]))

(defn- has-template-marker?
  "Does any node in x still carry an unexpanded :tik/param / :tik/when
  marker? The expander's soundness contract is that its output has none."
  [x]
  (let [found (atom false)]
    (clojure.walk/postwalk
     (fn [n]
       (when (and (vector? n) (#{:tik/param :tik/when} (first n)))
         (reset! found true))
       n)
     x)
    @found))

(defspec expand_is_total_and_its_output_is_marker_free (* 2 n)
  ;; the expander eats a template adopted from an untrusted library, so
  ;; it must fail well over hostile bodies AND — when it DOES return —
  ;; leave no marker behind (a surviving [:tik/param …] would be a
  ;; silently half-expanded definition, the worse-than-crashing outcome)
  (prop/for-all [[template params] gen-template]
    (let [outcome (try {:ok (tik.template/expand template params)}
                       (catch clojure.lang.ExceptionInfo _ :rejected)
                       (catch Throwable t {:crash t}))]
      (cond
        (= :rejected outcome) true
        (:crash outcome) (do (println "NOT TOTAL: expand on"
                                      (pr-str [template params])
                                      "->" (class (:crash outcome)))
                             false)
        :else (let [marker (has-template-marker? (:ok outcome))]
                (when marker
                  (println "MARKER LEAKED: expand on" (pr-str [template params])
                           "->" (pr-str (:ok outcome))))
                (not marker))))))

(def ^:private gen-garbage-definition
  "A process definition, structurally garbage or plausible-but-broken —
  the domain for the linter and definition lenses."
  (gen/one-of
   [gen/any-equatable
    (gen/let [stages (gen/vector
                      (gen/let [id gen/keyword
                                after (gen/vector gen/keyword 0 2)
                                guards (gen/vector gen-garbage-guard 0 2)]
                        {:stage/id id :after after :guards guards})
                      0 4)]
      {:process/id :fuzz :process/version 1 :process/stages stages})]))

(def totality-registry
  "The explicit set of PURE boundary functions that promise TOTALITY:
  over their whole domain they return a value or throw ex-info — never
  another Throwable, never a silent wrong answer. One table so the
  boundary set is auditable in one place and one property (below)
  enforces the floor uniformly; a new untrusted-input surface joins
  here or it is not covered.

  Each entry is {:sym :f :gen}; :sym is the fully-qualified var (the
  meta-check below reconciles it against the live public API), :gen
  produces the full argument VECTOR that :f is applied to. The
  generator is shaped to the function's real domain — feeding gen/any
  to a fn whose domain is event maps would only ever exercise the
  reject path — so both the compute and the reject sides are hit. I/O
  boundaries (the store readers, run-argv, the MCP loop) need
  filesystem/process context and are driven by their own deftests, not
  this pure-args table."
  [{:sym 'tik.canonical/emit :f canonical/emit
    :gen (one gen/any-equatable)}
   {:sym 'tik.canonical/content-address :f canonical/content-address
    :gen (one gen/any-equatable)}
   {:sym 'tik.canonical/check-nesting :f canonical/check-nesting
    :gen (one gen/string)}
   {:sym 'tik.canonical/parse :f canonical/parse
    :gen (one gen/string)}
   {:sym 'tik.event/mint :f event/mint
    :gen (one gen-garbage)}
   {:sym 'tik.reduce/ticket-state :f red/ticket-state
    :gen (gen/let [garbage (gen/vector gen-garbage 0 5)
                   valid ge/gen-events]
           [(into (vec valid) garbage)])}
   {:sym 'tik.guard/eval-guard :f guard/eval-guard
    :gen (gen/let [g gen-garbage-guard
                   events ge/gen-events]
           [g {:state (red/ticket-state events) :now (Instant/now)
               :roles ge/roles :reached #{}}])}
   {:sym 'tik.stage/effective-reached :f stage/effective-reached
    :gen gen-proc-events-now-roles}
   {:sym 'tik.explain/explain :f tik.explain/explain
    :gen gen-proc-events-now-roles}
   {:sym 'tik.causal/causal :f tik.causal/causal
    :gen gen-proc-events-now-roles}
   {:sym 'tik.next/contributions :f tik.next/contributions
    :gen (gen/fmap #(into [#uuid "018f2f6e-7c1a-7000-8000-00000000beef"] %)
                   gen-proc-events-now-roles)}
   {:sym 'tik.explain/render :f tik.explain/render
    :gen (gen/fmap (fn [reasons]
                     [[{:stage :fuzz :satisfied [] :missing reasons
                        :blocks #{}}]])
                   (gen/vector (gen/map gen/keyword gen/any-equatable
                                        {:max-elements 4})
                               0 4))}
   {:sym 'tik.dag/heads :f tik.dag/heads :gen (one gen-hostile-events)}
   {:sym 'tik.dag/roots :f tik.dag/roots :gen (one gen-hostile-events)}
   {:sym 'tik.dag/missing-parents :f tik.dag/missing-parents
    :gen (one gen-hostile-events)}
   {:sym 'tik.cli/parse-value :f cli-parse-value
    :gen (one gen/string)}
   {:sym 'tik.cli/parse-key :f cli-parse-key
    :gen (one gen/string)}
   {:sym 'tik.dupe/similarity :f tik.dupe/similarity
    :gen (gen/tuple (gen/one-of [gen/string (gen/return nil)])
                    (gen/one-of [gen/string (gen/return nil)]))}
   {:sym 'tik.author/check :f tik.author/check
    :gen (one gen/any-equatable)}
   {:sym 'tik.work/usage-totals :f work/usage-totals
    :gen (gen/fmap (fn [records] [records {"m" {:input 3.0}}])
                   (gen/vector (gen/map gen/keyword gen/any-equatable
                                        {:max-elements 4})
                               0 4))}
   {:sym 'tik.lint/lint :f tik.lint/lint
    :gen (one gen-garbage-definition)}
   {:sym 'tik.process/signing-roles :f tik.process/signing-roles
    :gen (one gen-garbage-guard)}
   {:sym 'tik.plan/summary :f tik.plan/summary
    :gen (gen/tuple gen-dep-graph gen-settled)}
   {:sym 'tik.plan/critical-path :f tik.plan/critical-path
    :gen (gen/tuple gen-dep-graph gen-settled)}
   {:sym 'tik.plan/longest-paths :f tik.plan/longest-paths
    ;; the generic walk takes a parents-of FN — synthesize it from a
    ;; hostile edge map so cycles and dangling parents are exercised
    :gen (gen/fmap (fn [[g s]]
                     [(fn [n] (get g n #{}))
                      (tik.plan/nodes g)
                      (fn [n] (contains? s n))])
                   (gen/tuple gen-dep-graph gen-settled))}
   {:sym 'tik.plan/cyclic-nodes :f tik.plan/cyclic-nodes
    :gen (one gen-dep-graph)}
   {:sym 'tik.plan/ready :f tik.plan/ready
    :gen (gen/tuple gen-dep-graph gen-settled)}
   {:sym 'tik.template/expand :f tik.template/expand
    ;; gen-template carries real + malformed markers and hostile param
    ;; specs; a separate defspec pins the marker-freedom of the output
    :gen (gen/one-of [gen-template
                      (gen/tuple gen/any-equatable
                                 (gen/map gen/keyword gen/any-equatable
                                          {:max-elements 4}))])}])

(deftest dag-walks-total-over-non-collection-parents
  ;; a hostile/corrupt store file can hold {:event/parents 5} — parse-event
  ;; only checks map?, and the totality generator only ever builds parents
  ;; via (set …), so the scalar case is untested. The dag boundary fns must
  ;; answer a value, not a raw ISeq/`empty?` throw (the totality contract).
  (doseq [bad [5 :kw "x" 42.0 nil]]
    (let [events [{:event/id "sha256-a" :event/type :ticket/create :event/parents bad}
                  {:event/id "sha256-b" :event/type :fact/assert
                   :event/parents #{"sha256-a"}}]]
      (is (set? (tik.dag/heads events)) (pr-str bad))
      (is (set? (tik.dag/missing-parents events)) (pr-str bad))
      (is (vector? (tik.dag/roots events)) (pr-str bad)))))

(defspec every_registered_boundary_is_total (* 3 n)
  ;; pick a boundary, generate an argument vector in ITS domain, apply,
  ;; and force any lazy result — the answer must be a value or an
  ;; ex-info, never another Throwable. The sym rides along so a failure
  ;; names the function, not just an opaque fn object.
  (prop/for-all [[sym f args]
                 (gen/let [{:keys [sym f gen]} (gen/elements totality-registry)
                           args gen]
                   [sym f args])]
    (let [result (fails-well?
                  #(let [r (apply f args)]
                     (when (seqable? r) (doall r))
                     nil))]
      (when-not result (println "NOT TOTAL:" sym "on" (pr-str args)))
      result)))

;; --------- the meta-check: no boundary function goes uncovered

(defn- kernel-namespaces
  "Every pure-kernel namespace, DERIVED from the source tree — the .cljc
  files under src/tik. Deriving the set instead of hand-listing it is
  the point: a newly added kernel namespace appears here automatically
  and must then be classified (swept or excluded), so it cannot slip
  past the sweep the way a hand-list silently would."
  []
  (->> (.listFiles (io/file (System/getProperty "user.dir") "src" "tik"))
       (keep (fn [^java.io.File f]
               (let [nm (.getName f)]
                 (when (str/ends-with? nm ".cljc")
                   (symbol (str "tik."
                                (-> nm (str/replace #"\.cljc$" "")
                                    (str/replace "_" "-"))))))))
       set))

(def ^:private swept-namespaces
  "Kernel namespaces whose ENTIRE public surface is under the totality
  sweep: every public fn must promise totality (appear in
  totality-registry) or be listed in totality-exemptions with the
  reason it need not. This is the forcing function at the fn level."
  '#{tik.canonical tik.event tik.reduce tik.guard tik.stage tik.dag
     tik.explain tik.causal tik.next tik.process tik.lint tik.plan tik.template})

(def ^:private excluded-namespaces
  "Kernel .cljc namespaces deliberately NOT swept fn-by-fn, each with
  the reason. Excluding a whole namespace is itself a recorded verdict —
  the derived check below still forces every kernel namespace into one
  bucket or the other."
  '{tik.work "activity/telemetry drafting porcelain with its own work-test suite; the one untrusted-body boundary (usage-totals) is in the registry, the rest are lenses over drafted telemetry"})

(def ^:private totality-exemptions
  "Public kernel fns deliberately NOT in the registry, each with why.
  Explicit so the exemption is a decision on the record, not an
  oversight the sweep silently tolerates."
  '{tik.canonical/sha256-hex        "total by construction: SHA-256 over any string"
    tik.canonical/sha256-hex-bytes  "total by construction: SHA-256 over any byte array"
    tik.event/valid?                "malli validator over the Event schema: a boolean over any input"
    tik.event/explain-event         "malli explainer over the Event schema: an explanation or nil over any input"
    tik.event/event-id              "content-address of a map; the hashing core of mint (registered)"
    tik.event/create-ticket         "thin constructor over mint (registered)"
    tik.event/migrate-process       "thin constructor over mint (registered)"
    tik.event/assert-fact           "thin constructor over mint (registered)"
    tik.event/dispute-fact          "thin constructor over mint (registered)"
    tik.event/retract-fact          "thin constructor over mint (registered)"
    tik.event/attach-artifact       "thin constructor over mint (registered)"
    tik.event/add-attestation       "thin constructor over mint (registered)"
    tik.event/chain                 "test/script helper: threads parents over caller-supplied steps"
    tik.reduce/dedupe-events        "internal to the fold; exercised via ticket-state (registered)"
    tik.reduce/order                "internal to the fold; exercised via ticket-state (registered)"
    tik.reduce/ordered              "internal to the fold; exercised via ticket-state (registered)"
    tik.reduce/apply-event          "single-step reducer under the fold's invariants; via ticket-state"
    tik.reduce/fact-entry           "lens over a derived state map, not raw input"
    tik.reduce/conflicting-claims   "internal to the fold; exercised via ticket-state (registered)"
    tik.reduce/fact-status          "lens over a derived state map; consulted only by eval-guard (registered)"
    tik.reduce/fact-value           "lens over a derived state map, not raw input"
    tik.guard/fact-map              "internal to eval-guard (registered)"
    tik.stage/sticky-ids            "internal to the fixpoint; exercised via effective-reached (registered)"
    tik.stage/reached-set           "internal to the fixpoint; exercised via effective-reached (registered)"
    tik.stage/evolve                "internal to the fixpoint; exercised via effective-reached (registered)"
    tik.stage/trace-sweeps          "internal to the fixpoint; exercised via effective-reached (registered)"
    tik.stage/stage-timeline        "lens over derived stages, driven by the stage property tests"
    tik.stage/ancestor-closure      "pure graph helper over a stage's :after, under process-lint invariants"
    tik.stage/downstream            "pure graph helper over a stage's :after, under process-lint invariants"
    tik.stage/current-stages        "lens over derived reached-set, not raw input"
    tik.explain/frontier            "internal to explain (registered)"
    tik.explain/actionability       "internal to explain (registered)"
    tik.explain/actionable-by?      "predicate over a derived reason, exercised via for-actor/explain"
    tik.explain/for-actor           "capability filter over explain's output (registered), not raw input"
    tik.explain/reason->text        "renders one derived reason to English; exercised via render (registered)"
    tik.causal/support              "internal to causal (registered): the events one guard consumes"
    tik.next/settled?               "predicate over a derived reached-set, not raw input"
    tik.next/settled-reached?       "predicate over a derived reached-set, not raw input; the fixpoint-sharing core of settled? (registered via contributions)"
    tik.next/inbox                  "combiner over already-derived contributions output (registered), not raw input"
    tik.next/admissible?            "predicate over one already-derived contribution action + an actor, not raw input; the per-actor gate inbox and cli/agent-admissible share"
    tik.process/valid?              "malli validator over the Process schema: a boolean over any input"
    tik.process/explain-process     "malli explainer over the Process schema: an explanation or nil over any input"
    tik.process/process-hash        "content-address of a definition via canonical/emit (registered)"
    tik.process/roles-gating        "lens over a definition; total, exercised via lint (registered) and process-test"
    tik.plan/nodes                  "structural: the node set of an edge map; total by construction"
    tik.plan/prereqs                "structural: one node's prerequisite set; total by construction"
    tik.plan/dependents             "structural: reverse edges of an edge map; total by construction"
    tik.plan/in-cycle?              "per-node cycle check; exercised via cyclic-nodes (registered)"
    tik.plan/blocked?               "per-node predicate over prereqs+settled; exercised via summary (registered)"
    tik.plan/status                 "per-node status; exercised via summary (registered)"
    tik.plan/unlocks                "per-node downstream impact; exercised via summary (registered)"
    tik.template/template?          "predicate: does a value carry :tik/template; total by construction"})

(deftest every_kernel_namespace_is_swept_or_excluded
  ;; the namespace-level forcing function, DERIVED from the source tree:
  ;; a hand-listed swept set can silently omit a namespace (it did —
  ;; causal and next were missed until a fuzz round noticed). Deriving
  ;; the kernel set from src/tik/*.cljc and requiring each to be swept
  ;; or excluded means a brand-new kernel file cannot slip past — it
  ;; appears here automatically and fails until classified.
  (let [discovered (kernel-namespaces)]
    (is (seq discovered) "the kernel namespace discovery found files")
    (doseq [ns-sym discovered]
      (is (or (contains? swept-namespaces ns-sym)
              (contains? excluded-namespaces ns-sym))
          (str ns-sym " is a kernel namespace with no sweep verdict —"
               " add it to swept-namespaces (its public fns get the"
               " registered-or-exempt treatment) or to"
               " excluded-namespaces (with the reason it need not)")))
    (testing "no stale classifications: every swept/excluded ns still exists"
      (doseq [ns-sym (concat swept-namespaces (keys excluded-namespaces))]
        (is (contains? discovered ns-sym)
            (str ns-sym " is classified but is no longer a kernel"
                 " .cljc namespace — drop it"))))))

(deftest every_public_boundary_fn_is_registered_or_exempt
  ;; within each SWEPT namespace, every public fn must carry a verdict —
  ;; a registry entry (it promises totality) or an exemption (with the
  ;; reason it need not). A newly added public fn in a swept namespace
  ;; fails this until it is classified; that is the whole point.
  (let [registered (set (map :sym totality-registry))]
    (doseq [ns-sym swept-namespaces]
      (require ns-sym)
      (doseq [[fn-name v] (ns-publics ns-sym)
              :when (fn? (deref v))
              :let [qsym (symbol (name ns-sym) (name fn-name))]]
        (is (or (contains? registered qsym)
                (contains? totality-exemptions qsym))
            (str qsym " is a public boundary fn with no totality verdict"
                 " — add it to totality-registry (it promises the"
                 " fail-well floor) or to totality-exemptions (with the"
                 " reason it need not)"))))
    (testing "no stale exemptions: every exempted sym still exists"
      (doseq [[qsym _] totality-exemptions]
        (is (resolve qsym)
            (str qsym " is exempted but no longer a public fn — drop it"))))))

;; -------------------------------- hash-valid garbage in a store file

(deftest hash_valid_garbage_is_rejected_cleanly_at_read_time
  ;; byte flips are caught by the name/content check — the sharper
  ;; attack stores GARBAGE whose name honestly hashes its bytes. The
  ;; reader must reject it with a data-carrying error, never explode.
  (let [dir (h/temp-dir! "tik-fuzz2")
        ticket (random-uuid)
        evdir (io/file dir "tickets" (str ticket) "events")]
    (.mkdirs evdir)
    (doseq [payload ["%%% not edn at all"
                     "{:not-an-event true}"
                     "[1 2 3"
                     "#=(java.lang.Runtime/getRuntime)"]]
      (let [bytes (.getBytes ^String payload "UTF-8")
            name (str "sha256-" (canonical/sha256-hex-bytes bytes) ".edn")]
        (java.nio.file.Files/write
         (.toPath (io/file evdir name)) ^bytes bytes
         ^"[Ljava.nio.file.OpenOption;"
         (make-array java.nio.file.OpenOption 0))))
    (let [s (fstore/file-store (str dir))]
      (is (fails-well? #(doall (red/ticket-state (store/events s ticket))))
          "reading a hostile store must be a clean rejection")
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (red/ticket-state (store/events s ticket))))
          "and specifically an ex-info, not a silent empty fold"))))

;; --------------------------------------------------- the argv surface

(deftest hostile_argv_never_stack_traces
  ;; every invocation a confused human or hostile script can produce
  ;; must exit 0 or 1 with WORDS on stderr — a stack trace on the
  ;; first contact is an H9 killer and a fuzz finding
  (let [root (h/temp-dir! "tik-argv")
        zalgo (apply str "ch" (map char [0x0341 0x0327 0x036 97 111 115]))
        run (h/tik-runner root)]
    (doseq [argv [["set"] ["set" "="] ["set" "x" "=y"]
                  ["status"] ["explain"] ["log"] ["causal"]
                  ["new" "\ud83c\udfab"] ["new" "track" "--title"]
                  ["ls" "--where" "="] ["ls" "--where" ":::"]
                  ["ls" "--where" "not"] ["ls" "--where" "fact:"]
                  ["ls" "--format" "yaml"] ["ls" "--format"]
                  ["dupes"] ["ls" "--where" "bogus=1"] ["search"]
                  ["whatif" "zzzz" "+NOT-A-DURATION"]
                  ["reprocess" "zzzz"] ["bundle"] ["witness" "zzzz"]
                  ["attest" "zzzz"] ["work" "??"]
                  ["author" "--from" "/nonexistent.edn"]
                  ["adopt"] ["adopt" "/nonexistent.tmpl.edn"]
                  ["gc" "--apply"] ["plan"]
                  ["show"] ["show" "/nonexistent.edn"] ["show" "track"]
                  ["author" "check"] ["rollout"] ["probe" "zzzz"]
                  ["serve" "--port" "abc"] ["actor" "add" "a" "/nope.pub"]
                  ["bridge" "oid4vci" "--credential" "/nope.jwt"]
                  ;; the agent frontier gate: a missing ticket or missing
                  ;; key=value / claim arg must be a usage message, not an
                  ;; NPE from resolve-id / str/split / parse over nil
                  ["agent" "set" "--actor" "a"] ["agent" "attest" "--actor" "a"]
                  ["agent" "actions" "--actor" "a"] ["agent" "set" "zzzz"]
                  ["lint" " "] ["--" "--" "--"]
                  [zalgo]]]
      (let [r (apply run argv)]
        (is (contains? #{0 1} (:exit r)) (pr-str argv))
        (is (h/clean-output? (str (:out r) (:err r)))
            (str (pr-str argv) "\n" (:err r)))))))

(deftest adopt_over_hostile_template_files_never_traces
  ;; adopt reads a .tmpl.edn from an untrusted library and expands it.
  ;; A body with no id, an id that is a marker (not a name until
  ;; expanded), a malformed marker, or a scalar body must each answer
  ;; with a message and exit 1 — never a ClassCast or other raw trace
  (let [root (h/temp-dir! "tik-adopt")
        run (fn [file body]
              (spit (io/file root file) body)
              (h/run-tik! {:root root :in ":x\n"}
                          "adopt" (str (io/file root file))))]
    (.mkdirs (io/file root "processes"))
    (doseq [[file body]
            [["a.tmpl.edn" "{:tik/template {:a 1}}"]                 ; no :process/id
             ["b.tmpl.edn" "{:tik/params [:map [:p :keyword]] :tik/template {:process/id [:tik/param :p] :process/version 1 :process/guard-vocab 1 :process/stages []}}"]
             ["c.tmpl.edn" "{:tik/template {:a [:tik/when :f]}}"]    ; malformed marker
             ["d.tmpl.edn" "{:tik/template 5}"]                      ; scalar body
             ["e.tmpl.edn" "{:tik/params :not-a-schema :tik/template {:process/id :e}}"]
             ["f.edn" "{:not :a-process}"]]]                         ; plain, no id
      (let [r (run file body)]
        (is (contains? #{0 1} (:exit r)) (pr-str [file (:exit r)]))
        (is (h/clean-output? (str (:out r) (:err r)))
            (str file "\n" (:out r) (:err r)))))))

(deftest process_test_runner_fails_well_on_hostile_files
  ;; `tik test` reads a scripted-test EDN and the process it names. A spec
  ;; that is not a map, omits :test/process (a relative path), points at a
  ;; missing process, or carries a malformed step must answer with a
  ;; message + exit 1 — never the NullPointer from (io/file parent nil) on
  ;; a nil path, nor a raw case/seq throw on a bad step (apply-step).
  (let [{:keys [root]} (h/temp-store!)
        _ (spit (io/file root "proc.edn")
                (slurp (io/file "processes" "hypothesis.edn")))
        write (fn [body] (spit (io/file root "t.tests.edn") body)
                (str (io/file root "t.tests.edn")))]
    (h/with-cli-root root
      (fn []
        (doseq [[body rx]
                [["42" #"must be a map"]
                 ["{:test/cases []}" #":test/process"]
                 ["{:test/process 42}" #":test/process"]
                 ["{:test/process \"nope.edn\" :test/cases []}" #"no process definition"]
                 ["{:test/process \"proc.edn\" :test/cases 42}" #":test/cases must be"]
                 ["{:test/process \"proc.edn\" :test/cases [{:case/name \"c\" :case/steps [42] :case/expect {}}]}" #"each step must be a list"]
                 ["{:test/process \"proc.edn\" :test/cases [{:case/name \"c\" :case/steps [[:frobnicate]] :case/expect {}}]}" #"unknown step op"]]]
          (let [r (tik.cli/run-argv ["test" (write body)])]
            (is (= 1 (:exit r)) body)
            (is (h/clean-output? (str (:out r) (:err r))) (str body "\n" (:err r)))
            (is (re-find rx (str (:out r) (:err r)))
                (str body "\n" (:out r) (:err r)))))))))

(deftest export_to_an_uncreatable_path_fails_well
  ;; `tik export <dir>` writes a file store at <dir>; a path that cannot
  ;; be created must be a clean message, never a raw FileNotFoundException
  ;; reported as an internal bug. A path UNDER an existing regular file is
  ;; portably uncreatable (mkdirs refuses to descend into a file).
  (let [{:keys [root store]} (h/temp-store!)
        t (Instant/parse "2026-01-01T00:00:00Z")]
    (h/seed-ticket! store {:ticket (random-uuid) :at t :title "e"})
    (spit (io/file root "a-file") "not a directory")
    (h/with-cli-root root
      (fn []
        (let [target (str (io/file root "a-file" "sub"))
              r (tik.cli/run-argv ["export" target])]
          (is (= 1 (:exit r)) target)
          (is (h/clean-output? (str (:out r) (:err r))) target)
          (is (re-find #"cannot create export directory"
                       (str (:out r) (:err r))) target))
        (testing "a creatable new nested path works (mkdir -p semantics)"
          (let [ok (str (io/file root "fresh" "nested" "out"))
                r (tik.cli/run-argv ["export" ok])]
            (is (zero? (:exit r)) (:err r))
            (is (.isDirectory (io/file ok "tickets")))))))))

(deftest show_over_hostile_definition_never_traces
  ;; `show` draws from an UNLINTED definition read straight off disk, so
  ;; every field is attacker-shaped. A :process/roles that is not a map,
  ;; a stage graph with dangling/self edges, non-map stages, or scalar ids
  ;; must render as best it can (or a clean message) — never a raw
  ;; ClassCast from (keys …) or the layout.
  (let [{:keys [root]} (h/temp-store!)
        defs {"roles-string.edn" "{:process/id :r :process/version 1 :process/guard-vocab 1 :process/roles \"nope\" :process/stages [{:stage/id :a}]}"
              "roles-vector.edn" "{:process/id :r :process/version 1 :process/guard-vocab 1 :process/roles [:a :b] :process/stages [{:stage/id :a}]}"
              "dangling.edn" "{:process/id :n :process/version 1 :process/guard-vocab 1 :process/stages [{:stage/id :a :stage/next [:ghost]}]}"
              "scalar-stages.edn" "{:process/id :m :process/version 1 :process/guard-vocab 1 :process/stages [:x 5 \"s\"]}"
              "self-loop.edn" "{:process/id :s :process/version 1 :process/guard-vocab 1 :process/stages [{:stage/id :a :stage/next [:a]}]}"
              "nil-id.edn" "{:process/id :w :process/version 1 :process/guard-vocab 1 :process/stages [{:stage/id nil}]}"}]
    (h/with-cli-root root
      (fn []
        (doseq [[file body] defs
                :let [_ (spit (io/file root file) body)
                      r (tik.cli/run-argv ["show" (str (io/file root file))])]]
          (is (contains? #{0 1} (:exit r)) (pr-str [file (:exit r)]))
          (is (h/clean-output? (str (:out r) (:err r)))
              (str file "\n" (:out r) (:err r))))))))

;; -------------------------------------------- garbage config files

(deftest garbage_configs_answer_with_words_never_traces
  ;; every EDN file an operator can typo must produce a message naming
  ;; the file and exit 1 — the top-level landing guarantees no trace
  (let [root (h/temp-dir! "tik-cfg")
        repo (System/getProperty "user.dir")
        run (h/tik-runner root)
        garbage "{:unclosed [vector :and (garbage"]
    (.mkdirs (io/file root "processes"))
    (doseq [[file argv]
            [["effects.edn" ["effects" "run"]]
             ["bridge.edn" ["bridge" "email"]]
             ["authoring-rules.edn" ["author" "prompt"]]
             ["answers.edn" ["author" "check" "answers.edn"]]
             ["processes/broken.edn" ["new" "broken"]]
             ["processes/broken.edn" ["lint" "processes/broken.edn"]]]]
      (spit (io/file root file) garbage)
      (let [r (if (= "email" (second argv))
                (apply run (concat argv [:in "From: a@b\nSubject: x\n\nhi"]))
                (apply run argv))]
        (is (= 1 (:exit r)) (pr-str [file argv]))
        (is (re-find #"malformed EDN in .*" (str (:err r)))
            (pr-str [file argv (:err r)]))
        (is (h/clean-output? (str (:out r) (:err r)))
            (pr-str [file argv]))))
    (testing "a garbage context marker fails new with the file named"
      ;; markers are read only when cwd sits beneath the store holder,
      ;; so this invocation runs FROM the store directory
      (spit (io/file root ".tik-facts.edn") garbage)
      (.mkdirs (io/file root "tickets"))
      (let [sub (doto (io/file root "inside") (.mkdirs))
            r (sh/sh "bb" "--config" (str repo "/bb.edn")
                     "tik" "new" "track" "--title" "x"
                     :dir (str sub)
                     :env (assoc (into {} (System/getenv))
                                 "TIK_ROOT" (str root)
                                 "TIK_ACTOR" "fuzz"))]
        (is (= 1 (:exit r)) (:err r))
        (is (re-find #"malformed EDN" (str (:err r))))))))

(deftest one_dead_sink_does_not_abandon_the_rest
  (let [root (h/temp-dir! "tik-sink")
        outfile (io/file root "delivered.json")
        run (h/tik-runner root)]
    (run "new" "track" "--title" "sink isolation")
    (spit (io/file root "effects.edn")
          (pr-str {:sinks [{:type :slack
                            :url "http://127.0.0.1:1/unroutable"}
                           {:type :command
                            :command ["sh" "-c" (str "cat >> " outfile)]}]}))
    (let [r (run "effects" "run" "--config"
                 (str (io/file root "effects.edn")))]
      (is (= 1 (:exit r)) "delivery failures exit nonzero")
      (is (re-find #"failed slack .* will retry next run" (str (:err r))))
      (is (.exists outfile)
          "the healthy sink delivered despite the dead one")
      (is (h/clean-output? (str (:err r)))))))

;; ------------------------------- packs, caches, hostile directory names

(defspec minted_events_always_read_back n
  ;; the corrupt-on-write guard: whatever survives mint must re-emit
  ;; to the same bytes after a plain EDN read — no store can gain a
  ;; hash-valid but forever-unreadable file through the front door
  (prop/for-all [v gen/any-equatable]
    (let [minted (try (event/assert-fact
                       {:ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef"
                        :actor "seb" :at (Instant/parse "2026-01-01T00:00:00Z")
                        :parents #{"sha256-x"} :path [:x] :value v})
                      (catch clojure.lang.ExceptionInfo _ ::refused))]
      (or (= ::refused minted)
          (let [bytes (canonical/emit (dissoc minted :event/id))]
            ;; read back with the format's own readers — #inst is an
            ;; Instant, the one time type the printer accepts
            (= bytes (canonical/emit (canonical/parse bytes))))))))

(deftest hostile_directory_names_never_corrupt_a_store
  (let [top (h/temp-dir! "tik-dirs")
        repo (System/getProperty "user.dir")
        run (fn [dir & args]
              (apply sh/sh (concat ["bb" "--config" (str repo "/bb.edn") "tik"]
                                   args
                                   [:dir (str dir)
                                    :env (assoc (into {} (System/getenv))
                                                "TIK_ACTOR" "fuzz")])))]
    (doseq [evil ["my repo" "wei{rd" "tick] et"]]
      (.mkdirs (io/file top evil ".git")))
    (run top "init" "--hidden")
    (testing "rollout across hostile names: strings, not broken keywords"
      (let [r (run top "rollout" "track")]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"3 ticket\(s\) created" (:out r)))))
    (testing "context facts from inside a hostile dir"
      (let [inside (io/file top "my repo")
            r (run inside "new" "track" "--title" "from within")]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"context: repo=\"my repo\"" (str (:err r))))))
    (testing "the whole store still verifies"
      (is (re-find #"verify: PASS" (:out (run top "verify")))))))

(deftest lying_pack_indexes_fail_well
  (let [dir (h/temp-dir! "tik-pack-fuzz")
        s (fstore/file-store (str dir))
        ticket (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")
        evs (event/chain
             (fn [_] (event/create-ticket {:ticket ticket :actor "seb"
                                           :at t :title "packed"
                                           :process :p}))
             #(event/assert-fact {:ticket ticket :actor "seb"
                                  :at (.plusSeconds t 1) :parents %
                                  :path [:x] :value 1}))
        _ (doseq [e evs] (store/append! s e))
        _ (fstore/pack! (str dir) ticket)
        evdir (io/file dir "tickets" (str ticket) "events")
        idx (io/file evdir "events.pack.idx")
        good (slurp idx)]
    (testing "the packed store reads and folds"
      (is (= 2 (count (store/events s ticket)))))
    (testing "garbage index fails well"
      (spit idx "{{{ not edn")
      (is (fails-well? #(doall (store/events s ticket))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (store/events s ticket)))))
    (testing "an index lying about offsets fails well, never EOFs raw"
      (spit idx (str/replace good #":offset 0" ":offset 999999"))
      (is (fails-well? #(doall (store/events s ticket)))))
    (testing "a truncated pack fails well"
      (spit idx good)
      (spit (io/file evdir "events.pack") "short")
      (is (fails-well? #(doall (store/events s ticket)))))))

(deftest corrupt_caches_are_misses_never_crashes
  (let [root (h/temp-dir! "tik-cache-fuzz")
        run (h/tik-runner root)]
    (run "new" "track" "--title" "cache fodder")
    (run "ls")                                   ; builds the cache
    (doseq [payload ["%%% not json" "[1,2,3]" "{\"x\": {\"row\": 7}}"
                     "{\"unclosed\": "]]
      (spit (io/file root ".derived-cache.json") payload)
      (let [r (run "ls")]
        (is (zero? (:exit r)) payload)
        (is (re-find #"cache fodder" (:out r)) payload)))))

;; ------------------------------- hostile DAGs and poisoned boards

(defspec ticket_state_folds_a_hostile_dag n
  ;; the dag graph walks (heads/roots/missing-parents) are in the
  ;; totality registry; this pins the extra: the reducer FOLDS a set of
  ;; events whose parents cycle or dangle — parents are integrity, not
  ;; order, so a broken DAG must still fold, never loop
  (prop/for-all [events gen-hostile-events]
    (fails-well? #(red/ticket-state events))))

(defspec reason_rendering_is_a_string_over_garbage n
  ;; render is in the registry for totality; this asserts the STRONGER
  ;; shape contract: whatever reasons arrive — including keys from tik
  ;; versions that do not exist yet — render answers with a STRING
  (prop/for-all [reasons (gen/vector
                          (gen/map gen/keyword gen/any-equatable
                                   {:max-elements 4})
                          0 4)]
    (string? (tik.explain/render
              [{:stage :fuzz :satisfied [] :missing reasons :blocks #{}}]))))

(deftest one_poisoned_ticket_never_hides_the_healthy
  (let [root (h/temp-dir! "tik-poison")
        run (h/tik-runner root)]
    (.mkdirs (io/file root "processes"))
    (spit (io/file root "processes" "poison.edn")
          (pr-str {:process/id :poison :process/version 1
                   :process/guard-vocab 1 :lint {:runbooks :off}
                   :process/stages
                   [{:stage/id :a :guards []}
                    {:stage/id :b :after [:a]
                     :guards [[:elapsed-since :ticket/create "garbage"]]}]}))
    (run "new" "track" "--title" "healthy neighbor")
    (run "new" "poison" "--title" "the poisoned one")
    (testing "lint refuses the poison at authoring time"
      (is (re-find #"not ISO-8601"
                   (:out (run "lint" "processes/poison.edn")))))
    (testing "ls shows the healthy ticket AND a visible error row"
      (let [r (run "ls")]
        (is (zero? (:exit r)))
        (is (re-find #"healthy neighbor" (:out r)))
        (is (re-find #"error\s+cannot derive" (:out r)))))
    (testing "next works around the poison and says so"
      (let [r (run "next" "--actor" "fuzz")]
        (is (zero? (:exit r)))
        (is (re-find #"skipping .*malformed guard" (str (:err r))))
        (is (re-find #"outcome" (:out r)))))
    (testing "verify reports the poison as a FAIL line and finishes"
      (let [r (run "verify")]
        (is (re-find #"FAIL  derivation raises" (:out r)))
        (is (re-find #"verify: FAIL" (:out r)))
        (is (re-find #"ok 1 event\(s\)" (:out r))
            "the audit covered the healthy ticket too")))))

;; ---------------- recursion bombs, sqlite hostility, signature sidecars

(deftest recursion_bombs_are_rejected_never_overflow
  ;; the emitter and every EDN reader recurse per nesting level, so a
  ;; 100k-deep vector is a stack bomb — and StackOverflowError is an
  ;; Error no fail-well guard catches. Write side and read side must
  ;; both answer with ex-info (or a value) long before the stack does
  (let [deep-value (reduce (fn [acc _] (vector acc)) 1 (range 100000))
        deep-text (str (str/join (repeat 100000 "["))
                       "1"
                       (str/join (repeat 100000 "]")))]
    (testing "emit rejects cleanly"
      (is (thrown? clojure.lang.ExceptionInfo (canonical/emit deep-value))))
    (testing "mint rejects cleanly"
      (is (thrown? clojure.lang.ExceptionInfo
                   (event/assert-fact
                    {:ticket (random-uuid) :actor "x" :at (Instant/now)
                     :parents #{"sha256-x"} :path [:x] :value deep-value}))))
    (testing "check-nesting rejects the raw text before any reader"
      (is (thrown? clojure.lang.ExceptionInfo
                   (canonical/check-nesting deep-text))))
    (testing "brackets inside strings and char literals don't count"
      (is (nil? (canonical/check-nesting
                 "{:a \"[[[[[[\" :b \\[ :c [1 2 3]}"))))
    (testing "parse-value answers with the literal string"
      (is (= deep-text (cli-parse-value deep-text))))
    (testing "an empty/whitespace value is the literal string, NOT the
              internal eof sentinel (which would land in a signed fact)"
      (is (= "" (cli-parse-value "")))
      (is (= "  " (cli-parse-value "  ")))
      (is (not= :tik.cli/eof (cli-parse-value ""))))))

(deftest hash_valid_recursion_bomb_in_a_store_fails_well
  ;; the sharpest form: a store file whose name honestly hashes a
  ;; 100k-deep vector. Reading must reject with words; the whole-store
  ;; audit must report THAT ticket and still finish
  (let [root (h/temp-dir! "tik-bomb")
        run (h/tik-runner root)]
    (run "new" "track" "--title" "healthy neighbor")
    ;; Plant the bomb in "the bombed one" DETERMINISTICALLY — target the ticket
    ;; by its id, never `(first (.listFiles tickets))` whose order is
    ;; filesystem-defined and would just as well bomb the healthy neighbor
    ;; (green locally, red in CI).
    (let [evdir (h/events-dir root (h/new-ticket! run "track" "--title" "the bombed one"))
          bomb (.getBytes (str (str/join (repeat 100000 "["))
                               "1"
                               (str/join (repeat 100000 "]")))
                          "UTF-8")
          name (str "sha256-" (canonical/sha256-hex-bytes bomb) ".edn")]
      (java.nio.file.Files/write
       (.toPath (io/file evdir name)) ^bytes bomb
       ^"[Ljava.nio.file.OpenOption;"
       (make-array java.nio.file.OpenOption 0)))
    (testing "ls isolates the bombed ticket"
      (let [r (run "ls")]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"healthy neighbor" (:out r)))
        (is (re-find #"error\s+cannot derive" (:out r)))))
    (testing "verify reports and finishes the audit"
      (let [r (run "verify")]
        (is (= 1 (:exit r)))
        (is (re-find #"unreadable" (:out r)))
        (is (re-find #"verify: FAIL" (:out r)))
        (is (re-find #"ok 1 event\(s\)" (:out r))
            "the audit covered the healthy ticket too")
        (is (h/clean-output? (str (:out r) (:err r))))))))

(deftest hostile_sqlite_rows_fail_well
  ;; rows written around append! — garbage bytes under an honest id, a
  ;; ticket column that is not a uuid, a db that is not a db — must all
  ;; reject with ex-info, never a raw cast or parse exception
  (let [db (str (System/getProperty "java.io.tmpdir")
                "/tik-fuzz-" (random-uuid) ".db")
        s (tik.store.sqlite/sqlite-store db)
        t #uuid "11111111-1111-1111-1111-111111111111"]
    (sh/sh "sqlite3" db
           (str "INSERT INTO events(id,ticket,bytes) VALUES"
                "('sha256-abc','" t "', X'25252520676172626167');"
                "INSERT INTO events(id,ticket,bytes) VALUES"
                "('sha256-def','not-a-uuid', X'6e696c');"))
    (testing "garbage bytes name the row"
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (store/events s t)))))
    (testing "a non-uuid ticket column names the value"
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (store/ticket-ids s)))))
    (testing "a corrupt db file is an ex-info, not a raw shell error"
      (spit db "NOT A DATABASE")
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (store/ticket-ids s)))))
    (io/delete-file db true)))

(deftest garbage_signature_sidecars_never_trace
  ;; sidecar verification shells to ssh-keygen; whatever bytes sit in a
  ;; .sig file — prose, truncated armor, nothing — the audit answers
  ;; with verdict lines, never a trace
  (let [root (h/temp-dir! "tik-sig")
        run (h/tik-runner root)]
    (run "new" "track" "--title" "sig fodder")
    (let [evdir (h/sole-ticket-events-dir root)
          id (-> ^java.io.File (first (.listFiles ^java.io.File evdir))
                 .getName
                 (str/replace #"\.edn$" ""))]
      (spit (io/file evdir (str id ".sig.deadbeefdeadbeef")) "garbage")
      (spit (io/file evdir (str id ".sig.0123456789abcdef"))
            "-----BEGIN SSH SIGNATURE-----\ntrunc")
      (spit (io/file evdir (str id ".sig.ffffffffffffffff")) ""))
    (spit (io/file root "actors")
          "fuzz namespaces=\"tik-*\" garbage-not-a-key\n")
    (let [r (run "verify")]
      (is (contains? #{0 1} (:exit r)))
      (is (re-find #"verify: (PASS|FAIL)" (:out r)))
      (is (h/clean-output? (str (:out r) (:err r)))))))

(deftest hostile_filenames_in_roots_and_stores_verify_well
  ;; verify derives display ids from FILENAMES — a witness sidecar's
  ;; attested-root, an event file's stem, a by-hash name. A hostile short
  ;; or empty name must not drive `(subs … 0 19)` past the end and crash
  ;; the whole audit: sid/shash are total, so these display as-is.
  (let [root (h/temp-dir! "tik-roots")
        run (h/tik-runner root)]
    (run "new" "track" "--title" "root fodder")
    (spit (io/file root "actors")
          "fuzz namespaces=\"tik-*\" ssh-ed25519 AAAA fuzz\n")
    (.mkdirs (io/file root "roots"))
    ;; witness sidecars whose attested-root (before .witness.) is short
    ;; or empty — the exact filename that used to crash shash
    (doseq [nm ["x.witness.deadbeefdeadbeef"
                ".witness.deadbeefdeadbeef"
                "sha256-ab.witness.0000000000000000"]]
      (spit (io/file root "roots" nm) "garbage"))
    ;; a short-named event file and a short by-hash file
    (let [evdir (h/sole-ticket-events-dir root)]
      (spit (io/file evdir "short.edn") "{:not :an-event}"))
    (.mkdirs (io/file root "processes" "by-hash"))
    (spit (io/file root "processes" "by-hash" "short.edn") "{:x 1}")
    (let [r (run "verify")]
      (is (contains? #{0 1} (:exit r)))
      (is (re-find #"verify: (PASS|FAIL)" (:out r)))
      (is (h/clean-output? (str (:out r) (:err r)))
          (str (:out r) (:err r))))))

;; ----------------- the HTTP serve and MCP stdio surfaces

(defn- http-status
  "GET path off the local server; the status code, or -1 on a
  connection error (the server refusing/closing, never our test)."
  [port path]
  (try
    (let [url (.toURL (java.net.URI. (str "http://127.0.0.1:" port path)))
          c (doto ^java.net.HttpURLConnection (.openConnection url)
              (.setConnectTimeout 2000)
              (.setReadTimeout 2000))]
      (try (.getResponseCode c) (finally (.disconnect c))))
    (catch Exception _ -1)))

(deftest serve_survives_every_hostile_request
  ;; the board server is read-only, but a hostile GET must not reach a
  ;; die/System-exit path — one bad request would take the board down
  ;; for everyone. Unknown ids 404, everything is request-scoped, and
  ;; the process is still answering after the whole barrage
  (let [root (h/temp-dir! "tik-serve")
        repo (System/getProperty "user.dir")
        port 7801
        _ (h/tik! {:root root} "new" "track" "--title" "served")
        cmd ^"[Ljava.lang.String;" (into-array
                                    String
                                    ["bb" "tik" "serve" "--port" (str port)])
        proc (.start (doto (ProcessBuilder. cmd)
                       (.directory (io/file repo))
                       (.redirectErrorStream true)
                       (.. environment (put "TIK_ROOT" (str root)))
                       (.. environment (put "TIK_ACTOR" "fuzz"))))]
    (try
      ;; wait for the port to answer
      (loop [tries 60]
        (when (and (pos? tries) (neg? (http-status port "/")))
          (Thread/sleep 250)
          (recur (dec tries))))
      (testing "the known routes serve"
        (is (= 200 (http-status port "/")))
        (is (= 200 (http-status port "/tickets.edn"))))
      (testing "hostile ids and paths are 404, never a crash"
        (doseq [path ["/explain/ffff.edn"
                      "/explain/.edn"
                      "/explain/-------.edn"
                      "/explain/deadbeef-0000.edn"
                      "/nonsense"
                      "/explain/00000000-0000-0000-0000-000000000000.edn"]]
          (is (contains? #{404 400} (http-status port path)) path)))
      (testing "the server is still alive after the barrage"
        (is (= 200 (http-status port "/")))
        (is (.isAlive proc)))
      (finally (.destroy proc)
               (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)))))

(deftest mcp_session_survives_every_hostile_line
  ;; the MCP server reads one JSON-RPC request per line off stdio; a
  ;; line that is not JSON, not an object, names no tool, or omits a
  ;; required argument must answer with a JSON-RPC result/error and
  ;; leave the SESSION intact — a later well-formed request still works
  (let [root (h/temp-dir! "tik-mcp")
        repo (System/getProperty "user.dir")
        _ (h/tik! {:root root} "new" "track" "--title" "mcp fodder")
        lines (str/join
               "\n"
               ["{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"
                "not json at all {{{"
                "[1,2,3]"
                "5"
                "null"
                "\"a bare string\""
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"tik_explain\",\"arguments\":\"not-a-map\"}}"
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"tik_explain\",\"arguments\":{}}}"
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"tik_assert\",\"arguments\":{\"ticket\":null,\"key\":7,\"value\":[1]}}}"
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\"}}"
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":9}"
                ;; the canary: a well-formed request AFTER all the abuse
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/list\"}"])
        r (sh/sh "bb" "mcp"
                 :in lines :dir repo
                 :env (assoc (into {} (System/getenv))
                             "TIK_ROOT" (str root) "TIK_ACTOR" "fuzz"))]
    (is (zero? (:exit r)) (:err r))
    (is (h/clean-output? (str (:out r) (:err r)))
        "no line stack-traces the session")
    (testing "every emitted line is a valid JSON-RPC object"
      (doseq [line (remove str/blank? (str/split-lines (:out r)))]
        (is (re-find #"\"jsonrpc\":\"2\.0\"" line) line)))
    (testing "the canary after the abuse still gets its answer"
      (is (re-find #"\"id\":99.*tik_board" (:out r))
          "the session survived every hostile line"))
    (testing "a garbage line answers with a parse error, not silence"
      (is (re-find #"-32700|parse error" (:out r))))))

;; ---------------------- the run-argv in-process seam

(defn- with-store
  "Run f with the CLI's private root accessor pinned to a temp dir, so
  run-argv calls resolve an isolated store with no TIK_ROOT env."
  [f]
  (let [root (h/temp-dir! "tik-runargv")]
    (h/with-cli-root root f)))

(deftest mcp_dispatches_in_process_the_way_the_binary_does
  ;; `tik mcp` is a first-class command, not a bb-only task: the dispatch
  ;; resolves tik.mcp/-main and runs the same stdio loop the native image
  ;; exposes (tik.main force-requires tik.mcp so the resolve succeeds
  ;; there too). Drive one handshake line plus a canary through run-argv
  ;; over a bound *in* and read the JSON-RPC answers back.
  (with-store
    (fn []
      (binding [*in* (java.io.BufferedReader.
                      (java.io.StringReader.
                       (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"
                            "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/list\"}\n")))]
        (let [r (tik.cli/run-argv ["mcp"])]
          (is (zero? (:exit r)) (:err r))
          (is (re-find #"\"id\":1" (:out r)) "the initialize handshake answered")
          (is (re-find #"\"id\":99.*tik_board" (:out r))
              "tools/list answered on the same in-process loop"))))))

(defspec run_argv_is_total_over_arbitrary_argv n
  ;; the in-process entry point the MCP server rides on: whatever an
  ;; embedder passes — empty, garbage tokens, unknown commands — it
  ;; must ANSWER with {:exit :out :err}, never throw, never write to
  ;; the real stdout (that would corrupt a host's JSON-RPC stream)
  (prop/for-all [argv (gen/vector
                       (gen/one-of [gen/string
                                    (gen/elements ["ls" "explain" "set"
                                                   "agent" "status" "--edn"
                                                   "--actor" "next"])])
                       0 5)]
    (with-store
      (fn []
        (let [leaked (java.io.StringWriter.)
              r (binding [*out* leaked] (tik.cli/run-argv argv))]
          (and (map? r)
               (integer? (:exit r))
               (string? (:out r))
               (string? (:err r))
               (zero? (.length (.getBuffer leaked)))))))))

(deftest run_argv_rejects_non_string_argv_cleanly
  ;; a non-string element used to reach parse-args and raise a raw NPE
  ;; out of the runner's return contract; now it is a clean exit-1
  (with-store
    (fn []
      (doseq [argv [[nil] ["ls" nil] [42] [["ls"]] "not-a-seq" [:ls]]]
        (let [r (tik.cli/run-argv argv)]
          (is (map? r) (pr-str argv))
          (is (= 1 (:exit r)) (pr-str argv))
          (is (str/blank? (:out r)) (pr-str argv)))))))

(deftest run_argv_traps_every_exit_path
  ;; success, a die (unknown id -> 1), and a gated refusal (-> 3) all
  ;; come back as CODES; none terminates or escapes the process
  (with-store
    (fn []
      (System/setProperty "user.name" "tester")
      (is (zero? (:exit (tik.cli/run-argv ["new" "track" "--title" "t"]))))
      (is (= 1 (:exit (tik.cli/run-argv ["explain" "nomatch"]))))
      (let [id (->> (:out (tik.cli/run-argv ["ls" "--edn"]))
                    (re-seq #"[0-9a-f]{8}-[0-9a-f-]{27}")
                    first)]
        (is (= 3 (:exit (tik.cli/run-argv
                         ["agent" "set" id "x=1" "--actor" "outsider"]))))))))

(deftest run_argv_is_concurrency_safe
  ;; each call binds its OWN *out*/*err*/*exit-fn* (thread-local), so
  ;; parallel embedders never cross output or exit codes — only the
  ;; shared derived-cache atom is touched concurrently, and it is
  ;; content-addressed so it cannot serve a wrong answer
  (with-store
    (fn []
      (System/setProperty "user.name" "tester")
      (dotimes [i 8]
        (tik.cli/run-argv ["new" "track" "--title" (str "ticket-" i)]))
      (let [results (->> (range 24)
                         (mapv (fn [_] (future (tik.cli/run-argv ["ls" "--edn"]))))
                         (mapv deref))]
        (is (every? #(zero? (:exit %)) results))
        (is (every? #(str/includes? (:out %) "ticket-0") results))
        (is (every? #(str/blank? (:err %)) results)
            "no call's stderr bled into another's")))))

(deftest hostile_link_rels_never_crash_a_render
  ;; mint does not constrain fact-path element types, so a signed
  ;; [:link <non-keyword>] fact is a valid event. The porcelain renders a
  ;; link's rel, and a raw (name 42) would cast-crash — poisoning the
  ;; whole board, since `ls --long` renders every ticket. Every render
  ;; must tolerate any rel.
  (let [{:keys [root store]} (h/temp-store!)
        s store
        ticket (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")]
    (h/seed-ticket! s {:ticket ticket :at t :title "linky"})
    ;; only rels the kernel will actually STORE — a double is refused at
    ;; mint by the canonical emitter, so it never reaches a render
    (doseq [[i rel] (map-indexed vector [42 true "str" :depends-on])]
      (store/append! s (event/assert-fact
                        {:ticket ticket :actor "seb"
                         :at (.plusSeconds t (inc i))
                         :parents (tik.dag/heads (store/events s ticket))
                         :path [:link rel] :value "deadbeef"})))
    (h/with-cli-root root
      (fn []
        (doseq [argv [["status" (str ticket)] ["ls" "--long"] ["ls"]]]
          (let [r (tik.cli/run-argv argv)]
            (is (map? r) (pr-str argv))
            (is (h/clean-output? (str (:out r) (:err r)))
                (str (pr-str argv) "\n" (:err r)))))))))

(deftest diff_k_argument_fails_well
  ;; `diff <id> <k>` rolls back k trailing events; k came from argv via a
  ;; raw Long/parseLong fed to subvec, so a non-number, negative, or
  ;; huge k was a NumberFormat/IndexOutOfBounds surfaced as "a bug in
  ;; tik". Now each is a clean message (or, for a valid k past the log,
  ;; clamped).
  (let [{:keys [root store]} (h/temp-store!)
        id (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")]
    (h/seed-ticket! store {:ticket id :at t :title "difffodder"})
    (h/with-cli-root root
      (fn []
        (doseq [[k clean-exit?] [["-1" false] ["abc" false] ["" false]
                                 ["999999999999999999999" false]
                                 ["0" true] ["1" true] ["500" true]]]
          (let [r (tik.cli/run-argv ["diff" (str id) k])]
            (is ((if clean-exit? zero? #(= 1 %)) (:exit r))
                (str "k=" (pr-str k) " " (:err r)))
            (is (h/clean-output? (str (:out r) (:err r)))
                (str "k=" (pr-str k) "\n" (:err r)))))))))

(deftest hostile_process_field_derives_cleanly_never_casts
  ;; the event body is an unconstrained map (:map-of :any :any), so a
  ;; signed create event can carry a non-name :ticket/process. A
  ;; single-ticket lens must surface that as a clean derivation error
  ;; (ex-info -> message, exit 1), never a raw ClassCast reported as a
  ;; bug; ls keeps isolating it per ticket and still shows the healthy.
  (let [{:keys [root store]} (h/temp-store!)
        s store
        healthy (random-uuid) hostile (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")]
    (h/seed-ticket! s {:ticket healthy :at t :title "ok"})
    (h/seed-ticket! s {:ticket hostile :at t :title "bad" :process 42})
    (h/with-cli-root root
      (fn []
        (doseq [argv [["status" (str hostile)]
                      ["explain" (str hostile)]
                      ["whatif" (str hostile) "x=1"]]]
          (let [r (tik.cli/run-argv argv)]
            (is (= 1 (:exit r)) (pr-str argv))
            (is (h/clean-output? (str (:out r) (:err r)))
                (str (pr-str argv) "\n" (:err r)))
            (is (re-find #"process is not a name" (:err r))
                (str (pr-str argv) "\n" (:err r)))))
        ;; the WHOLE-STORE lenses must isolate the poison per ticket — a
        ;; single unreadable ticket must never hide the healthy from the
        ;; board (fast path) OR from a selector query (slow path)
        (doseq [argv [["ls"]
                      ["ls" "--where" "stage=:open"]
                      ["ls" "--all" "--where" "stage=:open"]
                      ["search" "ok"]]]
          (let [r (tik.cli/run-argv argv)]
            (is (zero? (:exit r)) (pr-str argv))
            (is (re-find #"ok" (:out r))
                (str (pr-str argv) " — the healthy ticket still lists"))
            (is (h/clean-output? (str (:out r) (:err r)))
                (str (pr-str argv) "\n" (:err r)))))))))

(deftest every_whole_store_lens_isolates_a_poison
  ;; one unreadable ticket must never take down a WHOLE-STORE view — the
  ;; invariant ls/next honor, now shared by every consumer of
  ;; all-ticket-ctx (roles, plan, board, work, selectors). A poison ticket
  ;; is skipped and named on stderr; the command still succeeds.
  (let [{:keys [root store]} (h/temp-store!)
        healthy (random-uuid) hostile (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")
        board-html (str (io/file root "b.html"))]
    (h/seed-ticket! store {:ticket healthy :at t :title "ok"})
    (h/seed-ticket! store {:ticket hostile :at t :title "bad" :process 42})
    (h/with-cli-root root
      (fn []
        (doseq [argv [["roles"]
                      ["plan"]
                      ["board" board-html]
                      ["work" "week" "--actor" "seb"]
                      ["ls" "--where" "stage=:open"]
                      ["ls" "--all" "--where" "stage=:open"]
                      ["next" "--actor" "seb"]]]
          (let [r (tik.cli/run-argv argv)]
            (is (zero? (:exit r)) (str (pr-str argv) "\n" (:err r)))
            (is (h/clean-output? (str (:out r) (:err r)))
                (str (pr-str argv) "\n" (:err r)))
            (is (re-find #"skipping" (:err r))
                (str (pr-str argv) " should name the skipped poison"))))))))

(deftest garbage_time_args_fail_well
  ;; whatif's +duration, --at, and work week --from/--to parse ISO-8601
  ;; via Duration/Instant, which throw DateTimeParseException — an
  ;; ordinary exception, not ex-info. A natural typo (a wall-clock +2d, a
  ;; bare date) must be a clean, example-carrying message + exit 1, never
  ;; surfaced as 'a bug in tik'.
  (let [{:keys [root store]} (h/temp-store!)
        s store
        id (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")]
    (h/seed-ticket! s {:ticket id :at t :title "t"})
    (h/with-cli-root root
      (fn []
        (doseq [argv [["whatif" (str id) "+2d"]
                      ["whatif" (str id) "+GARBAGE"]
                      ["whatif" (str id) "+"]
                      ["whatif" (str id) "+PT99999999999999999H"]
                      ["status" (str id) "--at" "NOTADATE"]
                      ["work" "week" "--actor" "seb" "--from" "NOPE"]]]
          (let [r (tik.cli/run-argv argv)]
            (is (= 1 (:exit r)) (pr-str argv))
            (is (re-find #"ISO-8601" (:err r))
                (str (pr-str argv) "\n" (:err r)))
            (is (h/clean-output? (str (:out r) (:err r)))
                (str (pr-str argv) "\n" (:err r)))))
        (testing "the valid forms still work"
          (is (zero? (:exit (tik.cli/run-argv ["whatif" (str id) "+PT48H"]))))
          (is (zero? (:exit (tik.cli/run-argv
                             ["status" (str id) "--at"
                              "2026-06-01T00:00:00Z"])))))))))

(deftest every_edn_output_is_readable_edn
  ;; --edn output is a machine surface: every form it prints must read
  ;; back through the format's own readers. A type without a
  ;; print-method (java.time.Duration, LocalDate, File) prn's as
  ;; #object[…<identity-hash>…] — silently corrupting the stream — so
  ;; the rule is stringify-at-the-boundary (work/draft does) or carry a
  ;; canonical literal (Instant does, via the porcelain print-method).
  ;; This pins the invariant mechanically instead of type-by-type.
  (let [{:keys [root store]} (h/temp-store!)
        id (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")]
    (h/seed-ticket! store {:ticket id :at t :title "edn fodder"})
    ;; a time-typed fact (the class that used to print as #object) and a link
    (doseq [[i [path value]] (map-indexed vector
                                          [[[:when] t]
                                           [[:link :depends-on] "deadbeef"]])]
      (store/append! store (event/assert-fact
                            {:ticket id :actor "seb"
                             :at (.plusSeconds t (inc i))
                             :parents (tik.dag/heads (store/events store id))
                             :path path :value value})))
    (h/with-cli-root root
      (fn []
        (doseq [argv [["ls" "--edn"]
                      ["explain" (str id) "--edn"]
                      ["causal" (str id) "--edn"]
                      ["next" "--edn"]
                      ["work" "week" "--actor" "seb" "--edn"]
                      ["work" "cost" "--edn"]]]
          (let [r (tik.cli/run-argv argv)]
            (is (zero? (:exit r)) (pr-str argv))
            (doseq [line (remove str/blank? (str/split-lines (:out r)))]
              (is (try (canonical/parse line) true
                       (catch Exception _ false))
                  (str (pr-str argv) " printed unreadable EDN:\n" line)))))))))

(deftest agent_set_grounds_values_like_human_set
  ;; an agent and a human must ground the same key=value identically:
  ;; agent-set uses typed-value (declared-string aware), so a bare commit
  ;; ref stays a string for both — not keywordized for the agent path.
  (let [{:keys [root store]} (h/temp-store!)]
    (.mkdirs (io/file root "processes"))
    (spit (io/file root "processes" "sr.edn")
          (str "{:process/id :sr :process/version 1 :process/guard-vocab 1"
               " :process/facts {[:commit] :string}"
               " :process/stages [{:stage/id :open :guards []}"
               " {:stage/id :done :after [:open] :guards [[:fact [:commit]]]}]}"))
    (h/with-cli-root root
      (fn []
        (tik.cli/run-argv ["new" "sr" "--title" "t"])
        (let [id (re-find #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                          (:out (tik.cli/run-argv ["ls" "--edn"])))
              r (tik.cli/run-argv ["agent" "set" id "commit=a051932" "--actor" "seb"])
              ev (->> (store/events store (java.util.UUID/fromString id))
                      (filter #(= [:commit] (get-in % [:event/body :fact/path])))
                      first)]
          (is (zero? (:exit r)) (:err r))
          (is (= "a051932" (get-in ev [:event/body :fact/value]))
              "agent-set keeps a declared-string value a string, like tik set"))))))

(deftest every_json_output_is_readable_json
  ;; the --format json surface is machine-facing too: every line must
  ;; parse as JSON. json-safe must coerce what cheshire cannot encode
  ;; (Instant -> ISO string, set -> array, non-name key -> string), so a
  ;; time-typed fact or a set-valued reason never emits invalid JSON.
  (let [{:keys [root store]} (h/temp-store!)
        id (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")
        parse-json (requiring-resolve 'cheshire.core/parse-string)]
    (h/seed-ticket! store {:ticket id :at t :title "json fodder"})
    (doseq [[i [path value]] (map-indexed vector
                                          [[[:when] t]
                                           [[:link :depends-on] "deadbeef"]])]
      (store/append! store (event/assert-fact
                            {:ticket id :actor "seb"
                             :at (.plusSeconds t (inc i))
                             :parents (tik.dag/heads (store/events store id))
                             :path path :value value})))
    (h/with-cli-root root
      (fn []
        (doseq [argv [["ls" "--format" "json"]
                      ["explain" (str id) "--format" "json"]
                      ["next" "--format" "json"]
                      ["ls" "--all" "--where" "stage=:open" "--format" "json"]
                      ["work" "week" "--actor" "seb" "--format" "json"]
                      ["work" "cost" "--format" "json"]
                      ["status" (str id) "--format" "json"]
                      ["log" (str id) "--format" "json"]
                      ;; the agent verbs are the surface the MCP tools ride
                      ["agent" "actions" (str id) "--actor" "seb" "--format" "json"]]]
          (let [r (tik.cli/run-argv argv)]
            (is (zero? (:exit r)) (pr-str argv))
            (doseq [line (remove str/blank? (str/split-lines (:out r)))]
              (is (try (parse-json line) true
                       (catch Exception _ false))
                  (str (pr-str argv) " printed invalid JSON:\n" line)))))))))

(deftest agent_verbs_speak_json_on_request
  ;; MCP tool payloads route through --format json; the gated agent verbs
  ;; must answer in JSON on both paths — an admitted assert as an ok
  ;; object on stdout, a refused one as a verdict object on stderr with
  ;; exit 3 (a refusal is an error in every format).
  (let [{:keys [root]} (h/temp-store!)
        parse-json (requiring-resolve 'cheshire.core/parse-string)]
    (h/with-cli-root root
      (fn []
        (tik.cli/run-argv ["new" "track" "--title" "agent json"])
        (let [id (->> (:out (tik.cli/run-argv ["ls" "--edn"]))
                      (re-seq #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                      first)
              actions (parse-json (:out (tik.cli/run-argv
                                         ["agent" "actions" id "--actor" "seb"
                                          "--format" "json"])))
              ;; whatever set-action the frontier admits for seb, if any
              set-path (->> (get actions "admissible")
                            (keep (fn [a] (let [[op path] (get a "action")]
                                            (when (= "set" op) path))))
                            first)]
          (testing "actions answers with a JSON object"
            (is (vector? (get actions "admissible"))))
          (when set-path
            (testing "an admitted assert answers with an ok object on stdout"
              (let [k (str/join "." set-path)
                    ok (tik.cli/run-argv ["agent" "set" id (str k "=x")
                                          "--actor" "seb" "--format" "json"])]
                (is (zero? (:exit ok)) (:err ok))
                (is (true? (get (parse-json (:out ok)) "ok"))))))
          (testing "a refused assert answers with a JSON verdict on stderr, exit 3"
            (let [refused (tik.cli/run-argv
                           ["agent" "set" id "definitely-not-a-frontier-fact=x"
                            "--actor" "nobody" "--format" "json"])]
              (is (= 3 (:exit refused)))
              (is (str/blank? (:out refused)) "no data on stdout for a refusal")
              (let [v (parse-json (:err refused))]
                (is (contains? v "refused"))
                (is (vector? (get v "admissible")))))))))))

;; ------------------- the OIDC bridge over a hostile identity provider

(defn- b64url [s]
  (.encodeToString (java.util.Base64/getUrlEncoder) (.getBytes ^String s "UTF-8")))

(defspec decode_jwt_payload_is_total_over_hostile_tokens n
  ;; the bridge takes the token endpoint's word for the JWT payload
  ;; (ns docstring), but a broken or hostile IdP can return anything
  ;; where an id_token should be — no dots, non-base64url, base64 of
  ;; non-JSON. Decoding must fail well (the IdP's fault, an ex-info),
  ;; never a raw NPE or decoder/parser throw reworded as a tik bug
  (prop/for-all [token (gen/one-of
                        [gen/string
                         (gen/fmap #(str "h." % ".s") gen/string)
                         (gen/fmap #(str "h." (b64url %) ".s") gen/string)])]
    (fails-well? #(oidc/decode-jwt-payload token))))

(deftest oidc_flows_fail_well_over_hostile_idp_bodies
  ;; discover / password-flow / token->binding parse whatever the IdP
  ;; sends; a non-JSON body, a JSON non-object, or a token response
  ;; missing id_token must all be data-carrying rejections
  (let [bodies ["not json at all {{{" "[1,2,3]" "5" "null" ""
                "{\"token_endpoint\":42}" "\"a bare string\""]]
    (testing "discover over hostile discovery documents"
      (doseq [body bodies]
        (is (fails-well? #(oidc/discover (constantly body) "https://idp"))
            body)))
    (testing "password-flow over hostile token responses"
      (doseq [body bodies]
        (is (fails-well?
             #(oidc/password-flow (fn [_ _] body) {:token "t"} "c" "u" "p"))
            body)))
    (testing "a valid JSON response with no id_token names the IdP error"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"no id_token"
           (oidc/token->binding {:error "access_denied"
                                 :error_description "user said no"}
                                {:actor "a" :public-key "k"
                                 :issuer "https://idp"}))))
    (testing "a non-JSON body specifically throws an ex-info, not a raw parse error"
      (is (thrown? clojure.lang.ExceptionInfo
                   (oidc/discover (constantly "definitely not json")
                                  "https://idp"))))))

(deftest oidc_device_flow_survives_a_hostile_poll
  ;; device-flow's poll re-parses the token endpoint each tick; a
  ;; hostile endpoint that flips to garbage mid-poll must fail well,
  ;; and the injected sleep keeps the test instant
  (let [responses (atom ["{\"interval\":0,\"device_code\":\"d\",\"user_code\":\"U\"}"
                         "garbage not json"])
        post (fn [_ _] (let [[h & t] @responses]
                         (reset! responses (or t ["{}"])) h))
        {:keys [poll]} (oidc/device-flow post {:device "d" :token "t"}
                                         "client" (fn [_] nil))]
    (is (fails-well? poll)))
  (testing "a non-numeric or negative :interval is the IdP's word too —
            it must not crash the multiply or hand sleep a bad timeout"
    (doseq [start ["{\"device_code\":\"d\",\"interval\":\"fast\"}"
                   "{\"device_code\":\"d\",\"interval\":-5}"
                   "{\"device_code\":\"d\",\"interval\":{\"nested\":1}}"
                   "{\"device_code\":\"d\"}"]]
      (let [sleeps (atom [])
            post (fn [_ _] start)
            df (oidc/device-flow post {:device "d" :token "t"} "client"
                                 (fn [ms] (swap! sleeps conj ms)))]
        (is (map? df) start)
        ;; the poll's injected sleep only ever sees a non-negative number
        ((:poll df))
        (is (every? #(and (number? %) (not (neg? %))) @sleeps)
            (str start " -> " (pr-str @sleeps)))))))
