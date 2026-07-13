;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.cli-format-test
  "The --format axis is UNIVERSAL over data commands, enforced by a DERIVED
  sweep — the CLI analog of the kernel totality meta-check
  (every_registered_boundary_is_total). WHY it exists: 20 rounds of fuzzing
  never noticed status/log silently IGNORING --format json, because the
  fuzz oracle is crash-freedom (a no-op flag is not a crash) and the JSON
  test was a hardcoded allowlist, not a sweep. Here every command in the
  usage text is FORCED into a taxonomy — machine-output (must emit valid
  JSON under --format json) or human-only — so a new or forgotten command
  cannot slip past."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tik.cli]
            [tik.dag]
            [tik.event :as event]
            [tik.harness :as h]
            [tik.store.protocol :as store])
  (:import (java.time Instant)))

(defn- commands-from-usage []
  (->> (re-seq #"(?m)^\s{2}tik (\S+)" @#'tik.cli/usage)
       (map second) (remove #{"<command>"}) distinct set))

(def ^:private human-only
  "Commands that do not produce queryable data — actions, servers, visual
  renderers, or pass/fail audits — so --format does not apply."
  #{"actor" "adopt" "attach" "attest" "author" "board" "bridge" "bundle"
    "comment" "debug" "dispute" "effects" "export" "gc" "graph" "init"
    "lint" "mcp" "new" "pack" "probe" "process" "retract"
    "reprocess" "rollout" "root" "serve" "set" "show" "sign" "sim" "store" "test"
    "verify" "witness"})

(def ^:private machine-invocations
  "Data commands and an argv that exercises each as a query — :id is filled
  with a seeded ticket at runtime. Every one must emit valid JSON under
  --format json."
  {"ls" [] "query" ["stage=:open"] "search" ["fodder"] "next" []
   "explain" [:id] "status" [:id] "log" [:id] "plan" [] "diff" [:id]
   "whatif" [:id "sev=high"] "roles" [] "causal" [:id]
   "work" ["week" "--actor" "seb"] "agent" ["actions" :id "--actor" "seb"]})

(deftest every_command_is_classified_for_output
  (let [derived (commands-from-usage)
        classified (set/union (set (keys machine-invocations)) human-only)]
    (is (empty? (set/difference derived classified))
        (str "commands with NO output classification (add to machine-invocations"
             " or human-only): " (set/difference derived classified)))
    (is (empty? (set/difference classified derived))
        (str "classified commands not in usage (stale): "
             (set/difference classified derived)))))

(deftest every_machine_command_emits_valid_json
  (let [{:keys [root store]} (h/temp-store!)
        id (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")
        parse-json (requiring-resolve 'cheshire.core/parse-string)]
    (h/seed-ticket! store {:ticket id :at t :title "json fodder"})
    (store/append! store (event/assert-fact
                          {:ticket id :actor "seb" :at (.plusSeconds t 1)
                           :parents (tik.dag/heads (store/events store id))
                           :path [:sev] :value :low}))
    (h/with-cli-root root
      (fn []
        (doseq [[cmd args] machine-invocations
                :let [argv (-> (into [cmd] (map #(if (= :id %) (str id) %) args))
                               (conj "--format" "json"))
                      r (tik.cli/run-argv argv)]]
          (is (zero? (:exit r)) (str argv " -> " (:err r)))
          (doseq [line (remove str/blank? (str/split-lines (:out r)))]
            (is (try (parse-json line) true (catch Exception _ false))
                (str (pr-str argv) " printed invalid JSON:\n" line))))))))
