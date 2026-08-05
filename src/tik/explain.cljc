;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.explain
  "explain: the product surface, specified.

  For every frontier stage, a structured block where EVERY field is derived
  and true — no speculation, ever:
    :stage      the stage id
    :satisfied  guards already met (the checkmarks; earns trust)
    :missing    structured reasons from tik.guard (who can act is in
                :role/unsatisfied; disputes/conflicts carry :by and :note)
    :blocks     downstream stages unreachable until this stage lands
    :hint       authored knowledge link (OKF bundle), if declared

  The same data renders as CLI text (here), web forms (from the schemas in
  the reasons), and MCP task specs whose acceptance criteria ARE the
  guards. English lives in this lens, never in the kernel."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage]))

(defn frontier
  "Stages one step ahead: unreached, prerequisites reached."
  [process reached _ctx]
  (for [s (:process/stages process)
        :when (and (not (contains? reached (:stage/id s)))
                   (every? reached (:after s [])))]
    s))

(defn actionability
  "Rank of a reason by who can act on it RIGHT NOW, ascending: values
  anyone can supply, then corrections, then artifacts, then specific
  people, then attestations, then other stages, then time (nobody can
  act on time). Part of the ADR 0016 contract: :missing is sorted by
  this rank (stably — ties keep guard order), so every renderer shows
  the most actionable step first.

  An :alternatives ranks as its most actionable branch: a choice is
  exactly as reachable as its easiest option, and ranking the whole
  tree last would sort a fact anyone can supply below a wait for the
  clock."
  [{:keys [reason options]}]
  (case reason
    (:fact/missing :fact/mismatch :fact/invalid) 0
    (:fact/retracted :fact/disputed :fact/conflicted) 1
    :artifact/missing 2
    (:role/unsatisfied :role/same-person) 3
    (:attestation/missing :attestation/stale) 4
    :stage/not-reached 5
    :time/not-elapsed 6
    :alternatives (transduce (map actionability) min 9 (apply concat options))
    9))

(declare permanent?)

