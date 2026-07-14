;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.design
  "Designing and probing PROCESS DEFINITIONS, not tickets: sim (a live
  scratch ticket against an auto-reloading definition), test (scripted
  step-in/expected-stage-out cases), whatif (a counterfactual stage diff
  from hypothetical facts/elapsed time, nothing written), and debug (the
  fixpoint with its working shown). Read-only over a scratch or real
  ticket; porcelain over tik.cli-core and the kernel derivation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [actor parse-key parse-value read-edn-file]]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [die exit! load-process-arg load-ticket now
                                  parse-when resolve-file stage-delta the-store]]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.lint :as lint]
            [tik.reduce :as red]
            [tik.render :refer [emit-data print-problems tint]]
            [tik.stage :as stage])
  (:import (java.io File)
           (java.time Instant)))

(def sim-help
  "  set k=v [k=v ...]   assert facts        retract <k>      withdraw a fact
  dispute <k> <why>   dispute a fact      attach <name>    fake artifact
  now +PT48H | <inst> move evaluation time                 actor <name>
  reset               fresh scratch ticket                 quit
  (empty line re-renders; the process file reloads automatically on change)")

(def test-epoch (Instant/parse "2026-01-01T00:00:00Z"))

(defn apply-step
  "One scripted step against sim/test state {:events :now :actor}. Steps:
  [:actor \"x\"] [:now \"+PT48H\"|\"<inst>\"] [:set path value]
  [:retract path] [:dispute path reason] [:attach path]. Appended events
  get strictly increasing claimed times so supersedes never lose ties."
  [{:keys [events now actor] :as st} step]
  (when-not (sequential? step)
    (die (str "each step must be a list like [:set [:path] value], got "
              (pr-str step))))
  (let [[op & args] step
        tick (.plusMillis ^Instant now 1)
        arg {:ticket (:event/ticket (first events)) :actor actor :at tick
             :parents #{(:event/id (peek events))}}
        append (fn [e] (-> st (update :events conj e) (assoc :now tick)))]
    (case op
      :actor (assoc st :actor (first args))
      :now (assoc st :now (parse-when now (first args)))
      :set (append (event/assert-fact (assoc arg :path (first args)
                                             :value (second args))))
      :retract (append (event/retract-fact (assoc arg :path (first args))))
      :dispute (append (event/dispute-fact (assoc arg :path (first args)
                                                  :reason (second args))))
      :attach (append (event/attach-artifact
                       (assoc arg :path (first args)
                              :hash (str "sha256-" (canonical/sha256-hex
                                                    (first args))))))
      (die (str "unknown step op " (pr-str op)
                " — use :actor :now :set :retract :dispute :attach")))))

(defn sim-render [proc events sim-now]
  (let [roles (:process/roles proc {})
        reached (stage/effective-reached proc events sim-now roles)
        current (stage/current-stages proc reached)
        state (red/ticket-state events)]
    (println (str "now:   " sim-now))
    (println (str "stage: " (str/join ", " (map name (sort-by str current)))
                  "  (reached: "
                  (str/join ", " (map name (sort-by str reached))) ")"))
    (doseq [[path _] (sort-by (comp pr-str first) (:facts state))
            :let [{:keys [status value by]} (red/fact-status state path)]]
      (println "  " path "=" (pr-str value)
               (if (= :present status) (str "(by " by ")")
                   (str "[" (name status) "]"))))
    (print (explain/render
            (explain/explain proc events sim-now roles reached)))))

(defn sim-load [^File f]
  (let [p (read-edn-file f)]
    (when-not (print-problems (lint/lint p)) p)))

(defn cmd-sim
  "Live process design: a scratch ticket in memory, a definition that
  reloads whenever its file changes. Assert facts and watch stages
  derive; edit the EDN in another window and the next render uses the
  new rules. Pure derivation each round — nothing is stored."
  [{:keys [pos opts]}]
  (let [f (resolve-file (first pos))]
    (when-not (.exists f) (die "no such file:" (str f)))
    (let [tid (random-uuid)
          base (now)
          create (event/create-ticket {:ticket tid :actor "sim" :at base
                                       :title "sim" :process :sim})]
      (println sim-help)
      (loop [proc (or (sim-load f) (die "process has errors"))
             mtime (.lastModified f)
             st {:events [create] :now base :actor (actor opts)}]
        (let [changed? (not= mtime (.lastModified f))
              proc (if changed? (or (sim-load f) proc) proc)
              mtime (.lastModified f)]
          (when changed? (println "(process reloaded)"))
          (println)
          (sim-render proc (:events st) (:now st))
          (print "sim> ")
          (flush)
          (when-let [line (read-line)]
            (let [words (remove str/blank? (str/split (str/trim line) #"\s+"))
                  cmd (first words)
                  ;; string command -> apply-step step vectors
                  steps (case cmd
                          "actor" [[:actor (second words)]]
                          "now" [[:now (second words)]]
                          "set" (for [kv (rest words)
                                      :let [[k v] (str/split kv #"=" 2)]]
                                  [:set (parse-key k) (parse-value v)])
                          "retract" [[:retract (parse-key (second words))]]
                          "dispute" [[:dispute (parse-key (second words))
                                      (str/join " " (drop 2 words))]]
                          "attach" [[:attach (second words)]]
                          nil)]
              (cond
                (= "quit" cmd) nil
                (= "reset" cmd) (recur proc mtime
                                       {:events [create] :now base
                                        :actor (:actor st)})
                (nil? cmd) (recur proc mtime st)
                steps (recur proc mtime (reduce apply-step st steps))
                :else (do (println sim-help)
                          (recur proc mtime st))))))))))

(defn cmd-test
  "Process tests: scripted inputs, expected derived outcomes. The file is
  {:test/process \"<path relative to this file>\"
   :test/cases [{:case/name … :case/steps [[:set [:category] :technical] …]
                 :case/expect {:reached #{…} :current #{…}
                               :includes #{…} :excludes #{…}}} …]}
  Deterministic: fixed epoch, pure derivation. A failing case prints
  explain — the process tells you WHY the stage did not derive."
  [{:keys [pos]}]
  (let [f (resolve-file (first pos))
        _ (when-not (.exists f) (die "no such file:" (str f)))
        spec (read-edn-file f)
        _ (when-not (map? spec)
            (die "test file must be a map with :test/process and :test/cases"))
        {:test/keys [process cases]} spec
        _ (when-not (string? process)
            (die (str "test file needs :test/process: a path to the process"
                      " definition, relative to this file")))
        _ (when-not (or (nil? cases) (sequential? cases))
            (die ":test/cases must be a list of cases"))
        proc (read-edn-file (io/file (.getParentFile (.getAbsoluteFile f))
                                     process))
        _ (when-not (map? proc)
            (die (str "the :test/process path holds no process definition: "
                      process)))
        roles (:process/roles proc {})
        failures (atom 0)]
    (when (print-problems (lint/lint proc))
      (die "process definition has lint errors"))
    (doseq [{:case/keys [name steps expect]} cases]
      (let [create (event/create-ticket {:ticket (random-uuid) :actor "test"
                                         :at test-epoch :title name
                                         :process (:process/id proc)})
            {:keys [events now]} (reduce apply-step
                                         {:events [create] :now test-epoch
                                          :actor "test"}
                                         steps)
            reached (stage/effective-reached proc events now roles)
            current (stage/current-stages proc reached)
            problems
            (concat
             (when (and (:reached expect) (not= (:reached expect) reached))
               [(str "reached " (pr-str reached)
                     ", expected " (pr-str (:reached expect)))])
             (when (and (:current expect) (not= (:current expect) current))
               [(str "current " (pr-str current)
                     ", expected " (pr-str (:current expect)))])
             (for [s (:includes expect) :when (not (contains? reached s))]
               (str "expected " s " to be reached"))
             (for [s (:excludes expect) :when (contains? reached s)]
               (str "expected " s " NOT to be reached")))]
        (if (empty? problems)
          (println "  ok   " name)
          (do (swap! failures inc)
              (println "  FAIL " name)
              (doseq [p problems] (println "        " p))
              (print (str/replace
                      (explain/render
                       (explain/explain proc events now roles reached))
                      #"(?m)^" "        "))))))
    (if (zero? @failures)
      (println "test: PASS")
      (do (println (str "test: FAIL (" @failures ")")) (exit! 1)))))

(defn cmd-debug
  "The fixpoint with its working shown: every sweep, every stage, every
  guard verdict against the sweep-start snapshot. tik's EXPLAIN plan."
  [{:keys [pos opts]}]
  (let [proc-name (first pos)
        proc (load-process-arg proc-name)
        s (the-store)
        [state t roles]
        (if-let [tid (second pos)]
          (let [{:keys [state roles]} (load-ticket s tid)]
            [state (now) roles])
          [red/empty-state (now) (:process/roles proc {})])
        {:keys [reached sweeps]} (stage/trace-sweeps proc state t roles)]
    (when-not (emit-data opts {:reached reached :sweeps sweeps})
            (doseq [{:keys [sweep snapshot evaluated added]} sweeps]
        (println (tint "1" (str "sweep " sweep))
                 (tint "2" (str "against " (pr-str (vec (sort-by str snapshot))))))
        (doseq [{:keys [stage prereqs-met? guards]} evaluated]
          (if-not prereqs-met?
            (println (tint "2" (str "  " stage " prerequisites not reached — not evaluated")))
            (do (println (str "  " stage))
                (doseq [{:keys [guard verdict]} guards]
                  (println (if (:satisfied? verdict)
                             (tint "32" (str "    ✓ " (pr-str guard)))
                             (tint "31" (str "    ✗ " (pr-str guard)))))))))
        (println (str "  => added " (pr-str (vec (sort-by str added))))))
      (println (tint "1" (str "fixpoint: " (pr-str (vec (sort-by str reached)))))))))

(defn cmd-whatif
  "Counterfactuals: apply hypothetical steps to a ticket IN MEMORY and
  show what would change. Nothing is written — derivation over a
  hypothetical event set is the same pure function (PLAN §19)."
  [{:keys [pos opts]}]
  (let [s (the-store)
        {:keys [events process roles]} (load-ticket s (first pos))
        t (now)
        steps (for [kv (rest pos)]
                (cond
                  (str/starts-with? kv "+") [:now (str kv)]
                  (str/starts-with? kv "retract:") [:retract (parse-key (subs kv 8))]
                  :else (let [[k v] (str/split kv #"=" 2)]
                          [:set (parse-key k) (parse-value v)])))
        before (stage/effective-reached process events t roles)
        st (reduce apply-step
                   {:events (vec (red/ordered events)) :now t
                    :actor (actor opts)}
                   steps)
        after (stage/effective-reached process (:events st) (:now st) roles)
        {:keys [gained lost]} (stage-delta before after)
        data {:steps (vec (rest pos)) :gained (vec gained) :lost (vec lost)}]
    (when-not (emit-data opts data)
      (println (tint "2" (str "whatif " (str/join " " (rest pos))
                              "  (nothing recorded)")))
      (doseq [g gained]
        (println (tint "32" (str "  + " g " would become derivable"))))
      (doseq [l lost]
        (println (tint "31" (str "  - " l " would no longer derive"))))
      (when (= before after)
        (println "  no derived change")))))

