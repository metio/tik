;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.cli-core
  "The porcelain SUBSTRATE: the shared glue every command leans on —
  store discovery and the EventStore handle, the clock, process exit,
  process-definition loading, signing, ticket resolution and context
  derivation, the disposable derived-state cache, and store-context
  facts. Everything here points ONE way (kernel/store <- core <- the
  command namespaces); no command handler or lens lives in it, so the
  command namespaces (tik.effects, tik.audit, …) can depend on it without
  cycling back through tik.cli."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [tik.args :refer [read-edn-file]]
            [tik.author :as author]
            [tik.canonical :as canonical]
            [tik.store.sqlite :as sqlite]
            [tik.dag :as dag]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.render :refer [sid]]
            [tik.sign :as sign]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.io File)
           (java.time Instant)))

(defn discover-root
  "Git-style upward search from cwd: the nearest ancestor holding a
  hidden `.tik` store wins, else the nearest holding a classic
  `tickets/` directory (file store) or a `tik.db` (SQLite store). The
  walk never climbs past $HOME — a stray store high in the tree must not
  silently capture unrelated work — though it checks $HOME itself;
  outside $HOME it walks to the filesystem root like git does."
  []
  (let [home (str (.getCanonicalFile (io/file (System/getProperty "user.home"))))]
    (loop [d (.getCanonicalFile (io/file "."))]
      (when d
        (cond
          (.isDirectory (io/file d ".tik")) (str (io/file d ".tik"))
          (.isDirectory (io/file d "tickets")) (str d)
          (.isFile (io/file d "tik.db")) (str d)
          (= (str d) home) nil
          :else (recur (.getParentFile d)))))))

(def ^:private discovered-root (delay (discover-root)))

(defn root
  "The store root: TIK_ROOT always wins (scripts, multi-store work);
  else discovery; else the cwd — which is what makes a fresh directory
  become a store the moment `tik new` runs in it."
  []
  (or (System/getenv "TIK_ROOT") @discovered-root "."))

(defn store-established?
  "Is a real store reachable — a marker found on the upward walk, or
  TIK_ROOT pointing at one — versus `root` falling back to the cwd? The
  empty-board guidance leads with `tik init` only when it is NOT, so a
  fresh directory is told how to become a store deliberately."
  []
  (boolean (or (System/getenv "TIK_ROOT") @discovered-root)))

(defn now [] (Instant/now))

