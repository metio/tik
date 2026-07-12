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
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [tik.author :as author]
            [tik.oidc :as oidc]
            [tik.canonical :as canonical]
            [tik.causal :as causal]
            [tik.dag :as dag]
            [tik.dupe :as dupe]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.guard :as guard]
            [tik.next :as next-lens]
            [tik.work :as work]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite :as sqlite])
  (:import (java.io File)
           (java.time Duration Instant)))

(defn- discover-root
  "Git-style upward search from cwd: the nearest ancestor holding a
  hidden `.tik` store wins, else the nearest holding a classic
  `tickets/` directory. The walk never climbs past $HOME — a stray
  store high in the tree must not silently capture unrelated work —
  though it checks $HOME itself; outside $HOME it walks to the
  filesystem root like git does."
  []
  (let [home (str (.getCanonicalFile (io/file (System/getProperty "user.home"))))]
    (loop [d (.getCanonicalFile (io/file "."))]
      (when d
        (cond
          (.isDirectory (io/file d ".tik")) (str (io/file d ".tik"))
          (.isDirectory (io/file d "tickets")) (str d)
          (= (str d) home) nil
          :else (recur (.getParentFile d)))))))

(def ^:private discovered-root (delay (discover-root)))

(defn- root
  "The store root: TIK_ROOT always wins (scripts, multi-store work);
  else discovery; else the cwd — which is what makes a fresh directory
  become a store the moment `tik new` runs in it."
  []
  (or (System/getenv "TIK_ROOT") @discovered-root "."))

(defn- store-established?
  "Is a real store reachable — a marker found on the upward walk, or
  TIK_ROOT pointing at one — versus `root` falling back to the cwd? The
  empty-board guidance leads with `tik init` only when it is NOT, so a
  fresh directory is told how to become a store deliberately."
  []
  (boolean (or (System/getenv "TIK_ROOT") @discovered-root)))

(defn- now [] (Instant/now))

(defn- eval-instant
  "--at <inst>: evaluate at any moment — status on March 1 is just
  f(events, March 1); time travel was free the whole time (ADR 0012)."
  [opts]
  (if-let [at (:at opts)]
    (Instant/parse at)
    (now)))

(defn- process-exit!
  "The one true process boundary: really terminate. Bound as the default
  *exit-fn* for the binary entry point."
  [code]
  (System/exit code))

(def ^:dynamic *exit-fn*
  "How a command terminates. The binary leaves this as process-exit!;
  the in-process runner (run-argv) binds a throw, so one embedded call —
  an MCP tool, a test — can never take the host process down. Commands
  call (exit! code); only process-exit! calls System/exit."
  process-exit!)

(defn- exit! [code] (*exit-fn* code))

(defn- die [& msg]
  (binding [*out* *err*] (apply println msg))
  (exit! 1))

;; ---------------------------------------------------------------- color
;; Color is porcelain: tty-detected, NO_COLOR honored, and the kernel
;; lens keeps emitting plain data — only this file paints.
(def ^:private use-color?
  (delay (and (nil? (System/getenv "NO_COLOR"))
              (some? (System/console)))))

(defn- tint [code s]
  (if @use-color? (str "\u001b[" code "m" s "\u001b[0m") s))

(defn- paint-stage [stage-str settled? parked?]
  (cond
    settled? (tint "32" stage-str)                 ; green: finished
    parked? (tint "33" stage-str)                  ; yellow: waiting on a decision
    :else (tint "36" stage-str)))                  ; cyan: live work

(defn- paint-explain
  "Green checks, red crosses, dim hints — over the lens's plain text."
  [s]
  (->> (str/split-lines s)
       (map #(cond
               (str/starts-with? % "  ✓") (tint "32" %)
               (str/starts-with? % "  ✗") (tint "31" %)
               (str/starts-with? % "  (see:") (tint "2" %)
               (str/starts-with? % "To reach") (tint "1" %)
               :else %))
       (str/join "\n")))

(defn- parse-args [args]
  (loop [args args pos [] opts {}]
    (if (empty? args)
      {:pos pos :opts opts}
      (let [[a & more] args]
        (cond
          (not (str/starts-with? a "--"))
          (recur more (conj pos a) opts)
          ;; a flag with no value (end of args, or another option next)
          ;; is boolean true — e.g. --apply
          (or (empty? more) (str/starts-with? (first more) "--"))
          (recur more pos (assoc opts (keyword (subs a 2)) true))
          :else
          (recur (rest more) pos (assoc opts (keyword (subs a 2)) (first more))))))))

(defn- actor [opts]
  (or (:actor opts) (System/getenv "TIK_ACTOR")
      (System/getProperty "user.name")))

(defn- parse-key [s] (mapv keyword (str/split s #"\.")))

(defn- parse-value
  "Forgiving: one complete EDN form is taken as EDN (bare words become
  keywords); anything else — multi-word prose, 31ffd53-style
  hex-with-letters, stray punctuation — is the literal string. Nobody
  should need to know EDN quoting to record a description."
  [s]
  (let [[v ok?] (try
                  ;; depth precheck: a recursion-bomb argument must land
                  ;; as a literal string, not overflow the reader
                  (canonical/check-nesting s)
                  (with-open [r (java.io.PushbackReader.
                                 (java.io.StringReader. s))]
                    (let [form (edn/read {:eof ::eof} r)
                          rest (edn/read {:eof ::eof} r)]
                      [form (= ::eof rest)]))
                  (catch Exception _ [nil false]))]
    (cond
      (not ok?) s
      (symbol? v) (keyword (str v))
      :else v)))

(defn- read-edn-file
  "EDN from a file, failing WELL: a malformed config names itself
  instead of exploding the reader. nil when the file is absent."
  [^File f]
  (when (.exists f)
    (try (let [text (slurp f)]
           (canonical/check-nesting text)
           (edn/read-string text))
         (catch Exception e
           (throw (ex-info (str "malformed EDN in " f " — "
                                (ex-message e))
                           {:reason :config/malformed :file (str f)}
                           e))))))

(defn- declared-string?
  "Does the process declare this fact path as a string type? Then a
  bare token the parser would keywordize is really the user's string."
  [proc path]
  (let [t (get-in proc [:process/facts path])]
    (boolean (or (= :string t)
                 (and (vector? t) (= :string (first t)))))))

(defn- typed-value
  "parse-value informed by the DECLARED type: when the declaration
  says string and the raw text is a bare word (no explicit :colon),
  the raw text wins over keywordization — commit=a051932 lands as
  \"a051932\". Everything else parses exactly as before."
  [proc path raw]
  (let [parsed (parse-value raw)]
    (if (and (not (string? parsed))
             (or (declared-string? proc path)
                 ;; [:link <rel>] values are id REFERENCES — always
                 ;; strings. A uuid target starting with a letter would
                 ;; otherwise parse as a symbol and keywordize, breaking
                 ;; resolution (the leading ':' never matches a ticket id)
                 (= :link (first path))))
      ;; the declaration wants a string: bare hex, all-digit hashes,
      ;; words — the raw text IS the value (a quoted "..." already
      ;; parsed to a string and kept its EDN reading)
      (str/trim raw)
      parsed)))

(defn- process-file ^File [name] (io/file (root) "processes" (str name ".edn")))

(defn- by-hash-file ^File [hash] (io/file (root) "processes" "by-hash" (str hash ".edn")))

(defn- git-tracked?
  "Is `dir` inside a git work tree? Governs whether a destructive op can
  honestly promise git recovery."
  [dir]
  (try
    (zero? (:exit (sh/sh "git" "-C" (str dir) "rev-parse" "--is-inside-work-tree")))
    (catch Exception _ false)))

(defn- archive-process!
  "Store the definition content-addressed (processes/by-hash/<hash>.edn,
  canonical bytes — sha256sum(file) = filename, the ADR 0007 pattern),
  so a ticket's pinned hash resolves even after the named file moves on
  (ADR 0002: reproducibility over freshness)."
  [proc]
  (let [hash (process/process-hash proc)
        f (by-hash-file hash)]
    (when-not (.exists f)
      (io/make-parents f)
      (spit f (canonical/emit proc)))
    hash))

(defn- available-processes []
  (sort (keep #(second (re-matches #"(.+)\.edn" (.getName ^File %)))
              (filter #(not (str/ends-with? (.getName ^File %) ".tests.edn"))
                      (.listFiles (io/file (root) "processes"))))))

(defn- load-process [name]
  (when-not name
    (die (str "usage: tik new <process> [--title T]\n"
              (if-let [ps (seq (available-processes))]
                (str "available processes: " (str/join ", " ps))
                "no processes yet — `tik author` writes your first one"))))
  (let [f (process-file name)]
    (when (and (not (.exists f)) (= "track" name))
      ;; the built-in fallback works in an EMPTY store; materialize it
      ;; so the store stays self-contained and hand-editable
      (io/make-parents f)
      (spit f (with-out-str (pp/pprint author/track-process))))
    (when-not (.exists f)
      (die (str "no process named '" name "' (looked for " f ")\n"
                (if-let [ps (seq (available-processes))]
                  (str "available: " (str/join ", " ps)
                       "\nor `tik author` to create one")
                  "none exist yet — `tik author` writes your first one"))))
    (read-edn-file f)))

(defn- load-pinned-process
  "The definition a ticket derives under: its pinned hash from the
  archive when present, else the named file (with a warning when that
  file no longer matches the pin — an unmigrated ticket after the
  named definition moved on)."
  [state]
  (let [hash (:process-hash state)
        archived (some-> hash by-hash-file)]
    (if (and archived (.exists ^File archived))
      (read-edn-file archived)
      (let [proc (load-process (name (:process state)))]
        (when (and hash (not= hash (process/process-hash proc)))
          (binding [*out* *err*]
            (println "warning: pinned definition" hash
                     "not in processes/by-hash/ and the named file has"
                     "moved on — deriving under the file's current"
                     "version")))
        proc))))

(defn- db-path []
  (let [db (System/getenv "TIK_DB")]
    (when-not (str/blank? db) db)))

(defn- the-store
  "TIK_DB selects the single-file SQLite backend (ADR 0020); default is
  the file/git store under TIK_ROOT. Blobs and the actors registry stay
  filesystem-side under TIK_ROOT either way."
  []
  (if-let [db (db-path)]
    (sqlite/sqlite-store db)
    (fstore/file-store (root))))

(defn- events-dir ^File [ticket-id]
  (io/file (root) "tickets" (str ticket-id) "events"))

(defn- event-file ^File [event]
  (io/file (events-dir (:event/ticket event)) (str (:event/id event) ".edn")))

(defn- signing-key [opts]
  (let [k (or (:key opts) (System/getenv "TIK_KEY"))]
    (when-not (str/blank? k) k)))

(defn- append!*
  "Append, then sign the stored bytes when a key is configured (--key or
  TIK_KEY): authorship travels with the write (ADR 0010)."
  [s event opts]
  (store/append! s event)
  (when-let [key (signing-key opts)]
    (sign/sign! key (event-file event) (:event/id event)))
  event)

(defn- resolve-id [s ticket-str]
  (let [hits (filter #(str/starts-with? % ticket-str)
                     (map str (store/ticket-ids s)))]
    (case (count hits)
      1 (java.util.UUID/fromString (first hits))
      0 (die (str "no ticket starting with '" ticket-str
                  "' — `tik ls` lists open tickets, `tik ls --all` everything"))
      (die (str "'" ticket-str "' matches " (count hits)
                " tickets — add more characters:\n  "
                (str/join "\n  " (sort hits)))))))

(defn- ticket-ctx [s id]
  (let [evs (store/events s id)
        state (red/ticket-state evs)
        proc (load-pinned-process state)]
    {:events evs :state state :process proc
     :roles (:process/roles proc {})
     :heads (dag/heads evs)}))


(declare display-title link-facts ticket-ctx)

;; ------------------------------------------------ derived-state cache
;; ADR 0013 made disposable caches legal; ticket 11ae2438 settled this
;; design; the 10k-ticket benchmark (3.6s ls) fired its trigger. The
;; KEY is the input identity itself: event filenames ARE content
;; addresses, so the sorted directory listing hashes to a complete
;; fingerprint of everything derivation consumes — invalidation cannot
;; be got wrong because a changed input IS a changed key. Time-guarded
;; tickets carry a validity horizon (the earliest unsatisfied :due);
;; processes reading the log clock (:attested-within) never cache.
;; Deleting the cache file can only cost a recompute, never truth.

(def ^:private cache-state (atom nil))   ; {:entries {} :dirty? bool}

(defn- cache-file ^File [] (io/file (root) ".derived-cache.json"))

;; JSON, not EDN, purely for parse speed: reading a 10k-entry EDN
;; cache cost 0.8s — more than the folds it saved. Keywords ride as
;; \":kw\" strings and round-trip on load; the cache stays disposable,
;; so a decode surprise is a miss, never an error.
(defn- cache-encode [x]
  (cond
    (keyword? x) (str x)
    (map? x) (into {} (map (fn [[k v]] [(cache-encode k)
                                        (cache-encode v)]) x))
    (sequential? x) (mapv cache-encode x)
    (set? x) (mapv cache-encode x)
    :else x))

