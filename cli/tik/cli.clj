(ns tik.cli
  "The tik CLI. babashka-first, JVM-compatible.

  Work commands:      new set dispute attach comment status explain log ls verify
  Process developer:  lint

  Conventions:
  - store root:  $TIK_ROOT or the current directory
  - actor:       --actor, else $TIK_ACTOR, else the OS user
  - fact keys:   dotted paths -> keyword vectors
  - fact values: parsed as EDN; bare words become keywords"
  (:require [tik.args :refer [parse-args actor parse-key parse-value
                              read-edn-file slurp-existing typed-value]]
            [tik.audit :as audit]
            [tik.bridge :as bridge]
            [tik.design :as design]
            [tik.effects :as effects]
            [tik.storeops :as storeops]
            [tik.cli-core :refer [*exit-fn* all-ticket-ctx append!* archive-process!
                                  by-hash-file cache-flush! context-facts die
                                  display-title eval-instant exit! link-facts link-lines
                                  link-row load-process parse-instant
                                  stage-delta
                                  load-process-arg load-ticket now
                                  put-signature! resolve-file resolve-id
                                  resolve-id-soft root signed-event-ids signing-key
                                  store-established? the-store ticket-ctx
                                  ticket-row]]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [tik.author :as author]
            [tik.templates :as templates]
            [tik.canonical :as canonical]
            [tik.causal :as causal]
            [tik.dag :as dag]
            [tik.dupe :as dupe]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.guard :as guard]
            [tik.lint :as lint]
            [tik.next :as next-lens]
            [tik.draw :as draw]
            [tik.plan :as plan]
            [tik.select :as select]
            [tik.template :as template]
            [tik.text :refer [safe-name]]
            [tik.work :as work]
            [malli.core :as m]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.render :refer [tint sid shash print-problems
                                emit-data paint-stage paint-explain]]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.protocol :as store])
  (:import (java.io File)))





(defn- open-ticket-rows
  "{:id :title :text} for every unsettled ticket — the duplicate
  radar's comparison set."
  [s t]
  (vec
   (for [id (store/ticket-ids s)
         :let [{:keys [settled? title haystack]} (ticket-row s t id)]
         :when (not settled?)]
     {:id id :title title :text haystack})))

(defn- field-hint
  "A short type hint for prompting one template parameter."
  [child]
  (case (m/type child)
    :boolean "(y/N)"
    (:vector :sequential :set) "(one or more, space-separated)"
    (:int :double) "(a number)"
    :enum (str "(one of: " (str/join ", " (m/children child)) ")")
    ""))

