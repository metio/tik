;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.linting
  "The lint and show surfaces for PROCESS DEFINITIONS and the store: lint
  a definition (or, with no argument, the whole store — open tickets
  missing descriptions/titles/signatures), and show a definition as a
  vertical stage graph, optionally overlaying a ticket's progress.
  Porcelain over tik.cli-core, the linter (tik.lint), and the renderer
  (tik.draw)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [read-edn-file]]
            [tik.cli-core :refer [all-ticket-ctx die exit! load-process-arg load-ticket
                                  now resolve-file signed-event-ids the-store]]
            [tik.draw :as draw]
            [tik.guard :as guard]
            [tik.lint :as lint]
            [tik.next :as next-lens]
            [tik.reduce :as red]
            [tik.render :refer [print-problems sid tint]]
            [tik.stage :as stage]
            [tik.text :refer [safe-name]]))

(defn lint-store
  "Hygiene over every open ticket — the things verify (integrity) and
  explain (derivation) don't check because they're not wrong, just
  unkempt: a board line without a description, a blank title, events
  nobody signed. Derived on read like everything else; findings name
  the command that fixes them."
  []
  (let [s (the-store)
        t (now)
        findings
        (vec
         (for [{:keys [id events state process roles]} (all-ticket-ctx s)
               :let [short (sid id)
                     fm (guard/fact-map state)]
               :when (not (next-lens/settled? process events t roles))
               :let [desc (red/fact-status state [:description])
                     commit-fact (red/fact-status state [:commit])]
               problem
               [(when (str/blank? (:title state))
                  (str short " has no title — tik set " short " … happened at create; open a fresh ticket with --title"))
                (when-not (some fm [[:description] [:summary] [:statement]])
                  (str short " has no description — tik set " short " description=<one line: what is this ticket about?>"))
                ;; prose about the world can't be derived, but its AGE
                ;; can: a description older than the ticket's latest
                ;; landing is the prose most likely to be stale
                (when (and (= :present (:status desc))
                           (= :present (:status commit-fact))
                           (< (inst-ms (:at desc))
                              (inst-ms (:at commit-fact))))
                  (str short " description predates its latest landing — re-read it, then re-assert or amend"))
                ;; status reports about OTHER work belong in link facts
                ;; (derived live), never in prose (rots silently)
                (when (and (= :present (:status desc))
                           (string? (:value desc))
                           (re-find #"(?i)\b(?:shipped|landed|fixed) in\b|(?<![\w-])[0-9a-f]{8}(?![\w-])"
                                    (:value desc)))
                  (str short " description reports another ticket's status — that rots; record tik set " short " link.<rel>=<ticket-id> and let the board derive it live"))
                (let [signed (signed-event-ids s id)]
                  (when-let [n (seq (remove #(contains? signed (:event/id %)) events))]
                    (str short " has " (count n) " unsigned event(s) — tik sign " short " (or export TIK_KEY to sign as you write)")))]
               :when problem]
           problem))]
    (doseq [f findings] (println (str "[warning] " f)))
    (if (empty? findings)
      (println "store: clean")
      (do (println (str (count findings) " finding(s)"))
          (exit! 1)))))

(defn ticket-stage-status
  "{stage-id -> :reached|:frontier|:blocked} for `tid` under `proc` — the
  overlay `show <proc> <id>` draws. Frontier = prereqs met, guards still
  missing (actionable now); blocked = an :after prerequisite not reached."
  [s proc tid]
  ;; evaluate the SHOWN definition against its OWN roles — not the roles of
  ;; the ticket's pinned process (load-ticket's :roles), which may differ
  ;; from the definition being drawn (`tik show ./other.edn <id>`).
  (let [{:keys [events]} (load-ticket s tid)
        reached (stage/effective-reached proc events (now)
                                         (:process/roles proc {}))]
    (into {} (for [st (:process/stages proc)
                   :let [id (:stage/id st)]]
               [id (cond (reached id) :reached
                         (every? reached (:after st [])) :frontier
                         :else :blocked)]))))

(defn cmd-show
  "show <process|file.edn> [<id>]: draw the process as a vertical stage
  graph — a pure picture of the definition (stages, guards, forks, joins).
  With a ticket id, overlay that ticket's derived progress: ✓ reached,
  ◆ frontier (actionable now), · blocked. Reads a .edn path directly, or a
  process name from this store's processes/."
  [{:keys [pos]}]
  (let [arg (or (first pos) (die "usage: tik show <process|file.edn> [<id>]"))
        proc (load-process-arg arg)
        ;; show draws from an unlinted definition — :process/roles may be
        ;; any shape; only a map has role names to list.
        roles (when (map? (:process/roles proc)) (keys (:process/roles proc)))
        status (when-let [tid (second pos)]
                 (ticket-stage-status (the-store) proc tid))
        lines (draw/process proc status)]
    (println (tint "1" (str (safe-name (:process/id proc))
                            (when-let [v (:process/version proc)] (str "  (v" v ")")))))
    (when-let [p (:process/purpose proc)] (println (tint "2" (str "  " p))))
    (when (seq roles)
      (println (tint "2" (str "  roles: " (str/join ", " (map safe-name roles))))))
    (when status
      (println (tint "2" "  ✓ reached · ◆ actionable now · · blocked")))
    (println)
    (if (seq lines)
      (doseq [l lines] (println l))
      (println "(no stages)"))))

(defn cmd-lint [{:keys [pos]}]
  (if (empty? pos)
    (lint-store)
    (let [f (resolve-file (first pos))
          _ (when-not (.exists f)
              (die (str "no such file: " (first pos)
                        " — `tik lint` with no argument lints the store")))
          proc (read-edn-file f)
          missing-runbooks (for [s (:process/stages proc)
                                 :let [h (:hint s)]
                                 :when (and h (not (.exists (io/file h))))]
                             {:level :warning
                              :msg (str "stage " (:stage/id s) " :hint "
                                        h " does not exist on disk")})
          problems (concat (lint/lint proc) missing-runbooks)]
      (when (print-problems problems) (exit! 1))
      (when (empty? problems) (println "clean")))))