(defn- cache-decode [x]
  (cond
    (and (string? x) (str/starts-with? x ":")) (keyword (subs x 1))
    (map? x) (into {} (map (fn [[k v]] [(cache-decode k)
                                        (cache-decode v)]) x))
    (sequential? x) (mapv cache-decode x)
    :else x))

(defn- cache-entries []
  (:entries
   (or @cache-state
       (reset! cache-state
               {:dirty? false
                :entries
                (or (try
                      (when (.exists (cache-file))
                        (let [parse (requiring-resolve
                                     'cheshire.core/parse-string)]
                          (into {}
                                (map (fn [[k v]] [k (cache-decode v)]))
                                (parse (slurp (cache-file))))))
                      ;; a corrupt cache is not an error, it is a miss
                      (catch Exception _ nil))
                    {})}))))

(defn- cache-flush! []
  (when (:dirty? @cache-state)
    (let [emit (requiring-resolve 'cheshire.core/generate-string)]
      (spit (cache-file) (emit (into {}
                                     (map (fn [[k v]] [k (cache-encode v)]))
                                     (:entries @cache-state)))))
    (swap! cache-state assoc :dirty? false)))

(defn- event-ids-fingerprint
  "sha256 over the sorted event filenames — the complete input identity
  of one ticket's derivation, read from the directory listing alone.
  nil when the store is not file-backed (cache bypassed)."
  [id]
  (let [d (io/file (root) "tickets" (str id) "events")]
    (when (and (nil? (db-path)) (.isDirectory d))
      (canonical/sha256-hex
       (str (:pack (fstore/read-pack-index d)) "\n"
            (str/join "\n" (sort (keep #(let [n (.getName ^File %)]
                                           (when (str/ends-with? n ".edn") n))
                                        (.listFiles d)))))))))

(defn- guard-ops [proc]
  (let [ops (volatile! #{})]
    (doseq [{:keys [guards]} (:process/stages proc)]
      (walk/postwalk
       #(do (when (and (vector? %) (keyword? (first %)))
              (vswap! ops conj (first %)))
            %)
       guards))
    @ops))

(defn- compute-row
  "The full derivation for one ticket, shaped for the board lenses,
  plus its cache validity: nil valid-until means the row holds until
  the event set changes (reached is monotone without time guards);
  a time-guarded ticket expires at the earliest unsatisfied :due;
  :attested-within makes the ticket uncacheable."
  [s t id]
  (let [{:keys [events state process roles]} (ticket-ctx s id)
        reached (stage/effective-reached process events t roles)
        current (stage/current-stages process reached)
        ops (guard-ops process)
        blocks (when (contains? ops :elapsed-since)
                 (explain/explain process events t roles))
        dues (keep :due (mapcat :missing blocks))
        fm (guard/fact-map state)]
    {:valid-until (cond
                    (contains? ops :attested-within) :never-cache
                    (and (contains? ops :elapsed-since) (seq dues))
                    (inst-ms (first (sort dues)))
                    :else nil)
     :row {:title (display-title state)
           :depth (if (seq current)
                    (apply min (map #(count (stage/ancestor-closure
                                             process %))
                                    current))
                    -1)
           :repo (red/fact-value state [:repo])
           :describe (some fm [[:description] [:summary] [:statement]])
           :current (vec (sort-by str current))
           :reached (vec (sort-by str reached))
           :settled? (next-lens/settled? process events t roles)
           :last-event-ms (reduce (fn [acc e]
                                    (max acc (inst-ms (:event/at e))))
                                  0 events)
           :links (vec (link-facts state))
           :process-id (or (:process/id process) (:process state))
           :haystack (str/lower-case
                      (str (display-title state) " "
                           (str/join " " (map (comp str :value)
                                              (vals (:facts state))))))}}))

(defn- error-row
  "A poisoned ticket (unreadable event, unevaluable guard) must not
  hide its healthy neighbors: aggregate lenses render it as a visible
  error row and keep going. verify and single-ticket commands still
  fail loudly — isolation is for LISTS, never for audits."
  [id e]
  {:title (str "cannot derive: " (ex-message e))
   :error (ex-message e)
   :current [:error]
   :reached []
   :settled? false
   :depth -1
   :last-event-ms 0
   :links []
   :haystack (str/lower-case (str id " " (ex-message e)))})

(defn- ticket-row
  "The board row for one ticket, cached when the store is file-backed:
  a hit costs one directory listing, a miss folds and remembers.
  Failures isolate into an error row (cached under the same
  fingerprint, so a fixed ticket recovers on its next event)."
  [s t id]
  (try
    (if-let [fp (event-ids-fingerprint id)]
      (let [entry (get (cache-entries) (str id))]
        (if (and entry
                 (= fp (:fp entry))
                 (let [vu (:valid-until entry)]
                   (and (not= :never-cache vu)
                        (or (nil? vu) (< (inst-ms t) vu)))))
          (:row entry)
          (let [{:keys [valid-until row]} (compute-row s t id)]
            (swap! cache-state #(-> %
                                    (assoc-in [:entries (str id)]
                                              {:fp fp :valid-until valid-until
                                               :row row})
                                    (assoc :dirty? true)))
            row)))
      (:row (compute-row s t id)))
    (catch Exception e
      (error-row id e))))

(defn- display-title
  "The title a lens shows: a [:title] fact supersedes the created
  title — creation is immutable, names are corrections like any other
  fact. The create event keeps the original forever."
  [state]
  (or (red/fact-value state [:title]) (:title state)))

;; ---------------------------------------------------------------- commands

(defn- open-ticket-rows
  "{:id :title :text} for every unsettled ticket — the duplicate
  radar's comparison set."
  [s t]
  (vec
   (for [id (store/ticket-ids s)
         :let [{:keys [settled? title haystack]} (ticket-row s t id)]
         :when (not settled?)]
     {:id id :title title :text haystack})))

(defn- store-holder
  "The directory the store sits IN: the parent of a hidden .tik root,
  the root itself otherwise. Context collection walks cwd up to here."
  ^File []
  (let [r (.getCanonicalFile (io/file (root)))]
    (if (= ".tik" (.getName r)) (.getParentFile r) r)))