(defn- coerce-param
  "Coerce a raw string answer to a template parameter's declared type."
  [child raw]
  (let [raw (str/trim raw)]
    (case (m/type child)
      :boolean (contains? #{"y" "yes" "true" "1"} (str/lower-case raw))
      :int (parse-long raw)
      :double (parse-double raw)
      :string raw
      :keyword (keyword raw)
      (:vector :sequential) (mapv #(coerce-param (first (m/children child)) %)
                                  (remove str/blank? (str/split raw #"[\s,]+")))
      :set (set (mapv #(coerce-param (first (m/children child)) %)
                      (remove str/blank? (str/split raw #"[\s,]+"))))
      :enum (let [vs (m/children child)]
              (or (some #(when (= raw (str %)) %) vs) (keyword raw)))
      (canonical/parse raw))))

(defn- template-fields
  "Prompt specs for a template's :tik/params — one per :map entry."
  [tmpl]
  (when-let [schema (:tik/params tmpl)]
    (for [[k props child] (m/children (m/schema schema))]
      {:key k :optional? (boolean (:optional props))
       :desc (:description props) :child child :hint (field-hint child)})))

(defn- prompt-params
  "Interactively ask for each parameter, typed and validated by the
  template's own :tik/params spec — no hand-writing EDN. Eager (a
  reduce, not a lazy for) so the prompt/read side effects stay ordered."
  [proc-name fields]
  (println (str "\n" proc-name " needs a few choices:\n"))
  (reduce (fn [acc {:keys [key optional? desc child hint]}]
            (print (str "  " (format "%-14s" (name key))
                        (when desc (str desc "  ")) hint "\n              > "))
            (flush)
            (let [raw (or (read-line) "")]
              (if (and optional? (str/blank? raw))
                acc
                (assoc acc key (coerce-param child raw)))))
          {}
          fields))

(defn- collect-params
  "Parameters for a template: from --params <file.edn>, else interactive
  prompts driven by the template's spec."
  [tmpl proc-name opts]
  (if-let [pf (:params opts)]
    (read-edn-file (io/file pf))
    (prompt-params proc-name (template-fields tmpl))))

(defn- source-root
  "Where a bundle's :hint paths resolve: the store-root above a
  processes/ or templates/ file, else the file's own directory."
  ^File [^File f]
  (let [parent (.getParentFile (.getCanonicalFile f))]
    (if (#{"processes" "templates"} (.getName parent))
      (.getParentFile parent)
      parent)))

(defn- adopt-runbooks!
  "Copy the runbooks a definition's stages :hint into this store, from
  the bundle's source root. Returns how many were copied."
  [definition ^File src-root dest-root]
  (let [n (atom 0)]
    (doseq [h (keep :hint (:process/stages definition))
            :let [sf (io/file src-root h) df (io/file dest-root h)]
            :when (and (.exists sf) (not (.exists df)))]
      (io/make-parents df)
      (io/copy sf df)
      (swap! n inc))
    @n))

(defn- cmd-adopt
  "adopt <process-or-template.edn> [--params <p.edn>]: bring a process
  from a library into this store. A plain definition is copied; a
  template (carries :tik/params) is filled — interactively by default,
  its own spec driving typed, validated prompts — expanded to a
  definition, linted, and written to processes/, with its runbooks
  copied alongside. The expanded EDN is authoritative; nothing runs the
  template as code (§19)."
  [{:keys [pos opts]}]
  (let [src (or (first pos)
                (die "usage: tik adopt <process-or-template.edn> [--params p.edn]"))
        srcf (io/file src)
        _ (when-not (.exists srcf) (die (str "no such file: " src)))
        raw (read-edn-file srcf)
        tmpl? (template/template? raw)
        body (if tmpl? (:tik/template raw) raw)
        nameable? #(or (keyword? %) (string? %) (symbol? %))
        ;; a label for the prompts only — the body's id may be a param
        ;; marker (a vector), resolved only by expansion, so fall back to
        ;; the file stem rather than calling `name` on a non-name
        label (let [pid (:process/id body)]
                (if (nameable? pid)
                  (name pid)
                  (str/replace (.getName srcf) #"\.(tmpl\.)?edn$" "")))
        definition (if tmpl? (template/expand raw (collect-params raw label opts)) raw)
        ;; the authoritative id comes from the EXPANDED definition, and
        ;; must be a real name — a template that expands to no usable id
        ;; is rejected here, not cast-crashed downstream
        _ (when-not (and (map? definition) (nameable? (:process/id definition)))
            (die "not a process or template (its :process/id is missing or not a name)"))
        _ (when (print-problems (lint/lint definition))
            (die "refusing to adopt a definition with lint errors"))
        pname (name (:process/id definition))
        dest (io/file (root) "processes" (str pname ".edn"))]
    (io/make-parents dest)
    (spit dest (with-out-str (pp/pprint definition)))
    (let [copied (adopt-runbooks! definition (source-root srcf) (root))]
      (println (str "✓ " (if tmpl? "expanded" "adopted") " · lint clean → processes/"
                    pname ".edn"
                    (when (pos? copied) (str "  (+ " copied " runbook(s))"))))
      (when (some #(empty? (:members (val %) [])) (:process/roles definition))
        (println "  fill in the empty roles (tik actor add …), then:"))
      (println (str "  tik new " pname)))))

(defn- cmd-new [{:keys [pos opts]}]
  (let [[proc-name] pos
        proc (load-process proc-name)
        s (the-store)
        t (now)
        similar (when-let [title (:title opts)]
                  (take 3 (dupe/radar title (open-ticket-rows s t) 0.4)))
        id (random-uuid)
        e (event/create-ticket {:ticket id :actor (actor opts) :at t
                                ;; --title with no following value parses to Boolean true —
                                ;; the log stores strings, never a parser artifact
                                :title (let [t (:title opts)]
                                         (if (string? t) t ""))
                                :process (keyword proc-name)
                                :version (:process/version proc)
                                :process-hash (archive-process! proc)})]
    (append!* s e opts)
    (doseq [[path value] (context-facts)]
      (append!* s (event/assert-fact
                   {:ticket id :actor (actor opts) :at (now)
                    :parents (dag/heads (store/events s id))
                    :path path :value value})
                opts)
      (binding [*out* *err*]
        (println (str "context: " (str/join "." (map name path)) "="
                      (pr-str value)))))
    (println (str id))
    (let [{:keys [events process roles]} (ticket-ctx s id)
          current (stage/current-stages
                   process (stage/effective-reached process events (now) roles))]
      (binding [*out* *err*]
        (println (str "stage: " (str/join ", " (map name current))
                      " — next: tik explain " (sid id)))))
    (doseq [{existing :id :keys [title score]} similar]
      (binding [*out* *err*]
        (println (str "note: looks like " (sid existing) " \"" title
                      "\" (" (int (* 100 score)) "% similar) — if this IS "
                      "that, record it: tik set " (sid id)
                      " duplicate-of=\"" (sid existing) "\""))))
    (cache-flush!)))

(defn- cmd-set [{:keys [pos opts]}]
  (let [[ticket & kvs] pos
        _ (when (or (nil? ticket) (empty? kvs))
            (die "usage: tik set <id> key=value [key=value ...]   (dotted keys nest: payment.ref=abc)"))
        ;; unquoted prose splits at the shell; words without '=' belong
        ;; to the previous pair — `set <id> note=hello world` records
        ;; note="hello world" instead of erroring on "world"
        kvs (reduce (fn [acc kv]
                      (cond
                        (str/includes? kv "=") (conj acc kv)
                        (empty? acc)
                        (die (str "'" kv "' is not key=value — write it as "
                                  kv "=<value>"))
                        :else (conj (pop acc) (str (peek acc) " " kv))))
                    [] kvs)
        s (the-store)
        {:keys [id process]} (load-ticket s ticket)]
    ;; heads recomputed per event: linear chain within one command
    (doseq [kv kvs :let [[k v] (str/split kv #"=" 2)
                         path (parse-key k)]]
      (append!* s (event/assert-fact
                   {:ticket id :actor (actor opts) :at (now)
                    :parents (dag/heads (store/events s id))
                    :path path :value (typed-value process path v)})
                opts))
    (println "ok")))

(defn- cmd-retract [{:keys [pos opts]}]
  (let [[ticket k] pos
        s (the-store)
        id (resolve-id s ticket)]
    (append!* s (event/retract-fact
                 {:ticket id :actor (actor opts) :at (now)
                  :parents (dag/heads (store/events s id))
                  :path (parse-key k)
                  :reason (:reason opts)})
              opts)
    (println "ok")))

(defn- cmd-diff
  "Evidence gained between two points in the log: derive at event n-k and
  at the head, report stages that became derivable and facts that
  changed — never 'transitions performed', because none were."
  [{:keys [pos opts]}]
  (let [s (the-store)
        {:keys [events process roles]} (load-ticket s (first pos))
        ;; k = how many trailing events to roll back; a non-number,
        ;; negative, or huge k must be a clean usage error, not a raw
        ;; NumberFormat/IndexOutOfBounds surfaced as "a bug in tik"
        k (if-let [ks (second pos)]
            (or (parse-long ks)
                (die (str "diff <id> [k]: k must be a whole number, got " ks)))
            1)
        _ (when (neg? k) (die "diff k must be zero or positive"))
        ordered (vec (red/ordered events))
        before-events (subvec ordered 0 (max 1 (- (count ordered) (min k (count ordered)))))
        t (now)
        before-reached (stage/effective-reached process before-events t roles)
        after-reached (stage/effective-reached process ordered t roles)
        before-facts (guard/fact-map (red/ticket-state before-events))
        after-facts (guard/fact-map (red/ticket-state ordered))
        {:keys [gained lost]} (stage-delta before-reached after-reached)
        fact-changes (for [path (sort-by str (set (concat (keys before-facts)
                                                          (keys after-facts))))
                           :let [b (get before-facts path) a (get after-facts path)]
                           :when (not= b a)]
                       {:path path :before b :after a})
        data {:window (min k (dec (count ordered)))
              :gained (vec gained) :lost (vec lost) :facts (vec fact-changes)}]
    (when-not (emit-data opts data)
      (println (str "last " (:window data) " event(s):"))
      (doseq [stage-id gained] (println "  + stage" stage-id "became derivable"))
      (doseq [stage-id lost] (println "  - stage" stage-id "no longer derivable"))
      (doseq [{:keys [path before after]} fact-changes]
        (cond
          (nil? before) (println "  + fact" path "=" (pr-str after))
          (nil? after) (println "  - fact" path "(was" (pr-str before) ")")
          :else (println "  ~ fact" path "=" (pr-str after)
                         "(was" (pr-str before) ")")))
      (when (and (= before-reached after-reached) (= before-facts after-facts))
        (println "  no derivable change")))))

(defn- cmd-dispute [{:keys [pos opts]}]
  (let [[ticket k] pos
        s (the-store)
        id (resolve-id s ticket)]
    (append!* s (event/dispute-fact
                 {:ticket id :actor (actor opts) :at (now)
                  :parents (dag/heads (store/events s id))
                  :path (parse-key k)
                  :reason (or (:reason opts) "disputed")})
              opts)
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
    (append!* s (event/attach-artifact
                 (cond-> {:ticket id :actor (actor opts) :at (now)
                          :parents (dag/heads (store/events s id))
                          :path (str "repro/" (.getName src)) :hash hash}
                   ;; lineage is a CLAIM by the attacher (ADR 0014):
                   ;; carried in the event body, disputable like any claim
                   (:derived-from opts)
                   (assoc :derived-from (:derived-from opts))))
              opts)
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
    (append!* s (event/attach-artifact
                 {:ticket id :actor (actor opts) :at at
                  :parents (dag/heads (store/events s id))
                  :path (str "comment/" at) :hash hash})
              opts)
    (println "ok" hash)))

(defn- unmet-deps
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

(defn- cmd-status [{:keys [pos opts]}]
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

(defn- cmd-explain [{:keys [pos opts]}]
  (let [s (the-store)
        {:keys [events process roles]} (load-ticket s (first pos))
        blocks (explain/explain process events (eval-instant opts) roles)
        blocks (if-let [who (:actor opts)]
                 (explain/for-actor blocks roles who)
                 blocks)]
    (when-not (emit-data opts blocks)
      (print (paint-explain (explain/render blocks))))))

(defn- cmd-causal
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

(defn- cmd-log
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

(defn- cmd-next
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

(defn- plan-graph
  "The cross-ticket dependency graph from the store as tik.plan input:
  edges keyed by ticket id -> the set of resolved `:depends-on` target
  ids, the settled set, and a display-title per node. An unresolvable
  target is kept as its raw string — a node with no prerequisites that
  is never settled, so it blocks conservatively (matching `next`)."
  [s t]
  (let [ctx (into {} (map (juxt :id identity)) (all-ticket-ctx s))
        ids (vec (keys ctx))
        edges (into {}
                    (for [id ids]
                      [id (->> (link-facts (:state (ctx id)))
                               (filter #(= :depends-on (:rel %)))
                               (map #(or (resolve-id-soft s (:target %))
                                         (:target %)))
                               set)]))
        settled (set
                 (for [id ids
                       :let [{:keys [process events roles]} (ctx id)]
                       :when (next-lens/settled? process events t roles)]
                   id))
        title (fn [n] (if-let [c (ctx n)]
                        (display-title (:state c))
                        (str n)))]
    {:edges edges :settled settled :title title}))

(defn- short-title
  "A node label for one line: 8-char id + trimmed title."
  [title n]
  (let [t (some-> (title n) str)
        s (str n)]
    (str (if (re-matches #"[0-9a-f-]{8,}" s) (sid s) s)
         (when (and (seq t) (not= t s))
           (str " " (subs t 0 (min 32 (count t))))))))

(defn- plan-html
  "One self-contained HTML page: the plan as a roadmap — a critical-path
  track, then Ready / Blocked / Done columns of cards, cycles flagged in
  red. No scripts, no network; a rendering of tik.plan/summary."
  [{:keys [edges settled title]} summary]
  (let [{:keys [ready blocked cyclic critical-path unlocks status]} summary
        esc (fn [x] (-> (str x) (str/replace "&" "&amp;")
                        (str/replace "<" "&lt;") (str/replace ">" "&gt;")))
        card (fn [n]
               (let [st (status n)
                     u (get unlocks n 0)
                     deps (remove settled (get edges n))]
                 (str "<div class='card " (name st) "'>"
                      "<div class='t'>" (esc (title n)) "</div>"
                      "<div class='id'>" (esc (sid n)) "</div>"
                      (when (pos? u) (str "<div class='u'>unlocks " u "</div>"))
                      (when (seq deps)
                        (str "<div class='w'>waiting on "
                             (str/join ", " (map #(esc (sid %)) deps))
                             "</div>"))
                      "</div>")))
        column (fn [label ns]
                 (str "<section><h2>" label " <span class='n'>" (count ns) "</span></h2>"
                      (str/join (map card (sort-by str ns))) "</section>"))]
    (str "<!doctype html><meta charset=utf-8><title>tik plan</title>"
         "<style>"
         ":root{--bg:#fff;--fg:#111;--dim:#666;--line:#e2e2e2;--ready:#128a3a;"
         "--blocked:#b7791f;--done:#888;--cyc:#c0392b;--cp:#2456c9}"
         "@media(prefers-color-scheme:dark){:root{--bg:#14161a;--fg:#e8e8e8;"
         "--dim:#9aa;--line:#2a2e35;--ready:#3ddc84;--blocked:#e0b050;"
         "--done:#777;--cyc:#ff6b5e;--cp:#6aa1ff}}"
         "body{margin:0;font:15px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg)}"
         "header{padding:20px 28px;border-bottom:1px solid var(--line)}"
         "h1{font-size:18px;margin:0}"
         ".sum{color:var(--dim);margin-top:6px;font-size:13px}"
         ".cyc-banner{background:var(--cyc);color:#fff;padding:10px 28px;font-weight:600}"
         ".cp{padding:16px 28px;border-bottom:1px solid var(--line)}"
         ".cp h2{font-size:12px;text-transform:uppercase;letter-spacing:.06em;color:var(--dim);margin:0 0 10px}"
         ".track{display:flex;flex-wrap:wrap;gap:8px;align-items:center}"
         ".step{background:color-mix(in srgb,var(--cp) 15%,transparent);border:1px solid var(--cp);"
         "border-radius:8px;padding:6px 12px;font-weight:600}"
         ".arr{color:var(--cp)}"
         ".cols{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:20px;padding:24px 28px}"
         "section h2{font-size:13px;text-transform:uppercase;letter-spacing:.05em;color:var(--dim);margin:0 0 12px}"
         "section h2 .n{color:var(--fg);opacity:.6}"
         ".card{border:1px solid var(--line);border-left:4px solid var(--done);"
         "border-radius:8px;padding:10px 12px;margin-bottom:10px;background:color-mix(in srgb,var(--fg) 2%,transparent)}"
         ".card.ready{border-left-color:var(--ready)}"
         ".card.blocked{border-left-color:var(--blocked)}"
         ".card.cyclic{border-left-color:var(--cyc)}"
         ".card .t{font-weight:600}"
         ".card .id{color:var(--dim);font-size:12px;font-family:ui-monospace,monospace}"
         ".card .u{color:var(--ready);font-size:12px;margin-top:4px}"
         ".card .w{color:var(--blocked);font-size:12px;margin-top:4px}"
         "</style>"
         "<header><h1>tik plan</h1><div class='sum'>"
         (count ready) " ready · " (count blocked) " blocked · "
         (count settled) " done · "
         (if (seq cyclic) (str (count cyclic) " in a cycle") "no cycles")
         "</div></header>"
         (when (seq cyclic)
           (str "<div class='cyc-banner'>⚠ dependency cycle — "
                (str/join ", " (map #(esc (title %)) (sort-by str cyclic)))
                " can never proceed; break an edge</div>"))
         (when (seq critical-path)
           (str "<div class='cp'><h2>critical path — " (count critical-path)
                " step(s) remaining</h2><div class='track'>"
                (str/join "<span class='arr'>→</span>"
                          (map #(str "<span class='step'>" (esc (title %)) "</span>")
                               critical-path))
                "</div></div>"))
         "<div class='cols'>"
         (column "Ready" ready)
         (column "Blocked" blocked)
         (column "Done" settled)
         (when (seq cyclic) (column "Cyclic" cyclic))
         "</div>")))

(defn- cmd-plan
  "plan [<file.html>]: the dependency-link roadmap — a derived reading of
  every ticket, its `:depends-on` edges, and which are settled: what is
  ready, blocked, done, or caught in a cycle, plus the critical path and
  each item's downstream unlock impact. Not a scheduler (§19): it
  re-derives from current facts every read, so it never goes stale. With
  a .html argument, writes the fancy self-contained roadmap page."
  [{:keys [pos opts]}]
  (let [s (the-store)
        t (now)
        {:keys [edges settled title] :as g} (plan-graph s t)
        {:keys [ready blocked cyclic critical-path unlocks] :as summary}
        (plan/summary edges settled)]
    (cond
      (first pos)
      (do (spit (first pos) (plan-html g summary))
          (println (str "wrote " (first pos) " — the plan as a roadmap page")))
      (emit-data opts summary) nil
      :else
      (do
        (println (str (tint "1" "PLAN") "  "
                      (tint "32" (str (count ready) " ready")) " · "
                      (tint "33" (str (count blocked) " blocked")) " · "
                      (tint "2" (str (count settled) " done"))
                      (when (seq cyclic)
                        (str " · " (tint "31" (str (count cyclic) " in a cycle"))))))
        (when (seq cyclic)
          (println (tint "31" (str "\n⚠ dependency cycle (deadlock — break an edge): "
                                   (str/join ", " (map title (sort-by str cyclic)))))))
        (when (seq critical-path)
          (println (str "\ncritical path (" (count critical-path)
                        " step(s) remaining):"))
          (println (str "  " (str/join (tint "34" " → ")
                                       (map title critical-path)))))
        (when (seq ready)
          (println "\nready now:")
          (doseq [n (sort-by #(- (get unlocks % 0)) ready)]
            (println (str "  " (tint "32" "▸") " " (short-title title n)
                          (when (pos? (get unlocks n 0))
                            (tint "2" (str "   unlocks " (get unlocks n 0))))))))
        (when (seq blocked)
          (println "\nblocked:")
          (doseq [n (sort-by str blocked)
                  :let [deps (remove settled (get edges n))]]
            (println (str "  " (tint "33" "⏳") " " (short-title title n)
                          (tint "2" (str "   waiting on "
                                         (str/join ", " (map #(short-title title %) deps))))))))
        (when (and (empty? ready) (empty? blocked) (empty? cyclic))
          (println "\nno dependency links yet — `tik set <id> link.depends-on=<other>`"))))))

(defn- selector-row
  "A ticket's row for both selection and listing: the display fields plus
  every field a tik.select predicate reads (see that ns's row shape).
  One fold per ticket — the slow path a `--where` selector takes."
  [t {:keys [id events state process roles store]}]
  (let [reached (stage/effective-reached process events t roles)
        facts (guard/fact-map state)
        fstatus #(:status (red/fact-status state %))
        fkeys (keys (:facts state))]
    {:id id
     :title (display-title state)
     :current (stage/current-stages process reached)
     :reached reached
     :describe (some facts [[:description] [:summary] [:statement]])
     :links (vec (link-facts state))
     :state state
     :settled? (next-lens/settled-reached? process reached)
     ;; --- fields tik.select predicates read ---
     :facts facts
     :actors (into #{} (map :event/actor) events)
     :derived-from (into #{} (keep #(get-in % [:event/body
                                               :artifact/derived-from]))
                         events)
     :haystack (str/lower-case (str (display-title state) " " (pr-str facts)))
     :disputed? (boolean (some #(= :disputed (fstatus %)) fkeys))
     :conflicted? (boolean (some #(= :conflicted (fstatus %)) fkeys))
     :unsigned? (let [signed (signed-event-ids store id)]
                  (boolean (some #(not (contains? signed (:event/id %)))
                                 events)))}))

(defn- compiled-selector
  "Compile a `--where` value into a row predicate, dying with the usage
  message on a bad term. A missing selector (nil) matches everything."
  [expr]
  (when (true? expr)
    (die "usage: tik ls --where <selector>  (e.g. stage=:blocked and disputed)"))
  (try (select/compile (or expr ""))
       (catch clojure.lang.ExceptionInfo e (die (ex-message e)))))

(defn- selector-rows
  "A selector-row per ticket, over the already-isolated whole-store
  opener; a ticket whose DEEPER derivation (the stage fixpoint, facts)
  throws is skipped and named too, so one poison never hides a `--where`
  match. (ticket-ctx failures are already isolated by all-ticket-ctx.)"
  [s t]
  (keep (fn [ctx]
          (try (selector-row t ctx)
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*]
                   (println (str "skipping " (sid (:id ctx)) ": " (ex-message e))))
                 nil)))
        (all-ticket-ctx s)))

(defn- cmd-ls
  "Open tickets by default; settled ones (sticky terminal reached —
  :landed, :closed, :validated, :killed) hide behind --all. `--where`
  takes a selector (tik.select) — stage, facts, actors, disputes, text,
  composed — and reads every ticket's log; without it the cached fast
  path lists open tickets with zero event reads."
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        rows (if-not (:where opts)
               ;; the cached fast path: one directory listing per
               ;; unchanged ticket, zero event reads
               (for [id (store/ticket-ids s)
                     :let [row (ticket-row s t id)]]
                 (assoc row
                        :id id
                        :current (set (:current row))
                        :stale-ms (max 0 (- (inst-ms t)
                                            (:last-event-ms row 0)))))
               (filter (compiled-selector (:where opts))
                       (selector-rows s t)))
        visible (if (:all opts) rows (remove :settled? rows))]
    (when-not (emit-data opts
                         (mapv #(select-keys % [:id :title :current :describe :settled?])
                               visible))
            (doseq [{:keys [id current title describe settled? links error]} visible]
    (println (tint "2" (sid id))
             (if error
               (tint "31" (format "%-24s" "error"))
               (paint-stage (format "%-24s" (str/join "," (map name current)))
                            settled? (contains? current :parked)))
             title)
    (when (and (:long opts) describe)
      (println (tint "2" (str "         " describe))))
    (when (and (:long opts) (seq links))
      (doseq [line (link-lines s t links)]
        (println (tint "2" (str "         ↳ " line))))))
      (when (empty? visible)
        (if (empty? rows)
          (println
           (str (if (store-established?)
                  "no tickets yet — start with:\n"
                  (str "no tik store here yet — establish one, then start:\n"
                       "  tik init                    make this directory a store\n"
                       "  tik init --hidden           or keep it in .tik/ (e.g. above many repos)\n"))
                "  tik author                  describe your process; tik writes the definition\n"
                "  tik author --template bug   or start from a known-good shape\n"
                "  tik new track --title ...   or skip process design: just track a thing\n"
                "  tik set <id> key=value      record what is true; the stage derives itself"))
          (println "no matching tickets")))
      (let [hidden (- (count rows) (count visible))]
        (when (pos? hidden)
          (println (str "settled: " hidden
                        " finished ticket(s) hidden (--all shows)")))))
    (cache-flush!)))

(defn- cmd-search
  "tik search <text…> = ls over everything whose haystack holds every
  word — sugar for `ls --where '~w1 ~w2 …' --all`."
  [{:keys [pos opts]}]
  (cmd-ls {:opts (assoc opts
                        :where (str/join " " (map #(str "~" %) pos))
                        :all true)}))


(defn- cmd-reprocess
  "reprocess <id> <new.edn> [--apply]: re-pin a ticket to a new process
  definition. Dry-run BY DEFAULT (ADR 0002): a re-pin is a consequence-
  bearing decision, so show the derived-stage diff under the pinned
  definition vs the proposed one before anyone commits. --apply appends
  the signed :process/migrate event and archives the new definition by
  hash. (Distinct from `store migrate`, which converts the storage
  backend — this changes a ticket's rules, not where events live.)"
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        new-file (or (second pos)
                     (die "usage: tik reprocess <id> <new.edn> [--apply]"))
        _ (when-not (.exists (io/file new-file)) (die "no such file:" new-file))
        new-proc (read-edn-file (io/file new-file))
        _ (when (print-problems (lint/lint new-proc))
            (die "refusing to migrate to a definition with lint errors"))
        {:keys [events process]} (ticket-ctx s id)
        t (now)
        old-roles (:process/roles process {})
        new-roles (:process/roles new-proc {})
        before (stage/effective-reached process events t old-roles)
        after (stage/effective-reached new-proc events t new-roles)
        old-hash (process/process-hash process)
        new-hash (process/process-hash new-proc)]
    (when (= old-hash new-hash)
      (die "that is the definition the ticket is already pinned to"))
    (println (str "pinned:   v" (:process/version process) " @ " (shash old-hash) "…"))
    (println (str "proposed: v" (:process/version new-proc) " @ " (shash new-hash) "…"))
    (let [{:keys [gained lost]} (stage-delta before after)]
      (doseq [stage-id lost]
        (println "  - stage" stage-id "would REGRESS (no longer derivable)"))
      (doseq [stage-id gained]
        (println "  + stage" stage-id "would become derivable"))
      (when (= before after)
        (println "  derived stages unchanged")))
    ;; what the new rules would newly demand, for stages lost or blocked
    (doseq [{:keys [stage missing]} (explain/explain new-proc events t new-roles)
            :when (contains? before stage)]
      (println (str "  new blockers for " stage ":"))
      (doseq [r missing]
        (println (str "    ✗ " (explain/reason->text r)))))
    (if-not (:apply opts)
      (println "dry run — nothing recorded. Re-run with --apply to migrate.")
      (do (archive-process! new-proc)
          (append!* s (event/migrate-process
                       {:ticket id :actor (actor opts) :at t
                        :parents (dag/heads events)
                        :version (:process/version new-proc)
                        :process-hash new-hash
                        :reason (:reason opts)})
                    opts)
          (println "migrated — ticket now pins" (shash new-hash) "…")))))

(defn- cmd-process
  "process sign <name> [--key K]: publish the current definition —
  archive it content-addressed and sign the archived canonical bytes
  (namespace tik-process, ADR 0015). The hash stays the identity; the
  signature is the authority."
  [{:keys [pos opts]}]
  (let [[sub proc-name] pos]
    (when-not (and (= "sign" sub) proc-name)
      (die "usage: tik process sign <name> [--key K]"))
    (let [key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
          proc (load-process proc-name)
          hash (archive-process! proc)
          f (by-hash-file hash)
          sig (sign/sign! key f hash sign/namespace-process)]
      (println "published" proc-name "@" hash)
      (println "signature" (.getName ^File sig)))))

(defn- cmd-attest
  "attest <id> <claim-edn> [--body <edn>]: record an attestation — a
  signed claim whose semantics the kernel ignores (ADR 0009), read by
  lenses and by the v2 :attested-within guard."
  [{:keys [pos opts]}]
  (let [[ticket claim-str] pos
        _ (when-not claim-str (die "usage: tik attest <id> <claim-edn>"))
        s (the-store)
        id (resolve-id s ticket)
        claim (canonical/parse claim-str)
        extra (some-> (:body opts) canonical/parse)]
    (append!* s (event/add-attestation
                 {:ticket id :actor (actor opts) :at (now)
                  :parents (dag/heads (store/events s id))
                  :claim (merge {:claim claim} extra)})
              opts)
    (println "attested" (pr-str claim) "as" (actor opts))))

(defn- agent-admissible
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

(defn- agent-refuse! [opts actor-name attempted admissible]
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

(defn- cmd-agent
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

(defn- cmd-actor
  "actor add <name> <pubkey-file>: bind an actor to a key in the store's
  allowed-signers registry (identity ladder rung 1, PLAN §9)."
  [{:keys [pos]}]
  (let [[sub actor-name pubkey-file] pos]
    (when-not (and (= "add" sub) actor-name pubkey-file)
      (die "usage: tik actor add <name> <pubkey-file>"))
    ;; the name is written verbatim into the OpenSSH allowed-signers
    ;; registry (`<name> namespaces="tik-*" <key>`); whitespace, a quote,
    ;; or a newline would split it into a second, attacker-shaped line
    ;; (binding a victim principal to a stray key) or widen the namespace
    ;; restriction — reject it rather than corrupt the trust base
    (when (re-find #"[\s\"\\]" actor-name)
      (die (str "invalid actor name " (pr-str actor-name)
                ": no whitespace, quotes, or backslashes (the name is"
                " written verbatim into the allowed-signers registry)")))
    (let [pubkey (str/trim (slurp-existing "public key" pubkey-file))
          line (sign/allowed-signers-line actor-name pubkey)
          f (io/file (root) "actors")]
      (spit f (str line "
") :append true)
      (println "ok" (sign/fingerprint pubkey)))))

(defn- cmd-sign
  "Sign this actor's OWN events that this key has not signed yet. A
  signature is an authorship claim (ADR 0010), so signing another
  actor's events would assert something false — those are skipped."
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
        me (actor opts)
        fpr (sign/fingerprint (sign/pubkey key))
        names (set (store/sidecar-names s id))
        mine (filter #(= me (:event/actor %)) (store/events s id))
        unsigned (remove #(contains? names (str (:event/id %) ".sig." fpr))
                         mine)]
    (doseq [e unsigned]
      (put-signature! s key id (:event/id e) "sig" sign/namespace-event
                      (store/event-bytes s id (:event/id e))))
    (println "signed" (count unsigned) "event(s) as" me
             (str "(" (count mine) " authored, key " fpr ")"))))

(defn- cmd-dupes
  "dupes [--threshold 0.4]: pairwise near-title similarity across open
  tickets — the lookalike report. (Selecting tickets by a predicate is
  `tik ls --where '…'`, or `tik ls --all --where '…'` to include settled
  ones — one selector grammar, one board lens.)"
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        threshold (or (some-> (:threshold opts) parse-value double) 0.4)
        pairs (dupe/lookalikes (open-ticket-rows s t) threshold)]
    (when-not (emit-data opts (mapv #(select-keys % [:a :b :score]) pairs))
      (doseq [{:keys [a b score]} pairs]
        (println (str (sid a) " ~ " (sid b)
                      "  " (int (* 100 score)) "% similar")))
      (println (count pairs) "lookalike pair(s) at >="
               (str (int (* 100 threshold)) "%")))))

(defn- html-escape [x]
  (-> (str x)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- cmd-board
  "One self-contained HTML file: the whole board, stage-colored, with
  facts and explain per ticket. No dependencies, no scripts, no network
  — mail it, archive it, open it anywhere. A rendering of the same
  derivation as every other lens."
  [{:keys [pos]}]
  (let [s (the-store)
        t (now)
        rows (for [{:keys [id events state process roles]} (all-ticket-ctx s)
                   :let [reached (stage/effective-reached process events t roles)
                         current (stage/current-stages process reached)
                         settled? (next-lens/settled-reached? process reached)]]
               {:id id :title (display-title state) :process (:process state)
                :current current :settled? settled?
                :parked? (contains? current :parked)
                :facts (sort-by (comp pr-str key) (guard/fact-map state))
                :blocks (explain/explain process events t roles reached)})
        chip (fn [{:keys [settled? parked?]}]
               (cond settled? "chip done" parked? "chip parked" :else "chip live"))
        html
        (str
         "<!doctype html><html><head><meta charset=\"utf-8\">"
         "<title>tik board</title><style>"
         "body{font:15px/1.5 system-ui;margin:2rem auto;max-width:60rem;padding:0 1rem;background:#fff;color:#1a1a1a}"
         "@media(prefers-color-scheme:dark){body{background:#14161a;color:#d5d9de}.card{background:#1b1e24!important;border-color:#2a2e36!important}}"
         ".card{border:1px solid #ddd;border-radius:8px;padding:.8rem 1rem;margin:.6rem 0;background:#fafafa}"
         ".chip{display:inline-block;border-radius:99px;padding:.05rem .6rem;font-size:.8rem;margin-right:.5rem;color:#fff}"
         ".live{background:#0e7490}.done{background:#15803d}.parked{background:#b45309}"
         ".id{opacity:.55;font-family:monospace;font-size:.85rem}"
         "ul{margin:.3rem 0 .1rem 1.2rem;padding:0}li{margin:.1rem 0}"
         ".miss{color:#b91c1c}.sat{color:#15803d}"
         "code{font-size:.85em;opacity:.85}"
         "h1{font-size:1.3rem}small{opacity:.6}"
         "</style></head><body>"
         "<h1>tik board</h1><small>derived " (html-escape t)
         " — every line f(events, now); nothing stored</small>"
         (str/join
                (for [{:keys [id title current facts blocks] :as row} rows]
                  (str "<div class=\"card\">"
                       "<span class=\"" (chip row) "\">"
                       (html-escape (str/join ", " (map name current))) "</span>"
                       "<b>" (html-escape title) "</b> "
                       "<span class=\"id\">" (html-escape (sid id)) "</span>"
                       (when (seq facts)
                         (str "<ul>"
                              (str/join (for [[p v] facts]
                                           (str "<li><code>" (html-escape (pr-str p))
                                                " = " (html-escape (pr-str v))
                                                "</code></li>")))
                              "</ul>"))
                       (str/join
                              (for [{:keys [stage missing]} blocks]
                                (str "<div><small>to reach <b>"
                                     (html-escape (name stage)) "</b>:</small><ul>"
                                     (str/join
                                            (for [r missing]
                                              (str "<li class=\"miss\">✗ "
                                                   (html-escape (explain/reason->text r))
                                                   "</li>")))
                                     "</ul></div>")))
                       "</div>")))
         "</body></html>")]
    (if-let [out (first pos)]
      (do (spit out html) (println "wrote" out))
      (print html))))

(defn- cmd-serve
  "serve [--port N]: the live board over HTTP, read-only. GET / renders
  the same HTML as `tik board` — freshly derived per request, because
  derivation is cheap and caches lie eventually. /tickets.edn and
  /explain/<id>.edn expose the ADR 0016 data for tools. httpkit ships
  inside babashka: zero new dependencies, and READ-ONLY on purpose —
  writes stay with the signed CLI/bridge/MCP surfaces where authorship
  is enforced."
  [{:keys [opts]}]
  (let [run-server (requiring-resolve 'org.httpkit.server/run-server)
        port (if-let [p (:port opts)]
               (or (parse-long (str p))
                   (die (str "serve --port must be a number, got " p)))
               7777)
        handler
        ;; a server request must NEVER reach a die/System-exit path —
        ;; one hostile GET would take the board down for everyone. Ids
        ;; resolve softly (404, not exit) and everything else that
        ;; raises answers 500 with words, request-scoped
        (fn [{:keys [uri]}]
          (try
            (cond
              (= uri "/")
              {:status 200
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body (with-out-str (cmd-board {:pos []}))}

              (= uri "/tickets.edn")
              {:status 200
               :headers {"Content-Type" "application/edn"}
               :body (with-out-str
                       (cmd-ls {:opts {:edn true :all true}}))}

              (re-matches #"/explain/[0-9a-f-]+\.edn" uri)
              (let [prefix (second (re-matches #"/explain/([0-9a-f-]+)\.edn"
                                               uri))]
                (if-let [id (resolve-id-soft (the-store) prefix)]
                  {:status 200
                   :headers {"Content-Type" "application/edn"}
                   :body (with-out-str
                           (cmd-explain {:pos [(str id)] :opts {:edn true}}))}
                  {:status 404
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body (str "tik: no unique ticket matching '" prefix
                              "'\n")}))

              :else {:status 404 :body "tik: not found\n"})
            (catch Throwable e
              {:status 500
               :headers {"Content-Type" "text/plain; charset=utf-8"}
               :body (str "tik: " (or (ex-message e)
                                      (.getName (class e))) "\n")})))]
    (run-server handler {:port port})
    (println (str "tik board live at http://127.0.0.1:" port
                  "  (read-only; ctrl-c stops)"))
    @(promise)))

(defn- cmd-work
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

(defn- cmd-roles
  "Who gates what: every role on the open board with its members and
  the stages waiting on its signature — the admin's inverse of
  next --role, derived from the definitions open tickets actually pin."
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        procs (distinct
               (for [{:keys [events process roles]} (all-ticket-ctx s)
                     :when (not (next-lens/settled? process events t roles))]
                 process))
        ;; distinct on the RENDERED row: several archived versions of
        ;; one process may be pinned; identical gating collapses
        rows (distinct
              (for [p procs
                    [role {:keys [members stages]}] (process/roles-gating p)]
                {:role role :process (:process/id p)
                 :members members :stages stages}))
        by-role (group-by :role rows)]
    (when-not (emit-data opts (vec rows))
            (doseq [[role entries] (sort-by (comp str key) by-role)]
        (println (str (tint "1" (str role)) " — "
                      (let [ms (distinct (mapcat :members entries))]
                        (if (seq ms) (str/join ", " ms)
                            (tint "31" "NO MEMBERS — nothing can satisfy this role")))))
        (doseq [{:keys [process stages]} (sort-by (comp str :process) entries)]
          (println (str "  " process ": "
                        (if (seq stages)
                          (str "gates " (str/join ", " (map str (sort-by str stages))))
                          "declared, gates no stage by signature")))))
      (when (empty? by-role)
        (println "no roles on the open board")))))

(def ^:private author-llm-prompt-head
  "You are helping design a tik process definition. Interview me about my
workflow, then output ONLY an EDN map in exactly this shape (no prose,
no code fences):

{:name \"kebab-case-process-name\"
 :purpose \"one line: what this process is for\"
 :stages [{:name \"stage-name\"
           :purpose \"one line: what reaching this stage means\"
           :after []            ; names of prerequisite stages ([] for the start)
           :needs [ ... ]}]
 :roles {\"role-name\" [\"actor\" ...]}}

Each entry in :needs is one of:
  {:kind :fact   :path [:dotted :path]}                    ; information anyone records
  {:kind :fact   :path [:amount] :type :number}            ; a numeric fact
  {:kind :choice :path [:category] :values [:a :b :c]}     ; one of fixed options
  {:kind :signature :role :approver}                       ; a role signs off
  {:kind :signature :role :reviewer :over [:some :fact]}   ; a role signs a specific fact
  {:kind :file   :prefix \"evidence/\"}                    ; a file must be attached
  {:kind :waited :duration \"PT48H\"}                      ; ISO-8601 time since creation
")

(defn- authoring-rules
  "The active rule set: built-ins merged with the store's
  authoring-rules.edn ({:disable [ids…] :rules [{…}…]}) when present.
  The store can live in git, so committing that file IS the org-wide
  distribution mechanism: everyone's check and everyone's prompt
  tighten together."
  []
  (let [f (io/file (root) "authoring-rules.edn")]
    (author/merge-rules (read-edn-file f))))

(defn- author-llm-prompt
  "Philosophy first, then the ACTIVE rule set — the same data check
  enforces, so prompt and lint can never teach different laws."
  [rules]
  (str author-llm-prompt-head
       "\nHow to think about a process (this is what separates good"
       " ones from task lists):\n"
       "- EVIDENCE, not tasks. A process is a chain of states, each\n"
       "  defined by what has become TRUE and what proves it. Before\n"
       "  writing any :needs entry ask: what would an auditor want to\n"
       "  SEE a year from now?\n"
       "- Several checkboxes are often ONE piece of evidence. If three\n"
       "  tasks land in one commit, the commit reference is the fact —\n"
       "  not three booleans about it.\n"
       "- Never restate what a system of record already proves. If\n"
       "  git, a registry or a dashboard shows it, the fact REFERENCES\n"
       "  it (a path@commit, a URL, an id).\n"
       "- Accountability is a signature, not a checkbox: whoever\n"
       "  stands behind a judgment signs it.\n"
       "- Prefer a :choice over a yes/no fact; prefer facts over flags.\n"
       "\nRules (tik author check enforces exactly these):\n"
       (str/join "\n"
                 (for [{:keys [teach msg level]} rules]
                   (str "- " (or teach msg)
                        (when (= :error level) " (hard error)"))))
       "\n- Every stage after the first needs at least one :needs entry"
       " —\n  a stage with none derives instantly and says nothing.\n"
       "- Role members must be REAL actor names (never the role's own\n"
       "  name, never placeholders).\n"
       "- 3 to 6 stages is almost always right; if you need more, the\n"
       "  process probably wants splitting.\n"
       "\nWhen I have answered enough, print the EDN. I will save it as\n"
       "answers.edn and run: tik author --from answers.edn"))

(defn- author-write! [^File f content force?]
  (when (and (.exists f) (not force?))
    (die (str (.getPath f) " already exists — pass --force to overwrite")))
  (io/make-parents f)
  (spit f content))

(defn- author-render [definition]
  (str ";; SPDX-FileCopyrightText: The tik Authors\n"
       ";; SPDX-License-Identifier: 0BSD\n"
       ";;\n"
       ";; Written by `tik author`. Edit freely — the definition is plain\n"
       ";; EDN; `tik lint` checks it, `tik sim` runs it live, `tik test`\n"
       ";; runs the scripted cases next to it.\n"
       (with-out-str (pp/pprint definition))))

(defn- cmd-author
  "The authoring lens (H11): interview -> linted definition + a test
  skeleton. --from <answers.edn> skips the interview (agents, tests);
  `author prompt` prints the LLM recipe that produces such a file."
  [{:keys [pos opts]}]
  (when (= "prompt" (first pos))
    (println (author-llm-prompt (authoring-rules)))
    (exit! 0))
  (when (= "check" (first pos))
    (let [f (resolve-file (or (second pos)
                              (die "usage: tik author check <answers.edn>")))
          _ (when-not (.exists f) (die (str "no such file: " (second pos))))
          findings (author/check (read-edn-file f)
                                 (authoring-rules))
          errors? (print-problems findings)]
      (if (empty? findings)
        (println "clean — build it: tik author --from" (str f))
        (when errors? (exit! 1)))
      (exit! 0)))
  (let [answers (cond
                  (:template opts)
                  (or (get templates/templates (:template opts))
                      (die (str "no template '" (:template opts) "' — available: "
                                (str/join ", " (sort (keys templates/templates))))))
                  (:from opts) (read-edn-file (resolve-file (:from opts)))
                  :else (author/interview read-line #(do (print %) (flush))))
        answers (if (string? (:name opts))
                  (assoc answers :name (:name opts))
                  answers)
        _ (when (str/blank? (:name answers))
            (die "the process needs a name — run tik author again"))
        _ (when (binding [*out* *err*]
                  (print-problems (author/check answers (authoring-rules))))
            (die "fix the answers file first (tik author check <file> re-checks without writing)"))
        pname (:name answers)
        definition (author/with-runbook-hints
                    (author/build-process answers) pname)
        def-file (io/file (root) "processes" (str pname ".edn"))
        tests-file (io/file (root) "processes" (str pname ".tests.edn"))
        problems (lint/lint definition)]
    (author-write! def-file (author-render definition) (:force opts))
    (author-write! tests-file
                   (str ";; SPDX-FileCopyrightText: The tik Authors\n"
                        ";; SPDX-License-Identifier: 0BSD\n"
                        ";;\n"
                        ";; Starter cases from `tik author` — one per outcome. Each\n"
                        ";; fails until you state HOW the outcome is reached; the\n"
                        ";; failure prints explain, which shows what is missing.\n"
                        (with-out-str
                          (pp/pprint (author/tests-skeleton definition
                                                            (str pname ".edn")))))
                   (:force opts))
    (doseq [[path content] (author/runbook-stubs answers)]
      (let [f (io/file (root) path)]
        (author-write! f content (:force opts))
        (println (str "wrote " (.getPath f)))))
    (println (str "wrote " (.getPath def-file)))
    (println (str "wrote " (.getPath tests-file)))
    (if (empty? problems)
      (println "lint: clean")
      (print-problems problems))
    (println)
    (println "next steps:")
    (println (str "  bb tik sim " (.getPath def-file) "     try it live on a scratch ticket"))
    (println (str "  bb tik test " (.getPath tests-file) "  make the outcome cases pass"))
    (println (str "  bb tik new " pname "                    first real ticket"))
    (when (some #(= :error (:level %)) problems) (exit! 1))))

(defn- lint-store
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

(defn- ticket-stage-status
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

(defn- cmd-show
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

(defn- cmd-lint [{:keys [pos]}]
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

(def ^:private usage
  "tik — a process system, not a ticket system

  start here:
    tik author                       answer questions; tik writes the process
    tik new expense-approval         mint a ticket against it
    tik set <id> amount=120          record facts; the stage derives itself
    tik explain <id>                 what is missing, who can act

  tik init [--sqlite] [--hidden]                mark this directory as a store
                                                (--sqlite: single-file tik.db backend;
                                                default is the file/git store. --hidden:
                                                everything inside .tik/, e.g. above many repos).
  tik store migrate --to sqlite|file            convert this store's backend in place
                                                Commands find the store git-style:
                                                TIK_ROOT, else the nearest ancestor
                                                with .tik/ or tickets/, else here
  tik adopt <process|template.edn>              bring a library process into this store:
            [--params <p.edn>]                  a plain definition is copied; a template
                                                (:tik/params) is filled — interactively by
                                                default, its spec driving typed prompts —
                                                expanded, linted, and written with its
                                                runbooks. The expanded EDN is authoritative
  tik new <process> [--title T] [--actor A]     mint a ticket (pins process hash);
                                                created beneath a store it inherits
                                                context as signed facts: repo=<name>
                                                from the enclosing git repo, plus any
                                                .tik-facts.edn maps on the way down
                                                (nearest wins; explicit beats auto)
  tik set <id> k=v [k=v ...] [--actor A]        assert facts (dotted keys nest)
  tik retract <id> <k> [--reason R]             withdraw a fact (no replacement)
  tik dispute <id> <k> [--reason R]             reject a fact; stage regresses by derivation
  tik diff <id> [n]                             evidence gained over the last n events
  tik attach <id> <file>                        attach an artifact (stored by hash)
  tik comment <id> <text...>                    add a comment (a text blob, attached by hash)
  tik status <id> [--at <instant>]              derived stage, facts, what's next
                  [--links :stage]              (--at: the state at ANY moment;
                                                --links: only that stage's links —
                                                the header still counts them all)
  tik explain <id> [--actor A] [--edn]          what is needed to advance
                                                (--actor: only what THIS person can
                                                act on, with a count of the rest;
                                                --edn: the ADR 0016 data contract —
                                                stable plumbing; text is never stable)
  tik log <id>                                  the event history
  tik causal <id> [--edn]                       which signed events made each reached
                                                stage true (negations and time say so
                                                honestly) — the auditor's \"prove it\"
  tik ls [--all] [--long] [--where SELECTOR]    tickets with derived stages (open by
                                                default; --all includes settled);
                                                --long adds description + links;
                                                --where filters by a selector
  tik search <text...>                          ALL tickets whose title/facts hold
                                                every word (sugar for --all --where '~w …')
  tik dupes [--threshold 0.4]                   near-title lookalike report over open
                                                tickets (selection is `ls --where`)

  A SELECTOR is space-separated terms, all ANDed, each optionally `not`:
    stage=:blocked   fact:severity   fact:severity=:high   actor=seb
    disputed   conflicted   unsigned   derived-from=<hash>   ~text   (bare word)
    e.g.  stage=:blocked and fact:severity=:high and not disputed

  Machine output — any lens: --edn (short for --format edn) | --format json | human
  tik whatif <id> k=v|retract:k|+PT48H ...      counterfactual: stage diff, nothing
                                                written — e.g. tik whatif 3184 sev=:low
                                                +P2D (two days pass) retract:category
  tik debug <process> [<id>]                    the fixpoint with its working shown
  tik board [<file.html>]                       the whole board as ONE dependency-free
                                                HTML file — mail it, archive it
  tik serve [--port N]                          the live board over HTTP (read-only;
                                                /tickets.edn + /explain/<id>.edn for tools)
  tik mcp                                        MCP server over stdio: the frontier as an
                                                agent's gated tool surface (TIK_ACTOR/TIK_KEY)
  tik bridge email [--config F] < msg           mail in: sender->actor per config;
                                                [tik <id>] comments that ticket and
                                                tik> key=value lines become facts;
                                                else a new ticket
  tik bridge oidc [--registry ID] [--actor A]   identity rung 2: device-flow login ->
                  [--user U]                    a signed key-binding attestation on the
                  [--password-command C |       registry ticket; verification never
                   --password-file F]           calls the IdP. Give the password via a
                                                secret manager / file / TIK_OIDC_PASSWORD,
                                                not a literal --password (argv is public);
                                                fetches require HTTPS (loopback excepted)
  tik bridge oid4vci --credential vc.jwt        ingest a verifiable credential: verify
                  --registry ID                 the issuer signature against its JWKS,
                  [--jwks-url U | --jwks FILE]   mint it as a bridge-signed attestation
                                                (a VC is an attestation with an external
                                                issuer); offline-verifiable thereafter
  tik effects run [--config F] [--dry-run]      alerts out: derived transitions to
                                                slack|discord|matrix|teams|mattermost|
                                                rocketchat|googlechat|ntfy|gotify|
                                                pushover|telegram|opsgenie|alertmanager|
                                                pagerduty|webhook|email|command sinks
                                                (ADR 0019). Per-sink :template puts the
                                                message in your words ({{title}}
                                                {{stage}} {{short}} {{ticket}}). ANY sink
                                                field (a :url, :token, a :headers value)
                                                may be a secret: a literal, {:env \"NAME\"},
                                                {:command [\"pass\" \"show\" \"x\"]} (or a
                                                \"shell string\"), {:file \"/path\"}, or
                                                {:credential \"NAME\"} (systemd) — resolved
                                                at send time, so no secret sits in
                                                effects.edn; :command pipes the JSON to ANY
                                                program; email renders explain and its
                                                tik> replies close the loop
  tik rollout <process> [--parent <id>]         one ticket per git repo under the store
              [--parent-title T]                (context-tagged), wired as link facts on
                                                a parent: a checklist whose checkmarks
                                                derive from each child's evidence.
                                                Idempotent; re-runs report coverage
  tik probe [<id>] [--command C]                auto-derive facts from the world: run
                                                each repo ticket's probe (the :probe
                                                script of its process; any executable
                                                printing key=value) with cwd in that
                                                repo; changed values land as signed
                                                facts and stages derive. Idempotent
  tik next [--actor A] [--role :r] [--all]      the inbox: what unlocks the most work,
                                                quiet tickets rising; --actor = what I
                                                can do, --role = what a role is being
                                                waited on for (--all adds settled)
  tik pack [<id>]                               consolidate settled tickets into one
                                                content-addressed pack file each —
                                                fewer inodes and git objects; verify
                                                checks every packed slice against its
                                                id; later events land loose and merge
  tik plan [<file.html>]                        the dependency-link roadmap: ready /
                                                blocked / done / cyclic, the critical
                                                path, and each item's unlock impact —
                                                derived, never stale. .html arg writes
                                                the fancy self-contained page
  tik gc [--apply]                              remove archived process definitions no
                                                ticket pins (migrated-away versions);
                                                dry-run by default, verify stays PASS,
                                                only historical --at degrades
  tik verify [<id>] [--changed]                 the verify ladder; no id = whole store
                                                (--changed: skip unchanged heads —
                                                drift check, not the full audit)
  tik root [--witness] [--anchor]               ONE hash committing to the entire
                                                store; --witness countersigns it;
                                                --anchor adds a third-party timestamp
                                                (needs the external ots tool; optional)
  tik process sign <name> [--key K]             publish: sign the archived definition
  tik agent actions|set|attest <id> --actor A   the GATED agent surface: only what the
                                                frontier admits for the role; everything
                                                else is refused with the derived reason
  tik witness <id> [--key K]                    countersign the head(s): one signature
                                                timestamps the entire ancestry
  tik attest <id> <claim-edn>                   record a signed claim (kernel ignores
                                                semantics; :attested-within reads it)
  tik work record|week|cost                     work evidence (H6): telemetry claims in,
                                                machine-drafted human-signed records out,
                                                usage totals derived — never stored
  tik actor add <name> <key.pub>                register a signer (identity rung 1)
  tik sign <id> [--key K]                       sign your events (or set TIK_KEY to
                                                sign every write as it happens)
  tik reprocess <id> <new.edn> [--reason R]     re-pin a ticket to a new definition;
                [--apply]                       derived-stage diff, dry-run unless --apply
  tik export <dir>                              materialize any store as the file/git
                                                format (the audit interchange)
  tik bundle <id> [--out file.tgz]              ONE ticket as a portable evidence
                                                bundle: events, signatures, witness
                                                marks, pinned ruleset, verify.sh —
                                                checkable with coreutils + ssh-keygen,
                                                no tik required
  tik author [--from answers.edn] [--force]     guided interview -> a linted process
             [--template bug|change-request|    definition + test skeleton; no EDN
              purchase-approval] [--name N]     knowledge needed; templates are
                                                finished interviews to edit from;
                                                `author prompt` prints an LLM recipe
                                                that yields an answers.edn;
                                                `author check <answers.edn>` lints it
                                                without writing (schema + smells)
  tik roles [--edn]                             who gates what: every role on the open
                                                board, its members, and the stages
                                                waiting on its signature
  tik lint [<process.edn>]                      lint a process definition; with no
                                                argument, lint the STORE — open tickets
                                                missing descriptions/titles/signatures
  tik show <process|file.edn> [<id>]            draw the process as a vertical stage
                                                graph — stages, guards, forks, joins;
                                                with a ticket, overlay its progress
                                                (✓ reached ◆ actionable · blocked)
  tik sim <process.edn>                         live process design (scratch ticket,
                                                auto-reloading definition)
  tik test <tests.edn>                          run scripted process tests (steps in,
                                                expected stages out)")

(defn- dispatch [cmd parsed]
  (case cmd
      "init"    (storeops/cmd-init parsed)
      "store"   (storeops/cmd-store parsed)
      "rollout" (storeops/cmd-rollout parsed)
      "probe"   (storeops/cmd-probe parsed)
      "pack"    (storeops/cmd-pack parsed)
      "gc"      (storeops/cmd-gc parsed)
      "plan"    (cmd-plan parsed)
      "show"    (cmd-show parsed)
      "new"     (cmd-new parsed)
      "adopt"   (cmd-adopt parsed)
      "set"     (cmd-set parsed)
      "retract" (cmd-retract parsed)
      "dispute" (cmd-dispute parsed)
      "diff"    (cmd-diff parsed)
      "attach"  (cmd-attach parsed)
      "comment" (cmd-comment parsed)
      "status"  (cmd-status parsed)
      "explain" (cmd-explain parsed)
      "log"     (cmd-log parsed)
      "causal"  (cmd-causal parsed)
      "ls"      (cmd-ls parsed)
      "next"    (cmd-next parsed)
      "search"  (cmd-search parsed)
      "dupes"   (cmd-dupes parsed)
      "whatif"  (design/cmd-whatif parsed)
      "debug"   (design/cmd-debug parsed)
      "board"   (cmd-board parsed)
      "serve"   (cmd-serve parsed)
      ;; the MCP stdio loop lives in tik.mcp (which requires this ns);
      ;; resolve it lazily to avoid the require cycle — tik.main force-
      ;; requires tik.mcp so the native image can resolve it here too.
      "mcp"     ((requiring-resolve 'tik.mcp/-main))
      "bridge"  (bridge/cmd-bridge parsed)
      "effects" (effects/cmd-effects parsed)
      "verify"  (audit/cmd-verify parsed)
      "root"    (audit/cmd-root parsed)
      "author"  (cmd-author parsed)
      "roles"   (cmd-roles parsed)
      "bundle"  (audit/cmd-bundle parsed)
      "lint"    (cmd-lint parsed)
      "actor"   (cmd-actor parsed)
      "attest"  (cmd-attest parsed)
      "work"    (cmd-work parsed)
      "witness" (audit/cmd-witness parsed)
      "agent"   (cmd-agent parsed)
      "process" (cmd-process parsed)
      "sign"    (cmd-sign parsed)
      "reprocess" (cmd-reprocess parsed)
      "export"  (audit/cmd-export parsed)
      "sim"     (design/cmd-sim parsed)
      "test"    (design/cmd-test parsed)
      ("help" "--help" "-h" nil) (println usage)
      (do (println (str "tik: '" cmd "' is not a command — the full list:\n"))
          (println usage))))

(defn- dispatch-guarded
  "dispatch with the standard escape handling every entry point shares:
  an ex-info becomes its own one-line message (the kernel already words
  its rejections), anything else a this-is-a-bug line — both exit 1 via
  exit!, so the caller (binary vs in-process runner) decides whether
  that ends the process or is captured. The exit SIGNAL that exit! may
  throw in-process is re-thrown untouched, never reworded as a command
  error. Argument parsing runs INSIDE the guard, so a hostile argv
  (a non-string element from an embedder) fails well like any other
  input, on both entry points. TIK_DEBUG=1 rethrows for developers."
  [argv]
  (letfn [(run [] (let [[cmd & more] argv]
                    (dispatch cmd (parse-args (vec more)))))]
    (if (System/getenv "TIK_DEBUG")
      (run)
      (try
        (run)
        (catch clojure.lang.ExceptionInfo e
          (if (contains? (ex-data e) ::exit)
            (throw e)
            (do (binding [*out* *err*]
                  (println (str "tik: " (ex-message e)))
                  (when-let [file (:file (ex-data e))]
                    (println (str "  in: " file))))
                (exit! 1))))
        (catch Throwable e
          (binding [*out* *err*]
            (println (str "tik: unexpected error ("
                          (.getSimpleName (class e)) "): "
                          (or (ex-message e) "no message")))
            (println "  this is a bug in tik — TIK_DEBUG=1 shows the trace"))
          (exit! 1))))))

(defn -main
  "The binary entry point: dispatch with the shared escape handling,
  every exit! terminating the process (the default *exit-fn*)."
  [& args]
  (dispatch-guarded (vec args)))

(defn run-argv
  "The IN-PROCESS entry point beside -main: run one CLI invocation with
  the exact same dispatch and escape handling, but stdout/stderr are
  captured and every exit! — a command's own die/refuse, or the escape
  handler's — is trapped into an exit CODE instead of terminating.
  Returns {:exit int :out string :err string}. The MCP server (and any
  embedder) reuse the whole CLI through this, no subprocess.

  argv must be a sequence of strings; a non-string element is a caller
  error answered as a clean exit-1 result, never a raw NPE from the
  parser (the fail-well contract reaches the embedding seam too)."
  [argv]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        code (volatile! 0)]
    (binding [*out* out
              *err* err
              *exit-fn* (fn [c]
                          (vreset! code c)
                          (throw (ex-info "tik exit" {::exit c})))]
      (if-not (and (sequential? argv) (every? string? argv))
        (do (vreset! code 1)
            (binding [*out* *err*]
              (println "tik: run-argv requires a sequence of string arguments")))
        (try
          (dispatch-guarded argv)
          (catch clojure.lang.ExceptionInfo e
            ;; the only ex-info that reaches here is the exit signal —
            ;; dispatch-guarded handles every real command escape and
            ;; re-throws this one; @code already carries the exit value
            (when-not (contains? (ex-data e) ::exit)
              (vreset! code 1))))))
    {:exit @code :out (str out) :err (str err)}))










