;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.stage
  "Stage graph evaluation. Stage is DERIVED, never stored.

  v4 collapses what were three traversals (state reduce, per-prefix sticky
  replay, timeline) into ONE left fold: `evolve`. The accumulator carries
  {:state :reached :sticky-ever :timeline}; sticky milestones are a
  monotone set carried through the fold, replacing a prefix replay that
  re-derived the whole history per event. State, sticky semantics, the
  notifier's timeline, and status are now views of the same fold — one
  thing to test.

  What the fold costs, stated honestly because someone will size a
  deployment against it: `evolve` runs a full `reached-set` fixpoint per
  event, because sticky makes the reached set path-dependent — it is a
  function of the trajectory, not of the final state, so no per-event
  work can be skipped in general. Each fixpoint is bounded by
  |stages| sweeps over |stages| stages, and each guard's fact lookup is
  memoized per fixpoint (tik.guard/fact-status*) and served from the
  reducer's :writes / :parents indexes rather than a log scan. So the
  fold is linear in events and polynomial in the SIZE OF THE PROCESS,
  which is authored and small — not quadratic in history length, which
  is unbounded. Anything that reintroduces a per-call whole-log scan
  puts the quadratic back.

  Internal vocabulary (lattice, maximal, fixpoint) stays internal; user-
  facing surfaces say 'process graph' and 'current stage'."
  (:require [clojure.set :as set]
            [tik.guard :as guard]
            [tik.reduce :as red]))

(defn- stage-index [process]
  (into {} (map (juxt :stage/id identity)) (:process/stages process)))

(defn sticky-ids [process]
  (into #{} (comp (filter :stage/sticky?) (map :stage/id))
        (:process/stages process)))

