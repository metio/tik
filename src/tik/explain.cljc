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

(defn explain
  "[{:stage :satisfied :missing :blocks :hint?} ...] for the frontier."
  [process events now roles]
  (let [state (red/ticket-state events)
        reached (stage/effective-reached process events now roles)
        ctx {:state state :process process :now now
             :roles roles :reached reached}]
    (vec
     (for [s (frontier process reached ctx)
           :let [evaluated (map (fn [g] [g (guard/eval-guard g ctx)])
                                (:guards s []))
                 satisfied (into [] (comp (filter (comp :satisfied? second))
                                          (map first))
                                 evaluated)
                 missing (into [] (comp (mapcat (comp :reasons second))
                                        (distinct))
                               evaluated)]
           :when (seq missing)]
       (cond-> {:stage (:stage/id s)
                :satisfied satisfied
                :missing missing
                :blocks (stage/downstream process (:stage/id s))}
         (:hint s) (assoc :hint (:hint s)))))))

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
    :artifact/missing (str "attach an artifact under \"" prefix "\"")
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
         (map (fn [{:keys [stage satisfied missing blocks hint]}]
                (str "To reach " stage ":\n"
                     (str/join (map #(str "  ✓ " (pr-str %) "\n") satisfied))
                     (str/join (map #(str "  ✗ " (reason->text %) "\n")
                                    missing))
                     (when (seq blocks)
                       (str "  blocks: " (str/join ", " (map str (sort-by str blocks))) "\n"))
                     (when hint (str "  (see: " hint ")\n")))))
         (apply str))))