(defn- permanent-option?
  "An :or branch is dead when any one of its conjoined reasons is."
  [option ctx]
  (some #(permanent? % ctx) option))

(defn permanent?
  "Can NO actor and NO passage of time ever discharge this reason?

  Only three shapes are provably undischargeable from the definition
  and the log alone, and this stays conservative — a false 'impossible'
  tells someone to stop working on a ticket that was merely waiting:

  - :role/unsatisfied for a role with no members. There is no one to
    sign, and no event on this ticket can put anyone there — filling
    the role is an administrative act on the store's register
    (`tik role add`), which is precisely what the reader needs told.
  - :must-not-hold over [:stage-reached S] where S is sticky and
    already reached. Sticky is carried forward by the fold, so no
    appended event takes the reach away and the negation can never
    hold again. (A backdated event arriving by merge can — see
    `tik.stage/evolve` — which is why this is a statement about the
    event set in hand, re-derived on every read like everything else.)
  - :stage/not-reached for a stage that is itself dead, transitively.

  An :alternatives is dead only when every branch is: a choice is
  reachable as long as one option is."
  [{:keys [reason role guard stage options]} {:keys [roles sticky dead] :as ctx}]
  (case reason
    :role/unsatisfied (empty? (get-in roles [role :members]))
    :must-not-hold (and (= :stage-reached (first guard))
                        (contains? sticky (second guard)))
    :stage/not-reached (contains? dead stage)
    :alternatives (every? #(permanent-option? % ctx) options)
    false))

(defn- dead-stages
  "Least fixpoint of stages that can never be reached: a stage is dead
  when a prerequisite is dead, or when its own guards fail for a
  permanent reason. Monotone in the dead set, so it terminates in at
  most |stages| rounds."
  [process reached roles guard-ctx]
  (let [sticky (set/intersection reached (stage/sticky-ids process))
        candidates (remove #(contains? reached (:stage/id %))
                           (:process/stages process))]
    (loop [dead #{}]
      (let [ctx {:roles roles :sticky sticky :dead dead}
            dead' (into dead
                        (for [s candidates
                              :when (or (some dead (:after s []))
                                        (some (fn [g]
                                                (some #(permanent? % ctx)
                                                      (:reasons (guard/eval-guard g guard-ctx))))
                                              (:guards s [])))]
                          (:stage/id s)))]
        (if (= dead' dead) dead (recur dead'))))))

(defn explain
  "[{:stage :satisfied :missing :blocks :hint?} ...] for the frontier.
  :missing is sorted by `actionability`. The 5-arity accepts an
  already-derived `reached` set so a caller holding one (the inbox
  derives it for settledness too) pays for the stage fixpoint once."
  ([process events now roles]
   (explain process events now roles
            (stage/effective-reached process events now roles)))
  ([process events now roles reached]
   (let [state (red/ticket-state events)
         ctx {:state state :process process :now now
              :roles roles :reached reached :fact-memo (volatile! {})}
         pctx {:roles roles
               :sticky (set/intersection reached (stage/sticky-ids process))
               :dead (dead-stages process reached roles ctx)}]
     (vec
      (for [s (frontier process reached ctx)
            :let [evaluated (map (fn [g] [g (guard/eval-guard g ctx)])
                                 (:guards s []))
                  satisfied (into [] (comp (filter (comp :satisfied? second))
                                           (map first))
                                  evaluated)
                  ;; permanent reasons sort last: a step nobody can ever
                  ;; take is less actionable than waiting for the clock.
                  missing (vec (sort-by (juxt #(if (:permanent? %) 1 0)
                                              actionability)
                                        (sequence
                                         (comp (mapcat (comp :reasons second))
                                               (distinct)
                                               (map #(cond-> %
                                                       (permanent? % pctx)
                                                       (assoc :permanent? true))))
                                         evaluated)))]
            :when (seq missing)]
        (cond-> {:stage (:stage/id s)
                 :satisfied satisfied
                 :missing missing
                 :blocks (stage/downstream process (:stage/id s))}
          ;; every missing reason must be discharged for the stage to
          ;; land, so one permanent reason settles the whole stage.
          (some :permanent? missing) (assoc :impossible? true)
          (:hint s) (assoc :hint (:hint s))))))))

(defn actionable-by?
  "Can this actor act on this reason right now? Role-bound reasons
  need membership (for :role/same-person, membership minus being the
  very person whose signature already counted); time and other-stage
  waits are nobody's to act on; everything else — facts, corrections,
  artifacts, attestations — is anyone's."
  [{:keys [reason role by options permanent?]} roles actor]
  (if permanent?
    false
    (case reason
      :role/unsatisfied
      (contains? (set (get-in roles [role :members])) actor)
    ;; four-eyes: the two facts were asserted by the SAME person `by`, so
    ;; ANYONE else re-asserting one path breaks the tie. Not role-bound —
    ;; the reason carries no :role (get-in roles [nil …] would be empty).
      :role/same-person (not= by actor)
    ;; a choice is this actor's work when ANY branch is; a tree of pure
    ;; waits is a wait, not an option. Descends so that `for-actor`
    ;; counts an all-waiting :or under :hidden rather than showing it as
    ;; something to do — and so the inbox (tik.next/actionable, which
    ;; flattens the same way) and this view cannot disagree.
      :alternatives (boolean (some #(actionable-by? % roles actor)
                                   (apply concat options)))
      (:time/not-elapsed :stage/not-reached) false
      true)))

(defn for-actor
  "The capability view of explain: each block's :missing filtered to
  what `actor` can act on, with :hidden counting what was filtered so
  no renderer can silently pretend the rest does not exist."
  [blocks roles actor]
  (vec (for [b blocks
             :let [mine (filterv #(actionable-by? % roles actor)
                                 (:missing b))]]
         (assoc b :missing mine
                :hidden (- (count (:missing b)) (count mine))))))

(defn reason->text
  "One structured reason -> one English line. The only place guard failures
  become prose."
  [{:keys [reason path schema by note prefix role stage expected actual
           since duration due errors value options guard claim within
           last-at paths]}]
  (case reason
    :fact/missing    (str "set fact " path
                          (cond
                            expected (str " = " (pr-str expected))
                            schema (str " (" (pr-str schema) ")")))
    :fact/invalid    (str "fact " path " = " (pr-str value)
                          " is invalid: " (pr-str errors))
    :fact/retracted  (str "fact " path " was retracted by " by
                          (when note (str " (\"" note "\")"))
                          " — provide a new value")
    :fact/disputed   (str "fact " path " was disputed by " by
                          ": \"" note "\" — provide a corrected value")
    :fact/conflicted (str "fact " path " has conflicting concurrent"
                          " assertions — one must supersede (ADR 0003)")
    :fact/mismatch   (str "set fact " path " = " (pr-str expected)
                          " (currently " (pr-str actual) ")")
    ;; "starts with", not "under": the match is a raw string prefix, and
    ;; directory language would promise a path boundary the operator
    ;; does not enforce (lint pushes prefixes to end at one)
    :artifact/missing (str "attach an artifact whose path starts with \""
                           prefix "\"")
    :role/unsatisfied (str "fact " path " must be asserted by a member of"
                           " role " role " (currently by " (pr-str by) ")")
    :stage/not-reached (str "stage " stage " must be reached first")
    :time/not-elapsed (str duration " since " since " has not elapsed"
                           (when due (str " (due " due ")")))
    :schema/unsatisfied (str "facts do not satisfy schema: " (pr-str errors))
    :alternatives (str "one of: "
                       (str/join " | " (map #(str/join " + "
                                                       (map reason->text %))
                                            options)))
    :must-not-hold (str "must NOT hold: " (pr-str guard))
    :attestation/missing (str "attest " (pr-str claim)
                              " (none on record; needed within " within ")")
    :attestation/stale (str "re-attest " (pr-str claim)
                            " (last " last-at ", needed within " within ")")
    :role/same-person (str "facts " (pr-str paths)
                           " must come from different people"
                           " (both by " (pr-str by) ")")
    (pr-str reason)))

(defn render
  "Plain-text rendering for the CLI."
  [explanations]
  (if (empty? explanations)
    "Nothing to provide right now."
    (->> explanations
         (map (fn [{:keys [stage satisfied missing blocks hint hidden
                           impossible?]}]
                (str "To reach " stage
                     (when impossible? " (unreachable)") ":\n"
                     (str/join (map #(str "  ✓ " (pr-str %) "\n") satisfied))
                     (str/join (map #(str (if (:permanent? %) "  ⊘ " "  ✗ ")
                                          (reason->text %)
                                          (when (:permanent? %)
                                            " — nobody can ever do this")
                                          "\n")
                                    missing))
                     (when (and hidden (pos? hidden))
                       (str "  … " hidden " step(s) waiting on others or time\n"))
                     (when (seq blocks)
                       (str "  blocks: " (str/join ", " (map str (sort-by str blocks))) "\n"))
                     (when hint (str "  (see: " hint ")\n")))))
         (apply str))))
