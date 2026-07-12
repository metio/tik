;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.plan
  "The plan lens: pure graph derivations over cross-ticket dependency
  links. A plan is NOT a stored program to execute (§19 rejects the
  scheduler) — it is a DERIVED reading of a set of tickets, their
  `:depends-on` edges, and which are settled: what is done, ready,
  blocked, or caught in a dependency cycle, plus the critical path and
  each item's downstream unlock impact. A Gantt chart you maintain rots;
  this re-derives from current facts every read.

  Everything here is a pure function of the pair (edges, settled), and
  TOTAL over hostile input — a `:depends-on` fact can be hand-set to
  form a cycle or point nowhere, so cycle-safety is a hard requirement,
  not a nicety. That totality is what lets the plan lens carry a TLA+
  model and a conformance bridge.

  Input shape:
  - `edges`   : {node -> #{prerequisite nodes}} — node depends-on each.
  - `settled` : #{nodes that are done}.
  Prerequisites may be nodes absent from `edges` (dangling): they are
  ordinary nodes with no prerequisites of their own, blocking unless
  settled.")

(defn nodes
  "Every node mentioned — as a key or as a prerequisite."
  [edges]
  (into (set (keys edges)) cat (vals edges)))

(defn prereqs [edges n] (get edges n #{}))

(defn dependents
  "Reverse edges: node -> the set of nodes that depend on it."
  [edges]
  (reduce-kv (fn [m n ps]
               (reduce (fn [m p] (update m p (fnil conj #{}) n)) m ps))
             {}
             edges))

(defn- reachable
  "All prerequisites reachable from `n` (transitive), cycle-safe via a
  seen-set; excludes `n` itself unless a cycle returns to it."
  [edges n]
  (loop [stack (vec (prereqs edges n)) seen #{}]
    (if (empty? stack)
      seen
      (let [x (peek stack) stack (pop stack)]
        (if (contains? seen x)
          (recur stack seen)
          (recur (into stack (prereqs edges x)) (conj seen x)))))))

(defn in-cycle?
  "Is `n` part of a dependency cycle — reachable from itself?"
  [edges n]
  (contains? (reachable edges n) n))

(defn cyclic-nodes
  "Every node caught in a dependency cycle (a deadlock: none can ever
  proceed). The authoring-lint signal — like stage stratification."
  [edges]
  (into #{} (filter #(in-cycle? edges %)) (nodes edges)))

(defn blocked?
  "Does `n` have a direct prerequisite that is not settled? A settled
  prerequisite is DONE, so its own prerequisites are irrelevant — which
  is exactly why direct-blocking equals transitive-blocking here, and
  why the check is trivially cycle-safe."
  [edges settled n]
  (boolean (some #(not (contains? settled %)) (prereqs edges n))))

(defn status
  "One node's derived plan status: :done | :cyclic | :blocked | :ready.
  Cycles are reported before blocking so a deadlock is never mislabeled
  as ordinary waiting."
  [edges settled n]
  (cond
    (contains? settled n)      :done
    (in-cycle? edges n)        :cyclic
    (blocked? edges settled n) :blocked
    :else                      :ready))

(defn ready
  "Nodes ready to work now: not settled, not cyclic, no unsettled
  prerequisite."
  [edges settled]
  (into #{} (filter #(= :ready (status edges settled %))) (nodes edges)))

(defn unlocks
  "How many currently-not-ready nodes would become ready if `n` were
  settled — the downstream impact that ranks 'what to do next'. A node
  counts if settling `n` removes its LAST unsettled prerequisite."
  [edges settled n]
  (let [settled' (conj settled n)]
    (count (for [d (get (dependents edges) n)
                 :when (and (not (contains? settled d))
                            (blocked? edges settled d)
                            (not (blocked? edges settled' d))
                            (not (in-cycle? edges d)))]
             d))))

(defn longest-paths
  "node -> the longest upstream path ending at that node, over ANY graph
  given as a (node -> parent-nodes) fn — the one memoized longest-path
  recursion shared by `critical-path` (prerequisite chains) and the draw
  lens (stage depth). Cycle-safe two ways, because callers differ: a
  `skip?` predicate excludes nodes entirely (critical-path drops settled
  and cyclic nodes, making its walk a DAG), and a seen-set truncates any
  back-edge that still remains (draw must place cyclic stages somewhere
  rather than not draw them). Skipped nodes get no entry."
  [parents-of nodes skip?]
  (let [memo (volatile! {})]
    (letfn [(lp [n seen]
              (or (get @memo n)
                  (if (contains? seen n)
                    [n]                          ; back-edge: truncate here
                    (let [seen' (conj seen n)
                          best (->> (parents-of n)
                                    (remove skip?)
                                    (map #(lp % seen'))
                                    (sort-by count >)
                                    first)
                          path (conj (vec best) n)]
                      (vswap! memo assoc n path)
                      path))))]
      (into {} (map (fn [n] [n (lp n #{})])) (remove skip? nodes)))))

(defn critical-path
  "The longest chain of UNSETTLED, acyclic nodes along dependency edges —
  the sequence that gates total completion (its length is the minimum
  number of sequential steps remaining). Returned deepest-prerequisite
  first. Nodes in cycles are excluded; the walk is therefore over a DAG."
  [edges settled]
  (let [cyc (cyclic-nodes edges)
        skip? (fn [n] (or (contains? settled n) (contains? cyc n)))]
    (->> (vals (longest-paths #(prereqs edges %) (nodes edges) skip?))
         (sort-by count >)
         first
         vec)))

(defn summary
  "The whole plan as data — the single derivation every renderer (CLI,
  HTML) draws from. Pure over (edges, settled)."
  [edges settled]
  (let [ns (nodes edges)
        by-status (group-by #(status edges settled %) ns)
        cp (critical-path edges settled)]
    {:nodes ns
     :status (into {} (map (juxt identity #(status edges settled %))) ns)
     :done (set (:done by-status))
     :ready (set (:ready by-status))
     :blocked (set (:blocked by-status))
     :cyclic (set (:cyclic by-status))
     :critical-path cp
     :unlocks (into {} (map (juxt identity #(unlocks edges settled %))) ns)}))
