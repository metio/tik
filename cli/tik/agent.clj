;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.agent
  "The gated agent surface (PLAN §12): agent actions|set|attest — the
  admissible action set the frontier derives for an actor on a ticket, and
  writes that are REFUSED (exit 3) unless the frontier admits them. The
  authorization boundary is the derivation itself (next-lens/admissible?),
  the same one the inbox projects; everything an agent does lands as an
  ordinary signed event. Porcelain over tik.cli-core."
  (:require [clojure.string :as str]
            [tik.args :refer [parse-key typed-value]]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [append!* die exit! now resolve-id the-store ticket-ctx]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.next :as next-lens]
            [tik.render :refer [emit-data]]
            [tik.store.protocol :as store]))
(defn agent-admissible
  "The actions the frontier admits for this actor on this ticket —
  derived from process + roles + current evidence, nothing else. THE
  authorization boundary (PLAN §12): not a permission table, a
  projection of the process definition."
  [id actor-name]
  (let [s (the-store)
        {:keys [events process roles]} (ticket-ctx s id)
        {:keys [actions]} (next-lens/contributions id process events
                                                   (now) roles)]
    ;; the SAME admissibility the inbox projects — role membership plus
    ;; the four-eyes :not-actor exclusion — so the gate never admits a
    ;; write the inbox would deny (they share next-lens/admissible?)
    (filterv #(next-lens/admissible? % actor-name) actions)))

(defn agent-refuse! [opts actor-name attempted admissible]
  ;; a refusal is an error: it goes to stderr and exits 3 in every format,
  ;; so a machine reads the verdict from the exit code and a JSON body from
  ;; the same stream an MCP client already captures.
  (binding [*out* *err*]
    (when-not (emit-data opts {:refused attempted
                               :actor actor-name
                               :admissible (mapv :action admissible)})
      (println "REFUSED:" (pr-str attempted) "is not admitted by the"
               "frontier for actor" actor-name)
      (println "admissible now:"
               (pr-str (mapv :action admissible)))))
  (exit! 3))

(defn cmd-agent
  "The gated surface an agent works through (H7):
    agent actions <id> --actor A         the admissible action set (EDN)
    agent set <id> k=v --actor A         assert — ONLY if admitted
    agent attest <id> <claim> --actor A  attest — ONLY if admitted
  Enforcement is derivation: the same contributions the inbox shows.
  Everything an agent does lands as ordinary signed events; the
  accountability trail is the ticket itself (PLAN §12/§13)."
  [{:keys [pos opts]}]
  (let [[sub ticket & args] pos
        who (or (:actor opts) (die "agent commands require --actor"))
        _ (when-not (contains? #{"actions" "set" "attest"} sub)
            (die "usage: tik agent actions|set|attest <id> ... --actor A"))
        _ (when-not ticket
            (die (str "usage: tik agent " sub " <id> ... --actor A")))
        s (the-store)
        id (resolve-id s ticket)
        admissible (agent-admissible id who)]
    (case sub
      "actions" (let [data {:actor who :ticket id
                            :admissible (mapv #(select-keys % [:action :stage])
                                              admissible)}]
                  (when-not (emit-data opts data)
                    (prn data)))
      "set" (let [kv (or (first args)
                         (die "usage: tik agent set <id> key=value --actor A"))
                  [k v] (str/split kv #"=" 2)
                  path (parse-key k)
                  attempted [:set path]]
              (when-not (some #(= attempted (:action %)) admissible)
                (agent-refuse! opts who attempted admissible))
              ;; declared-type aware, exactly as `tik set` — an agent and a
              ;; human must ground the same key=value identically
              (append!* s (event/assert-fact
                           {:ticket id :actor who :at (now)
                            :parents (dag/heads (store/events s id))
                            :path path
                            :value (typed-value (:process (ticket-ctx s id))
                                                path v)})
                        opts)
              (when-not (emit-data opts {:ok true :action attempted})
                (println "ok" (pr-str attempted))))
      "attest" (let [claim (canonical/parse
                            (or (first args)
                                (die "usage: tik agent attest <id> <claim-edn> --actor A")))
                     attempted [:attest claim]]
                 (when-not (some #(= attempted (:action %)) admissible)
                   (agent-refuse! opts who attempted admissible))
                 (append!* s (event/add-attestation
                              {:ticket id :actor who :at (now)
                               :parents (dag/heads (store/events s id))
                               :claim {:claim claim}})
                           opts)
                 (when-not (emit-data opts {:ok true :action attempted})
                   (println "ok" (pr-str attempted))))
      (die "usage: tik agent actions|set|attest <id> ... --actor A"))))
