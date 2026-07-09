;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.cli
  "The tik CLI. babashka-first, JVM-compatible.

  Work commands:      new set dispute attach comment status explain log ls verify
  Process developer:  lint

  Conventions:
  - store root:  $TIK_ROOT or the current directory
  - actor:       --actor, else $TIK_ACTOR, else the OS user
  - fact keys:   dotted paths -> keyword vectors
  - fact values: parsed as EDN; bare words become keywords"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.guard :as guard]
            [tik.next :as next-lens]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.io File)
           (java.time Duration Instant)))

(defn- root [] (or (System/getenv "TIK_ROOT") "."))
(defn- now [] (Instant/now))

(defn- die [& msg]
  (binding [*out* *err*] (apply println msg))
  (System/exit 1))

(defn- parse-args [args]
  (loop [args args pos [] opts {}]
    (if (empty? args)
      {:pos pos :opts opts}
      (let [[a & more] args]
        (if (str/starts-with? a "--")
          (recur (rest more) pos (assoc opts (keyword (subs a 2)) (first more)))
          (recur more (conj pos a) opts))))))

(defn- actor [opts]
  (or (:actor opts) (System/getenv "TIK_ACTOR")
      (System/getProperty "user.name")))

(defn- parse-key [s] (mapv keyword (str/split s #"\.")))

(defn- parse-value [s]
  (let [v (edn/read-string s)]
    (if (symbol? v) (keyword (str v)) v)))

(defn- process-file ^File [name] (io/file (root) "processes" (str name ".edn")))

(defn- load-process [name]
  (let [f (process-file name)]
    (when-not (.exists f) (die "no such process:" (str f)))
    (edn/read-string (slurp f))))

(defn- the-store [] (fstore/file-store (root)))

(defn- resolve-id [s ticket-str]
  (let [hits (filter #(str/starts-with? % ticket-str)
                     (map str (store/ticket-ids s)))]
    (case (count hits)
      1 (java.util.UUID/fromString (first hits))
      0 (die "no ticket matching" ticket-str)
      (die "ambiguous prefix" ticket-str "->" (pr-str (vec hits))))))

(defn- ticket-ctx [s id]
  (let [evs (store/events s id)
        state (red/ticket-state evs)
        proc (load-process (name (:process state)))]
    {:events evs :state state :process proc
     :roles (:process/roles proc {})
     :heads (dag/heads evs)}))

;; ---------------------------------------------------------------- commands

(defn- cmd-new [{:keys [pos opts]}]
  (let [[proc-name] pos
        proc (load-process proc-name)
        id (random-uuid)
        e (event/create-ticket {:ticket id :actor (actor opts) :at (now)
                                :title (or (:title opts) "")
                                :process (keyword proc-name)
                                :version (:process/version proc)
                                :process-hash (process/process-hash proc)})]
    (store/append! (the-store) e)
    (println (str id))))

(defn- cmd-set [{:keys [pos opts]}]
  (let [[ticket & kvs] pos
        s (the-store)
        id (resolve-id s ticket)]
    ;; heads recomputed per event: linear chain within one command
    (doseq [kv kvs :let [[k v] (str/split kv #"=" 2)]]
      (store/append! s (event/assert-fact
                        {:ticket id :actor (actor opts) :at (now)
                         :parents (dag/heads (store/events s id))
                         :path (parse-key k) :value (parse-value v)})))
    (println "ok")))

(defn- cmd-retract [{:keys [pos opts]}]
  (let [[ticket k] pos
        s (the-store)
        id (resolve-id s ticket)]
    (store/append! s (event/retract-fact
                      {:ticket id :actor (actor opts) :at (now)
                       :parents (dag/heads (store/events s id))
                       :path (parse-key k)
                       :reason (:reason opts)}))
    (println "ok")))

(defn- cmd-diff
  "Evidence gained between two points in the log: derive at event n-k and
  at the head, report stages that became derivable and facts that
  changed — never 'transitions performed', because none were."
  [{:keys [pos]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        k (if (second pos) (Long/parseLong (second pos)) 1)
        {:keys [events process roles]} (ticket-ctx s id)
        ordered (vec (red/ordered events))
        before-events (subvec ordered 0 (max 1 (- (count ordered) k)))
        t (now)
        before-reached (stage/effective-reached process before-events t roles)
        after-reached (stage/effective-reached process ordered t roles)
        before-facts (guard/fact-map (red/ticket-state before-events))
        after-facts (guard/fact-map (red/ticket-state ordered))]
    (println (str "last " (min k (dec (count ordered))) " event(s):"))
    (doseq [stage-id (sort-by str (remove before-reached after-reached))]
      (println "  + stage" stage-id "became derivable"))
    (doseq [stage-id (sort-by str (remove after-reached before-reached))]
      (println "  - stage" stage-id "no longer derivable"))
    (doseq [path (sort-by str (set (concat (keys before-facts) (keys after-facts))))
            :let [b (get before-facts path) a (get after-facts path)]
            :when (not= b a)]
      (cond
        (nil? b) (println "  + fact" path "=" (pr-str a))
        (nil? a) (println "  - fact" path "(was" (pr-str b) ")")
        :else    (println "  ~ fact" path "=" (pr-str a)
                          "(was" (pr-str b) ")")))
    (when (and (= before-reached after-reached) (= before-facts after-facts))
      (println "  no derivable change"))))

(defn- cmd-dispute [{:keys [pos opts]}]
  (let [[ticket k] pos
        s (the-store)
        id (resolve-id s ticket)]
    (store/append! s (event/dispute-fact
                      {:ticket id :actor (actor opts) :at (now)
                       :parents (dag/heads (store/events s id))
                       :path (parse-key k)
                       :reason (or (:reason opts) "disputed")}))
    (println "ok")))

(defn- cmd-attach [{:keys [pos opts]}]
  (let [[ticket path] pos
        s (the-store)
        id (resolve-id s ticket)
        src (io/file path)
        _ (when-not (.exists src) (die "no such file:" path))
        bytes (java.nio.file.Files/readAllBytes (.toPath src))
        hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
        ;; blobs stored BY HASH: nothing in the store is addressed by
        ;; anything except what it is
        dest (io/file (root) "tickets" (str id) "blobs" hash)]
    (.mkdirs (.getParentFile dest))
    (io/copy src dest)
    (store/append! s (event/attach-artifact
                      {:ticket id :actor (actor opts) :at (now)
                       :parents (dag/heads (store/events s id))
                       :path (str "repro/" (.getName src)) :hash hash}))
    (println "ok" hash)))

(defn- cmd-comment
  "A comment IS an artifact: a text blob stored by hash, attached under
  comment/<at>. No dedicated event type (v6) — one attach covers both."
  [{:keys [pos opts]}]
  (let [[ticket & words] pos
        s (the-store)
        id (resolve-id s ticket)
        at (now)
        text (str/join " " words)
        bytes (.getBytes ^String text "UTF-8")
        hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
        dest (io/file (root) "tickets" (str id) "blobs" hash)]
    (.mkdirs (.getParentFile dest))
    (spit dest text)
    (store/append! s (event/attach-artifact
                      {:ticket id :actor (actor opts) :at at
                       :parents (dag/heads (store/events s id))
                       :path (str "comment/" at) :hash hash}))
    (println "ok" hash)))

(defn- cmd-status [{:keys [pos]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        {:keys [events state process roles]} (ticket-ctx s id)
        reached (stage/effective-reached process events (now) roles)
        current (stage/current-stages process reached)]
    (println "ticket: " id)
    (println "title:  " (:title state))
    (println "process:" (:process state)
             (str "v" (:process-version state))
             (str "@ " (some-> (:process-hash state) (subs 0 19)) "…"))
    (println "stage:  " (str/join ", " (map name current))
             (str "(reached: " (str/join ", " (map name (sort-by str reached))) ")"))
    (println "facts:")
    (doseq [[path _] (sort-by (comp pr-str first) (:facts state))
            :let [{:keys [status value by note]} (red/fact-status state path)]]
      (println " " path "=" (pr-str value)
               (case status
                 :present   (str "(by " by ")")
                 :disputed  (str "[DISPUTED by " by ": " note "]")
                 :retracted (str "[retracted by " by "]")
                 :conflicted "[CONFLICTED]"
                 "")))
    (println)
    (print (explain/render (explain/explain process events (now) roles)))))

(defn- cmd-explain [{:keys [pos]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        {:keys [events process roles]} (ticket-ctx s id)]
    (print (explain/render (explain/explain process events (now) roles)))))

(defn- cmd-log
  "The evidence timeline: stored events interleaved with DERIVED stage
  transitions, computed at render time from the evolve fold and never
  stored — the one law applied to the UI's own furniture."
  [{:keys [pos]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        {:keys [events process roles]} (ticket-ctx s id)
        timeline (:timeline (stage/evolve process events roles))]
    (doseq [[prev entry] (map vector (cons nil timeline) timeline)
            :let [e (first (filter #(= (:event-id entry) (:event/id %))
                                   events))
                  gained (sort-by str
                                  (remove (:reached prev #{})
                                          (:reached entry)))]]
      (println (str (:event/at e)) (name (:event/type e))
               (:event/actor e) (pr-str (:event/body e)))
      (doseq [stage-id gained]
        (println (str (:event/at e)) "derived —"
                 (str "stage " stage-id " became reachable"))))))

(defn- cmd-next
  "The inbox: frontier actions across every ticket, sorted by unlock
  count, filtered by --actor when given. A rendering of tik.next."
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        per-ticket (for [id (store/ticket-ids s)
                         :let [{:keys [events process roles]} (ticket-ctx s id)]]
                     (next-lens/contributions id process events t roles))
        {:keys [items waiting]} (next-lens/inbox per-ticket (:actor opts))]
    (if (empty? items)
      (println "Nothing actionable"
               (if (:actor opts) (str "for " (:actor opts)) "right now") "—"
               (count waiting) "stage(s) waiting on time or upstream stages.")
      (do
        (doseq [{:keys [action who unlocks]} items]
          (println (format "%-42s unlocks %d"
                           (str (name (first action)) " "
                                (pr-str (second action))
                                (when (not= :anyone who)
                                  (str "  (" (clojure.string/join ", " (sort who)) ")")))
                           (count unlocks)))
          (doseq [{:keys [ticket stage hint]} unlocks]
            (println (str "    " (subs (str ticket) 0 8) " -> " stage
                          (when hint (str "  (see: " hint ")"))))))
        (when (seq waiting)
          (println (str "waiting: " (count waiting)
                        " stage(s) gated on time or upstream stages")))))))

(defn- cmd-ls [_]
  (let [s (the-store)]
    (doseq [id (store/ticket-ids s)
            :let [{:keys [events state process roles]} (ticket-ctx s id)
                  reached (stage/effective-reached process events (now) roles)
                  current (stage/current-stages process reached)]]
      (println (subs (str id) 0 8)
               (format "%-24s" (str/join "," (map name current)))
               (:title state)))))

(defn- apply-step
  "One scripted step against sim/test state {:events :now :actor}. Steps:
  [:actor \"x\"] [:now \"+PT48H\"|\"<inst>\"] [:set path value]
  [:retract path] [:dispute path reason] [:attach path]. Appended events
  get strictly increasing claimed times so supersedes never lose ties."
  [{:keys [events now actor] :as st} [op & args]]
  (let [tick (.plusMillis ^Instant now 1)
        arg {:ticket (:event/ticket (first events)) :actor actor :at tick
             :parents #{(:event/id (peek events))}}
        append (fn [e] (-> st (update :events conj e) (assoc :now tick)))]
    (case op
      :actor (assoc st :actor (first args))
      :now (let [w (first args)]
             (assoc st :now (if (str/starts-with? w "+")
                              (.plus ^Instant now (Duration/parse (subs w 1)))
                              (Instant/parse w))))
      :set (append (event/assert-fact (assoc arg :path (first args)
                                             :value (second args))))
      :retract (append (event/retract-fact (assoc arg :path (first args))))
      :dispute (append (event/dispute-fact (assoc arg :path (first args)
                                                  :reason (second args))))
      :attach (append (event/attach-artifact
                       (assoc arg :path (first args)
                              :hash (str "sha256-" (canonical/sha256-hex
                                                    (first args)))))))))

(defn- sim-render [proc events sim-now]
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
    (print (explain/render (explain/explain proc events sim-now roles)))))

(def ^:private sim-help
  "  set k=v [k=v ...]   assert facts        retract <k>      withdraw a fact
  dispute <k> <why>   dispute a fact      attach <name>    fake artifact
  now +PT48H | <inst> move evaluation time                 actor <name>
  reset               fresh scratch ticket                 quit
  (empty line re-renders; the process file reloads automatically on change)")

(defn- sim-load [^File f]
  (let [p (edn/read-string (slurp f))
        problems (process/lint p)]
    (doseq [{:keys [level msg]} problems]
      (println (str "[" (name level) "] " msg)))
    (when (not-any? #(= :error (:level %)) problems) p)))

(defn- cmd-sim
  "Live process design: a scratch ticket in memory, a definition that
  reloads whenever its file changes. Assert facts and watch stages
  derive; edit the EDN in another window and the next render uses the
  new rules. Pure derivation each round — nothing is stored."
  [{:keys [pos opts]}]
  (let [f (io/file (first pos))]
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

(def ^:private test-epoch (Instant/parse "2026-01-01T00:00:00Z"))

(defn- cmd-test
  "Process tests: scripted inputs, expected derived outcomes. The file is
  {:test/process \"<path relative to this file>\"
   :test/cases [{:case/name … :case/steps [[:set [:category] :technical] …]
                 :case/expect {:reached #{…} :current #{…}
                               :includes #{…} :excludes #{…}}} …]}
  Deterministic: fixed epoch, pure derivation. A failing case prints
  explain — the process tells you WHY the stage did not derive."
  [{:keys [pos]}]
  (let [f (io/file (first pos))
        _ (when-not (.exists f) (die "no such file:" (str f)))
        {:test/keys [process cases]} (edn/read-string (slurp f))
        proc (edn/read-string (slurp (io/file (.getParentFile (.getAbsoluteFile f))
                                              process)))
        roles (:process/roles proc {})
        lint-errors (filter #(= :error (:level %)) (process/lint proc))
        failures (atom 0)]
    (doseq [{:keys [msg]} lint-errors] (println "[error]" msg))
    (when (seq lint-errors) (die "process definition has lint errors"))
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
              (print (str/replace (explain/render
                                   (explain/explain proc events now roles))
                                  #"(?m)^" "        "))))))
    (if (zero? @failures)
      (println "test: PASS")
      (do (println (str "test: FAIL (" @failures ")")) (System/exit 1)))))

(defn- cmd-lint [{:keys [pos]}]
  (let [proc (edn/read-string (slurp (first pos)))
        problems (process/lint proc)]
    (doseq [{:keys [level msg]} problems]
      (println (str "[" (name level) "] " msg)))
    (when (some #(= :error (:level %)) problems) (System/exit 1))
    (when (empty? problems) (println "clean"))))

(defn- cmd-verify
  "The verify ladder, Phase 0 rungs:
  L0 integrity — every file's canonical bytes hash to its name; every
     parent exists; exactly one root
  L1 authenticity — signatures (Phase 1; reported as skipped)
  L2 reproducibility — re-derive stages under the hash-pinned process."
  [{:keys [pos]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        dir (io/file (root) "tickets" (str id) "events")
        files (filter #(.isFile ^File %) (.listFiles dir))
        failures (atom 0)
        check (fn [ok? msg]
                (println (str (if ok? "  ok    " "  FAIL  ") msg))
                (when-not ok? (swap! failures inc)))]
    (println "L0 integrity")
    (doseq [^File f files
            :let [e (fstore/read-event f)
                  bytes-on-disk (slurp f)
                  stem (str/replace (.getName f) #"\.edn$" "")]]
      (check (= bytes-on-disk (canonical/emit (dissoc e :event/id)))
             (str stem " bytes are exactly the canonical hashed region"))
      (check (= stem (event/event-id e))
             (str stem " sha256(bytes) = filename = id"))
      (check (event/valid? e) (str stem " schema-valid")))
    (let [evs (store/events s id)]
      (check (empty? (dag/missing-parents evs)) "all parents present")
      (check (= 1 (count (dag/roots evs))) "exactly one root (:ticket/create)")
      (println "L1 authenticity")
      (println "  skip  no detached signatures in phase 0 (ADR 0007)")
      (println "L2 reproducibility")
      (let [state (red/ticket-state evs)
            proc (load-process (name (:process state)))]
        (check (or (nil? (:process-hash state))
                   (= (:process-hash state) (process/process-hash proc)))
               "pinned process hash matches definition on disk")
        (let [reached (stage/effective-reached proc evs (now)
                                               (:process/roles proc {}))]
          (println (str "  ok    derived: "
                        (str/join ", " (map str (sort-by str reached))))))))
    (if (zero? @failures)
      (println "verify: PASS")
      (do (println (str "verify: FAIL (" @failures ")")) (System/exit 1)))))

(def ^:private usage
  "tik — a process system, not a ticket system

  tik new <process> [--title T] [--actor A]     mint a ticket (pins process hash)
  tik set <id> k=v [k=v ...] [--actor A]        assert facts (dotted keys nest)
  tik retract <id> <k> [--reason R]             withdraw a fact (no replacement)
  tik dispute <id> <k> [--reason R]             reject a fact; stage regresses by derivation
  tik diff <id> [n]                             evidence gained over the last n events
  tik attach <id> <file>                        attach an artifact (stored by hash)
  tik comment <id> <text...>                    add a comment (a text blob, attached by hash)
  tik status <id>                               derived stage, facts, what's next
  tik explain <id>                              what is needed to advance
  tik log <id>                                  the event history
  tik ls                                        all tickets with derived stages
  tik next [--actor A]                          the inbox: what unlocks the most work
  tik verify <id>                               the verify ladder (L0/L2)
  tik lint <process.edn>                        lint a process definition
  tik sim <process.edn>                         live process design (scratch ticket,
                                                auto-reloading definition)
  tik test <tests.edn>                          run scripted process tests (steps in,
                                                expected stages out)")

(defn -main [& args]
  (let [[cmd & more] args
        parsed (parse-args (vec more))]
    (case cmd
      "new"     (cmd-new parsed)
      "set"     (cmd-set parsed)
      "retract" (cmd-retract parsed)
      "dispute" (cmd-dispute parsed)
      "diff"    (cmd-diff parsed)
      "attach"  (cmd-attach parsed)
      "comment" (cmd-comment parsed)
      "status"  (cmd-status parsed)
      "explain" (cmd-explain parsed)
      "log"     (cmd-log parsed)
      "ls"      (cmd-ls parsed)
      "next"    (cmd-next parsed)
      "verify"  (cmd-verify parsed)
      "lint"    (cmd-lint parsed)
      "sim"     (cmd-sim parsed)
      "test"    (cmd-test parsed)
      (println usage))))
