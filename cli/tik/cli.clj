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
            [clojure.string :as str]
            [clojure.walk :as walk]
            [tik.author :as author]
            [tik.jwks :as jwks]
            [tik.oid4vci :as oid4vci]
            [tik.oidc :as oidc]
            [tik.canonical :as canonical]
            [tik.causal :as causal]
            [tik.dag :as dag]
            [tik.dupe :as dupe]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.guard :as guard]
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

(declare parse-instant)

(defn- eval-instant
  "--at <inst>: evaluate at any moment — status on March 1 is just
  f(events, March 1); time travel was free the whole time (ADR 0012)."
  [opts]
  (if-let [at (:at opts)]
    (parse-instant at)
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

;; java.time.Instant has no print-method, so pr-str renders it as
;; #object[…<identity-hash>…] — gibberish for humans and UNREADABLE as
;; EDN, which corrupts every --edn output carrying a time-typed fact.
;; Instants are the one canonical time type; print them as the #inst
;; literal the canonical readers parse right back. Porcelain-side on
;; purpose: the kernel never prints (it emits via canonical/emit).
(defmethod print-method java.time.Instant [^java.time.Instant i ^java.io.Writer w]
  (.write w (str "#inst \"" i "\"")))

(defn- sid
  "A ticket id's display form: the first 8 hex chars. Total over any
  input — real ids are long, but hostile FILENAMES (roots/, event and
  by-hash files) reach the display path too, so a short string returns
  whole rather than an out-of-bounds crash."
  [x]
  (let [s (str x)] (subs s 0 (min 8 (count s)))))

(defn- shash
  "A content hash's display form: `sha256-` plus the first 12 hex chars.
  Total over any input (see sid): a hostile-short hash-shaped filename
  displays whole, never crashes."
  [x]
  (let [s (str x)] (subs s 0 (min 19 (count s)))))

(defn- print-problems
  "Print lint problems as `[level] msg` lines; true when any is an
  error — the callers' gate condition."
  [problems]
  (doseq [{:keys [level msg]} problems]
    (println (str "[" (safe-name level) "] " msg)))
  (boolean (some #(= :error (:level %)) problems)))

;; ---------------------------------------------------------------- output
;; Every lens produces DATA; the format is one axis over it, not a branch
;; each command re-implements. --format human|edn|json (with --edn as the
;; short alias for edn). human is the default and is unchanged.

(defn- json-safe
  "Coerce a lens's EDN value into a shape cheshire can encode: Instants
  to ISO strings (the one time type has no JSON form), sets to arrays,
  and any non-name map key to its printed string (JSON keys are strings)."
  [x]
  (cond
    (instance? java.time.Instant x) (str x)
    (map? x) (into {} (map (fn [[k v]]
                             [(if (or (keyword? k) (string? k)) k (pr-str k))
                              (json-safe v)]))
                   x)
    (set? x) (mapv json-safe x)
    (sequential? x) (mapv json-safe x)
    :else x))

(defn- json-encode [x]
  ((requiring-resolve 'cheshire.core/generate-string) (json-safe x)))

(defn- output-format [opts]
  (cond
    (:edn opts) :edn
    (nil? (:format opts)) :human
    (contains? #{"edn" "json" "human"} (:format opts)) (keyword (:format opts))
    :else (die (str "unknown --format " (:format opts) " (edn | json | human)"))))

(defn- emit-data
  "Emit a lens's `data` in the requested machine format (edn | json) and
  return true; return false for :human so the caller renders for people.
  One dispatch replaces every command's `(if (:edn opts) (prn …) …)`:
  `(when-not (emit-data opts data) <human>)`."
  [opts data]
  (case (output-format opts)
    :edn (do (prn data) true)
    :json (do (println (json-encode data)) true)
    false))

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
                    (let [form (edn/read {:eof ::eof
                                           :readers canonical/edn-readers} r)
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
    (try (canonical/parse (slurp f))
         (catch Exception e
           (throw (ex-info (str "malformed EDN in " f " — "
                                (ex-message e))
                           {:reason :config/malformed :file (str f)}
                           e))))))

(defn- slurp-existing
  "Read a user-supplied file, dying cleanly if it is absent — never a raw
  FileNotFoundException surfaced as 'a bug in tik'."
  [what path]
  (if (.exists (io/file path))
    (slurp path)
    (die (str "no such " what " file: " path))))

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

(defn- process-file
  "The named definition's file. Built by string concatenation, not
  io/file parenting — a path-shaped name (absolute, slashed) must yield
  a file that merely does not exist, so the caller's 'no process named'
  die fires instead of an IllegalArgumentException."
  ^File [name]
  (io/file (str (root) File/separator "processes" File/separator name ".edn")))

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

(defn- process-name
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
      (let [proc (load-process (process-name state))]
        (when (and hash (not= hash (process/process-hash proc)))
          (binding [*out* *err*]
            (println "warning: pinned definition" hash
                     "not in processes/by-hash/ and the named file has"
                     "moved on — deriving under the file's current"
                     "version")))
        proc))))

(defn- db-path
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

(defn- the-store
  "The store backing THIS root: a SQLite single-file store when the root
  holds a tik.db (ADR 0020), else the file/git store. Blobs and the
  actors registry stay filesystem-side under the root either way."
  []
  (if-let [db (db-path)]
    (sqlite/sqlite-store db)
    (fstore/file-store (root))))

(defn- signing-key [opts]
  (let [k (or (:key opts) (System/getenv "TIK_KEY"))]
    (when-not (str/blank? k) k)))

(defn- put-signature!
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

(defn- sidecar-names-for
  "Sidecar names of one `kind` (\"sig\"/\"witness\") for `target-id`."
  [s ticket-id target-id kind]
  (filterv #(str/starts-with? % (str target-id "." kind "."))
           (store/sidecar-names s ticket-id)))

(defn- signed-event-ids
  "The set of event ids in a ticket that carry ≥1 authorship signature —
  computed once from the sidecar names, so an unsigned check is O(events)."
  [s ticket-id]
  (into #{} (keep #(when-let [i (str/index-of % ".sig.")] (subs % 0 i)))
        (store/sidecar-names s ticket-id)))

(defn- append!*
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

(defn- matching-ticket-ids
  "Stringified ticket ids whose text starts with `prefix`."
  [s prefix]
  (filter #(str/starts-with? % (str prefix)) (map str (store/ticket-ids s))))

(defn- resolve-id [s ticket-str]
  (let [hits (matching-ticket-ids s ticket-str)]
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

(defn- load-ticket
  "Resolve a user-supplied ticket reference and derive its context in
  one step — the opener nearly every single-ticket command shares.
  Returns ticket-ctx plus :id."
  [s ref]
  (let [id (resolve-id s ref)]
    (assoc (ticket-ctx s id) :id id)))

(defn- all-ticket-ctx
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

(declare display-title link-facts)

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
                 (explain/explain process events t roles reached))
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
           :settled? (next-lens/settled-reached? process reached)
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

(declare link-row link-lines resolve-file)

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
        all (vec (all-ticket-ctx s))
        child-of (into {}
                       (for [{:keys [id state]} all
                             :when (= (keyword proc-name) (:process state))
                             :let [r (red/fact-value state [:repo])]
                             :when r]
                         [(safe-name r) id]))
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
              (println (str "parent " (sid (:event/ticket e))
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
    (println (str "watch it: tik status " (sid parent-id)))))

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
        ;; a named ticket derives strictly (die if it cannot); the whole
        ;; store isolates per ticket, so one poison never aborts the sweep
        ctxs (if (seq pos)
               [(load-ticket s (first pos))]
               (all-ticket-ctx s))
        changed (atom 0)]
    (doseq [{:keys [id events state process roles]} ctxs
            :let [repo (red/fact-value state [:repo])]
            :when (and repo
                       (or (seq pos)
                           (not (next-lens/settled? process events t roles))))
            :let [repo-name (safe-name repo)
                  dir (io/file holder repo-name)
                  probe (or (:command opts)
                            (let [f (process-file (process-name state))]
                              (when (.exists f)
                                (:probe (read-edn-file f)))))]]
      (cond
        (nil? probe) nil
        (not (.isDirectory dir))
        (println (str (sid id) " " repo-name
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
            (println (str (sid id) " " repo-name ": probe failed — "
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
                (println (str (sid id) " " repo-name ": " k " = "
                              (pr-str value))))
              (let [evs (store/events s id)
                    after (stage/effective-reached process evs (now) roles)
                    gained (sort-by str (remove before after))]
                (when (seq gained)
                  (println (str (sid id) " " repo-name " -> "
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
              (for [{:keys [id events process roles]} (all-ticket-ctx s)
                    :when (next-lens/settled? process events t roles)]
                id))
        packed (atom 0)]
    (doseq [id ids
            :let [r (fstore/pack! (root) id)]
            :when r]
      (swap! packed inc)
      (println (str (sid id) " packed " (:packed r)
                    " event(s) -> " (shash (:pack r)) "…")))
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
  "init [--sqlite] [--hidden]: mark this directory as a store. The
  backend is the store's own shape (ADR 0020): the default is the
  file/git store (a `tickets/` tree, sha256sum-auditable); --sqlite is
  the single-file operational store (a `tik.db`). --hidden puts
  everything inside .tik/ (one dot-entry beside your repos — the
  portfolio-store shape); without it the store is the classic visible
  layout. Either marker makes every tik command work from ANY
  directory beneath this one. Switch backends later with `tik store
  migrate`."
  [{:keys [opts]}]
  (let [here (.getCanonicalFile (io/file "."))
        store (if (:hidden opts) (io/file here ".tik") here)
        tickets (io/file store "tickets")
        db (io/file store "tik.db")]
    (when (or (.isDirectory tickets) (.isFile db))
      (die (str "already a store: " store)))
    (.mkdirs store)
    (if (:sqlite opts)
      (do (sqlite/sqlite-store (str db))       ; init! writes the schema file
          (println (str "SQLite store initialized at " db)))
      (do (.mkdirs tickets)
          (println (str "store initialized at " store))))
    (println (str "every tik command run at or below " here
                  " now uses it — try:"))
    (println "  tik new track --title \"the first thing\"")
    (println "  tik author --template bug")))

(defn- delete-tree!
  "Remove a file or directory tree, postorder (contents before the dir)."
  [^File f]
  (doseq [^File c (reverse (file-seq f))] (.delete c)))

(defn- cmd-store
  "store migrate --to sqlite|file: convert THIS store's event backend in
  place (ADR 0020). Reads every event AND every detached sidecar
  (signatures/witnesses) from the current backend, writes them to the
  other (append is idempotent by id), verifies the full event-id and
  sidecar-name sets carried over, then removes the source. Lossless —
  authorship and countersignatures travel with the events (both backends
  hold sidecars, ADR 0007). Blobs, the actors registry, and processes are
  untouched — they live filesystem-side regardless of backend."
  [{:keys [pos opts]}]
  (when-not (= "migrate" (first pos))
    (die "usage: tik store migrate --to sqlite|file"))
  (let [to (some-> (:to opts) str keyword)
        _ (when-not (#{:sqlite :file} to)
            (die "usage: tik store migrate --to sqlite|file"))
        r (root)
        db (io/file r "tik.db")
        tickets (io/file r "tickets")
        from (if (.isFile db) :sqlite :file)]
    (when (= from to)
      (die (str "this store is already " (name to) "-backed")))
    (let [src (the-store)
          ids (vec (store/ticket-ids src))
          events (vec (mapcat #(store/events src %) ids))
          src-ids (set (map :event/id events))
          sidecars (vec (for [id ids, name (store/sidecar-names src id)]
                          [id name]))
          dst (case to
                :sqlite (sqlite/sqlite-store (str db))
                :file (fstore/file-store r))]
      ;; move bytes only — no re-signing (migration preserves authorship,
      ;; it does not re-author); events then their endorsements
      (doseq [e events] (store/append! dst e))
      (doseq [[id name] sidecars]
        (store/put-sidecar! dst id name (store/read-sidecar src id name)))
      (let [dst-ids (set (mapcat #(map :event/id (store/events dst %))
                                 (store/ticket-ids dst)))
            dst-sidecars (reduce + (map #(count (store/sidecar-names dst %)) ids))]
        (when-not (and (= src-ids dst-ids) (= (count sidecars) dst-sidecars))
          (die (str "migration incomplete: " (count src-ids) " event(s)/"
                    (count sidecars) " sidecar(s) read, " (count dst-ids) "/"
                    dst-sidecars " written — source left intact for retry"))))
      (case from
        :sqlite (doseq [suffix ["" "-journal" "-wal" "-shm"]]
                  (.delete (io/file (str db suffix))))
        :file (delete-tree! tickets))
      (println (str "migrated " (count ids) " ticket(s), " (count src-ids)
                    " event(s), " (count sidecars) " sidecar(s) to the "
                    (name to) " backend"))
      (println "run `tik verify` to confirm the derivation is unchanged"))))

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
        _ (when (print-problems (process/lint definition))
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

(defn- stage-delta
  "The stages gained and lost moving from reached-set `before` to `after`,
  each sorted for stable display — the shared arithmetic behind diff,
  whatif, and reprocess (each words and colors its own rendering)."
  [before after]
  {:gained (sort-by str (remove before after))
   :lost (sort-by str (remove after before))})

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

(defn- resolve-id-soft
  "resolve-id without the die: the uuid on a unique match, nil
  otherwise. Lenses rendering links must degrade, not crash."
  [s prefix]
  (let [hits (matching-ticket-ids s prefix)]
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
       :rest (str (sid target-id) " " title
                  ;; nested-repo rels dot their slashes; either spelling
                  ;; in the title makes the suffix redundant
                  (when-not (or (str/includes? title (safe-name rel))
                                (str/includes? title (str/replace (safe-name rel)
                                                                  "." "/")))
                    (str "  [" (safe-name rel) "]")))})
    {:sort [1 0 "" (str target)]
     :stage "(unresolved)"
     :stage-kws #{}
     :rest (str target "  [" (safe-name rel) "]")}))

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

(defn- bad-time!
  "Raise a clean, example-carrying rejection for an unparsable time
  argument — never leak a raw DateTimeParseException as 'a bug in tik'."
  [kind w]
  (throw (ex-info (str "not an ISO-8601 "
                       (if (= :duration kind)
                         "duration (e.g. +PT48H = 48 hours, +PT30M = 30 min)"
                         "instant (e.g. 2026-01-01T00:00:00Z)")
                       ": " w)
                  {:reason :time/unparsable :arg w})))

(defn- parse-instant
  "An absolute ISO-8601 instant from a CLI option (--at/--from/--to),
  failing well on a typo instead of a raw DateTimeParseException."
  [w]
  (try (Instant/parse w)
       (catch java.time.DateTimeException _ (bad-time! :instant w))))

(defn- parse-when
  "A :now step's argument: `+<ISO-8601 duration>` advances from `now`
  (e.g. +PT48H), a bare string is an absolute ISO-8601 instant. A typo —
  a wall-clock `+2d`, a plain date, an out-of-range duration — must be a
  clean message, never a raw DateTimeParseException surfaced as 'a bug in
  tik'. Overflow of `.plus` throws Arithmetic/DateTimeException too."
  [^Instant now w]
  (if (str/starts-with? w "+")
    (try (.plus now (Duration/parse (subs w 1)))
         (catch java.time.DateTimeException _ (bad-time! :duration w))
         (catch ArithmeticException _ (bad-time! :duration w)))
    (parse-instant w)))

(defn- apply-step
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
    (print (explain/render
            (explain/explain proc events sim-now roles reached)))))

(def ^:private sim-help
  "  set k=v [k=v ...]   assert facts        retract <k>      withdraw a fact
  dispute <k> <why>   dispute a fact      attach <name>    fake artifact
  now +PT48H | <inst> move evaluation time                 actor <name>
  reset               fresh scratch ticket                 quit
  (empty line re-renders; the process file reloads automatically on change)")

(defn- sim-load [^File f]
  (let [p (read-edn-file f)]
    (when-not (print-problems (process/lint p)) p)))

(defn- resolve-file
  "A file argument as given, else relative to the store root — so
  `tik test processes/bug.tests.edn` works both inside the store and
  wherever TIK_ROOT points from."
  ^File [path]
  (let [as-given (io/file path)]
    (if (or (.exists as-given) (.isAbsolute as-given))
      as-given
      (io/file (root) path))))

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
    (when (print-problems (process/lint proc))
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
        _ (when (print-problems (process/lint new-proc))
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

(defn- cmd-export
  "Materialize the current store (whatever backend) as a file/git store
  at <dir> — the auditor-grade interchange format where sha256sum(file)
  = filename. Events only: blobs and the actors registry are filesystem
  artifacts under TIK_ROOT and copy with cp."
  [{:keys [pos]}]
  (let [target (or (first pos) (die "usage: tik export <dir>"))
        dir (io/file target)
        _ (when (and (not (.isDirectory dir)) (not (.mkdirs dir)))
            (die (str "cannot create export directory: " target)))
        src (the-store)
        dest (fstore/file-store target)
        n (try (reduce (fn [n id]
                         (reduce (fn [n e] (store/append! dest e) (inc n))
                                 n (store/events src id)))
                       0 (store/ticket-ids src))
               (catch java.io.IOException e
                 (die (str "export to " target " failed: " (ex-message e)))))]
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
    (filterv #(or (= :anyone (:who %))
                  (contains? (:who %) actor-name))
             actions)))

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

(defn- cmd-witness
  "witness <id> [--key K]: countersign every current head — a detached
  <head>.witness.<fpr> sidecar over the head event's stored bytes. One
  signature timestamps the entire ancestry the head commits to
  (ADR 0004); observation, not authorship, hence its own namespace."
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
        fpr (sign/fingerprint (sign/pubkey key))
        heads (dag/heads (store/events s id))]
    (doseq [head heads
            :let [name (str head ".witness." fpr)]]
      (when-not (some #{name} (store/sidecar-names s id))
        (put-signature! s key id head "witness" sign/namespace-witness
                        (store/event-bytes s id head)))
      (println "witnessed" (shash head) "…"))
    (println (count heads) "head(s) countersigned — each timestamps its"
             "entire ancestry")))

(defn- cmd-actor
  "actor add <name> <pubkey-file>: bind an actor to a key in the store's
  allowed-signers registry (identity ladder rung 1, PLAN §9)."
  [{:keys [pos]}]
  (let [[sub actor-name pubkey-file] pos]
    (when-not (and (= "add" sub) actor-name pubkey-file)
      (die "usage: tik actor add <name> <pubkey-file>"))
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

(defn- load-process-arg
  "A process from a `.edn` path argument, else by name from this store's
  processes/ — the shape the debug and graph lenses accept."
  [proc-name]
  (if (str/ends-with? (str proc-name) ".edn")
    (or (read-edn-file (io/file proc-name))
        (die (str "no such file: " proc-name)))
    (load-process proc-name)))

(defn- cmd-debug
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

(defn- cmd-whatif
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

(defn- parse-rfc822
  "Minimal RFC822: headers to the first blank line, body after.
  Header folding (continuation lines) honored. From/Subject drive the
  human path; In-Reply-To/References/X-Tik-Ticket drive robust
  ticket association (see ticket-ref-of)."
  [text]
  (let [lines (str/split-lines text)
        [head body] (split-with #(not (str/blank? %)) lines)
        headers (loop [hs [] [l & more] head]
                  (cond
                    (nil? l) hs
                    (and (seq hs) (re-matches #"^[ \t].*" l))
                    (recur (conj (pop hs) (str (peek hs) " " (str/trim l))) more)
                    :else (recur (conj hs l) more)))
        header-vals (fn [k]
                      (keep #(when-let [[_ v] (re-matches
                                               (re-pattern (str "(?i)^" k ":\\s*(.*)$")) %)]
                               (str/trim v))
                            headers))
        header (fn [k] (first (header-vals k)))]
    {:from (some->> (header "From")
                    (re-find #"[\w.+-]+@[\w.-]+")
                    str/lower-case)
     :subject (or (header "Subject") "")
     :in-reply-to (header "In-Reply-To")
     :references (header "References")
     :x-tik-ticket (header "X-Tik-Ticket")
     ;; every Authentication-Results header (there can be several) — the
     ;; DKIM verdict tik's own MTA stamped; the actor gate reads these
     :auth-results (vec (header-vals "Authentication-Results"))
     :body (str/trim (str/join "\n" (rest body)))}))

(defn- ticket-ref-of
  "Which ticket an inbound message is about, most reliable source first:
  the explicit `X-Tik-Ticket` header, then a tik-shaped Message-ID the
  reply threads on (`In-Reply-To`/`References` — set automatically by the
  sender's mail client from what the outbound sink stamped), then the
  `[tik <id>]` subject tag as the human-visible fallback. The id is
  ENCODED in the Message-ID, never stored in a lookup table — the
  association is derived, like everything else."
  [{:keys [x-tik-ticket in-reply-to references subject]}]
  (or (some-> x-tik-ticket str/trim not-empty)
      (some->> (str in-reply-to " " references)
               (re-find #"tik\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
               second)
      (second (re-find #"\[tik ([0-9a-f-]+)\]" (str subject)))))

(defn- dkim-passing-domains
  "The domains that pass DKIM per the message's `Authentication-Results`
  headers, considering ONLY headers stamped by a trusted verifier
  (authserv-id ∈ `trusted`). tik does not re-implement DKIM's fragile
  canonicalization — it consumes the standard verdict of the MTA it runs
  behind (RFC 8601). SECURITY: that MTA MUST strip inbound A-R headers
  bearing its own authserv-id (RFC 8601 §5), or an attacker forges the
  verdict; pinning `trusted` to your MTA's id is what makes this sound."
  [{:keys [auth-results]} trusted]
  (set
   (for [ar auth-results
         :let [fields (str/split (str ar) #";")
               ;; an all-`;` or empty header splits to no fields — a
               ;; hostile A-R must not NPE `(str/trim nil)` on the gate path
               authserv (some-> (first fields) str/trim str/lower-case)]
         :when (contains? trusted authserv)
         method (rest fields)
         :when (re-find #"(?i)\bdkim\s*=\s*pass\b" method)
         :let [d (second (re-find #"(?i)header\.d\s*=\s*([\w.-]+)" method))]
         :when d]
     (str/lower-case d))))

(defn- dkim-aligned?
  "Is `from-domain` authenticated by one of the `passing` DKIM domains —
  exact, or a subdomain (relaxed DMARC-style alignment)? Total over a nil
  from-domain (a From with no @domain). NOTE: relaxed alignment trusts
  `header.d` as a registered domain — it does not consult the public
  suffix list, which is sound here because `header.d` comes from a DKIM
  signature the MTA actually verified (nobody can sign as a bare TLD)."
  [from-domain passing]
  (boolean (when from-domain
             (some #(or (= from-domain %) (str/ends-with? from-domain (str "." %)))
                   passing))))

(defn- require-dkim!
  "When `bridge.edn` carries `:dkim {:require true :authserv-id …}`, gate
  the sender BEFORE its From→actor mapping is trusted: the From domain
  must be DKIM-authenticated by a trusted verifier, else refuse — the
  sender→actor binding becomes cryptographic, not header-trusting."
  [{:keys [dkim]} {:keys [from] :as msg}]
  (when (:require dkim)
    (let [trusted (set (map (comp str/lower-case str)
                            (let [a (:authserv-id dkim)] (if (coll? a) a [a]))))
          from-domain (some-> from (str/split #"@") second str/lower-case)
          passing (dkim-passing-domains msg trusted)]
      (when (empty? trusted)
        (die ":dkim {:require true} needs :authserv-id (your MTA's verifier id)"))
      (when-not (and from-domain (dkim-aligned? from-domain passing))
        (die (str "refusing " (or from "an unsigned sender") ": no dkim=pass"
                  " for " (or from-domain "its domain") " from a trusted"
                  " verifier " trusted
                  (when (seq passing) (str " (passing: " passing ")"))))))))

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
        public-key (str/trim (slurp-existing "key" key-file))
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
                  ") to actor '" who "' — attestation " (shash (:event/id e))
                  "… on " registry-id))
    (exit! 0)))

(defn- cmd-bridge-oid4vci
  "bridge oid4vci: ingest a Verifiable Credential (an OID4VCI issuance
  output — JWT-VC or SD-JWT-VC) as a bridge-signed ATTESTATION on the
  registry ticket. A VC is a signed attestation with an external issuer
  (docs/IDEAS.md): the issuer signature is verified at INGEST against the
  issuer's JWKS (fetched over TLS from `<issuer>/.well-known/jwks.json`,
  or `--jwks-url`, or a local `--jwks <file>`), then the bridge signs its
  OWN attestation carrying the credential (raw included for re-audit).
  Verification thereafter never calls the issuer — offline-forever, the
  same trust model as the OIDC/email bridges (ADR 0019). No kernel
  change: the credential becomes one more signed attestation."
  [opts]
  (let [cfg-file (or (:config opts) (str (io/file (root) "oid4vci.edn")))
        cfg (if (.exists (io/file cfg-file)) (read-edn-file (io/file cfg-file)) {})
        registry-ref (or (:registry opts) (:registry cfg)
                         (die (str "no registry ticket — mint one with\n"
                                   "  tik new identity-registry --title 'identity registry'\n"
                                   "then pass --registry <its id>")))
        cred-str (str/trim (if-let [f (:credential opts)]
                             (slurp-existing "credential" f)
                             (slurp *in*)))
        who (actor opts)
        cred (oid4vci/parse-credential cred-str)
        issuer (or (:issuer opts) (:issuer cred)
                   (die "credential carries no issuer (iss); pass --issuer"))
        jwks-json (cond
                    (:jwks opts) (slurp-existing "jwks" (:jwks opts))
                    (:jwks-url opts) (oidc/http-get (:jwks-url opts))
                    :else (oidc/http-get (str (str/replace issuer #"/$" "")
                                              "/.well-known/jwks.json")))
        verifier (jwks/verifier (jwks/parse-jwks jwks-json))
        verified (try (oid4vci/verify cred-str verifier)
                      (catch clojure.lang.ExceptionInfo e (die (ex-message e))))
        s (the-store)
        registry-id (resolve-id s registry-ref)
        claim (oid4vci/credential-claim verified who)
        heads (dag/heads (store/events s registry-id))
        e (event/add-attestation {:ticket registry-id :actor who :at (now)
                                  :parents heads :claim claim})]
    (append!* s e opts)
    (println (str "ingested credential from " (:credential/issuer claim)
                  " for subject " (:credential/subject claim)
                  " (" (:credential/type claim) ") as actor '" who
                  "' — attestation " (shash (:event/id e)) "… on " registry-id))
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
  (when (= "oid4vci" (first pos)) (cmd-bridge-oid4vci opts))
  (when-not (= "email" (first pos))
    (die (str "usage: tik bridge email [--config bridge.edn] < message\n"
              "       tik bridge oidc [--config oidc.edn] [--registry ID] [--actor A]\n"
              "       tik bridge oid4vci --credential vc.jwt --registry ID"
              " [--jwks-url URL | --jwks FILE]")))
  (let [cfg-file (or (:config opts) (str (io/file (root) "bridge.edn")))
        cfg (if (.exists (io/file cfg-file))
              (read-edn-file (io/file cfg-file))
              {})
        {:keys [from subject body] :as msg} (parse-rfc822 (slurp *in*))
        ;; if configured, the From must be DKIM-authenticated before we
        ;; trust it enough to attribute events to an actor
        _ (require-dkim! cfg msg)
        actor-name (or (get-in cfg [:from->actor from])
                       (:default-actor cfg)
                       (die (str "unknown sender " from
                                 " and no :default-actor in " cfg-file)))
        opts (assoc opts :actor actor-name)
        s (the-store)
        ticket-ref (ticket-ref-of msg)]
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
          (println (str "comment -> " (sid id) " as " actor-name
                        (when (seq facts)
                          (str " (+ " (count facts) " fact(s))"))))))
      (let [proc-name (safe-name (or (:process cfg) (die (str "no :process in " cfg-file))))
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
      (str/replace "{{short}}" (sid ticket))
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
  "RFC822 text asking a human for what the stage needs. Association is
  carried three ways, robust first: a tik-shaped Message-ID (the
  sender's client threads on it via In-Reply-To/References, so a plain
  reply routes back with no cooperation), an explicit X-Tik-Ticket
  header (for filters/clients), and the [tik <id>] subject tag (the
  human-visible fallback). The body renders explain and teaches the
  reply convention — the email IS a capability-scoped view of the same
  derivation."
  [{:keys [to from]} {:keys [ticket title stage]} explain-text]
  (str "To: " to "\r\n"
       "From: " (or from "tik") "\r\n"
       ;; the ticket id is ENCODED in the Message-ID (per stage, so each
       ;; notification is a distinct message), never a stored map — a
       ;; reply's In-Reply-To carries it straight back to ticket-ref-of
       "Message-ID: <tik." ticket "." (name stage) "@tik.local>\r\n"
       "X-Tik-Ticket: " ticket "\r\n"
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
    (doseq [{:keys [id events state process roles]} (all-ticket-ctx s)
            :let [timeline (:timeline (stage/evolve process events roles))
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
        (println "would send" (safe-name (:type sink)) "<-"
                 (str (sid (:ticket tr)) " " (:stage tr)))
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
              (println "sent" (safe-name (:type sink)) "<-"
                       (str (sid (:ticket tr)) " "
                            (:stage tr)))
          (catch Exception e
            (swap! failed inc)
            (binding [*out* *err*]
              (println (str "failed " (safe-name (:type sink)) " <- "
                            (sid (:ticket tr)) " " (:stage tr)
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
          (println "witnessed" (shash root) "… ->" (.getName target)))))
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
                (println "anchored" (shash root)
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
                       (str (shash attested-root)
                            "… CURRENT root witnessed by " (or who "<unregistered>"))))
              (io/delete-file tmp))
            (println (str "  note  " (shash attested-root)
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
        {:keys [id state]} (load-ticket s ticket)
        phash (:process-hash state)
        out (io/file (or (:out opts) (str "tik-bundle-" (sid id) ".tgz")))
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
        _ (when (binding [*out* *err*]
                  (print-problems (author/check answers (authoring-rules))))
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
  (let [{:keys [events roles]} (load-ticket s tid)
        reached (stage/effective-reached proc events (now) roles)]
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
          problems (concat (process/lint proc) missing-runbooks)]
      (when (print-problems problems) (exit! 1))
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
               (str (shash stem) "… definition bytes hash to filename"))
        (let [sigs (sign/sidecars dir stem)]
          (if (empty? sigs)
            (println (str "  note  " (shash stem)
                          "… unsigned definition (tik process sign)"))
            (doseq [sig sigs
                    :let [who (and (.exists signers)
                                   (first (sign/find-principals
                                           signers f sig
                                           sign/namespace-process)))]]
              (check (boolean
                      (and who (sign/verify signers f sig who
                                            sign/namespace-process)))
                     (str (shash stem) "… published by "
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
                  (try (canonical/parse (slurp cache-file))
                       (catch Exception _ {}))
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
                         (str "  FAIL  " (sid id)
                              " unverifiable: " (ex-message e) "\n")))]
            (if (str/includes? r "FAIL")
              (do (print r) (swap! failures inc))
              (do (swap! verified assoc id heads)
                  (println (tint "2" (sid id))
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
                               (check false (str (shash eid)
                                                 "… row bytes unreadable: "
                                                 (ex-message ex)))
                               nil))
                    e (when raw
                        (try (assoc (canonical/parse raw) :event/id eid)
                             (catch Exception ex
                               (check false (str (shash eid)
                                                 "… row unreadable: "
                                                 (ex-message ex)))
                               nil)))]
              :when e]
        (check (= eid (str "sha256-" (canonical/sha256-hex raw)))
               (str (shash eid) "… hash(stored bytes) = id"))
        (check (= raw (canonical/emit (dissoc e :event/id)))
               (str (shash eid) "… bytes are exactly the hashed region"))
        (check (event/valid? e) (str (shash eid) "… schema-valid")))
      (do
        (when-let [{:keys [entries pack]} (fstore/read-pack-index dir)]
          (let [pack-bytes (java.nio.file.Files/readAllBytes
                            (.toPath (io/file dir "events.pack")))]
            (check (= pack (str "sha256-"
                                (canonical/sha256-hex-bytes pack-bytes)))
                   (str "pack " (shash pack)
                        "… bytes hash to the index's pack address"))
            (doseq [{:keys [id] :as entry} entries
                    :let [slice (fstore/pack-slice dir entry)]]
              (check (= id (str "sha256-"
                                (canonical/sha256-hex-bytes slice)))
                     (str (shash id) "… packed slice hashes to id")))))
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
                    :let [names (sidecar-names-for s id (:event/id e) "sig")]]
              (if (empty? names)
                (swap! unsigned inc)
                (let [ev-bytes (store/event-bytes s id (:event/id e))]
                  (doseq [name names
                          :let [sig (store/read-sidecar s id name)]]
                    (check (boolean
                            (and ev-bytes sig
                                 (sign/verify-bytes signers ev-bytes sig
                                                    (:event/actor e))))
                           (str (shash (:event/id e)) "… signed by "
                                (:event/actor e)))))))
            (when (pos? @unsigned)
              (println (str "  note  " @unsigned " event(s) unsigned"
                            " (authenticity unclaimed, not failed)"))))))
      (println "L3 provenance (witness countersignatures)")
      (let [signers (io/file (root) "actors")
            heads (dag/heads evs)
            witnessed (for [head heads
                            name (sidecar-names-for s id head "witness")]
                        [head name])]
        (if (empty? witnessed)
          (println "  note  no countersigned heads (tik witness <id>)")
          (doseq [[head name] witnessed
                  :let [ev-bytes (store/event-bytes s id head)
                        sig (store/read-sidecar s id name)
                        who (and (.exists signers) ev-bytes sig
                                 (first (sign/find-principals-bytes
                                         signers ev-bytes sig
                                         sign/namespace-witness)))]]
            (check (boolean (and who (sign/verify-bytes signers ev-bytes sig who
                                                        sign/namespace-witness)))
                   (str (shash head) "… witnessed by "
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
                  [--user U --password P]       a signed key-binding attestation on the
                                                registry ticket; verification never
                                                calls the IdP
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
      "init"    (cmd-init parsed)
      "store"   (cmd-store parsed)
      "rollout" (cmd-rollout parsed)
      "probe"   (cmd-probe parsed)
      "pack"    (cmd-pack parsed)
      "gc"      (cmd-gc parsed)
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
      "whatif"  (cmd-whatif parsed)
      "debug"   (cmd-debug parsed)
      "board"   (cmd-board parsed)
      "serve"   (cmd-serve parsed)
      ;; the MCP stdio loop lives in tik.mcp (which requires this ns);
      ;; resolve it lazily to avoid the require cycle — tik.main force-
      ;; requires tik.mcp so the native image can resolve it here too.
      "mcp"     ((requiring-resolve 'tik.mcp/-main))
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
      "reprocess" (cmd-reprocess parsed)
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
