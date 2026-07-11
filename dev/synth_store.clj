;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
;;
;; Synthesizes N throwaway tickets into a store for macro benchmarks.
;; bb --config bb.edn -f dev/synth_store.clj <n> <store-dir>
(require '[tik.event :as event] '[tik.store.file :as fstore]
         '[tik.store.protocol :as store])
(let [n (parse-long (first *command-line-args*))
      root (second *command-line-args*)
      s (fstore/file-store root)
      t0 (java.time.Instant/parse "2026-01-01T00:00:00Z")]
  (dotimes [i n]
    (let [ticket (random-uuid)
          e1 (event/create-ticket {:ticket ticket :actor "seb"
                                   :at (.plusSeconds t0 i)
                                   :title (str "synthetic ticket " i)
                                   :process :track})
          e2 (event/assert-fact {:ticket ticket :actor "seb"
                                 :at (.plusSeconds t0 (inc i))
                                 :parents #{(:event/id e1)}
                                 :path [:note] :value (str "note " i)})
          e3 (when (even? i)
               (event/assert-fact {:ticket ticket :actor "seb"
                                   :at (.plusSeconds t0 (+ i 2))
                                   :parents #{(:event/id e2)}
                                   :path [:outcome] :value (str "done " i)}))]
      (doseq [e (remove nil? [e1 e2 e3])] (store/append! s e))))
  (println "synthesized" n "tickets"))