(defn process-exit!
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

(defn exit! [code] (*exit-fn* code))

(defn die [& msg]
  (binding [*out* *err*] (apply println msg))
  (exit! 1))



(defn process-file
  "The named definition's file. Built by string concatenation, not
  io/file parenting — a path-shaped name (absolute, slashed) must yield
  a file that merely does not exist, so the caller's 'no process named'
  die fires instead of an IllegalArgumentException."
  ^File [name]
  (io/file (str (root) File/separator "processes" File/separator name ".edn")))

(defn by-hash-file ^File [hash] (io/file (root) "processes" "by-hash" (str hash ".edn")))

(defn git-tracked?
  "Is `dir` inside a git work tree? Governs whether a destructive op can
  honestly promise git recovery."
  [dir]
  (try
    (zero? (:exit (sh/sh "git" "-C" (str dir) "rev-parse" "--is-inside-work-tree")))
    (catch Exception _ false)))

(defn archive-process!
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

(defn available-processes []
  (sort (keep #(second (re-matches #"(.+)\.edn" (.getName ^File %)))
              (filter #(not (str/ends-with? (.getName ^File %) ".tests.edn"))
                      (.listFiles (io/file (root) "processes"))))))

(defn load-process [name]
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

(defn process-name
  "The process name a ticket's state references, as a string. The event
  body is an unconstrained map (:map-of :any :any), so a hash-valid
  create event can carry a non-name :ticket/process — reject it as a
  clean derivation error (ex-info, rendered as a message) rather than
  let `name` cast-crash a single-ticket lens. `ls`/`next` already
  isolate a raising derivation per ticket; this keeps status/explain/
  whatif from surfacing it as an 'unexpected error'."
  [state]
  (let [p (:process state)]
    (if (or (keyword? p) (string? p) (symbol? p))
      (name p)
      (throw (ex-info (str "ticket's process is not a name: " (pr-str p))
                      {:reason :ticket/bad-process :process p})))))

(defn load-pinned-process
  "The definition a ticket derives under: its pinned hash from the
  archive when present, else the named file (with a warning when that
  file no longer matches the pin — an unmigrated ticket after the
  named definition moved on)."
  [state]
  (let [hash (:process-hash state)
        archived (some-> hash by-hash-file)]
    (if (and archived (.exists ^File archived))
      (read-edn-file archived)
      (let [proc (load-process (process-name state))]
        (when (and hash (not= hash (process/process-hash proc)))
          (binding [*out* *err*]
            (println "warning: pinned definition" hash
                     "not in processes/by-hash/ and the named file has"
                     "moved on — deriving under the file's current"
                     "version")))
        proc))))

(defn db-path
  "The SQLite db when THIS store is sqlite-backed — DERIVED from the
  store's own shape (ADR 0020), never an ambient override: a `tik.db`
  beside the store root means SQLite, its absence means the file/git
  store. Each store thus describes its own backend, so working in one
  store never reroutes another. nil for a file store."
  []
  (let [r (root)
        db (io/file r "tik.db")]
    (when (.isFile db)
      (when (.isDirectory (io/file r "tickets"))
        (die (str "ambiguous store at " r ": both tik.db and tickets/ are"
                  " present — a backend migration left both; remove the stale"
                  " one (the file store's tickets/ or the SQLite tik.db)")))
      (str db))))

(defn the-store
  "The store backing THIS root: a SQLite single-file store when the root
  holds a tik.db (ADR 0020), else the file/git store. Blobs and the
  actors registry stay filesystem-side under the root either way."
  []
  (if-let [db (db-path)]
    (sqlite/sqlite-store db)
    (fstore/file-store (root))))

(defn signing-key [opts]
  (let [k (or (:key opts) (System/getenv "TIK_KEY"))]
    (when-not (str/blank? k) k)))

(defn put-signature!
  "Sign `bytes` and store the detached sidecar through the backend seam —
  a file beside the event, or a table row, the store's choice (ADR 0007).
  `kind` is \"sig\" (authorship) or \"witness\" (observation); the sidecar
  name is <target-id>.<kind>.<fpr>. Returns the name."
  [s key ticket-id target-id kind sig-namespace ^bytes bytes]
  (let [fpr (sign/fingerprint (sign/pubkey key))
        name (str target-id "." kind "." fpr)]
    (store/put-sidecar! s ticket-id name
                        (sign/sign-bytes key bytes sig-namespace))
    name))

(defn sidecar-names-for
  "Sidecar names of one `kind` (\"sig\"/\"witness\") for `target-id`."
  [s ticket-id target-id kind]
  (filterv #(str/starts-with? % (str target-id "." kind "."))
           (store/sidecar-names s ticket-id)))

(defn signed-event-ids
  "The set of event ids in a ticket that carry ≥1 authorship signature —
  computed once from the sidecar names, so an unsigned check is O(events)."
  [s ticket-id]
  (into #{} (keep #(when-let [i (str/index-of % ".sig.")] (subs % 0 i)))
        (store/sidecar-names s ticket-id)))

(defn append!*
  "Append, then sign the stored bytes when a key is configured (--key or
  TIK_KEY): authorship travels with the write (ADR 0010)."
  [s event opts]
  (store/append! s event)
  (when-let [key (signing-key opts)]
    (put-signature! s key (:event/ticket event) (:event/id event)
                    "sig" sign/namespace-event
                    (.getBytes (canonical/emit (dissoc event :event/id))
                               "UTF-8")))
  event)

(defn matching-ticket-ids
  "Stringified ticket ids whose text starts with `prefix`."
  [s prefix]
  (filter #(str/starts-with? % (str prefix)) (map str (store/ticket-ids s))))

(defn resolve-id [s ticket-str]
  (let [hits (matching-ticket-ids s ticket-str)]
    (case (count hits)
      1 (java.util.UUID/fromString (first hits))
      0 (die (str "no ticket starting with '" ticket-str
                  "' — `tik ls` lists open tickets, `tik ls --all` everything"))
      (die (str "'" ticket-str "' matches " (count hits)
                " tickets — add more characters:\n  "
                (str/join "\n  " (sort hits)))))))

(defn ticket-ctx [s id]
  (let [evs (store/events s id)
        state (red/ticket-state evs)
        proc (load-pinned-process state)]
    {:events evs :state state :process proc
     :roles (:process/roles proc {})
     :heads (dag/heads evs)}))

(defn load-ticket
  "Resolve a user-supplied ticket reference and derive its context in
  one step — the opener nearly every single-ticket command shares.
  Returns ticket-ctx plus :id."
  [s ref]
  (let [id (resolve-id s ref)]
    (assoc (ticket-ctx s id) :id id)))

(defn all-ticket-ctx
  "Every ticket's context (ticket-ctx plus :id), lazily — the opener of
  every whole-store lens (roles, plan, board, report, work, selectors).
  ISOLATES a ticket whose log cannot derive: it is skipped and named on
  stderr, never taking a whole-store view down. This is the one-poisoned-
  ticket-never-hides-the-healthy invariant, shared by every consumer
  instead of re-implemented (or forgotten) per command. Cache-gated
  loops (cmd-next) that avoid the full fold on purpose do NOT use this."
  [s]
  (keep (fn [id]
          (try (assoc (ticket-ctx s id) :id id :store s)
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*]
                   (println (str "skipping " (sid id) ": " (ex-message e))))
                 nil)))
        (store/ticket-ids s)))

(def cache-state (atom nil))   ; {:entries {} :dirty? bool}

(defn cache-file ^File [] (io/file (root) ".derived-cache.json"))

;; JSON, not EDN, purely for parse speed: reading a 10k-entry EDN
;; cache cost 0.8s — more than the folds it saved. Keywords ride as
;; \":kw\" strings and round-trip on load; the cache stays disposable,
;; so a decode surprise is a miss, never an error.
(defn cache-encode [x]
  (cond
    (keyword? x) (str x)
    (map? x) (into {} (map (fn [[k v]] [(cache-encode k)
                                        (cache-encode v)]) x))
    (sequential? x) (mapv cache-encode x)
    (set? x) (mapv cache-encode x)
    :else x))

(defn cache-decode [x]
  (cond
    (and (string? x) (str/starts-with? x ":")) (keyword (subs x 1))
    (map? x) (into {} (map (fn [[k v]] [(cache-decode k)
                                        (cache-decode v)]) x))
    (sequential? x) (mapv cache-decode x)
    :else x))

(defn cache-entries []
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

(defn cache-flush! []
  (when (:dirty? @cache-state)
    (let [emit (requiring-resolve 'cheshire.core/generate-string)]
      (spit (cache-file) (emit (into {}
                                     (map (fn [[k v]] [k (cache-encode v)]))
                                     (:entries @cache-state)))))
    (swap! cache-state assoc :dirty? false)))

(defn event-ids-fingerprint
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

(defn guard-ops [proc]
  (let [ops (volatile! #{})]
    (doseq [{:keys [guards]} (:process/stages proc)]
      (walk/postwalk
       #(do (when (and (vector? %) (keyword? (first %)))
              (vswap! ops conj (first %)))
            %)
       guards))
    @ops))

(defn store-holder
  "The directory the store sits IN: the parent of a hidden .tik root,
  the root itself otherwise. Context collection walks cwd up to here."
  ^File []
  (let [r (.getCanonicalFile (io/file (root)))]
    (if (= ".tik" (.getName r)) (.getParentFile r) r)))

(defn context-facts
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

(defn resolve-id-soft
  "resolve-id without the die: the uuid on a unique match, nil
  otherwise. Lenses rendering links must degrade, not crash."
  [s prefix]
  (let [hits (matching-ticket-ids s prefix)]
    (when (= 1 (count hits))
      (java.util.UUID/fromString (first hits)))))

(defn load-process-arg
  "A process from a `.edn` path argument, else by name from this store's
  processes/ — the shape the debug and graph lenses accept."
  [proc-name]
  (if (str/ends-with? (str proc-name) ".edn")
    (or (read-edn-file (io/file proc-name))
        (die (str "no such file: " proc-name)))
    (load-process proc-name)))

(defn store-root-doc
  "The canonical root document: sorted ticket -> sorted heads. Derived,
  deterministic, tiny — and it commits to EVERY event in the store,
  because each head commits to its entire ancestry (ADR 0004). Never
  stored as authority: regenerated on demand, byte-identical each time."
  [s]
  (into (sorted-map)
        (for [id (sort-by str (store/ticket-ids s))]
          [id (into (sorted-set) (dag/heads (store/events s id)))])))

(defn root-dir-roots ^File [] (io/file (root) "roots"))