(defn- context-facts
  "Facts a ticket created HERE inherits, as [path value] pairs:
  - repo=<name> from the nearest enclosing git repo strictly below
    the store holder — the cross-repo dimension nobody has to
    remember to type;
  - .tik-facts.edn markers from the holder down to cwd, deeper files
    winning per key; keys are keywords (single-segment paths) or
    vectors (explicit paths); explicit keys beat the automatic repo.
  Pure context collection at WRITE time: everything lands as ordinary
  signed events, the kernel never reads a marker file."
  []
  (let [holder (store-holder)
        cwd (.getCanonicalFile (io/file "."))
        below? (str/starts-with? (str cwd) (str holder "/"))
        chain (when below?
                (->> (iterate #(.getParentFile ^File %) cwd)
                     (take-while #(and % (not= (str %) (str holder))))
                     reverse))                       ; holder-side first
        repo (some #(when (.exists (io/file % ".git"))
                      (let [n (.getName ^File %)]
                        (if (re-matches #"[a-zA-Z][a-zA-Z0-9_.-]*" n)
                          (keyword n)
                          n)))
                   (reverse chain))                  ; nearest git wins
        ;; a marker AT the holder applies store-wide; deeper wins
        markers (apply merge
                       (for [^File d (when below? (cons holder chain))
                             :let [f (io/file d ".tik-facts.edn")]
                             :when (.isFile f)]
                         (read-edn-file f)))
        explicit (into {}
                       (for [[k v] markers]
                         [(if (vector? k) k [k]) v]))]
    (into (if (and repo (not (contains? explicit [:repo])))
            [[[:repo] repo]]
            [])
          explicit)))

(declare link-facts link-row link-lines resolve-file)

(defn- cmd-rollout
  "rollout <process> [--parent <id>] [--parent-title T]
  One ticket per git repository under the store holder, each carrying
  its repo=<name> fact, all wired as link.<repo> facts on a parent —
  a checklist whose checkmarks DERIVE from each child's evidence.
  Idempotent: re-runs create only uncovered repos and missing links,
  and report coverage. The parent's own completion stays a human
  signature; guards never query across tickets (ADR 0004 scope).

  Repos are found RECURSIVELY (GitLab-style group/subgroup/repo trees
  work); descent stops at each repo, hidden directories are skipped.
  A nested repo's identity is its relative path: the [:repo] fact and
  queries use the string \"group/repo\", flat repos keep the keyword."
  [{:keys [pos opts]}]
  (let [proc-name (or (first pos)
                      (die "usage: tik rollout <process> [--parent <id>] [--parent-title T]"))
        proc (load-process proc-name)
        holder (store-holder)
        walk (fn walk [^File d rel]
               (cond
                 (str/starts-with? (.getName d) ".") []
                 (.exists (io/file d ".git")) [rel]
                 :else (mapcat #(walk % (str rel "/" (.getName ^File %)))
                               (filter #(.isDirectory ^File %)
                                       (or (.listFiles d) [])))))
        repos (sort (mapcat #(walk % (.getName ^File %))
                            (filter #(.isDirectory ^File %)
                                    (or (.listFiles holder) []))))
        repo-value #(if (re-matches #"[a-zA-Z][a-zA-Z0-9_.-]*" %)
                      (keyword %)
                      ;; spaces, slashes, EDN-hostile characters: the
                      ;; name rides as a string (mint would refuse a
                      ;; keyword that cannot round-trip)
                      %)
        _ (when (empty? repos)
            (die (str "no git repositories directly under " holder)))
        s (the-store)
        t (now)
        all (vec (for [id (store/ticket-ids s)]
                   (assoc (ticket-ctx s id) :id id)))
        child-of (into {}
                       (for [{:keys [id state]} all
                             :when (= (keyword proc-name) (:process state))
                             :let [r (red/fact-value state [:repo])]
                             :when r]
                         [(if (keyword? r) (name r) (str r)) id]))
        parent-title (or (:parent-title opts) (str proc-name " rollout"))
        parent-id
        (or (when-let [p (:parent opts)] (resolve-id s p))
            (some (fn [{:keys [id state events process roles]}]
                    (when (and (= parent-title (display-title state))
                               (not (next-lens/settled? process events t roles)))
                      id))
                  all)
            (let [track (load-process "track")
                  e (event/create-ticket
                     {:ticket (random-uuid) :actor (actor opts) :at (now)
                      :title parent-title :process :track
                      :version (:process/version track)
                      :process-hash (archive-process! track)})]
              (append!* s e opts)
              (println (str "parent " (subs (str (:event/ticket e)) 0 8)
                            " \"" parent-title "\""))
              (:event/ticket e)))
        created (atom 0)]
    (doseq [repo repos]
      (let [child-title (str proc-name ": " repo)
            child (or (child-of repo)
                      (let [e (event/create-ticket
                               {:ticket (random-uuid) :actor (actor opts)
                                :at (now) :title child-title
                                :process (keyword proc-name)
                                :version (:process/version proc)
                                :process-hash (archive-process! proc)})]
                        (append!* s e opts)
                        (append!* s (event/assert-fact
                                     {:ticket (:event/ticket e)
                                      :actor (actor opts) :at (now)
                                      :parents #{(:event/id e)}
                                      :path [:repo] :value (repo-value repo)})
                                  opts)
                        (swap! created inc)
                        (:event/ticket e)))
            {:keys [state]} (ticket-ctx s parent-id)]
        ;; re-runs converge names too: retitling is a superseding fact
        (let [cstate (:state (ticket-ctx s child))]
          (when-not (= child-title (display-title cstate))
            (append!* s (event/assert-fact
                         {:ticket child :actor (actor opts) :at (now)
                          :parents (dag/heads (store/events s child))
                          :path [:title] :value child-title})
                      opts)))
        (let [rel (keyword (str/replace
                            (str/replace repo "/" ".")
                            #"[^a-zA-Z0-9_.-]" "_"))]
          (when-not (= (str child)
                       (red/fact-value state [:link rel]))
            (append!* s (event/assert-fact
                         {:ticket parent-id :actor (actor opts) :at (now)
                          :parents (dag/heads (store/events s parent-id))
                          :path [:link rel]
                          :value (str child)})
                      opts)))))
    (println (str @created " ticket(s) created, "
                  (- (count repos) @created) " already covered, "
                  (count repos) " repo(s) total — the living checklist:"))
    (let [{:keys [state]} (ticket-ctx s parent-id)]
      (doseq [line (link-lines s t state)]
        (println "  " line)))
    (println (str "watch it: tik status " (subs (str parent-id) 0 8)))))

(defn- cmd-probe
  "probe [<id>] [--command C]
  Auto-derive facts from the world: for every open ticket carrying a
  [:repo] fact (or just <id>), run the probe — an executable printing
  key=value lines — with cwd set to that ticket's repository under the
  store holder, and assert each CHANGED value as an ordinary signed
  fact. The probe comes from --command or the :probe field of the
  ticket's process definition (looked up by name — a porcelain
  convenience, never derivation semantics). Idempotent by
  construction: unchanged values assert nothing; stages derive from
  whatever landed."
  [{:keys [pos opts]}]
  (let [s (the-store)
        t (now)
        holder (store-holder)
        ids (if (seq pos)
              [(resolve-id s (first pos))]
              (store/ticket-ids s))
        changed (atom 0)]
    (doseq [id ids
            :let [{:keys [events state process roles]} (ticket-ctx s id)
                  repo (red/fact-value state [:repo])]
            :when (and repo
                       (or (seq pos)
                           (not (next-lens/settled? process events t roles))))
            :let [repo-name (if (keyword? repo) (name repo) (str repo))
                  dir (io/file holder repo-name)
                  probe (or (:command opts)
                            (let [f (process-file (name (:process state)))]
                              (when (.exists f)
                                (:probe (read-edn-file f)))))]]
      (cond
        (nil? probe) nil
        (not (.isDirectory dir))
        (println (str (subs (str id) 0 8) " " repo-name
                      ": no such directory under " holder " — skipped"))
        :else
        (let [^File f (resolve-file probe)
              argv (if (.exists f) ["sh" (str f)] ["sh" "-c" probe])
              r (apply sh/sh (concat argv
                                     [:dir (str dir)
                                      :env (assoc (into {} (System/getenv))
                                                  "TIK_TICKET" (str id)
                                                  "TIK_REPO" repo-name)]))
              before (stage/effective-reached process events t roles)]
          (if-not (zero? (:exit r))
            (println (str (subs (str id) 0 8) " " repo-name ": probe failed — "
                          (str/trim (:err r))))
            (do
              (doseq [line (str/split-lines (:out r))
                      :let [[_ k v] (re-matches #"\s*([^=\s]+)=(.*)" line)]
                      :when k
                      :let [path (parse-key k)
                            value (typed-value process path (str/trim v))]
                      :when (not= value (red/fact-value
                                         (red/ticket-state (store/events s id))
                                         path))]
                (append!* s (event/assert-fact
                             {:ticket id :actor (actor opts) :at (now)
                              :parents (dag/heads (store/events s id))
                              :path path :value value})
                          opts)
                (swap! changed inc)
                (println (str (subs (str id) 0 8) " " repo-name ": " k " = "
                              (pr-str value))))
              (let [evs (store/events s id)
                    after (stage/effective-reached process evs (now) roles)
                    gained (sort-by str (remove before after))]
                (when (seq gained)
                  (println (str (subs (str id) 0 8) " " repo-name " -> "
                                (tint "32" (str/join ", " (map name gained))))))))))))
    (println (str @changed " fact(s) derived from the world — signed like"
                  " any other claim"))))

(defn- cmd-pack
  "pack [<id>]: consolidate settled tickets' loose event files into
  one content-addressed events.pack + index per ticket (given an id,
  pack that ticket regardless of settledness). The pack holds the
  exact per-event hashed byte regions, so verify still checks every
  event against its id — as a slice. Appends after packing land loose
  and merge on read; re-packing folds them in. Fewer inodes, fewer
  git objects, and the board fingerprints a packed ticket by one
  address instead of a directory listing."
  [{:keys [pos]}]
  (when (db-path)
    (die "pack is for the file store — the SQLite backend is already one file"))
  (let [s (the-store)
        t (now)
        ids (if (seq pos)
              [(resolve-id s (first pos))]
              (for [id (store/ticket-ids s)
                    :let [{:keys [events process roles]} (ticket-ctx s id)]
                    :when (next-lens/settled? process events t roles)]
                id))
        packed (atom 0)]
    (doseq [id ids
            :let [r (fstore/pack! (root) id)]
            :when r]
      (swap! packed inc)
      (println (str (subs (str id) 0 8) " packed " (:packed r)
                    " event(s) -> " (subs (:pack r) 0 19) "…")))
    (println (str @packed " ticket(s) packed"))
    (cache-flush!)))

(defn- cmd-gc
  "gc [--apply]: collect ORPHANED archived process definitions — files
  in processes/by-hash/ that NO ticket currently pins (every ticket that
  once used one has since migrated away). Dry-run BY DEFAULT (ADR 0002
  caution): lists candidates and states the one cost.

  Removing an orphan is safe for the load-bearing surfaces: `verify`
  stays PASS and every CURRENT derivation is unchanged — the pinned hash
  of every live ticket still resolves, because an orphan is by definition
  pinned by none. The only thing lost is historical time-travel: `status
  <id> --at <before the migration that abandoned it>` then derives under
  the current named rules with a warning, instead of the exact old
  definition. The store is a git repo, so a removed definition is
  recoverable from history. Value is tidiness, not disk — definitions are
  a few KB."
  [{:keys [opts]}]
  (let [s (the-store)
        ;; live = every process-hash a ticket currently pins
        live (into #{}
                   (keep #(:process-hash (red/ticket-state (store/events s %))))
                   (store/ticket-ids s))
        dir (io/file (root) "processes" "by-hash")
        archives (when (.isDirectory dir)
                   (filter #(str/ends-with? (.getName ^File %) ".edn")
                           (.listFiles dir)))
        orphans (remove #(contains? live (str/replace (.getName ^File %)
                                                      #"\.edn$" ""))
                        archives)]
    (if (empty? orphans)
      (println (str "gc: no orphaned definitions — every archived process"
                    " is currently pinned by a ticket"))
      (do
        (println (str (count orphans) " orphaned definition(s), pinned by no"
                      " ticket:"))
        (doseq [^File f (sort-by #(.getName ^File %) orphans)]
          (println (str "  " (str/replace (.getName f) #"\.edn$" ""))))
        (println (str "removing these keeps `verify` PASS and every current"
                      " derivation intact;"))
        (println (str "only `status --at <before a migration>` degrades to a"
                      " warning + current-"))
        (println "rules fallback for tickets that once used them.")
        (if (:apply opts)
          (do (doseq [^File f orphans] (io/delete-file f))
              (println (str "removed " (count orphans) " definition(s)."
                            (if (git-tracked? (root))
                              " Recoverable from git history if needed."
                              (str " This store is NOT version-controlled —"
                                   " back up first (or `git init` the store)"
                                   " if you might ever need the old rules.")))))
          (println "dry run — nothing deleted. Re-run with --apply to remove."))))))

(defn- cmd-init
  "init [--hidden]: mark this directory as a store. --hidden puts
  everything inside .tik/ (one dot-entry beside your repos — the
  portfolio-store shape); without it the store is the classic visible
  layout. Either marker makes every tik command work from ANY
  directory beneath this one."
  [{:keys [opts]}]
  (let [here (.getCanonicalFile (io/file "."))
        store (if (:hidden opts) (io/file here ".tik") here)
        marker (io/file store "tickets")]
    (when (.isDirectory marker)
      (die (str "already a store: " store)))
    (.mkdirs marker)
    (println (str "store initialized at " store))
    (println (str "every tik command run at or below " here
                  " now uses it — try:"))
    (println "  tik new track --title \"the first thing\"")
    (println "  tik author --template bug")))

(defn- cmd-new [{:keys [pos opts]}]
  (let [[proc-name] pos
        proc (load-process proc-name)
        s (the-store)
        t (now)
        similar (when-let [title (:title opts)]
                  (take 3 (dupe/radar title (open-ticket-rows s t) 0.4)))
        id (random-uuid)
        e (event/create-ticket {:ticket id :actor (actor opts) :at t
                                :title (or (:title opts) "")
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
                      " — next: tik explain " (subs (str id) 0 8)))))
    (doseq [{existing :id :keys [title score]} similar]
      (binding [*out* *err*]
        (println (str "note: looks like " (subs (str existing) 0 8) " \"" title
                      "\" (" (int (* 100 score)) "% similar) — if this IS "
                      "that, record it: tik set " (subs (str id) 0 8)
                      " duplicate-of=\"" (subs (str existing) 0 8) "\""))))
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
        id (resolve-id s ticket)
        proc (:process (ticket-ctx s id))]
    ;; heads recomputed per event: linear chain within one command
    (doseq [kv kvs :let [[k v] (str/split kv #"=" 2)
                         path (parse-key k)]]
      (append!* s (event/assert-fact
                   {:ticket id :actor (actor opts) :at (now)
                    :parents (dag/heads (store/events s id))
                    :path path :value (typed-value proc path v)})
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

(defn- resolve-id-soft
  "resolve-id without the die: the uuid on a unique match, nil
  otherwise. Lenses rendering links must degrade, not crash."
  [s prefix]
  (let [hits (filter #(str/starts-with? % (str prefix))
                     (map str (store/ticket-ids s)))]
    (when (= 1 (count hits))
      (java.util.UUID/fromString (first hits)))))

(defn- link-facts
  "[{:rel :target}] for every present [:link <rel>] fact — the
  reference is DECLARED (an id is stable), the target's stage is
  DERIVED at render time, which is why links cannot rot the way
  prose status reports do."
  [state]
  (for [[path _] (:facts state)
        :when (and (= 2 (count path)) (= :link (first path)))
        :let [{:keys [status value]} (red/fact-status state path)]
        :when (= :present status)]
    ;; strip a leading ':' so a link stored as a keyword (an older set,
    ;; a hand-edit) still resolves — targets are ids, never keywords
    {:rel (second path) :target (str/replace (str value) #"^:" "")}))

(defn- link-row
  "One link as data for sorted display: the derived stage leads, the
  rel shows only when the title does not already say it, and :sort
  orders by the TARGET process\u2019s own stage depth (ancestor count) —
  parallel branches tie and fall back to names. Unresolved links sink
  to the end instead of crashing a lens."
  [s t {:keys [rel target]}]
  (if-let [target-id (resolve-id-soft s target)]
    (let [{:keys [current title depth settled?]} (ticket-row s t target-id)
          stages (str/join ", " (map name current))]
      {:sort [0 depth stages title]
       :stage (str "(" stages ")")
       :stage-kws (set current)
       :settled? settled?
       :parked? (contains? (set current) :parked)
       :rest (str (subs (str target-id) 0 8) " " title
                  ;; nested-repo rels dot their slashes; either spelling
                  ;; in the title makes the suffix redundant
                  (when-not (or (str/includes? title (name rel))
                                (str/includes? title (str/replace (name rel)
                                                                  "." "/")))
                    (str "  [" (name rel) "]")))})
    {:sort [1 0 "" (str target)]
     :stage "(unresolved)"
     :stage-kws #{}
     :rest (str target "  [" (name rel) "]")}))

(defn- link-lines
  "Every link of `state`, rendered and ordered for humans: least
  progressed first, per the targets\u2019 own process ordering; `only`
  narrows to rows whose current stages include it. The stage column
  pads to the widest stage on THIS list and paints like ls does."
  ([s t state] (link-lines s t state nil))
  ([s t state only]
   (let [links (if (map? state) (link-facts state) state)
         rows (cond->> (sort-by :sort (map #(link-row s t %) links))
                only (filter #(contains? (:stage-kws %) only)))
         width (transduce (map (comp count :stage)) max 0 rows)]
     (for [{:keys [stage rest settled? parked?]} rows]
       (str (paint-stage (format (str "%-" (max 1 width) "s") stage)
                         settled? parked?)
            "  " rest)))))

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
        id (resolve-id s (first pos))
        {:keys [events state process roles]} (ticket-ctx s id)
        t (eval-instant opts)
        reached (stage/effective-reached process events t roles)
        current (stage/current-stages process reached)]
    (println "ticket: " id)
    (println "title:  " (display-title state))
    ;; the hash is the RULE SET's identity, never the ticket's — label
    ;; it so nobody misreads the pin as a mutable ticket id
    ;; the PINNED definition names the rules — after a migration the
    ;; create-time label would lie
    (println "rules:  " (or (:process/id process) (:process state))
             (str "v" (or (:process/version process)
                          (:process-version state)))
             (str "(pinned @ " (some-> (:process-hash state) (subs 0 19))
                  "…)"))
    (println "stage:  " (str/join ", " (map name current))
             (str "(reached: " (str/join ", " (map name (sort-by str reached))) ")"))
    (when-let [deps (seq (unmet-deps s t id))]
      (println "blocked:"
               (str/join ", "
                         (map (fn [{:keys [target tid]}]
                                (str (if tid (subs (str tid) 0 8) target)
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
    (print (paint-explain (explain/render (explain/explain process events t roles))))
    (cache-flush!)))

(defn- cmd-explain [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        {:keys [events process roles]} (ticket-ctx s id)
        blocks (explain/explain process events (eval-instant opts) roles)
        blocks (if-let [who (:actor opts)]
                 (explain/for-actor blocks roles who)
                 blocks)]
    (if (:edn opts)
      (prn blocks)
      (print (paint-explain (explain/render blocks))))))

(defn- cmd-causal
  "Which signed events made each reached stage true — forensics: the
  auditor's 'prove it' rendered from the same fold as everything else."
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        {:keys [events process roles]} (ticket-ctx s id)
        by-id (into {} (map (juxt :event/id identity)) events)
        blocks (causal/causal process events (eval-instant opts) roles)]
    (if (:edn opts)
      (prn blocks)
      (doseq [{:keys [stage support]} blocks]
        (println (str (tint "32" (str stage)) " is supported by:"))
        (if (empty? support)
          (println "  nothing — no guards, reachable by structure alone")
          (doseq [{:keys [via events note]} support]
            (println (str "  " (pr-str via)))
            (doseq [eid events
                    :let [e (by-id eid)]]
              (println (tint "2" (str "    ← " (subs eid 0 15) "… "
                                      (name (:event/type e)) " by "
                                      (:event/actor e) " @ "
                                      (:event/at e)))))
            (when note
              (println (tint "2" (str "    ← " note))))))))))

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
                                                     (subs (str id) 0 8)
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
    (if (:edn opts)
      (prn inbox)
      (do
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
              (println (str "    " (subs (str ticket) 0 8) " -> " stage
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
                        " escape hatches)")))))
    (cache-flush!)))

(defn- stage-filter
  "--filter ':a :b' matches tickets whose current stage is any of them;
  a leading :not negates: --filter ':not :running'."
  [expr]
  (let [ks (mapv edn/read-string (str/split (str/trim (str expr)) #"[\s,]+"))]
    (if (= :not (first ks))
      (let [ex (set (rest ks))] #(empty? (set/intersection ex %)))
      (let [in (set ks)] #(boolean (seq (set/intersection in %)))))))

(defn- cmd-ls
  "Open tickets by default; settled ones (sticky terminal reached —
  :landed, :closed, :validated, :killed) hide behind --all. An explicit
  query (--filter / --search) searches EVERYTHING — asking for :landed
  and getting silence would be a lie."
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
               (for [id (store/ticket-ids s)
                   :let [{:keys [events state process roles]} (ticket-ctx s id)
                         reached (stage/effective-reached process events t roles)
                         current (stage/current-stages process reached)]]
               {:id id :title (display-title state) :current current
                ;; the description is a FACT (searchable), not a comment:
                ;; summary on work tickets, statement on hypotheses, plus
                ;; the parked/verdict context where present
                :describe (let [fm (guard/fact-map state)]
                            (or (some fm [[:description] [:summary] [:statement]])
                                nil))
                :links (vec (link-facts state))
                :state state
                :haystack (str/lower-case
                           (str (display-title state) " "
                                (pr-str (guard/fact-map state))))
                :settled? (next-lens/settled? process events t roles)}))
        rows (cond->> rows
               (:filter opts) (filter (comp (stage-filter (:filter opts))
                                            :current))
               (:search opts) (filter #(str/includes?
                                        (:haystack %)
                                        (str/lower-case (str (:search opts)))))
               (string? (:where opts))
               (filter (let [[k v] (str/split (:where opts) #"=" 2)
                             _ (when-not v
                                 (die "usage: tik ls --where key=value"))
                             path (parse-key k)
                             want (parse-value v)]
                         #(let [fv (red/fact-value (:state %) path)]
                            (or (= want fv)
                                ;; keyword/string spellings of the same
                                ;; name match — repo=:jaas finds both
                                (and fv (= (str/replace (str want) #"^:" "")
                                           (str/replace (str fv) #"^:" ""))))))))
        visible (if (:all opts) rows (remove :settled? rows))]
    (if (:edn opts)
      (prn (mapv #(select-keys % [:id :title :current :describe :settled?])
                 visible))
      (do
        (doseq [{:keys [id current title describe settled? links error]} visible]
      (println (tint "2" (subs (str id) 0 8))
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
                          " finished ticket(s) hidden (--all shows)"))))))
    (cache-flush!)))

(defn- cmd-search
  "tik search <text…> = ls --search over everything, settled included."
  [{:keys [pos opts]}]
  (cmd-ls {:opts (assoc opts :search (str/join " " pos) :all true)}))

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
  (let [p (read-edn-file f)
        problems (process/lint p)]
    (doseq [{:keys [level msg]} problems]
      (println (str "[" (name level) "] " msg)))
    (when (not-any? #(= :error (:level %)) problems) p)))

(defn- resolve-file
  "A file argument as given, else relative to the store root — so
  `tik test processes/bug.tests.edn` works both inside the store and
  wherever TIK_ROOT points from."
  ^File [path]
  (let [as-given (io/file path)]
    (if (.exists as-given) as-given (io/file (root) path))))

(defn- cmd-sim
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
  (let [f (resolve-file (first pos))
        _ (when-not (.exists f) (die "no such file:" (str f)))
        {:test/keys [process cases]} (read-edn-file f)
        proc (read-edn-file (io/file (.getParentFile (.getAbsoluteFile f))
                                     process))
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
      (do (println (str "test: FAIL (" @failures ")")) (exit! 1)))))

(defn- cmd-migrate
  "Dry-run BY DEFAULT (ADR 0002): a migration is a consequence-bearing
  decision, so show the derived-stage diff under the pinned definition
  vs the proposed one before anyone commits. --apply appends the signed
  :process/migrate event and archives the new definition by hash."
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        new-file (or (second pos) (die "usage: tik migrate <id> <new.edn> [--apply]"))
        _ (when-not (.exists (io/file new-file)) (die "no such file:" new-file))
        new-proc (read-edn-file (io/file new-file))
        problems (process/lint new-proc)
        _ (do (doseq [{:keys [level msg]} problems]
                (println (str "[" (name level) "] " msg)))
              (when (some #(= :error (:level %)) problems)
                (die "refusing to migrate to a definition with lint errors")))
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
    (println (str "pinned:   v" (:process/version process) " @ " (subs old-hash 0 19) "…"))
    (println (str "proposed: v" (:process/version new-proc) " @ " (subs new-hash 0 19) "…"))
    (doseq [stage-id (sort-by str (remove after before))]
      (println "  - stage" stage-id "would REGRESS (no longer derivable)"))
    (doseq [stage-id (sort-by str (remove before after))]
      (println "  + stage" stage-id "would become derivable"))
    (when (= before after)
      (println "  derived stages unchanged"))
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
          (println "migrated — ticket now pins" (subs new-hash 0 19) "…")))))

(defn- cmd-export
  "Materialize the current store (whatever backend) as a file/git store
  at <dir> — the auditor-grade interchange format where sha256sum(file)
  = filename. Events only: blobs and the actors registry are filesystem
  artifacts under TIK_ROOT and copy with cp."
  [{:keys [pos]}]
  (let [target (or (first pos) (die "usage: tik export <dir>"))
        src (the-store)
        dest (fstore/file-store target)
        n (reduce (fn [n id]
                    (reduce (fn [n e] (store/append! dest e) (inc n))
                            n (store/events src id)))
                  0 (store/ticket-ids src))]
    (println "exported" n "event(s) to" target)))

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
        claim (edn/read-string claim-str)
        extra (some-> (:body opts) edn/read-string)]
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
    (filterv #(or (= :anyone (:who %))
                  (contains? (:who %) actor-name))
             actions)))

(defn- agent-refuse! [actor-name attempted admissible]
  (binding [*out* *err*]
    (println "REFUSED:" (pr-str attempted) "is not admitted by the"
             "frontier for actor" actor-name)
    (println "admissible now:"
             (pr-str (mapv :action admissible))))
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
        s (the-store)
        id (resolve-id s ticket)
        admissible (agent-admissible id who)]
    (case sub
      "actions" (prn {:actor who :ticket id
                      :admissible (mapv #(select-keys % [:action :stage])
                                        admissible)})
      "set" (let [[k v] (str/split (first args) #"=" 2)
                  path (parse-key k)
                  attempted [:set path]]
              (when-not (some #(= attempted (:action %)) admissible)
                (agent-refuse! who attempted admissible))
              (append!* s (event/assert-fact
                           {:ticket id :actor who :at (now)
                            :parents (dag/heads (store/events s id))
                            :path path :value (parse-value v)})
                        opts)
              (println "ok" (pr-str attempted)))
      "attest" (let [claim (edn/read-string (first args))
                     attempted [:attest claim]]
                 (when-not (some #(= attempted (:action %)) admissible)
                   (agent-refuse! who attempted admissible))
                 (append!* s (event/add-attestation
                              {:ticket id :actor who :at (now)
                               :parents (dag/heads (store/events s id))
                               :claim {:claim claim}})
                           opts)
                 (println "ok" (pr-str attempted)))
      (die "usage: tik agent actions|set|attest <id> ... --actor A"))))

(defn- cmd-witness
  "witness <id> [--key K]: countersign every current head — a detached
  <head>.witness.<fpr> sidecar over the head event's stored bytes. One
  signature timestamps the entire ancestry the head commits to
  (ADR 0004); observation, not authorship, hence its own namespace."
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
        dir (events-dir id)
        heads (dag/heads (store/events s id))]
    (doseq [head heads
            :let [f (io/file dir (str head ".edn"))
                  fpr (sign/fingerprint (sign/pubkey key))
                  target (io/file dir (str head ".witness." fpr))]]
      (when-not (.exists target)
        (let [produced (sign/sign! key f (str head ".witness-tmp")
                                   sign/namespace-witness)]
          (.renameTo ^File produced target)))
      (println "witnessed" (subs head 0 19) "…"))
    (println (count heads) "head(s) countersigned — each timestamps its"
             "entire ancestry")))

(defn- witness-sidecars [dir head]
  (let [prefix (str head ".witness.")]
    (->> (.listFiles (io/file dir))
         (filter (fn [^File f]
                   (and (.isFile f)
                        (str/starts-with? (.getName f) prefix))))
         (sort-by (fn [^File f] (.getName f))))))

(defn- cmd-actor
  "actor add <name> <pubkey-file>: bind an actor to a key in the store's
  allowed-signers registry (identity ladder rung 1, PLAN §9)."
  [{:keys [pos]}]
  (let [[sub actor-name pubkey-file] pos]
    (when-not (and (= "add" sub) actor-name pubkey-file)
      (die "usage: tik actor add <name> <pubkey-file>"))
    (let [line (sign/allowed-signers-line
                actor-name (str/trim (slurp pubkey-file)))
          f (io/file (root) "actors")]
      (spit f (str line "
") :append true)
      (println "ok" (sign/fingerprint (str/trim (slurp pubkey-file)))))))

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
        dir (events-dir id)
        mine (filter #(= me (:event/actor %)) (store/events s id))
        unsigned (remove #(.exists (sign/sig-file dir (:event/id %) fpr))
                         mine)]
    (doseq [e unsigned]
      (sign/sign! key (io/file dir (str (:event/id e) ".edn")) (:event/id e)))
    (println "signed" (count unsigned) "event(s) as" me
             (str "(" (count mine) " authored, key " fpr ")"))))

(defn- cmd-debug
  "The fixpoint with its working shown: every sweep, every stage, every
  guard verdict against the sweep-start snapshot. tik's EXPLAIN plan."
  [{:keys [pos opts]}]
  (let [proc-name (first pos)
        proc (if (str/ends-with? (str proc-name) ".edn")
               (edn/read-string (slurp proc-name))
               (load-process proc-name))
        s (the-store)
        [state t roles]
        (if-let [tid (second pos)]
          (let [{:keys [state roles]} (ticket-ctx s (resolve-id s tid))]
            [state (now) roles])
          [red/empty-state (now) (:process/roles proc {})])
        {:keys [reached sweeps]} (stage/trace-sweeps proc state t roles)]
    (if (:edn opts)
      (prn {:reached reached :sweeps sweeps})
      (do
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
        (println (tint "1" (str "fixpoint: " (pr-str (vec (sort-by str reached))))))))))

(defn- cmd-graph
  "The :after DAG by strata; with a ticket, overlay derived status:
  ● reached, ◐ frontier (prereqs met, guards missing), ○ blocked."
  [{:keys [pos]}]
  (let [proc-name (first pos)
        proc (if (str/ends-with? (str proc-name) ".edn")
               (edn/read-string (slurp proc-name))
               (load-process proc-name))
        s (the-store)
        reached (when-let [tid (second pos)]
                  (let [{:keys [events process roles]}
                        (ticket-ctx s (resolve-id s tid))]
                    (stage/effective-reached process events (now) roles)))
        stages (:process/stages proc)
        depth (fn depth [id seen]
                (let [st (first (filter #(= id (:stage/id %)) stages))]
                  (if (or (seen id) (empty? (:after st [])))
                    0
                    (inc (apply max (map #(depth % (conj seen id))
                                         (:after st)))))))
        by-depth (group-by #(depth (:stage/id %) #{}) stages)]
    (doseq [d (sort (keys by-depth))]
      (doseq [st (sort-by :stage/id (by-depth d))
              :let [id (:stage/id st)
                    frontier? (and reached
                                   (not (reached id))
                                   (every? reached (:after st [])))
                    glyph (cond (nil? reached) "·"
                                (reached id) (tint "32" "●")
                                frontier? (tint "36" "◐")
                                :else (tint "2" "○"))]]
        (println (str (str/join (repeat (* 2 d) " "))
                      glyph " " (name id)
                      (when (seq (:after st))
                        (tint "2" (str "  <- " (str/join ", " (map name (:after st))))))
                      (when (:stage/sticky? st) (tint "33" "  [sticky]"))))))))

(defn- cmd-whatif
  "Counterfactuals: apply hypothetical steps to a ticket IN MEMORY and
  show what would change. Nothing is written — derivation over a
  hypothetical event set is the same pure function (PLAN §19)."
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        {:keys [events process roles]} (ticket-ctx s id)
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
        after (stage/effective-reached process (:events st) (:now st) roles)]
    (println (tint "2" (str "whatif " (str/join " " (rest pos))
                            "  (nothing recorded)")))
    (doseq [g (sort-by str (remove before after))]
      (println (tint "32" (str "  + " g " would become derivable"))))
    (doseq [l (sort-by str (remove after before))]
      (println (tint "31" (str "  - " l " would no longer derive"))))
    (when (= before after)
      (println "  no derived change"))))

(defn- cmd-query
  "The query lens: questions across EVERY ticket's log.
  disputed | conflicted | unsigned | fact <k> [<v>] | actor <name> | stage <:kw>"
  [{:keys [pos opts]}]
  (let [s (the-store)
        t (now)
        [q & args] pos
        _ (when (= "duplicates" q)
            (let [threshold (or (some-> (:threshold opts) parse-value double) 0.4)
                  pairs (dupe/lookalikes (open-ticket-rows s t) threshold)]
              (doseq [{:keys [a b score]} pairs]
                (println (str (subs (str a) 0 8) " ~ " (subs (str b) 0 8)
                              "  " (int (* 100 score)) "% similar")))
              (println (count pairs) "lookalike pair(s) at >="
                       (str (int (* 100 threshold)) "%"))
              (exit! 0)))
        rows (for [id (store/ticket-ids s)
                   :let [{:keys [events state process roles]} (ticket-ctx s id)]]
               {:id id :title (display-title state) :state state :events events
                :reached (stage/effective-reached process events t roles)})
        hit? (case q
               "disputed" (fn [{:keys [state]}]
                            (some #(= :disputed (:status (red/fact-status state %)))
                                  (keys (:facts state))))
               "conflicted" (fn [{:keys [state]}]
                              (some #(= :conflicted (:status (red/fact-status state %)))
                                    (keys (:facts state))))
               "unsigned" (fn [{:keys [id events]}]
                            (let [dir (events-dir id)]
                              (some #(empty? (sign/sidecars dir (:event/id %)))
                                    events)))
               "fact" (let [path (parse-key (first args))
                            v (some-> (second args) parse-value)]
                        (fn [{:keys [state]}]
                          (let [fs (red/fact-status state path)]
                            (and (= :present (:status fs))
                                 (or (nil? v) (= v (:value fs)))))))
               "actor" (fn [{:keys [events]}]
                         (some #(= (first args) (:event/actor %)) events))
               "stage" (fn [{:keys [reached]}]
                         (contains? reached (edn/read-string (first args))))
               "derived-from" (fn [{:keys [events]}]
                                (some #(= (first args)
                                          (get-in % [:event/body
                                                     :artifact/derived-from]))
                                      events))
               (die "usage: tik query disputed|conflicted|unsigned|fact <k> [v]|actor <name>|stage <:kw>|derived-from <hash>|duplicates [--threshold 0.4]"))
        hits (filter hit? rows)]
    (if (:edn opts)
      (prn (mapv #(select-keys % [:id :title :reached]) hits))
      (do (doseq [{:keys [id title reached]} hits]
            (println (tint "2" (subs (str id) 0 8)) title
                     (tint "2" (pr-str (vec (sort-by str reached))))))
          (println (count hits) "ticket(s)")))))

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
        rows (for [id (store/ticket-ids s)
                   :let [{:keys [events state process roles]} (ticket-ctx s id)
                         reached (stage/effective-reached process events t roles)
                         current (stage/current-stages process reached)
                         settled? (next-lens/settled? process events t roles)]]
               {:id id :title (display-title state) :process (:process state)
                :current current :settled? settled?
                :parked? (contains? current :parked)
                :facts (sort-by (comp pr-str key) (guard/fact-map state))
                :blocks (explain/explain process events t roles)})
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
                       "<span class=\"id\">" (html-escape (subs (str id) 0 8)) "</span>"
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

(defn- parse-rfc822
  "Minimal RFC822: headers to the first blank line, body after.
  Header folding (continuation lines) honored; only From and Subject
  are consumed."
  [text]
  (let [lines (str/split-lines text)
        [head body] (split-with #(not (str/blank? %)) lines)
        headers (loop [hs [] [l & more] head]
                  (cond
                    (nil? l) hs
                    (and (seq hs) (re-matches #"^[ \t].*" l))
                    (recur (conj (pop hs) (str (peek hs) " " (str/trim l))) more)
                    :else (recur (conj hs l) more)))
        header (fn [k]
                 (some #(when-let [[_ v] (re-matches
                                          (re-pattern (str "(?i)^" k ":\\s*(.*)$")) %)]
                          (str/trim v))
                       headers))]
    {:from (some->> (header "From")
                    (re-find #"[\w.+-]+@[\w.-]+")
                    str/lower-case)
     :subject (or (header "Subject") "")
     :body (str/trim (str/join "\n" (rest body)))}))

(defn- cmd-bridge-oidc
  "bridge oidc: identity rung 2 (PLAN §9). Login against the config's
  issuer — device flow by default, password grant when --user and
  --password are given (headless onboarding) — and append the signed
  key-binding attestation to the registry ticket. Verification of the
  binding never calls the IdP."
  [opts]
  (let [cfg-file (or (:config opts) (str (io/file (root) "oidc.edn")))
        cfg (if (.exists (io/file cfg-file))
              (read-edn-file (io/file cfg-file)) {})
        issuer (or (:issuer opts) (:issuer cfg)
                   (die "no OIDC issuer — pass --issuer or put :issuer in oidc.edn"))
        client-id (or (:client-id opts) (:client-id cfg) "tik")
        registry-ref (or (:registry opts) (:registry cfg)
                         (die (str "no registry ticket — mint one with\n"
                                   "  tik new identity-registry --title 'identity registry'\n"
                                   "then pass --registry <its id>")))
        who (actor opts)
        key-file (or (:key opts)
                     (some-> (System/getenv "TIK_KEY") (str ".pub"))
                     (die "which key binds to this login? --key <pubkey.pub> or set TIK_KEY"))
        public-key (str/trim (slurp key-file))
        s (the-store)
        registry-id (resolve-id s registry-ref)
        endpoints (oidc/discover oidc/http-get issuer)
        response (if (and (:user opts) (:password opts))
                   (oidc/password-flow oidc/http-post endpoints client-id
                                       (:user opts) (:password opts))
                   (let [{:keys [prompt poll]} (oidc/device-flow
                                                oidc/http-post endpoints
                                                client-id #(Thread/sleep (long %)))]
                     (println prompt)
                     (loop [] (or (poll) (recur)))))
        claim (try (oidc/token->binding response {:actor who
                                                  :public-key public-key
                                                  :issuer issuer})
                   (catch clojure.lang.ExceptionInfo e (die (ex-message e))))
        heads (dag/heads (store/events s registry-id))
        e (event/add-attestation {:ticket registry-id :actor who :at (now)
                                  :parents heads :claim claim})]
    (append!* s e opts)
    (println (str "bound " (:identity/issuer claim) " subject "
                  (:identity/subject claim) " (" (:identity/username claim)
                  ") to actor '" who "' — attestation " (subs (:event/id e) 0 15)
                  "… on " registry-id))
    (exit! 0)))

(defn- cmd-bridge
  "bridge email [--config bridge.edn] < message
  One RFC822 message on stdin — MTA-agnostic: procmail, fetchmail,
  maildrop, or a paste all work. The bridge is an ACTOR (ADR 0019
  inbound): the sender maps to an actor via the config's :from->actor
  (unknown senders use :default-actor or are rejected), a subject
  containing [tik <id-prefix>] comments that ticket, anything else
  opens a new ticket under :process with the body as first comment.
  Set TIK_KEY so the bridge's claims are signed like anyone else's."
  [{:keys [pos opts]}]
  (when (= "oidc" (first pos)) (cmd-bridge-oidc opts))
  (when-not (= "email" (first pos))
    (die "usage: tik bridge email [--config bridge.edn] < message\n       tik bridge oidc [--config oidc.edn] [--registry ID] [--actor A]"))
  (let [cfg-file (or (:config opts) (str (io/file (root) "bridge.edn")))
        cfg (if (.exists (io/file cfg-file))
              (read-edn-file (io/file cfg-file))
              {})
        {:keys [from subject body]} (parse-rfc822 (slurp *in*))
        actor-name (or (get-in cfg [:from->actor from])
                       (:default-actor cfg)
                       (die (str "unknown sender " from
                                 " and no :default-actor in " cfg-file)))
        opts (assoc opts :actor actor-name)
        s (the-store)
        ticket-ref (second (re-find #"\[tik ([0-9a-f-]+)\]" subject))]
    (if ticket-ref
      (let [id (resolve-id s ticket-ref)
            at (now)
            text (str subject "\n\n" body)
            bytes (.getBytes ^String text "UTF-8")
            hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
            dest (io/file (root) "tickets" (str id) "blobs" hash)]
        (io/make-parents dest)
        (spit dest text)
        (append!* s (event/attach-artifact
                     {:ticket id :actor actor-name :at at
                      :parents (dag/heads (store/events s id))
                      :path (str "comment/" at) :hash hash})
                  opts)
        ;; the reply convention: `tik> key=value` lines become signed
        ;; facts — the other half of the info-request loop (the email
        ;; sink teaches exactly this syntax)
        (let [proc (:process (ticket-ctx s id))
              facts (for [line (str/split-lines (or body ""))
                          :let [[_ k v] (re-matches #"\s*tik>\s*([^=\s]+)=(.*)" line)]
                          :when k
                          :let [path (parse-key k)]]
                      [path (typed-value proc path (str/trim v))])]
          (doseq [[path value] facts]
            (append!* s (event/assert-fact
                         {:ticket id :actor actor-name :at (now)
                          :parents (dag/heads (store/events s id))
                          :path path :value value})
                      opts))
          (println (str "comment -> " (subs (str id) 0 8) " as " actor-name
                        (when (seq facts)
                          (str " (+ " (count facts) " fact(s))"))))))
      (let [proc-name (name (or (:process cfg) (die (str "no :process in " cfg-file))))
            proc (load-process proc-name)
            id (random-uuid)
            e (event/create-ticket {:ticket id :actor actor-name :at (now)
                                    :title subject
                                    :process (keyword proc-name)
                                    :version (:process/version proc)
                                    :process-hash (archive-process! proc)})]
        (append!* s e opts)
        (when-not (str/blank? body)
          (let [at (now)
                bytes (.getBytes ^String body "UTF-8")
                hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
                dest (io/file (root) "tickets" (str id) "blobs" hash)]
            (io/make-parents dest)
            (spit dest body)
            (append!* s (event/attach-artifact
                         {:ticket id :actor actor-name :at at
                          :parents (dag/heads (store/events s id))
                          :path (str "comment/" at) :hash hash})
                      opts)))
        (println (str id))))))

;; ---------------------------------------------------------------- effects
;; ADR 0019: effects observe derivation. Delivery lives entirely outside
;; the log; the sent-ledger is a DISPOSABLE cache (ADR 0013) — deleting
;; it can only cause a resend, never wrong truth.

(defn- render-template
  "{{ticket}} {{short}} {{title}} {{stage}} in the sink's own words —
  every sink may carry a :template; the default reads like a log line."
  [template {:keys [ticket title stage]}]
  (-> template
      (str/replace "{{ticket}}" (str ticket))
      (str/replace "{{short}}" (subs (str ticket) 0 8))
      (str/replace "{{title}}" (str title))
      (str/replace "{{stage}}" (str stage))))

(defn- effect-payload
  "One derived transition in each service's native shape. Every
  adapter is a pure data mapping over the same {ticket title stage};
  credentials, addressing and message :template come from the sink's
  own config. The fallback shape is the stable :webhook contract."
  [{:keys [type] :as sink} {:keys [ticket title stage] :as tr}]
  (let [text (render-template
              (or (:template sink)
                  "tik: \"{{title}}\" reached {{stage}} ({{short}})")
              tr)]
    (case type
      :slack {"text" text}
      :discord {"content" text}
      :matrix {"msgtype" "m.text" "body" text}
      :mattermost {"text" text}
      :rocketchat {"text" text}
      :googlechat {"text" text}
      :teams {"type" "message" "text" text}
      :ntfy {"topic" (:topic sink) "title" (str "tik: " title)
             "message" (if (:template sink) text (str "reached " stage))}
      :gotify {"title" (str "tik: " title)
               "message" (if (:template sink) text (str "reached " stage))
               "priority" 5}
      :pushover {"token" (:token sink) "user" (:user sink)
                 "message" text}
      :telegram {"chat_id" (:chat-id sink) "text" text}
      :opsgenie {"message" text
                 "alias" (str ticket ":" stage)
                 "source" "tik"}
      :alertmanager [{"labels" {"alertname" "tik_stage_reached"
                                "ticket" (str ticket)
                                "stage" (str stage)}
                      "annotations" {"summary" text}}]
      :pagerduty {"payload" {"summary" text
                             "source" "tik"
                             "severity" "info"}
                  "event_action" "trigger"}
      {"ticket" (str ticket) "title" title
       "stage" (str stage) "text" text})))

(defn- email-message
  "RFC822 text asking a human for what the stage needs. The subject
  carries [tik <id>] so a plain reply routes back through `tik bridge
  email`; the body renders explain and teaches the reply convention —
  the email IS a capability-scoped view of the same derivation."
  [{:keys [to from]} {:keys [ticket title stage]} explain-text]
  (str "To: " to "\r\n"
       "From: " (or from "tik") "\r\n"
       "Subject: [tik " ticket "] " title " — " stage "\r\n"
       "\r\n"
       "\"" title "\" reached " stage " and needs something only you"
       " can provide.\r\n\r\n"
       explain-text
       "\r\nReply to this email. Lines like\r\n\r\n"
       "  tik> key=value\r\n\r\n"
       "become facts on the ticket (everything else is kept as a"
       " comment), and the process moves on the moment the facts"
       " arrive.\r\n"))

(defn- json-str
  "Tiny JSON emitter for the flat payloads above — no dependency."
  [x]
  (cond
    (map? x) (str "{" (str/join "," (for [[k v] x]
                                      (str (json-str (str k)) ":" (json-str v))))
                  "}")
    (sequential? x) (str "[" (str/join "," (map json-str x)) "]")
    (string? x) (str "\"" (-> x (str/replace "\\" "\\\\")
                               (str/replace "\"" "\\\"")
                               (str/replace "\n" "\\n"))
                     "\"")
    :else (str x)))

(defn- resolve-header-value
  "A header value is a string, {:env \"NAME\"} (process environment),
  or {:command [\"pass\" \"show\" \"x\"]} (first line of a local
  secret-manager's stdout — pass, passage, gopass, op read, anything
  that prints the secret). Resolved at send time so effects.edn can be
  committed with no secret in it. Failures are loud and name the
  source; a silent blank header would just be a mysterious 401 later."
  [k v]
  (cond
    (and (map? v) (:env v))
    (or (System/getenv (:env v))
        (throw (ex-info (str "header " k " wants environment variable "
                             (:env v) ", which is not set")
                        {:header k :env (:env v)})))

    (and (map? v) (:command v))
    (let [r (apply sh/sh (:command v))]
      (if (zero? (:exit r))
        (str/trim-newline (first (str/split-lines (:out r))))
        (throw (ex-info (str "header " k " lookup command "
                             (pr-str (:command v)) " failed: "
                             (str/trim (:err r)))
                        {:header k :command (:command v)
                         :exit (:exit r)}))))

    :else v))

(defn- resolve-headers [headers]
  (into {} (for [[k v] headers] [k (resolve-header-value k v)])))

(defn- post!
  "POST JSON; babashka's built-in http client, resolved lazily so the
  namespace loads on the JVM too. Extra headers come from the sink's
  :headers — enough for every token-authenticated service (opsgenie's
  GenieKey, gotify's X-Gotify-Key, bearer tokens) without tik ever
  storing credentials anywhere but the operator's own config or, via
  {:env \"NAME\"} values, the process environment."
  [url body headers]
  (let [post (requiring-resolve 'babashka.http-client/post)]
    (post url {:headers (merge {"Content-Type" "application/json"}
                               (resolve-headers headers))
               :body body
               :throw false})))

(defn- cmd-effects
  "effects run [--config effects.edn] [--dry-run]
  Fire configured sinks for every newly derived stage transition.
  Config: {:sinks [{:type :slack :url \"…\"} …] :stages #{:landed}}
  (:stages optional — default every transition). Idempotent via
  content-hashed effect keys in .effects-sent; at-least-once on ledger
  loss, exactly what ADR 0019 promises."
  [{:keys [pos opts]}]
  (when-not (= "run" (first pos))
    (die "usage: tik effects run [--config effects.edn] [--dry-run]"))
  (let [cfg-file (or (:config opts) (str (io/file (root) "effects.edn")))
        _ (when-not (.exists (io/file cfg-file))
            (die "no effects config:" cfg-file))
        {:keys [sinks stages]} (read-edn-file (io/file cfg-file))
        ledger-file (io/file (root) ".effects-sent")
        sent (if (.exists ledger-file)
               (set (str/split-lines (slurp ledger-file)))
               #{})
        s (the-store)
        fired (atom 0)
        failed (atom 0)]
    (doseq [id (store/ticket-ids s)
            :let [{:keys [events state process roles]} (ticket-ctx s id)
                  timeline (:timeline (stage/evolve process events roles))
                  transitions
                  (distinct
                   (for [[prev entry] (map vector (cons nil timeline) timeline)
                         stage-id (sort-by str (remove (:reached prev #{})
                                                       (:reached entry)))
                         :when (or (nil? stages) (contains? stages stage-id))]
                     {:ticket id :title (display-title state) :stage stage-id}))]
            tr transitions
            sink sinks
            :let [key (canonical/sha256-hex
                       (pr-str [(:ticket tr) (:stage tr)
                                (:type sink) (:url sink) (:to sink)
                                (:command sink) (:topic sink)
                                (:chat-id sink)]))]
            :when (not (contains? sent key))]
      (if (:dry-run opts)
        (println "would send" (name (:type sink)) "<-"
                 (str (subs (str (:ticket tr)) 0 8) " " (:stage tr)))
        ;; one dead endpoint must not abandon the other sinks: failures
        ;; report and count, the ledger stays unmarked (retry next run),
        ;; the loop continues — at-least-once, per sink
        (try
          (case (:type sink)
              ;; sendmail-compatible: the :command reads RFC822 on stdin
              ;; (default sendmail -t); MTA-agnostic like the inbound
              ;; bridge — procmail in, sendmail out, tik stays porcelain
              :email
              (let [text (email-message
                          sink tr
                          (explain/render
                           (explain/explain process events (now) roles)))
                    cmdv (or (:command sink) ["sendmail" "-t"])
                    r (apply sh/sh (concat cmdv [:in text]))]
                (when-not (zero? (:exit r))
                  (die (str "email sink failed: " (:err r)))))
              ;; the universal escape hatch: the webhook JSON on stdin
              ;; to ANY program — notify-send wrappers, SMS gateways,
              ;; syslog, a shop's existing paging script
              :command
              (let [r (apply sh/sh (concat (:command sink)
                                           [:in (json-str
                                                 (effect-payload
                                                  (assoc sink :type :webhook)
                                                  tr))]))]
                (when-not (zero? (:exit r))
                  (die (str "command sink failed: " (:err r)))))
              (post! (:url sink) (json-str (effect-payload sink tr))
                     (:headers sink)))
              (spit ledger-file (str key "\n") :append true)
              (swap! fired inc)
              (println "sent" (name (:type sink)) "<-"
                       (str (subs (str (:ticket tr)) 0 8) " "
                            (:stage tr)))
          (catch Exception e
            (swap! failed inc)
            (binding [*out* *err*]
              (println (str "failed " (name (:type sink)) " <- "
                            (subs (str (:ticket tr)) 0 8) " " (:stage tr)
                            ": " (ex-message e)
                            " — will retry next run")))))))
    (when-not (:dry-run opts)
      (println @fired "effect(s) fired — delivery state is disposable,"
               "truth untouched")
      (when (pos? @failed)
        (println @failed "delivery failure(s) — unmarked in the ledger,"
                 "next run retries")
        (exit! 1)))))

(declare root-dir-roots verify-roots)

(defn- store-root-doc
  "The canonical root document: sorted ticket -> sorted heads. Derived,
  deterministic, tiny — and it commits to EVERY event in the store,
  because each head commits to its entire ancestry (ADR 0004). Never
  stored as authority: regenerated on demand, byte-identical each time."
  [s]
  (into (sorted-map)
        (for [id (sort-by str (store/ticket-ids s))]
          [id (into (sorted-set) (dag/heads (store/events s id)))])))

(defn- cmd-root
  "root [--witness [--key K]]: one hash for the whole store. Two
  replicas agree iff their roots agree — O(1) comparison over millions
  of tickets; a witness countersignature over the root timestamps
  everything at once. Only the SIDECAR is kept (roots/): the root
  document is derived and regenerates byte-identically, so verification
  re-derives it and checks the signature against fresh bytes."
  [{:keys [opts]}]
  (let [s (the-store)
        doc (store-root-doc s)
        bytes (canonical/emit doc)
        root (str "sha256-" (canonical/sha256-hex bytes))]
    (println root (str "(" (count doc) " tickets)"))
    (when (:witness opts)
      (let [key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
            dir (io/file (root-dir-roots))
            f (io/file dir (str root ".edn"))]
        (io/make-parents f)
        (spit f bytes)
        (let [produced (sign/sign! key f (str root ".witness-tmp")
                                   sign/namespace-witness)
              target (io/file dir (str root ".witness."
                                       (sign/fingerprint (sign/pubkey key))))]
          (.renameTo ^File produced target)
          ;; the doc regenerates; only the endorsement is worth keeping
          (io/delete-file f)
          (println "witnessed" (subs root 0 19) "… ->" (.getName target)))))
    (when (:anchor opts)
      ;; DELIBERATELY light integration: anchoring shells out to the
      ;; external `ots` tool if — and only if — the operator installed
      ;; it. tik ships no timestamping dependency, mentions no chain in
      ;; its surfaces, and works identically without this flag; the
      ;; .ots sidecar is just one more detached endorsement for those
      ;; who want third-party timestamping (PLAN §10).
      (when-not (zero? (:exit (sh/sh "sh" "-c" "command -v ots")))
        (die (str "--anchor needs the external `ots` tool"
                  " (opentimestamps-client) — entirely optional;"
                  " everything else works without it")))
      (let [dir (io/file (root-dir-roots))
            f (io/file dir (str root ".edn"))
            ots-target (io/file dir (str root ".ots"))]
        (io/make-parents f)
        (spit f bytes)
        (let [r (sh/sh "ots" "stamp" (str f))]
          (if (and (zero? (:exit r))
                   (.exists (io/file (str f ".ots"))))
            (do (.renameTo (io/file (str f ".ots")) ots-target)
                (println "anchored" (subs root 0 19)
                         "… ->" (.getName ots-target)
                         "(upgrade after ~1 block: ots upgrade)"))
            (println "anchor failed (calendars unreachable?):"
                     (str/trim (str (:err r))))))
        (io/delete-file f)))))

(defn- root-dir-roots ^File [] (io/file (root) "roots"))

(defn- verify-roots
  "Every kept root countersignature must verify against the FRESHLY
  re-derived root document — if the store changed since witnessing,
  the root it attests simply is not this store's current root (which
  is fine; roots are moments), but the signature must still verify
  against its own root's regenerated bytes when the root matches."
  [s check]
  (let [dir (root-dir-roots)
        signers (io/file (root) "actors")]
    (when (.isDirectory dir)
      (println "roots")
      (let [current-doc (store-root-doc s)
            current-bytes (canonical/emit current-doc)
            current-root (str "sha256-" (canonical/sha256-hex current-bytes))]
        (doseq [^File sc (.listFiles dir)
                :when (str/includes? (.getName sc) ".witness.")
                :let [attested-root (first (str/split (.getName sc)
                                                      #"\.witness\."))]]
          (if (= attested-root current-root)
            (let [tmp (io/file dir (str attested-root ".edn"))]
              (spit tmp current-bytes)
              (let [who (and (.exists signers)
                             (first (sign/find-principals
                                     signers tmp sc
                                     sign/namespace-witness)))]
                (check (boolean (and who (sign/verify signers tmp sc who
                                                      sign/namespace-witness)))
                       (str (subs attested-root 0 19)
                            "… CURRENT root witnessed by " (or who "<unregistered>"))))
              (io/delete-file tmp))
            (println (str "  note  " (subs attested-root 0 19)
                          "… witnessed root is historical (store has"
                          " grown since)"))))))))

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
        port (if (:port opts) (Long/parseLong (str (:port opts))) 7777)
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
            body (edn/read-string (nth pos 2))]
        (append!* s (event/add-attestation
                     {:ticket id :actor (actor opts) :at (now)
                      :parents (dag/heads (store/events s id))
                      :claim (merge {:claim :work} body)})
                  opts)
        (println "recorded" (pr-str (:work/kind body :work))
                 "on" (subs (str id) 0 8)))

      "week"
      (let [who (or (:actor opts) (die "work week requires --actor"))
            from (some-> (:from opts) Instant/parse)
            to (some-> (:to opts) Instant/parse)
            per-ticket (for [id (store/ticket-ids s)
                             :let [{:keys [events state]} (ticket-ctx s id)]]
                         {:ticket id :title (:title state) :events events})
            d (work/draft per-ticket who from to)]
        (if (:edn opts)
          (prn d)
          (do
            (println (tint "1" (str "activity draft — " who))
                     (tint "2" (str "(" (get-in d [:method :statement]) ")")))
            (doseq [{:keys [ticket title sessions duration evidence]}
                    (:tickets d)]
              (println (format "  %s  ~%-10s %d session(s), %d event(s)  %s"
                               (subs (str ticket) 0 8)
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
                                    " you, carried with evidence"))))))))

      "cost"
      (let [pricing (some-> (:pricing opts) slurp edn/read-string)
            records (mapcat (fn [id]
                              (map #(assoc % :ticket id)
                                   (work/work-records
                                    (store/events s id))))
                            (store/ticket-ids s))
            agent-runs (filter :usage records)
            totals (work/usage-totals agent-runs pricing)]
        (if (:edn opts)
          (prn totals)
          (do
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
                                      " observations don't rot)")))))))

      (die "usage: tik work record|week|cost ..."))))

(def ^:private bundle-verify-sh
  "POSIX shell + coreutils + ssh-keygen — no tik required. The bundle
  is evidence precisely because the recipient needs nothing of ours to
  check it."
  "#!/bin/sh
# Verifies this tik evidence bundle. Requires only coreutils and
# ssh-keygen (OpenSSH 8.2+). Run from anywhere: ./verify.sh
set -u
cd \"$(dirname \"$0\")\"
fail=0

# L0 integrity: every stored file's name IS the sha256 of its bytes.
for f in tickets/*/events/*.edn processes/by-hash/*.edn; do
  [ -e \"$f\" ] || continue
  base=$(basename \"$f\" .edn)
  sum=$(sha256sum \"$f\" | cut -d' ' -f1)
  if [ \"sha256-$sum\" = \"$base\" ]; then
    echo \"ok    $base bytes match their name\"
  else
    echo \"FAIL  $f bytes do not match their name\"; fail=1
  fi
done
for f in tickets/*/blobs/*; do
  [ -e \"$f\" ] || continue
  base=$(basename \"$f\")
  sum=$(sha256sum \"$f\" | cut -d' ' -f1)
  if [ \"sha256-$sum\" = \"$base\" ]; then
    echo \"ok    $base blob bytes match their name\"
  else
    echo \"FAIL  $f blob bytes do not match their name\"; fail=1
  fi
done

# L1 authenticity: detached signatures against the actors registry.
check_sig() { # $1 sidecar, $2 target file, $3 namespace
  p=$(ssh-keygen -Y find-principals -f actors -n \"$3\" -s \"$1\" < \"$2\" 2>/dev/null)
  if [ -z \"$p\" ]; then
    echo \"FAIL  $1 signed by a key absent from the actors registry\"; fail=1; return
  fi
  if ssh-keygen -Y verify -f actors -I \"$p\" -n \"$3\" -s \"$1\" < \"$2\" >/dev/null 2>&1; then
    echo \"ok    $(basename \"$1\") verifies as $p\"
  else
    echo \"FAIL  $1 does not verify\"; fail=1
  fi
}
for sig in tickets/*/events/*.sig.*; do
  [ -e \"$sig\" ] || continue
  check_sig \"$sig\" \"${sig%%.sig.*}.edn\" tik-event
done
for sig in tickets/*/events/*.witness.*; do
  [ -e \"$sig\" ] || continue
  check_sig \"$sig\" \"${sig%%.witness.*}.edn\" tik-witness
done
for sig in processes/by-hash/*.sig.*; do
  [ -e \"$sig\" ] || continue
  check_sig \"$sig\" \"${sig%%.sig.*}.edn\" tik-process
done

if [ \"$fail\" = 0 ]; then echo 'bundle: PASS'; else echo 'bundle: FAIL'; exit 1; fi
")

(defn- bundle-readme [id title process-hash]
  (str "<!--\nSPDX-FileCopyrightText: The tik Authors\n"
       "SPDX-License-Identifier: 0BSD\n-->\n\n"
       "# Evidence bundle: " title "\n\n"
       "Ticket `" id "` as a self-contained, independently verifiable\n"
       "artifact. Nothing here requires tik or trusts its producer:\n\n"
       "- `tickets/…/events/*.edn` — the append-only log. Each file's\n"
       "  NAME is the sha256 of its BYTES, and parents inside each event\n"
       "  chain them into a Merkle DAG: one head commits to all history.\n"
       "- `*.sig.*` — detached authorship signatures (`ssh-keygen -Y`).\n"
       "- `*.witness.*` — third-party countersignatures over a head:\n"
       "  one signature timestamps the entire ancestry.\n"
       "- `processes/by-hash/" process-hash ".edn` — the exact ruleset\n"
       "  the ticket pinned at creation, plus publication signatures.\n"
       "- `actors` — the allowed-signers registry the signatures check\n"
       "  against. Verify its keys out of band; it names who, the\n"
       "  signatures prove that they, and the hashes prove what.\n\n"
       "## Verify\n\n"
       "```sh\n./verify.sh\n```\n\n"
       "coreutils + ssh-keygen only. To additionally REPLAY the\n"
       "derivation (what stage these facts imply), install tik and run\n"
       "`tik export`/`tik verify` over this directory — derivation is a\n"
       "pure function of these files, so any tik, anywhere, forever,\n"
       "derives the same answer.\n"))

(defn- cmd-bundle
  "The evidence bundle (PLAN §10): one ticket as a portable artifact a
  third party verifies with coreutils + ssh-keygen — no tik, no trust
  in us. This is H5's deliverable and the thing H8 sells."
  [{:keys [pos opts]}]
  (let [ticket (or (first pos) (die "usage: tik bundle <id> [--out file.tgz]"))
        s (the-store)
        id (resolve-id s ticket)
        {:keys [state]} (ticket-ctx s id)
        phash (:process-hash state)
        out (io/file (or (:out opts) (str "tik-bundle-" (subs (str id) 0 8) ".tgz")))
        work (.toFile (java.nio.file.Files/createTempDirectory
                       "tik-bundle" (make-array java.nio.file.attribute.FileAttribute 0)))
        bdir (io/file work "bundle")
        copy! (fn [^File src ^File dst]
                (when (.exists src)
                  (if (.isDirectory src)
                    (doseq [^File f (.listFiles src)
                            :when (.isFile f)]
                      (io/make-parents (io/file dst (.getName f)))
                      (io/copy f (io/file dst (.getName f))))
                    (do (io/make-parents dst) (io/copy src dst)))))]
    (copy! (io/file (root) "tickets" (str id) "events")
           (io/file bdir "tickets" (str id) "events"))
    (copy! (io/file (root) "tickets" (str id) "blobs")
           (io/file bdir "tickets" (str id) "blobs"))
    (copy! (io/file (root) "actors") (io/file bdir "actors"))
    (when phash
      (doseq [^File f (.listFiles (io/file (root) "processes" "by-hash"))
              :when (str/starts-with? (.getName f) phash)]
        (copy! f (io/file bdir "processes" "by-hash" (.getName f)))))
    (spit (io/file bdir "verify.sh") bundle-verify-sh)
    (.setExecutable (io/file bdir "verify.sh") true)
    (spit (io/file bdir "README.md")
          (bundle-readme id (:title state) (or phash "unpinned")))
    (let [r (sh/sh "tar" "czf" (str (.getAbsoluteFile out))
                   "-C" (str bdir) ".")]
      (when-not (zero? (:exit r)) (die (str "tar failed: " (:err r)))))
    (println (str "wrote " out))
    (println "verify anywhere with: tar xzf, then ./verify.sh (coreutils + ssh-keygen only)")))

(defn- cmd-roles
  "Who gates what: every role on the open board with its members and
  the stages waiting on its signature — the admin's inverse of
  next --role, derived from the definitions open tickets actually pin."
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        procs (distinct
               (for [id (store/ticket-ids s)
                     :let [{:keys [events process roles]} (ticket-ctx s id)]
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
    (if (:edn opts)
      (prn (vec rows))
      (do
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
          (println "no roles on the open board"))))))

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
                                 (authoring-rules))]
      (doseq [{:keys [level msg]} findings]
        (println (str "[" (name level) "] " msg)))
      (if (empty? findings)
        (println "clean — build it: tik author --from" (str f))
        (when (some #(= :error (:level %)) findings)
          (exit! 1)))
      (exit! 0)))
  (let [answers (cond
                  (:template opts)
                  (or (get author/templates (:template opts))
                      (die (str "no template '" (:template opts) "' — available: "
                                (str/join ", " (sort (keys author/templates))))))
                  (:from opts) (read-edn-file (resolve-file (:from opts)))
                  :else (author/interview read-line #(do (print %) (flush))))
        answers (if (string? (:name opts))
                  (assoc answers :name (:name opts))
                  answers)
        _ (when (str/blank? (:name answers))
            (die "the process needs a name — run tik author again"))
        answer-findings (author/check answers (authoring-rules))
        _ (doseq [{:keys [level msg]} answer-findings]
            (binding [*out* *err*]
              (println (str "[" (name level) "] " msg))))
        _ (when (some #(= :error (:level %)) answer-findings)
            (die "fix the answers file first (tik author check <file> re-checks without writing)"))
        pname (:name answers)
        definition (author/with-runbook-hints
                    (author/build-process answers) pname)
        def-file (io/file (root) "processes" (str pname ".edn"))
        tests-file (io/file (root) "processes" (str pname ".tests.edn"))
        problems (process/lint definition)]
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
      (doseq [{:keys [level msg]} problems]
        (println (str "[" (name level) "] " msg))))
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
         (for [id (store/ticket-ids s)
               :let [{:keys [events state process roles]} (ticket-ctx s id)
                     short (subs (str id) 0 8)
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
                (when-let [n (seq (filter #(empty? (sign/sidecars (events-dir id) (:event/id %))) events))]
                  (str short " has " (count n) " unsigned event(s) — tik sign " short " (or export TIK_KEY to sign as you write)"))]
               :when problem]
           problem))]
    (doseq [f findings] (println (str "[warning] " f)))
    (if (empty? findings)
      (println "store: clean")
      (do (println (str (count findings) " finding(s)"))
          (exit! 1)))))

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
          problems (concat (process/lint proc) missing-runbooks)]
      (doseq [{:keys [level msg]} problems]
        (println (str "[" (name level) "] " msg)))
      (when (some #(= :error (:level %)) problems) (exit! 1))
      (when (empty? problems) (println "clean")))))

(declare verify-ticket)

(defn- verify-definitions
  "Audit processes/by-hash/: every archived definition's bytes hash to
  its filename (the ADR 0007 property applied to governance), and every
  publication signature (namespace tik-process, ADR 0015) verifies
  against a registered principal."
  [check]
  (let [dir (io/file (root) "processes" "by-hash")
        signers (io/file (root) "actors")]
    (when (.isDirectory dir)
      (println "definitions")
      (doseq [^File f (.listFiles dir)
              :when (str/ends-with? (.getName f) ".edn")
              :let [stem (str/replace (.getName f) #"\.edn$" "")]]
        (check (= stem (str "sha256-" (canonical/sha256-hex (slurp f))))
               (str (subs stem 0 19) "… definition bytes hash to filename"))
        (let [sigs (sign/sidecars dir stem)]
          (if (empty? sigs)
            (println (str "  note  " (subs stem 0 19)
                          "… unsigned definition (tik process sign)"))
            (doseq [sig sigs
                    :let [who (and (.exists signers)
                                   (first (sign/find-principals
                                           signers f sig
                                           sign/namespace-process)))]]
              (check (boolean
                      (and who (sign/verify signers f sig who
                                            sign/namespace-process)))
                     (str (subs stem 0 19) "… published by "
                          (or who "<unregistered key>"))))))))))

(defn- cmd-verify
  "The verify ladder. With a ticket id: that ticket. With no arguments:
  the WHOLE STORE — every ticket plus every archived definition and its
  publication signatures. One command, complete audit."
  [{:keys [pos] :as parsed}]
  (if (empty? pos)
    (let [s (the-store)
          ids (store/ticket-ids s)
          failures (atom 0)
          check (fn [ok? msg]
                  (when-not ok?
                    (println (tint "31" (str "  FAIL  " msg)))
                    (swap! failures inc)))
          ;; --changed: skip tickets whose heads match the last full
          ;; audit. A disposable accelerator (ADR 0013): it detects
          ;; DRIFT, not in-place tampering of already-audited bytes —
          ;; the full run remains the audit, and always outranks.
          cache-file (io/file (root) ".verify-cache")
          cache (if (and (:changed (:opts parsed)) (.exists cache-file))
                  (edn/read-string (slurp cache-file))
                  {})
          skipped (atom 0)
          verified (atom {})]
      (doseq [id ids
              ;; even LISTING a ticket's events can raise on a hostile
              ;; store; the audit reports that ticket and continues
              :let [heads (try (dag/heads (store/events s id))
                               (catch Exception _ ::unreadable))]]
        (if (= heads (get cache id))
          (do (swap! skipped inc)
              (swap! verified assoc id heads))
          (let [r (try (with-out-str (verify-ticket parsed id))
                       (catch Exception e
                         (str "  FAIL  " (subs (str id) 0 8)
                              " unverifiable: " (ex-message e) "\n")))]
            (if (str/includes? r "FAIL")
              (do (print r) (swap! failures inc))
              (do (swap! verified assoc id heads)
                  (println (tint "2" (subs (str id) 0 8))
                           (tint "32" "ok")
                           (str (count (store/events s id)) " event(s)")))))))
      (verify-definitions check)
      (verify-roots s check)
      (when (pos? @skipped)
        (println (tint "2" (str "skipped " @skipped " ticket(s) with"
                                " unchanged heads — drift check only;"
                                " run without --changed for the audit"))))
      (if (zero? @failures)
        (do (spit cache-file (pr-str @verified))
            (println (tint "32" (str "verify: PASS (" (count ids)
                                     " tickets)"))))
        (do (println (tint "31" (str "verify: FAIL (" @failures ")")))
            (exit! 1))))
    (let [f (verify-ticket parsed (resolve-id (the-store) (first pos)))]
      (verify-definitions
       (fn [ok? msg]
         (println (str (if ok? "  ok    " "  FAIL  ") msg))
         (when-not ok? (exit! 1))))
      (when (pos? f) (exit! 1)))))

(defn- verify-ticket
  "The per-ticket ladder; prints, exits nonzero on failure when run for
  a single ticket."
  [{:keys []} id]
  (let [s (the-store)
        dir (io/file (root) "tickets" (str id) "events")
        files (filter (fn [^File f]
                        (and (.isFile f)
                             (str/ends-with? (.getName f) ".edn")))
                      (.listFiles dir))
        failures (atom 0)
        check (fn [ok? msg]
                (println (str (if ok? "  ok    " "  FAIL  ") msg))
                (when-not ok? (swap! failures inc)))]
    (println "L0 integrity")
    (if-let [db (db-path)]
      ;; SQLite: the raw BLOB must be the exact hashed region — checked
      ;; against storage, not against this process's parsing (ADR 0020)
      (doseq [[eid hex] (sqlite/raw-rows db id)
              :let [raw (try (String. ^bytes (sqlite/hex->bytes hex) "UTF-8")
                             (catch Exception ex
                               (check false (str (subs eid 0 19)
                                                 "… row bytes unreadable: "
                                                 (ex-message ex)))
                               nil))
                    e (when raw
                        (try (canonical/check-nesting raw)
                             (assoc (edn/read-string
                                     {:readers fstore/edn-readers} raw)
                                    :event/id eid)
                             (catch Exception ex
                               (check false (str (subs eid 0 19)
                                                 "… row unreadable: "
                                                 (ex-message ex)))
                               nil)))]
              :when e]
        (check (= eid (str "sha256-" (canonical/sha256-hex raw)))
               (str (subs eid 0 19) "… hash(stored bytes) = id"))
        (check (= raw (canonical/emit (dissoc e :event/id)))
               (str (subs eid 0 19) "… bytes are exactly the hashed region"))
        (check (event/valid? e) (str (subs eid 0 19) "… schema-valid")))
      (do
        (when-let [{:keys [entries pack]} (fstore/read-pack-index dir)]
          (let [pack-bytes (java.nio.file.Files/readAllBytes
                            (.toPath (io/file dir "events.pack")))]
            (check (= pack (str "sha256-"
                                (canonical/sha256-hex-bytes pack-bytes)))
                   (str "pack " (subs (str pack) 0 19)
                        "… bytes hash to the index's pack address"))
            (doseq [{:keys [id] :as entry} entries
                    :let [slice (fstore/pack-slice dir entry)]]
              (check (= id (str "sha256-"
                                (canonical/sha256-hex-bytes slice)))
                     (str (subs id 0 19) "… packed slice hashes to id")))))
        (doseq [^File f files
                :let [stem (str/replace (.getName f) #"\.edn$" "")
                      e (try (fstore/read-event f)
                             (catch Exception ex
                               (check false (str stem " unreadable: "
                                                 (ex-message ex)))
                               nil))]
                :when e
                :let [bytes-on-disk (slurp f)]]
          (check (= bytes-on-disk (canonical/emit (dissoc e :event/id)))
                 (str stem " bytes are exactly the canonical hashed region"))
          (check (= stem (event/event-id e))
                 (str stem " sha256(bytes) = filename = id"))
          (check (event/valid? e) (str stem " schema-valid")))))
    ;; the audit reports and continues even when the event SET cannot be
    ;; assembled — L0 above already named the offending file/row
    (when-let [evs (try (store/events s id)
                        (catch Exception e
                          (check false (str "events unreadable: "
                                            (ex-message e)))
                          nil))]
      (check (empty? (dag/missing-parents evs)) "all parents present")
      (check (= 1 (count (dag/roots evs))) "exactly one root (:ticket/create)")
      (println "L1 authenticity")
      (let [signers (io/file (root) "actors")]
        (if-not (.exists signers)
          (println "  skip  no actors registry (tik actor add <name> <key.pub>)")
          (let [unsigned (atom 0)]
            (doseq [e (red/ordered evs)
                    :let [sigs (sign/sidecars dir (:event/id e))]]
              (if (empty? sigs)
                (swap! unsigned inc)
                (doseq [sig sigs]
                  (check (sign/verify signers
                                      (io/file dir (str (:event/id e) ".edn"))
                                      sig (:event/actor e))
                         (str (subs (:event/id e) 0 19) "… signed by "
                              (:event/actor e))))))
            (when (pos? @unsigned)
              (println (str "  note  " @unsigned " event(s) unsigned"
                            " (authenticity unclaimed, not failed)"))))))
      (println "L3 provenance (witness countersignatures)")
      (let [signers (io/file (root) "actors")
            heads (dag/heads evs)
            witnessed (for [head heads
                            sc (witness-sidecars dir head)]
                        [head sc])]
        (if (empty? witnessed)
          (println "  note  no countersigned heads (tik witness <id>)")
          (doseq [[head ^File sc] witnessed
                  :let [f (io/file dir (str head ".edn"))
                        who (and (.exists signers)
                                 (first (sign/find-principals
                                         signers f sc
                                         sign/namespace-witness)))]]
            (check (boolean (and who (sign/verify signers f sc who
                                                  sign/namespace-witness)))
                   (str (subs head 0 19) "… witnessed by "
                        (or who "<unregistered key>")
                        " (whole ancestry)")))))
      (println "L2 reproducibility")
      (let [state (red/ticket-state evs)
            proc (load-pinned-process state)]
        (check (or (nil? (:process-hash state))
                   (= (:process-hash state) (process/process-hash proc)))
               "pinned process hash resolves to its definition")
        ;; an audit reports and continues — a derivation that raises is
        ;; a FAIL line for THIS ticket, never an aborted audit
        (try
          (let [reached (stage/effective-reached proc evs (now)
                                                 (:process/roles proc {}))]
            (println (str "  ok    derived: "
                          (str/join ", " (map str (sort-by str reached))))))
          (catch Exception e
            (check false (str "derivation raises: " (ex-message e)))))))
    (if (zero? @failures)
      (println "verify: PASS")
      (println (str "verify: FAIL (" @failures ")")))
    @failures))

(def ^:private usage
  "tik — a process system, not a ticket system

  start here:
    tik author                       answer questions; tik writes the process
    tik new expense-approval         mint a ticket against it
    tik set <id> amount=120          record facts; the stage derives itself
    tik explain <id>                 what is missing, who can act

  tik init [--hidden]                           mark this directory as a store
                                                (--hidden: everything inside .tik/ —
                                                one dot-entry, e.g. above many repos).
                                                Commands find the store git-style:
                                                TIK_ROOT, else the nearest ancestor
                                                with .tik/ or tickets/, else here
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
  tik ls [--all] [--long]                       open tickets with derived stages;
         [--filter ':a :b'|':not :a']           --long adds each ticket's description
         [--search TEXT] [--where k=v]          fact; filter by current stage
                                                (negatable), search titles+facts, or
                                                match any fact (--where repo=:jaas)
  tik search <text...>                          search ALL tickets, titles and facts
  tik query <question> [args]                   across every log: disputed|conflicted|
                                                unsigned|fact <k> [v]|actor <n>|stage <:s>|
                                                duplicates [--threshold 0.4] (lookalike
                                                open tickets, best match first)
  tik whatif <id> k=v|retract:k|+PT48H ...      counterfactual: stage diff, nothing
                                                written — e.g. tik whatif 3184 sev=:low
                                                +P2D (two days pass) retract:category
  tik debug <process> [<id>]                    the fixpoint with its working shown
  tik graph <process> [<id>]                    the stage DAG; ● reached ◐ frontier ○ blocked
  tik board [<file.html>]                       the whole board as ONE dependency-free
                                                HTML file — mail it, archive it
  tik serve [--port N]                          the live board over HTTP (read-only;
                                                /tickets.edn + /explain/<id>.edn for tools)
  tik bridge email [--config F] < msg           mail in: sender->actor per config;
                                                [tik <id>] comments that ticket and
                                                tik> key=value lines become facts;
                                                else a new ticket
  tik bridge oidc [--registry ID] [--actor A]   identity rung 2: device-flow login ->
                  [--user U --password P]       a signed key-binding attestation on the
                                                registry ticket; verification never
                                                calls the IdP
  tik effects run [--config F] [--dry-run]      alerts out: derived transitions to
                                                slack|discord|matrix|teams|mattermost|
                                                rocketchat|googlechat|ntfy|gotify|
                                                pushover|telegram|opsgenie|alertmanager|
                                                pagerduty|webhook|email|command sinks
                                                (ADR 0019). Per-sink :template puts the
                                                message in your words ({{title}}
                                                {{stage}} {{short}} {{ticket}}); :headers
                                                carries auth — values are literal,
                                                {:env \"NAME\"} or {:command [\"pass\"
                                                \"show\" \"x\"]}; :command pipes the JSON to
                                                ANY program; email renders explain and
                                                its tik> replies close the loop
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
  tik migrate <id> <new.edn> [--reason R]       derived-stage diff under the proposed
                [--apply]                       definition; dry-run unless --apply
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
  tik sim <process.edn>                         live process design (scratch ticket,
                                                auto-reloading definition)
  tik test <tests.edn>                          run scripted process tests (steps in,
                                                expected stages out)")

(defn- dispatch [cmd parsed]
  (case cmd
      "init"    (cmd-init parsed)
      "rollout" (cmd-rollout parsed)
      "probe"   (cmd-probe parsed)
      "pack"    (cmd-pack parsed)
      "gc"      (cmd-gc parsed)
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
      "causal"  (cmd-causal parsed)
      "ls"      (cmd-ls parsed)
      "next"    (cmd-next parsed)
      "search"  (cmd-search parsed)
      "query"   (cmd-query parsed)
      "whatif"  (cmd-whatif parsed)
      "debug"   (cmd-debug parsed)
      "graph"   (cmd-graph parsed)
      "board"   (cmd-board parsed)
      "serve"   (cmd-serve parsed)
      "bridge"  (cmd-bridge parsed)
      "effects" (cmd-effects parsed)
      "verify"  (cmd-verify parsed)
      "root"    (cmd-root parsed)
      "author"  (cmd-author parsed)
      "roles"   (cmd-roles parsed)
      "bundle"  (cmd-bundle parsed)
      "lint"    (cmd-lint parsed)
      "actor"   (cmd-actor parsed)
      "attest"  (cmd-attest parsed)
      "work"    (cmd-work parsed)
      "witness" (cmd-witness parsed)
      "agent"   (cmd-agent parsed)
      "process" (cmd-process parsed)
      "sign"    (cmd-sign parsed)
      "migrate" (cmd-migrate parsed)
      "export"  (cmd-export parsed)
      "sim"     (cmd-sim parsed)
      "test"    (cmd-test parsed)
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
