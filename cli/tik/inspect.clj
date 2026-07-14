;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.inspect
  "The single-ticket lenses (plus the inbox): status (derived stage,
  facts, links, what's next), explain (what evidence is missing and who
  can act), causal (which signed events made each reached stage true),
  log (the event history), and next (the inbox across tickets, ranked by
  what unlocks the most). Read-only renderings of the derivation over
  tik.cli-core."
  (:require [clojure.string :as str]
            [tik.args :refer [parse-value]]
            [tik.causal :as causal]
            [tik.cli-core :refer [cache-flush! display-title eval-instant link-facts
                                  link-lines link-row load-ticket now resolve-id-soft
                                  the-store ticket-ctx ticket-row]]
            [tik.explain :as explain]
            [tik.next :as next-lens]
            [tik.reduce :as red]
            [tik.render :refer [emit-data paint-explain shash sid tint]]
            [tik.stage :as stage]
            [tik.store.protocol :as store]))

(defn unmet-deps
  "The tickets this one `[:link :depends-on]`s that are NOT yet settled —
  the cross-ticket blockers `next` respects. Pure derivation over the
  store; the per-ticket kernel is untouched (dependency-readiness needs
  many logs, so it lives in porcelain). An unresolvable target counts as
  unmet — never call a ticket ready on an unknown dependency."
  [s t id]
  (for [{:keys [rel target]} (link-facts (:state (ticket-ctx s id)))
        :when (= :depends-on rel)
        :let [tid (resolve-id-soft s target)
              done? (when tid
                      (let [{:keys [process events roles]} (ticket-ctx s tid)]
                        (next-lens/settled? process events t roles)))]
        :when (not done?)]
    {:target target :tid tid}))

