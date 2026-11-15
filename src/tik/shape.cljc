;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.shape
  "Argument contracts at the kernel's public boundary.

  Every public kernel function returns a value or throws ex-info,
  whatever it is handed (the fail-well contract tik.fuzz-test and
  tik.totality-test enforce). The checks here are how a boundary states
  what it accepts: each returns its argument unchanged when it conforms
  and otherwise throws one ex-info carrying
  {:reason :kernel/malformed-argument :argument <name> :expected <kw>}.

  Kernel data is EDN data, and EDN has no sorted collections. A sorted
  map or set is therefore rejected wherever a plain one is expected:
  looking a keyword up in (sorted-set 1 2) throws a ClassCastException
  from the comparator, which no amount of care at the lookup site can
  avoid.

  Checks are shallow on purpose. Each one covers the shape the receiving
  function itself destructures, seqs, counts or compares; deeper values
  are the business of whichever function consumes them, and every such
  function carries its own check. Validity in the schema sense (a uuid
  ticket id, a known event type) stays with tik.event and tik.process:
  a shape check only rules out what would otherwise escape as a raw
  host exception."
  #?(:clj (:import (java.time Instant))))

(defn reject
  "Throw the kernel's one malformed-argument error."
  [argument expected value]
  (throw (ex-info (str "malformed argument " (pr-str argument)
                       ": expected " (pr-str expected))
                  {:reason :kernel/malformed-argument
                   :argument argument
                   :expected expected
                   :value value})))

(defn plain-map?
  "A map that is not sorted."
  [x]
  (and (map? x) (not (sorted? x))))

(defn plain-coll?
  "A collection that is not sorted."
  [x]
  (and (coll? x) (not (sorted? x))))

(defn plain-set?
  "A set that is not sorted."
  [x]
  (and (set? x) (not (sorted? x))))

(defn instant?
  "The one time type kernel data carries."
  [x]
  #?(:clj (instance? Instant x)
     :cljs (inst? x)))

(defn plain-map
  "`x` when it is nil or a plain map."
  [argument x]
  (if (or (nil? x) (plain-map? x)) x (reject argument :map x)))

(defn plain-coll
  "`x` when it is nil or a plain collection."
  [argument x]
  (if (or (nil? x) (plain-coll? x)) x (reject argument :collection x)))

(defn plain-set
  "`x` when it is nil or a plain set."
  [argument x]
  (if (or (nil? x) (plain-set? x)) x (reject argument :set x)))

(defn maps
  "`xs` when it is nil or a plain collection of plain maps."
  [argument xs]
  (plain-coll argument xs)
  (doseq [x xs]
    (when-not (plain-map? x) (reject argument :maps x)))
  xs)

(defn instant
  "`x` when it is an instant."
  [argument x]
  (if (instant? x) x (reject argument :instant x)))

(defn optional-instant
  "`x` when it is nil or an instant."
  [argument x]
  (if (or (nil? x) (instant? x)) x (reject argument :instant x)))

(defn string
  "`x` when it is a string."
  [argument x]
  (if (string? x) x (reject argument :string x)))

(defn function
  "`x` when it is a function."
  [argument x]
  (if (fn? x) x (reject argument :function x)))

(defn field
  "`k` of `x` when `x` is a plain map, else nil — for lenses that read
  whatever a log holds and decline what is not a map."
  [x k]
  (when (plain-map? x) (get x k)))

;; ---------------------------------------------------------------- events

(defn parent-ids
  "An event's parent ids as a set, or nil when :event/parents is not a
  plain collection of strings. A corrupt or hostile store file that passed the
  fold's entry check but not the schema keeps every DAG walk total; verify
  L0's schema check names the malformation."
  [event]
  (let [p (field event :event/parents)]
    (when (and (plain-coll? p) (not (map? p)) (every? string? p))
      (if (set? p) p (set p)))))

(defn event
  "`e` when it carries what ordering and folding depend on: a plain map
  with a string id, an instant, a keyword type, and a body that is absent
  or a plain map. Parents are not checked here; `parent-ids` reads them
  defensively instead, so a corrupt parents value never makes a whole
  ticket underivable."
  [e]
  (when-not (and (plain-map? e)
                 (string? (:event/id e))
                 (instant? (:event/at e))
                 (keyword? (:event/type e))
                 (let [b (:event/body e)] (or (nil? b) (plain-map? b))))
    (throw (ex-info "malformed event in the log"
                    {:reason :event/malformed :event e})))
  e)

(defn events
  "`es` when it is nil or a plain collection of well-formed events."
  [es]
  (plain-coll :events es)
  (run! event es)
  es)

;; ---------------------------------------------------------------- definitions

(defn- stage-shape? [s]
  (and (plain-map? s)
       (let [after (:after s) guards (:guards s)]
         (and (or (nil? after) (plain-coll? after))
              (or (nil? guards) (plain-coll? guards))))))

(defn process
  "`p` when it is a plain map whose stages (if any) are a plain collection
  of plain stage maps with collection-valued :after and :guards. Guards
  themselves are not checked: tik.guard/eval-guard is total over any
  guard, which is how an unlinted definition still derives."
  [p]
  (plain-map :process p)
  (let [stages (:process/stages p)]
    (when-not (and (or (nil? stages) (plain-coll? stages))
                   (every? stage-shape? stages))
      (reject :process :process-stages stages))
    (plain-map :process-roles (:process/roles p)))
  p)

(defn roles
  "`r` when it is nil or a plain map from role to a plain map whose
  :members is absent or a plain collection."
  [r]
  (plain-map :roles r)
  (doseq [[_ v] r]
    (when-not (and (plain-map? v)
                   (let [m (:members v)] (or (nil? m) (plain-coll? m))))
      (reject :roles :role-members v)))
  r)

(defn state
  "`s` when it is a plain map whose fold indexes are absent or plain maps."
  [s]
  (plain-map :state s)
  (doseq [k [:facts :artifacts :writes :parents :linear-writes]]
    (plain-map :state (get s k)))
  (plain-coll :state (:log s))
  s)