(defn- guards-ok? [stage ctx]
  (every? #(:satisfied? (guard/eval-guard % ctx)) (:guards stage [])))

(defn reached-set
  "Fixpoint of reached stage ids at one (state, now), seeded with `base`
  (the sticky carry). Deterministic; ADR 0005's stratification lint is what
  makes determinism provable rather than incidental in the presence of
  [:not [:stage-reached ...]].

  The SYNCHRONOUS SWEEP is normative, not an implementation choice: each
  iteration evaluates every stage against the sweep-start snapshot and
  adds all enabled stages at once. Firing one stage at a time is
  order-dependent even on linter-clean processes (a later stratum
  negating an earlier one can jump the queue) — spec/ChaoticFixpoint.tla
  exhibits the counterexample; the corpus case sweep-order-negation pins
  the correct answer."
  ([process state now roles] (reached-set process state now roles #{}))
  ([process state now roles base]
   (let [memo (volatile! {})]
     (loop [reached base]
       (let [ctx {:state state :process process :now now
                  :roles roles :reached reached :fact-memo memo}
             r' (into reached
                      (for [s (:process/stages process)
                            :when (and (not (contains? reached (:stage/id s)))
                                       (every? reached (:after s []))
                                       (guards-ok? s ctx))]
                        (:stage/id s)))]
         (if (= r' reached) reached (recur r')))))))

(defn- not-after
  "The earlier of a claimed instant and the read's now. Instants are
  Comparable on every host, so this stays free of platform interop."
  [at now]
  (if (pos? (compare at now)) now at))

(defn evolve
  "THE fold. One pass over the deduped, ordered event set, producing
  {:state ticket-state
   :reached #{...}          ; at the time of the last event
   :sticky-ever #{...}      ; sticky stages ever reached (monotone)
   :timeline [{:event-id :at :reached} ...]}  ; the notifier's entire input

  :sticky-ever is monotone IN FOLD POSITION, not in the event set. It
  accumulates what the fixpoint yielded at each prefix, so it is a
  function of the trajectory rather than of the final state. A merge that
  brings in a backdated event splices a new prefix into the middle of
  that trajectory and every later prefix is re-derived under different
  facts — which can REMOVE a sticky reach this replica derived a moment
  ago. Convergence is unaffected (the same event set always derives the
  same answer); what does not hold is that a reach observed before a sync
  survives it. Callers that act on a reach — notifiers, effect pipelines
  — must record having acted as their own fact rather than assume the
  derivation keeps re-deriving it.

  Each step evaluates guards at the event's own claimed :at, CLAMPED to
  the read's `now`: no step may act as though more time has passed than
  actually has. An event dated in the future would otherwise satisfy
  :elapsed-since at its claimed instant, and on a sticky stage the fold
  would carry that reach forward permanently — a milestone reachable by
  writing a date, unretractable by any later evidence, and unfixable at
  our own write boundary because such an event can arrive already
  postdated from another replica. Clamping keeps derivation a pure
  function of (events, now) and merely refuses to read the future early:
  as `now` advances past the claimed :at, the clamp lifts on its own and
  the event is evaluated at the instant it claims. Logs whose events are
  all dated at or before the read derive exactly as before."
  [process events roles now]
  (let [sticky (sticky-ids process)]
    (reduce
     (fn [acc e]
       (let [state (red/apply-event (:state acc) e)
             at (:event/at e)
             reached (reached-set process state (not-after at now) roles
                                  (:sticky-ever acc))]
         {:state state
          :reached reached
          :sticky-ever (into (:sticky-ever acc) (set/intersection reached sticky))
          ;; :at is the event's own claim, unclamped — the timeline
          ;; records what the log says, not what the read believed
          :timeline (conj (:timeline acc)
                          {:event-id (:event/id e) :at at :reached reached})}))
     {:state red/empty-state :reached #{} :sticky-ever #{} :timeline []}
     (red/ordered events))))

(defn trace-sweeps
  "The fixpoint with its working shown: one entry per sweep, recording
  which stages were evaluated against the sweep-start snapshot, every
  guard's verdict, and what the sweep added. Pure — the process
  debugger's data source, and executable documentation of the
  normative synchronous-sweep semantics (ADR 0005/0018)."
  [process state now roles]
  (loop [reached #{} sweeps []]
    (let [ctx {:state state :process process :now now
               :roles roles :reached reached
               :fact-memo (volatile! {})}
          evaluated (for [s (:process/stages process)
                          :when (not (contains? reached (:stage/id s)))]
                      {:stage (:stage/id s)
                       :prereqs-met? (every? reached (:after s []))
                       :guards (when (every? reached (:after s []))
                                 (mapv (fn [g]
                                         {:guard g
                                          :verdict (guard/eval-guard g ctx)})
                                       (:guards s [])))})
          added (into #{}
                      (comp (filter :prereqs-met?)
                            (filter #(every? (comp :satisfied? :verdict)
                                             (:guards %)))
                            (map :stage))
                      evaluated)
          reached' (into reached added)
          sweeps' (conj sweeps {:sweep (inc (count sweeps))
                                :snapshot reached
                                :evaluated (vec evaluated)
                                :added added})]
      (if (= reached' reached)
        {:reached reached :sweeps sweeps'}
        (recur reached' sweeps')))))

(defn effective-reached
  "Reached set at `now` (which may be later than the last event — time alone
  can satisfy :elapsed-since), seeded with the sticky carry from the fold."
  [process events now roles]
  (let [{:keys [state sticky-ever]} (evolve process events roles now)]
    (reached-set process state now roles sticky-ever)))

(defn stage-timeline [process events roles now]
  (:timeline (evolve process events roles now)))

(defn ancestor-closure
  "All stages strictly upstream of stage-id via :after."
  [process stage-id]
  (let [idx (stage-index process)]
    (loop [frontier (set (get-in idx [stage-id :after])) acc #{}]
      (if (empty? frontier)
        acc
        (let [nxt (into #{} (mapcat #(get-in idx [% :after] [])) frontier)]
          (recur (set/difference nxt acc) (into acc frontier)))))))

(defn downstream
  "All stages strictly downstream — what this stage blocks. Feeds explain.
  (Not named `descendants`: that shadows clojure.core's hierarchy fn.)"
  [process stage-id]
  (into #{}
        (comp (map :stage/id)
              (filter #(contains? (ancestor-closure process %) stage-id)))
        (:process/stages process)))

(defn current-stages
  "Maximal reached stages — the ticket's current position(s). Conditional
  prerequisites are guards, not edges, so parallel branch tips can both be
  current; display layers may collapse."
  [process reached]
  (let [dominated (into #{} (mapcat #(ancestor-closure process %)) reached)]
    (set/difference reached dominated)))
