;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.next
  "The inbox lens: \"the smallest set of facts that unlocks the most
  work\", H3's simplest honest version — frontier actions sorted by
  unlock count, grouped across tickets, filtered by who-can-act. An
  inbox, deliberately not a scheduler (PLAN §12).

  Derivation only: every item is a rendering of explain's structured
  reasons, so the soundness law is mechanical — an action appears here
  iff some ticket's explain lists a reason it would satisfy. Reasons
  that no actor can act on (time not elapsed, upstream stage not
  reached, must-not-hold) are WAITING, not actionable, and are counted
  rather than listed."
  (:require [tik.explain :as explain]))

(defn- reason->action
  "The concrete act that would satisfy a structured reason, or nil when
  no actor can act on it directly."
  [r]
  (case (:reason r)
    (:fact/missing :fact/invalid :fact/retracted
                   :fact/disputed :fact/conflicted :role/unsatisfied)
    [:set (:path r)]
    :artifact/missing [:attach (:prefix r)]
    nil))

(defn- signed-by-restrictions
  "path -> role for every [:signed-by role path] in a stage's guard
  tree. Consulted so the inbox answers who can UNLOCK, not merely who
  could assert: a fact that is missing reads as anyone's work, but if a
  sibling guard demands a role's signature over the same path, only
  that role's assertion advances the stage. (Conservative under :or —
  a role restriction inside an alternative still restricts; the honest
  refinement waits for a real process that needs it.)"
  [process stage-id]
  (let [stage (first (filter #(= stage-id (:stage/id %))
                             (:process/stages process)))
        walk (fn walk [g]
               (when (vector? g)
                 (case (first g)
                   :signed-by [[(second g) (nth g 2)]]
                   (:and :or) (mapcat walk (rest g))
                   :not (walk (second g))
                   nil)))]
    (into {} (map (fn [[role path]] [path role]))
          (mapcat walk (:guards stage [])))))

(defn- reason->who
  "Who may perform the action: a set of actor names, or :anyone."
  [r roles restrictions]
  (if-let [role (or (when (= :role/unsatisfied (:reason r)) (:role r))
                    (restrictions (:path r)))]
    (set (get-in roles [role :members]))
    :anyone))

(defn- actionable
  "Flatten one reason into actionable sub-reasons: :alternatives means
  any one branch helps, so every branch's actions are offered."
  [r]
  (if (= :alternatives (:reason r))
    (mapcat actionable (apply concat (:options r)))
    (if (reason->action r) [r] [])))

(defn contributions
  "One ticket's actionable work: [{:ticket :stage :action :who :hint}].
  Also returns waiting reasons under :waiting."
  [ticket-id process events now roles]
  (let [blocks (explain/explain process events now roles)
        actions (for [{:keys [stage missing hint]} blocks
                      :let [restrictions (signed-by-restrictions process stage)]
                      r (distinct (mapcat actionable missing))]
                  (cond-> {:ticket ticket-id
                           :stage stage
                           :action (reason->action r)
                           :who (reason->who r roles restrictions)}
                    hint (assoc :hint hint)))]
    {:actions (vec (distinct actions))
     :waiting (vec (for [{:keys [stage missing]} blocks
                         r missing
                         :when (empty? (actionable r))]
                     {:ticket ticket-id :stage stage :reason (:reason r)}))}))

(defn- allowed? [who actor]
  (or (nil? actor) (= :anyone who) (contains? who actor)))

(defn inbox
  "Group contributions from many tickets into the inbox: items sorted by
  how much each action unlocks, optionally filtered to what `actor` may
  do. `per-ticket` is a coll of `contributions` results.

  Returns {:items [{:action :who :unlocks [{:ticket :stage :hint?}]}]
           :waiting [{:ticket :stage :reason}]}."
  ([per-ticket] (inbox per-ticket nil))
  ([per-ticket actor]
   (let [actions (filter #(allowed? (:who %) actor)
                         (mapcat :actions per-ticket))
         items (for [[action contribs] (group-by :action actions)]
                 {:action action
                  ;; :anyone absorbs into the most permissive answer;
                  ;; otherwise the union of actors any unlock admits
                  :who (if (some #(= :anyone (:who %)) contribs)
                         :anyone
                         (reduce into #{} (map :who contribs)))
                  :unlocks (vec (distinct
                                 (map #(select-keys % [:ticket :stage :hint])
                                      contribs)))})]
     {:items (vec (sort-by (juxt #(- (count (:unlocks %)))
                                 #(pr-str (:action %)))
                           items))
      :waiting (vec (mapcat :waiting per-ticket))})))
