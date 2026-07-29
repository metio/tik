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
  (:require [clojure.string :as str]
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
              :roles roles :reached reached :fact-memo (volatile! {})}]
     (vec
      (for [s (frontier process reached ctx)
            :let [evaluated (map (fn [g] [g (guard/eval-guard g ctx)])
                                 (:guards s []))
                  satisfied (into [] (comp (filter (comp :satisfied? second))
                                           (map first))
                                  evaluated)
                  missing (vec (sort-by actionability
                                        (sequence
                                         (comp (mapcat (comp :reasons second))
                                               (distinct))
                                         evaluated)))]
            :when (seq missing)]
        (cond-> {:stage (:stage/id s)
                 :satisfied satisfied
                 :missing missing
                 :blocks (stage/downstream process (:stage/id s))}
          (:hint s) (assoc :hint (:hint s))))))))

(defn actionable-by?
  "Can this actor act on this reason right now? Role-bound reasons
  need membership (for :role/same-person, membership minus being the
  very person whose signature already counted); time and other-stage
  waits are nobody's to act on; everything else — facts, corrections,
  artifacts, attestations — is anyone's."
  [{:keys [reason role by options]} roles actor]
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
    true))

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
         (map (fn [{:keys [stage satisfied missing blocks hint hidden]}]
                (str "To reach " stage ":\n"
                     (str/join (map #(str "  ✓ " (pr-str %) "\n") satisfied))
                     (str/join (map #(str "  ✗ " (reason->text %) "\n")
                                    missing))
                     (when (and hidden (pos? hidden))
                       (str "  … " hidden " step(s) waiting on others or time\n"))
                     (when (seq blocks)
                       (str "  blocks: " (str/join ", " (map str (sort-by str blocks))) "\n"))
                     (when hint (str "  (see: " hint ")\n")))))
         (apply str))))
