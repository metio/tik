;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.storeops
  "Whole-store operations, beyond any single ticket: rollout (one ticket
  per git repo under the store, wired as a parent checklist), probe
  (auto-derive facts by running each repo's probe), pack (consolidate
  settled tickets), gc (drop unpinned archived definitions), init (mark a
  directory a store), and store migrate (convert the backend in place).
  Porcelain over tik.cli-core."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.args :refer [actor parse-key read-edn-file typed-value]]
            [tik.cli-core :refer [all-ticket-ctx append!* archive-process! cache-flush!
                                  db-path die display-title git-tracked? link-lines
                                  load-process load-ticket now parse-instant process-file
                                  process-name resolve-file resolve-id root store-holder
                                  the-store ticket-ctx]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.next :as next-lens]
            [tik.reduce :as red]
            [tik.render :refer [shash sid tint]]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite :as sqlite]
            [tik.text :refer [safe-name]])
  (:import (java.io File)
           (java.time LocalDate ZoneOffset)))

(defn cmd-rollout
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
        ;; the link rel is the repo's OWN identity (repo-value: a keyword
        ;; for a flat repo, the "group/repo" string for a nested one) — an
        ;; injective key, so a flat `a.b` and a nested `a/b` never collapse
        ;; to the same [:link] path and drop a child from the checklist.
        (let [rel (repo-value repo)]
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

(defn- recur-id
  "A ticket id that is a pure function of (process, period): identical on
  every node, so independent backends firing the same schedule mint the
  SAME create event — byte-identical, one content address, so a later
  union merge keeps ONE ticket. This is what makes recurring-ticket
  creation leaderless: exactly-once without a lock. Namespaced so these
  ids never collide with random-uuid tickets or across processes/periods."
  [proc-name period]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "tik/recur " proc-name " " period) "UTF-8")))

(defn- period-start
  "Porcelain-only: the canonical UTC start instant of a recurring PERIOD
  label, so the minted events carry a deterministic `:at` and stay
  byte-identical across nodes (the content-address dedup above needs every
  field deterministic, `:at` included — wall-clock `now` would differ per
  fire and defeat it). Only porcelain reads a calendar; the kernel stays
  clockless. Recognizes the ISO forms schedules use — year, quarter,
  month, day, and ISO week (Monday 00:00Z of the week). A freeform label
  that matches none needs an explicit --at to pin its instant."
  [label at-opt]
  (if at-opt
    (parse-instant at-opt)
    (letfn [(utc [^LocalDate d] (.toInstant (.atStartOfDay d ZoneOffset/UTC)))
            (iso-week [y w]
              (let [jan4 (LocalDate/of (int y) 1 4)              ; ISO week 1 always contains Jan 4
                    monday1 (.minusDays jan4 (dec (.getValue (.getDayOfWeek jan4))))]
                (.plusWeeks monday1 (dec w))))]
      (try
        (condp re-matches label
          #"(\d{4})"                 :>> (fn [[_ y]] (utc (LocalDate/of (Integer/parseInt y) 1 1)))
          #"(\d{4})-Q([1-4])"        :>> (fn [[_ y q]] (utc (LocalDate/of (Integer/parseInt y)
                                                                          (int (inc (* 3 (dec (Integer/parseInt q))))) 1)))
          #"(\d{4})-(\d{2})"         :>> (fn [[_ y m]] (utc (LocalDate/of (Integer/parseInt y) (Integer/parseInt m) 1)))
          #"(\d{4})-(\d{2})-(\d{2})" :>> (fn [[_ y m d]] (utc (LocalDate/of (Integer/parseInt y) (Integer/parseInt m) (Integer/parseInt d))))
          #"(\d{4})-W(\d{2})"        :>> (fn [[_ y w]] (utc (iso-week (Integer/parseInt y) (Integer/parseInt w))))
          (die (str "recur period " label " is not a recognized calendar label"
                    " (yyyy, yyyy-Qn, yyyy-MM, yyyy-MM-dd, yyyy-Www);"
                    " pass --at <ISO-8601 instant> to pin a freeform label")))
        (catch java.time.DateTimeException _
          (die (str "recur period " label " has an out-of-range date component")))))))

(defn cmd-recur
  "recur <process> --period <label> [--at <inst>] [--title T] [--actor A]
  Idempotently mint the CURRENT period's ticket for a recurring process:
  create one only if no ticket for this process already carries
  period=<label>, else report the existing one. The cadence lives OUTSIDE
  the log — a cron/systemd timer runs this on schedule; tik never stores
  or enforces a schedule (§19), it only derives \"does this period's
  ticket exist yet?\" and mints on a miss. So re-running is safe and a
  missed run self-heals on the next fire. The label is the caller's word
  (2026-W29, 2026-Q3) — the kernel supplies no clock. Sibling of rollout:
  the same idempotent-create-what-is-missing shape.

  The minted events are a PURE FUNCTION of (process, period): a
  deterministic ticket id and a period-start `:at` (derived from the
  label, or --at for a freeform one). So two backends that cannot see each
  other yet — firing the same schedule concurrently — mint byte-identical
  events that union-merge into ONE ticket. That is the leaderless
  exactly-once path: no lock, no single writer needed to avoid duplicates."
  [{:keys [pos opts]}]
  (let [proc-name (or (first pos)
                      (die "usage: tik recur <process> --period <label> [--at <inst>] [--title T]"))
        period (or (:period opts)
                   (die (str "recur needs --period <label> (e.g. 2026-W29):"
                             " the kernel has no clock, so the caller names"
                             " the period")))
        proc (load-process proc-name)
        pk (keyword proc-name)
        s (the-store)
        existing (some (fn [{:keys [id state]}]
                         (when (and (= pk (:process state))
                                    (= period (str (red/fact-value state [:period]))))
                           id))
                       (all-ticket-ctx s))]
    (if existing
      (println (str "already have " proc-name " for " period ": " (sid existing)
                    " — nothing recorded"))
      (let [at (period-start period (:at opts))
            id (recur-id proc-name period)
            e (event/create-ticket {:ticket id :actor (actor opts) :at at
                                    :title (or (:title opts)
                                               (str proc-name " " period))
                                    :process pk
                                    :version (:process/version proc)
                                    :process-hash (archive-process! proc)})]
        (append!* s e opts)
        (append!* s (event/assert-fact {:ticket id :actor (actor opts) :at at
                                        :parents #{(:event/id e)}
                                        :path [:period] :value period})
                  opts)
        (cache-flush!)
        (println (str "created " proc-name " for " period ": " (sid id)))))))

(defn cmd-probe
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

(defn cmd-pack
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

(defn cmd-gc
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

(defn cmd-init
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

(defn delete-tree!
  "Remove a file or directory tree, postorder (contents before the dir)."
  [^File f]
  (doseq [^File c (reverse (file-seq f))] (.delete c)))

(defn cmd-store
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
