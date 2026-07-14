;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.workcmd
  "tik work record|week|cost (H6): telemetry claims in, machine-drafted
  human-signed work records out, usage totals derived — never stored.
  Porcelain over the pure work lens (tik.work) and tik.cli-core."
  (:require [clojure.java.io :as io]
            [tik.args :refer [actor read-edn-file]]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [all-ticket-ctx append!* die now parse-instant
                                  resolve-id the-store]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.render :refer [emit-data sid tint]]
            [tik.store.protocol :as store]
            [tik.work :as work]))
(defn cmd-work
  "The work-evidence surface (H6):
    work record <id> <edn>          agent/tool telemetry as a :work claim
    work week [--actor A] [--from I --to I] [--sign]
                                    machine-drafted activity: method
                                    declared, durations marked as
                                    inferences, every line tracing to
                                    event ids; --sign turns the draft
                                    into human-signed per-ticket claims
    work cost [--pricing F]         usage totals derived by folding;
                                    money only via an explicit pricing
                                    table — observations never rot"
  [{:keys [pos opts]}]
  (let [s (the-store)
        sub (first pos)]
    (case sub
      "record"
      (let [id (resolve-id s (second pos))
            body (canonical/parse (nth pos 2))]
        (append!* s (event/add-attestation
                     {:ticket id :actor (actor opts) :at (now)
                      :parents (dag/heads (store/events s id))
                      :claim (merge {:claim :work} body)})
                  opts)
        (println "recorded" (pr-str (:work/kind body :work))
                 "on" (sid id)))

      "week"
      (let [who (or (:actor opts) (die "work week requires --actor"))
            from (some-> (:from opts) parse-instant)
            to (some-> (:to opts) parse-instant)
            per-ticket (for [{:keys [id events state]} (all-ticket-ctx s)]
                         {:ticket id :title (:title state) :events events})
            d (work/draft per-ticket who from to)]
        (when-not (emit-data opts d)
                    (println (tint "1" (str "activity draft — " who))
                   (tint "2" (str "(" (get-in d [:method :statement]) ")")))
          (doseq [{:keys [ticket title sessions duration evidence]}
                  (:tickets d)]
            (println (format "  %s  ~%-10s %d session(s), %d event(s)  %s"
                             (sid ticket)
                             (subs (str duration) 2)
                             sessions (count evidence) title)))
          (println (tint "1" (str "  total ~" (subs (:total d) 2)
                                  "  — an inference, not a measurement")))
          (if-not (:sign opts)
            (println (tint "2" "  review, then --sign to record it as your claim"))
            (do
              (doseq [{:keys [ticket duration evidence]} (:tickets d)]
                (append!* s (event/add-attestation
                             {:ticket ticket :actor who :at (now)
                              :parents (dag/heads (store/events s ticket))
                              :claim {:claim :work
                                      :work/kind :human
                                      :work/duration duration
                                      :work/method (get-in d [:method :method])
                                      :work/evidence evidence}})
                          opts))
              (println (tint "32"
                             (str "  signed " (count (:tickets d))
                                  " per-ticket claim(s) — corrected by"
                                  " you, carried with evidence")))))))

      "cost"
      (let [pricing (some-> (:pricing opts) io/file read-edn-file)
            records (mapcat (fn [id]
                              (map #(assoc % :ticket id)
                                   (work/work-records
                                    (store/events s id))))
                            (store/ticket-ids s))
            agent-runs (filter :usage records)
            totals (work/usage-totals agent-runs pricing)]
        (when-not (emit-data opts totals)
                    (doseq [[model u] (:observations totals)]
            (println (tint "1" (str model)))
            (doseq [[k v] (sort u)]
              (println (format "    %-20s %,d" (name k) (long v))))
            (when-let [cost (get-in totals [:priced model])]
              (println (tint "33" (format "    ≈ %.2f (per --pricing table, today)"
                                          (double cost))))))
          (when (empty? agent-runs)
            (println "no agent-run work records yet — tik work record"))
          (when-not pricing
            (println (tint "2" (str "  raw observations only — pass"
                                    " --pricing <file.edn> to price them"
                                    " (money is a lens, prices change,"
                                    " observations don't rot)"))))))

      (die "usage: tik work record|week|cost ..."))))