(defn cmd-status [{:keys [pos opts]}]
  (let [s (the-store)
        {:keys [id events state process roles]} (load-ticket s (first pos))
        t (eval-instant opts)
        reached (stage/effective-reached process events t roles)
        current (stage/current-stages process reached)
        fact-entry (fn [path]
                     (select-keys (red/fact-status state path)
                                  [:status :value :by :note]))
        data {:ticket id
              :title (display-title state)
              :rules {:process (or (:process/id process) (:process state))
                      :version (or (:process/version process)
                                   (:process-version state))
                      :hash (:process-hash state)}
              :current (vec current)
              :reached (vec (sort-by str reached))
              :blocked (vec (unmet-deps s t id))
              :facts (into {} (for [[path _] (:facts state)
                                    :when (not (or (= :link (first path))
                                                   (= [:title] path)))]
                                [path (fact-entry path)]))
              :links (vec (sort-by :sort (map #(link-row s t %)
                                              (link-facts state))))
              :blocks (explain/explain process events t roles reached)
              :at (when (:at opts) t)}]
    (when-not (emit-data opts data)
     (println "ticket: " id)
    (println "title:  " (display-title state))
    ;; the hash is the RULE SET's identity, never the ticket's — label
    ;; it so nobody misreads the pin as a mutable ticket id
    ;; the PINNED definition names the rules — after a migration the
    ;; create-time label would lie
    (println "rules:  " (or (:process/id process) (:process state))
             (str "v" (or (:process/version process)
                          (:process-version state)))
             (str "(pinned @ " (some-> (:process-hash state) shash)
                  "…)"))
    (println "stage:  " (str/join ", " (map name current))
             (str "(reached: " (str/join ", " (map name (sort-by str reached))) ")"))
    (when-let [deps (seq (unmet-deps s t id))]
      (println "blocked:"
               (str/join ", "
                         (map (fn [{:keys [target tid]}]
                                (str (if tid (sid tid) target)
                                     (if tid " (not settled)" " (unresolved)")))
                              deps))
               "— :depends-on an upstream ticket that is not done"))
    (println "facts:")
    (doseq [[path _] (sort-by (comp pr-str first) (:facts state))
            ;; links and the title override render in their own homes;
            ;; repeating them here would just be the same data twice
            :when (not (or (= :link (first path)) (= [:title] path)))
            :let [{:keys [status value by note]} (red/fact-status state path)]]
      (println " " path "=" (pr-str value)
               (case status
                 :present   (str "(by " by ")")
                 :disputed  (str "[DISPUTED by " by ": " note "]")
                 :retracted (str "[retracted by " by "]")
                 :conflicted "[CONFLICTED]"
                 "")))
    (when-let [links (seq (link-facts state))]
      (let [rows (sort-by :sort (map #(link-row s t %) links))
            counts (->> rows (map :stage) (partition-by identity)
                        (map #(str (str/replace (first %) #"[()]" "")
                                   " " (count %))))]
        (println (str "links:  (" (str/join " · " counts) ")"))
        (doseq [line (link-lines s t state
                                 (when (string? (:links opts))
                                   (parse-value (:links opts))))]
          (println "  " line))))
    (when (:at opts)
      (println (tint "33" (str "as of:   " t "  (time travel — nothing is stored)"))))
    (println)
    (print (paint-explain
            (explain/render (explain/explain process events t roles reached)))))
    (cache-flush!)))

(defn cmd-explain [{:keys [pos opts]}]
  (let [s (the-store)
        {:keys [events process roles]} (load-ticket s (first pos))
        blocks (explain/explain process events (eval-instant opts) roles)
        blocks (if-let [who (:actor opts)]
                 (explain/for-actor blocks roles who)
                 blocks)]
    (when-not (emit-data opts blocks)
      (print (paint-explain (explain/render blocks))))))

(defn cmd-causal
  "Which signed events made each reached stage true — forensics: the
  auditor's 'prove it' rendered from the same fold as everything else."
  [{:keys [pos opts]}]
  (let [s (the-store)
        {:keys [events process roles]} (load-ticket s (first pos))
        by-id (into {} (map (juxt :event/id identity)) events)
        blocks (causal/causal process events (eval-instant opts) roles)]
    (when-not (emit-data opts blocks)
      (doseq [{:keys [stage support]} blocks]
        (println (str (tint "32" (str stage)) " is supported by:"))
        (if (empty? support)
          (println "  nothing — no guards, reachable by structure alone")
          (doseq [{:keys [via events note]} support]
            (println (str "  " (pr-str via)))
            (doseq [eid events
                    :let [e (by-id eid)]]
              (println (tint "2" (str "    ← " (shash eid) "… "
                                      (name (:event/type e)) " by "
                                      (:event/actor e) " @ "
                                      (:event/at e)))))
            (when note
              (println (tint "2" (str "    ← " note))))))))))

(defn cmd-log
  "The evidence timeline: stored events interleaved with DERIVED stage
  transitions, computed at render time from the evolve fold and never
  stored — the one law applied to the UI's own furniture."
  [{:keys [pos opts]}]
  (let [s (the-store)
        {:keys [events process roles]} (load-ticket s (first pos))
        timeline (:timeline (stage/evolve process events roles))
        entries (for [[prev entry] (map vector (cons nil timeline) timeline)
                      :let [e (first (filter #(= (:event-id entry) (:event/id %))
                                             events))
                            gained (sort-by str (remove (:reached prev #{})
                                                        (:reached entry)))]]
                  {:at (:event/at e) :type (:event/type e)
                   :actor (:event/actor e) :body (:event/body e)
                   :derived (vec gained)})]
    (when-not (emit-data opts (vec entries))
      (doseq [{:keys [at type actor body derived]} entries]
        (println (str at) (name type) actor (pr-str body))
        (doseq [stage-id derived]
          (println (str at) "derived —"
                   (str "stage " stage-id " became reachable")))))))

(defn cmd-next
  "The inbox: frontier actions across every ticket, sorted by unlock
  count, filtered by --actor when given. A rendering of tik.next — plus
  a cross-ticket step: a ticket whose `[:link :depends-on]` upstream is
  not yet settled is held back as blocked, not offered as work."
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        include-settled? (:all opts)
        ;; the cache answers settled? from a directory listing; only
        ;; live tickets pay for the full contributions fold
        live-ids (for [id (store/ticket-ids s)
                       :let [row (ticket-row s t id)]
                       :when (or include-settled? (not (:settled? row)))
                       :when (or (nil? (:error row))
                                 (do (binding [*out* *err*]
                                       (println (str "skipping "
                                                     (sid id)
                                                     ": " (:error row))))
                                     false))]
                   id)
        deps-blocked (set (filter #(seq (unmet-deps s t %)) live-ids))
        per-ticket (for [id live-ids
                         :when (not (contains? deps-blocked id))
                         :let [{:keys [events process roles]} (ticket-ctx s id)]]
                     (next-lens/contributions id process events t roles))
        settled-skipped (when-not include-settled?
                          (count (filter #(:settled? (ticket-row s t %))
                                         (store/ticket-ids s))))
        {:keys [items waiting settled] :as inbox}
        (next-lens/inbox per-ticket (:actor opts)
                         {:include-settled? (:all opts)
                          :role (some-> (:role opts) parse-value)})
        settled (or settled-skipped settled)]
    (when-not (emit-data opts inbox)
            (if (empty? items)
        (println "Nothing actionable"
                 (if (:actor opts) (str "for " (:actor opts)) "right now")
                 "right now.")
        (doseq [{:keys [action who unlocks stale-ms]} items
                :let [days (quot (or stale-ms 0) 86400000)]]
          (println (format "%-42s unlocks %d%s"
                           (str (tint "1" (str (name (first action)) " "
                                               (pr-str (second action))))
                                (when (not= :anyone who)
                                  (tint "2" (str "  ("
                                                 (str/join ", " (sort who))
                                                 ")"))))
                           (count unlocks)
                           (if (>= days 2)
                             (tint "33" (str "  (quiet " days "d)"))
                             "")))
          (doseq [{:keys [ticket stage hint]} unlocks]
            (println (str "    " (sid ticket) " -> " stage
                          (when hint (str "  (see: " hint ")")))))))
      ;; waiting and blocked are reported whether or not anything is
      ;; actionable — a fully dependency-blocked store must still say so
      (when (seq waiting)
        (println (str "waiting: " (count waiting)
                      " stage(s) gated on time or upstream stages")))
      (when (seq deps-blocked)
        (println (str "blocked: " (count deps-blocked)
                      " ticket(s) waiting on an upstream ticket"
                      " (:depends-on not yet settled)")))
      (when (and (pos? (or settled 0)) (not (:all opts)))
        (println (str "settled: " settled
                      " finished ticket(s) hidden (--all shows their"
                      " escape hatches)"))))
    (cache-flush!)))
