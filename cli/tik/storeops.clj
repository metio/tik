;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.storeops
  "Whole-store operations, beyond any single ticket: rollout (one ticket
  per member of a population — the git repos under the store, or one
  declared in populations.edn — wired as a parent checklist), probe
  (auto-derive facts by running each ticket's probe), pack (consolidate
  settled tickets), gc (drop unpinned archived definitions), init (mark a
  directory a store), and store migrate (convert the backend in place).
  Porcelain over tik.cli-core."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.args :refer [actor parse-key read-edn-file typed-value]]
            [tik.cli-core :refer [all-ticket-ctx append!* archive-process! available-processes blob-dir
                                  cache-flush!
                                  db-path die display-title exit! git-tracked? link-lines
                                  load-process load-ticket now parse-instant process-file
                                  process-name repo-key resolve-file resolve-id root
                                  store-holder the-store ticket-ctx]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.next :as next-lens]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.render :refer [shash sid tint]]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite :as sqlite]
            [tik.text :refer [safe-name]])
  (:import (java.io File)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.time Instant LocalDate ZoneOffset)))

(defn- minted-id
  "A ticket id that is a pure function of every byte its create event
  carries, under a per-verb namespace (`kind`, e.g. \"rollout\"). Two
  replicas that form the same intent mint the same create event, so a
  union merge keeps ONE ticket (ADR 0021). Any input that differs yields
  a different ticket, never a second create event under one id. The parts
  are stringified before printing, so the encoding is injective (a title
  may hold spaces) and stable (pr-str of a string, never of an Instant)."
  [kind & parts]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (pr-str (into [(str "tik/" kind)] (map str) parts)) "UTF-8")))

(defn- write-at
  "The claimed instant of a rollout write onto a log: the run's base
  instant, or just after the latest event the write observed when that is
  later. Deterministic for a given log, and never ordered before an event
  it saw — a backdated re-assert of a link a human retracted later the
  same day would fold before the retraction, lose, and be written again on
  every run."
  ^Instant [^Instant base events]
  (reduce (fn [^Instant acc e]
            (let [at (:event/at e)]
              (if (and (instance? Instant at) (not (.isAfter acc ^Instant at)))
                (.plusMillis ^Instant at 1)
                acc)))
          base
          events))

(declare period-start rollout-population! rollout-mint!)

(defn subject-path
  "The fact path that names a rollout child's subject: the definition's
  :subject (a keyword or a vector of keywords), else [:repo] for the git
  walk and [:project] for a declared population. {:path p}, or {:error
  msg} when :subject has another shape."
  [proc population?]
  (let [p (:subject proc)]
    (cond
      (nil? p) {:path (if population? [:project] [:repo])}
      (keyword? p) {:path [p]}
      (and (vector? p) (seq p) (every? keyword? p)) {:path p}
      :else {:error (str ":subject must be a keyword or a vector of keywords, got "
                         (pr-str p))})))

(defn- git-members
  "Every git repository under the store holder, found RECURSIVELY
  (GitLab-style group/subgroup/repo trees work). Descent stops at each
  repository and skips hidden directories. A member's subject and
  directory are both its path relative to the holder."
  [^File holder]
  (let [walk (fn walk [^File d rel]
               (cond
                 (str/starts-with? (.getName d) ".") []
                 (.exists (io/file d ".git")) [rel]
                 :else (mapcat #(walk % (str rel "/" (.getName ^File %)))
                               (filter #(.isDirectory ^File %)
                                       (or (.listFiles d) [])))))]
    (vec (for [r (sort (mapcat #(walk % (.getName ^File %))
                               (filter #(.isDirectory ^File %)
                                       (or (.listFiles holder) []))))]
           {:subject r :repo r}))))

(defn- below-holder
  "The canonical directory `c` as {:repo rel}, rel its path relative to
  the holder, or {:error msg} when it is not strictly below the holder or
  does not exist. A probe runs in the directory a ticket names, so this
  is the one check between a fact and a working directory."
  [^File holder ^File c shown]
  (let [h (str (.getCanonicalFile holder) File/separator)]
    (cond
      (not (str/starts-with? (str c File/separator) h))
      {:error (str shown " is outside " holder)}
      (= (str c File/separator) h)
      {:error (str shown " is the store holder itself, not a directory below it")}
      (not (.isDirectory c))
      {:error (str "no such directory: " c)}
      :else {:repo (subs (str c) (count h))})))

(defn repo-under-holder
  "Where a ticket's [:repo] value `repo` points: {:dir File} for a
  relative path that resolves strictly below the holder, else {:error
  msg}. An absolute path or one that climbs out is refused."
  [^File holder repo]
  (let [f (io/file (str repo))]
    (if (.isAbsolute f)
      {:error (str repo " is an absolute path; [:repo] is relative to " holder)}
      (let [r (below-holder holder (.getCanonicalFile (io/file holder (str repo))) repo)]
        (if (:repo r) {:dir (io/file (.getCanonicalFile holder) ^String (:repo r))} r)))))

(defn- member-repo
  "Where a declared member's work happens, as a path relative to the
  holder: {:repo rel}, {} for a member without a directory, or {:error
  msg}. A relative directory is read against the holder. A directory
  outside the holder, or one that does not exist, is an error, since a
  probe could never run there."
  [^File holder dir]
  (if (nil? dir)
    {}
    (let [f (io/file dir)]
      (below-holder holder (.getCanonicalFile (if (.isAbsolute f) f (io/file holder dir))) dir))))

(defn- command-entries
  "The entries a population's :command prints: one member per line, as
  `subject` or `subject<TAB>dir`; blank lines are skipped. The command
  runs with cwd at the holder. A failing command dies: an unanswered
  question about who belongs must never read as an empty population,
  which --retire would act on."
  [pop-name command ^File holder]
  (let [r (try (sh/sh "sh" "-c" command :dir (str holder))
               (catch java.io.IOException e {:exit -1 :err (ex-message e)}))]
    (when-not (zero? (long (:exit r)))
      (die (str "population " (name pop-name) ": command exited " (:exit r)
                (when-let [e (not-empty (str/trim (str (:err r))))]
                  (str " — " e)))))
    (vec (for [[n line] (map-indexed vector (str/split-lines (:out r)))
               :when (not (str/blank? line))
               :let [fields (str/split line #"\t" -1)]]
           (case (count fields)
             1 {:subject (first fields)}
             2 {:subject (first fields) :dir (second fields)}
             (die (str "population " (name pop-name) ": line " (inc n) " has "
                       (count fields) " tab-separated fields; print `subject` or"
                       " `subject<TAB>dir`, e.g. `aro list | cut -f1,4`")))))))

(defn- list-entries
  "The entries of a population's :list: strings, or maps of :subject and
  an optional :dir."
  [pop-name entries]
  (when-not (sequential? entries)
    (die (str "population " (name pop-name) ": :list must be a vector, got "
              (pr-str entries))))
  (vec (for [e entries]
         (cond
           (string? e) {:subject e}
           (and (map? e) (string? (:subject e))
                (or (nil? (:dir e)) (string? (:dir e))))
           (select-keys e [:subject :dir])
           :else (die (str "population " (name pop-name)
                           ": an entry is a string or {:subject \"…\" :dir \"…\"}, got "
                           (pr-str e)))))))

(defn population-members
  "The members of population `pop-name`, declared in the store's
  populations.edn as {name {:list [...]}} or {name {:command \"…\"}}.
  A member is {:subject s} plus :repo when it names a directory below
  the holder. Returns {:members [...] :errors [[subject msg] ...]}: a
  member whose directory is unusable is an error for that member alone.
  A missing file, an undeclared name, a malformed declaration and a
  subject listed twice with different directories die."
  [^File holder pop-name]
  (let [f (io/file (root) "populations.edn")
        decls (or (read-edn-file f)
                  (die (str "no populations.edn in " (root)
                            " — declare the population there first")))
        _ (when-not (map? decls)
            (die (str f " must hold a map of population names to declarations")))
        spec (or (get decls (keyword (safe-name pop-name)))
                 (die (str "population " (safe-name pop-name) " is not declared in " f
                           (when (seq decls)
                             (str " — declared: "
                                  (str/join ", " (sort (map safe-name (keys decls)))))))))
        _ (when-not (and (map? spec) (= 1 (count (select-keys spec [:list :command]))))
            (die (str "population " (safe-name pop-name)
                      " must declare exactly one of :list or :command, got " (pr-str spec))))
        _ (when (and (contains? spec :command)
                     (not (and (string? (:command spec)) (seq (:command spec)))))
            (die (str "population " (safe-name pop-name)
                      ": :command must be a shell command string")))
        entries (if (contains? spec :list)
                  (list-entries pop-name (:list spec))
                  (command-entries pop-name (:command spec) holder))
        resolved (for [{:keys [subject dir]} entries
                       :let [subject (str/trim subject)]]
                   (do (when (or (str/blank? subject) (re-find #"[\r\n]" subject))
                         (die (str "population " (safe-name pop-name)
                                   ": a subject must be a non-blank single line, got "
                                   (pr-str subject))))
                       (assoc (member-repo holder dir) :subject subject)))
        by-subject (group-by :subject resolved)]
    (doseq [[subject ms] by-subject
            :when (< 1 (count (distinct ms)))]
      (die (str "population " (safe-name pop-name) " lists " subject
                " more than once with different directories")))
    {:members (vec (for [[_ [m]] (sort-by key by-subject) :when (not (:error m))]
                     (select-keys m [:subject :repo])))
     :errors (vec (for [[subject [m]] (sort-by key by-subject) :when (:error m)]
                    [subject (:error m)]))}))

(defn- scope-verdict
  "Whether a member belongs to a rollout's population: run `scope` — a
  script resolved like a probe, or a shell command — with cwd in the
  member's repository (the holder for a member without one), TIK_SUBJECT
  set, and TIK_REPO set when the member has a repository. Exit 0 is :in
  and exit 1 is :out, the convention `test` and `grep -q` follow. Any
  other exit is an unanswered question, returned as {:error msg}, so a
  broken scope script never reads as an empty population."
  [scope ^File holder {:keys [subject repo]}]
  (let [^File f (resolve-file scope)
        argv (if (.exists f) ["sh" (str f)] ["sh" "-c" scope])
        r (try (apply sh/sh (concat argv
                                    [:dir (str (if repo (io/file holder repo) holder))
                                     :env (cond-> (assoc (into {} (System/getenv))
                                                         "TIK_SUBJECT" subject)
                                            repo (assoc "TIK_REPO" repo))]))
               (catch java.io.IOException e {:exit -1 :err (ex-message e)}))]
    (case (long (:exit r))
      0 :in
      1 :out
      {:error (str "scope exited " (:exit r)
                   (when-let [e (not-empty (str/trim (str (:err r))))]
                     (str " — " e)))})))

(defn cmd-rollout
  "rollout <process> [--population P] [--parent <id>] [--parent-title T]
          [--period L] [--at I] [--scope C] [--retire]
  One ticket per member of a population, each carrying its subject fact,
  all wired as link facts on a parent — a checklist whose checkmarks
  DERIVE from each child's evidence. Idempotent: re-runs create only
  uncovered members and missing links, and report coverage. The parent's
  own completion stays a human signature; guards never query across
  tickets (ADR 0004 scope).

  Without a population the members are the git repositories under the
  store holder, and the subject fact is [:repo]: a nested repository's
  identity is its relative path, so the [:repo] fact and queries use the
  string \"group/repo\", flat repos keep the keyword. --population (or the
  definition's :population) names a population declared in the store's
  populations.edn instead (see `population-members`); its members need no
  git repository and no directory at all. The definition's :subject names
  their fact, [:project] by default, and a member with a directory below
  the holder also gets [:repo], where its probe runs.

  The minted events are a pure function of their intent: ticket ids
  derive from (role, subject, process hash, actor, base instant) and every
  write claims `write-at` the base. The base is the period start of
  --period (the labels recur reads), --at, or the wall clock. A scheduler
  passes --period, so two backends running the same rollout concurrently
  mint byte-identical events that union-merge to one set (ADR 0021).

  The process's :scope (or --scope, which wins) narrows the population:
  an executable or shell command asked once per member, see
  `scope-verdict`. A member whose scope check fails, or whose declared
  directory is unusable, is reported and left out of this run, the
  others still roll out, and the exit code is 1 so a scheduler logs the
  failure.

  A child whose member is gone, or now outside the scope, is reported;
  with --retire, rollout records it as a signed [:population :left] fact
  naming the reason. A process that gives that fact a sticky terminal
  stage settles such tickets, so the inbox and probe sweeps drop them.
  Retiring is opt-in because a partial checkout looks exactly like
  deleted repositories."
  [{:keys [pos opts]}]
  (let [proc-name (or (first pos)
                      (die "usage: tik rollout <process> [--population P] [--parent <id>] [--parent-title T] [--scope C]"))
        proc (load-process proc-name)
        holder (store-holder)
        pop-name (or (:population opts) (:population proc))
        _ (when (and (some? pop-name)
                     (not (or (and (string? pop-name) (seq pop-name)) (keyword? pop-name))))
            (die (str "rollout population must be a name, got " (pr-str pop-name))))
        {path :path path-error :error} (subject-path proc (some? pop-name))
        _ (when path-error (die (str "process " proc-name " " path-error)))
        {:keys [members errors]} (if pop-name
                                   (population-members holder pop-name)
                                   {:members (git-members holder) :errors []})
        _ (when (and (nil? pop-name) (empty? members))
            (die (str "no git repositories directly under " holder)))
        ;; [:repo] is a member's directory, so a member without one cannot
        ;; be a [:repo] subject
        dirless (when (= path [:repo])
                  (for [m members :when (nil? (:repo m))]
                    [(:subject m) "no directory, and the subject fact is [:repo]"]))
        errors (into errors dirless)
        members (if (seq dirless) (filterv :repo members) members)
        scope (or (:scope opts) (:scope proc))
        _ (when (and (some? scope) (not (and (string? scope) (seq scope))))
            (die (str "rollout scope must be a script path or shell command, got "
                      (pr-str scope))))
        verdicts (into {} (for [m members]
                            [(:subject m) (if scope (scope-verdict scope holder m) :in)]))
        errors (into errors (for [[subject v] verdicts :when (map? v)] [subject (:error v)]))
        _ (doseq [[subject error] errors]
            (binding [*out* *err*]
              (println (str subject ": " error " — left out of this run"))))
        unanswered (set (map first errors))
        outside (count (filter #(= :out (val %)) verdicts))
        noun (if pop-name "subject" "repo")
        scope-note (str (when scope (str ", " outside " outside the scope"))
                        (when (seq errors) (str ", " (count errors) " unanswered")))]
    (rollout-population! {:proc-name proc-name :proc proc :path path :noun noun
                          :pop-name pop-name :members members :verdicts verdicts
                          :unanswered unanswered :opts opts :scope-note scope-note})
    (when (seq errors) (exit! 1))))

(defn- rollout-children
  "This process's rollout children, read from the store: every ticket of
  the process carrying the subject fact at `path`, with whether it has
  LEFT the population (a present [:population :left] fact) and whether it
  is settled. A child that has left no longer covers its subject, so a
  subject that returns gets a fresh ticket."
  [all proc-name t path]
  (for [{:keys [id state events process roles]} all
        :when (= (keyword proc-name) (:process state))
        :let [v (red/fact-value state path)]
        :when (some? v)]
    {:id id
     :subject (safe-name v)
     :left? (some? (red/fact-value state [:population :left]))
     :settled? (next-lens/settled? process events t roles)}))

(defn- leave-reason
  "Why a child's subject is no longer in the population, or nil while it
  is: the walk or the declaration no longer lists it, or the scope
  answered 1. An unanswered question — a failed scope check, an unusable
  directory — is no answer, so it never retires anything."
  [{:keys [verdicts unanswered pop-name]} subject]
  (cond
    (contains? unanswered subject) nil
    :else (case (get verdicts subject ::absent)
            ::absent (if pop-name
                       (str "no longer in population " (safe-name pop-name))
                       "repository absent")
            :out "outside the scope"
            nil)))

(defn- rollout-population!
  "The writing half of `cmd-rollout`: retire the children whose subject
  left (only under --retire, otherwise report them), then mint what the
  members in scope lack."
  [{:keys [proc-name path noun verdicts opts scope-note] :as run}]
  (let [in-scope (filterv #(= :in (verdicts (:subject %))) (:members run))
        s (the-store)
        t (now)
        who (actor opts)
        _ (doseq [k [:period :at]
                  :let [v (get opts k)]
                  :when (and (some? v) (not (string? v)))]
            (die (str "rollout --" (name k) " needs a value")))
        base (cond
               (:period opts) (period-start (:period opts) (:at opts))
               (:at opts) (parse-instant (:at opts))
               :else t)
        heads-at (fn [id]
                   (let [evs (store/events s id)]
                     [(dag/heads evs) (write-at base evs)]))
        all (vec (all-ticket-ctx s))
        children (rollout-children all proc-name t path)
        child-of (into {} (for [c children :when (not (:left? c))]
                            [(:subject c) (:id c)]))
        ;; how many earlier tickets of a subject have left: part of a
        ;; returning subject's ticket id, so its new ticket never shares
        ;; an id with the one that left in the same period
        episodes (frequencies (map :subject (filter :left? children)))
        leavers (sort-by :subject
                         (for [c children
                               :when (not (or (:left? c) (:settled? c)))
                               :let [why (leave-reason run (:subject c))]
                               :when why]
                           (assoc c :why why)))
        _ (if (:retire opts)
            (doseq [{:keys [id subject why]} leavers]
              (let [[parents at] (heads-at id)]
                (append!* s (event/assert-fact
                             {:ticket id :actor who :at at :parents parents
                              :path [:population :left] :value why})
                          opts)
                (println (str subject ": " why " — retired " (sid id)))))
            (when (seq leavers)
              (println (str (count leavers) " ticket(s) whose "
                            (if (= noun "repo") "repository" "subject")
                            " left the population"
                            " (pass --retire to record it): "
                            (str/join ", " (map #(str (:subject %) " (" (:why %) ")")
                                                leavers))))))]
    (if (empty? in-scope)
      ;; nothing to roll out mints nothing, a parent included: an empty
      ;; checklist would be a ticket about no subject
      (println (str "0 " noun "(s) in scope" scope-note " — nothing rolled out"))
      (rollout-mint! (assoc run :s s :t t :who who :base base :heads-at heads-at
                            :all all :child-of child-of :episodes episodes
                            :members in-scope)))))

(defn- rollout-mint!
  "Mint the parent if missing, a child per uncovered member in scope, and
  the links that make the parent's checklist. A [:repo] subject keeps the
  id inputs it always had; any other subject path joins them, so the same
  string under two subject facts names two different tickets."
  [{:keys [s t who base heads-at all child-of episodes proc-name proc path
           noun members opts scope-note]}]
  (let [parent-title (or (:parent-title opts) (str proc-name " rollout"))
        parent-id
        (or (when-let [p (:parent opts)] (resolve-id s p))
            (some (fn [{:keys [id state events process roles]}]
                    (when (and (= parent-title (display-title state))
                               (not (next-lens/settled? process events t roles)))
                      id))
                  all)
            (let [track (load-process "track")
                  track-hash (archive-process! track)
                  e (event/create-ticket
                     {:ticket (minted-id "rollout" "parent" parent-title track-hash who base)
                      :actor who :at base
                      :title parent-title :process :track
                      :version (:process/version track)
                      :process-hash track-hash})]
              (append!* s e opts)
              (println (str "parent " (sid (:event/ticket e))
                            " \"" parent-title "\""))
              (:event/ticket e)))
        proc-hash (archive-process! proc)
        repo-subject? (= path [:repo])
        assert! (fn [id fpath value]
                  (let [[parents at] (heads-at id)]
                    (append!* s (event/assert-fact
                                 {:ticket id :actor who :at at :parents parents
                                  :path fpath :value value})
                              opts)))
        created (atom 0)]
    (doseq [{:keys [subject repo]} members]
      (let [child-title (str proc-name ": " subject)
            child (or (child-of subject)
                      (let [id (apply minted-id "rollout" "child" proc-name proc-hash
                                      (concat (when-not repo-subject? [path])
                                              [subject who base]
                                              (when-let [n (episodes subject)] [n])))
                            e (event/create-ticket
                               {:ticket id :actor who :at base
                                :title child-title
                                :process (keyword proc-name)
                                :version (:process/version proc)
                                :process-hash proc-hash})]
                        (append!* s e opts)
                        (assert! id path (if repo-subject? (repo-key subject) subject))
                        (when (and repo (not repo-subject?))
                          (assert! id [:repo] (repo-key repo)))
                        (swap! created inc)
                        id))
            {:keys [state]} (ticket-ctx s parent-id)]
        ;; re-runs converge names too: retitling is a superseding fact
        (let [cstate (:state (ticket-ctx s child))]
          (when-not (= child-title (display-title cstate))
            (assert! child [:title] child-title)))
        ;; the link rel is the subject's OWN identity (a keyword when it
        ;; reads as one, the string otherwise) — an injective key, so a
        ;; flat `a.b` and a nested `a/b` never collapse to the same [:link]
        ;; path and drop a child from the checklist.
        (let [rel (repo-key subject)]
          (when-not (= (str child)
                       (red/fact-value state [:link rel]))
            (assert! parent-id [:link rel] (str child))))))
    (println (str @created " ticket(s) created, "
                  (- (count members) @created) " already covered, "
                  (count members) " " noun "(s) total" scope-note
                  " — the living checklist:"))
    (let [{:keys [state]} (ticket-ctx s parent-id)]
      (doseq [line (link-lines s t state)]
        (println "  " line)))
    (println (str "watch it: tik status " (sid parent-id)))))

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

  The minted events are a PURE FUNCTION of their intent: the ticket id
  derives from every byte the create event carries (process and its hash,
  period, title, actor, claimed instant — see `minted-id`) and `:at` is
  the period start (derived from the label, or --at for a freeform one).
  So two backends that cannot see each other yet — firing the same
  schedule concurrently — mint byte-identical events that union-merge
  into ONE ticket. That is the leaderless exactly-once path: no lock, no
  single writer needed to avoid duplicates. Backends that differ in
  delegate or definition mint distinct tickets, each with one create."
  [{:keys [pos opts]}]
  (let [proc-name (or (first pos)
                      (die "usage: tik recur <process> --period <label> [--at <inst>] [--title T]"))
        period (or (:period opts)
                   (die (str "recur needs --period <label> (e.g. 2026-W29):"
                             " the kernel has no clock, so the caller names"
                             " the period")))
        _ (doseq [k [:period :at :title]
                  :let [v (get opts k)]
                  :when (and (some? v) (not (string? v)))]
            (die (str "recur --" (name k) " needs a value")))
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
            who (actor opts)
            title (or (:title opts) (str proc-name " " period))
            proc-hash (archive-process! proc)
            id (minted-id "recur" proc-name proc-hash period title who at)
            e (event/create-ticket {:ticket id :actor who :at at
                                    :title title
                                    :process pk
                                    :version (:process/version proc)
                                    :process-hash proc-hash})]
        (append!* s e opts)
        (append!* s (event/assert-fact {:ticket id :actor who :at at
                                        :parents #{(:event/id e)}
                                        :path [:period] :value period})
                  opts)
        (cache-flush!)
        (println (str "created " proc-name " for " period ": " (sid id)))))))

(defn- env-name
  "A fact path as an environment variable name: segments joined with
  `__`, upper-cased, every character outside [A-Z0-9_] replaced with
  `_`, under the `TIK_FACT_` prefix — so [:candidate :repo] becomes
  TIK_FACT_CANDIDATE__REPO. Path segments are rendered without `name`,
  which throws on the non-keyword a hostile log can carry."
  [path]
  (str "TIK_FACT_"
       (-> (str/join "__" (map #(str/replace (str %) #"^:" "") path))
           str/upper-case
           (str/replace #"[^A-Z0-9_]" "_"))))

(defn- env-value
  "A fact value as a shell would want it: a keyword loses its colon
  (:carry -> carry), an Instant its object wrapper, and anything with no
  flat form keeps its EDN so the probe can parse what it asked for."
  [v]
  (cond
    (keyword? v) (subs (pr-str v) 1)
    (string? v) v
    (or (number? v) (boolean? v) (uuid? v) (instance? Instant v)) (str v)
    :else (pr-str v)))

(defn fact-env
  "The ticket's own PRESENT facts as environment variables, so a probe
  whose subject is a fact rather than a repository can tell WHICH
  subject this invocation is about. One repository holding many
  subjects — a package, a service, a tenant, a workload per ticket — is
  otherwise unprobeable: cwd and TIK_REPO are identical across every one
  of those tickets, and the discriminator sits in a fact the probe
  cannot see.

  Only :present facts are exported: a retracted fact lingering in the
  environment would answer a question the log no longer answers.

  Returns {:env {name value} :collisions [[name [path ...]] ...]}.
  Sanitising is lossy — [:a-b] and [:a_b] both yield TIK_FACT_A_B — and
  colliding names are DROPPED rather than resolved by an arbitrary
  winner, because a probe silently reading the wrong subject's value has
  no way to notice, while a missing variable announces itself on the
  first run. The reserved TIK_TICKET and TIK_REPO cannot be shadowed: a
  fact named `ticket` exports as TIK_FACT_TICKET, a different name by
  construction."
  [state]
  (let [entries (for [path (sort-by pr-str (keys (:facts state)))
                      :let [{:keys [status value]} (red/fact-status state path)]
                      :when (= :present status)]
                  {:name (env-name path) :value (env-value value) :path path})
        by-name (group-by :name entries)]
    {:env (into {} (for [[n [e :as es]] by-name
                         :when (= 1 (count es))]
                     [n (:value e)]))
     :collisions (vec (for [[n es] (sort-by key by-name)
                            :when (< 1 (count es))]
                        [n (mapv :path es)]))}))

(defn cmd-probe
  "probe [<id>] [--command C]
  Auto-derive facts from the world: for every open ticket carrying a
  [:repo] fact or the :subject fact its definition declares (or just
  <id>), run the probe — an executable printing key=value lines — with
  cwd set to that ticket's repository under the store holder, or the
  holder itself for a ticket whose subject has no repository, and assert
  each CHANGED value as an ordinary signed fact. The probe comes from --command or the :probe field of the
  ticket's process definition (looked up by name — a porcelain
  convenience, never derivation semantics). Idempotent by
  construction: unchanged values assert nothing; stages derive from
  whatever landed.

  The sweep skips what it cannot probe and counts it; naming an id
  refuses instead, because \"0 fact(s) derived\" for a probe that never
  ran is an unperformed measurement wearing the shape of a clean one.
  A ticket is unprobeable when it carries neither a [:repo] fact nor its
  definition's :subject fact, when its process declares no :probe and no
  --command supplies one, or when the repository is absent under the
  store holder. A ticket without either fact stays unprobeable on
  purpose: a change minted with --no-repo has nowhere its probe means
  anything.

  The probe's environment carries TIK_TICKET, TIK_REPO when the ticket
  has a repository, TIK_SUBJECT when it has a declared subject, and every
  present fact as TIK_FACT_<PATH> (see `fact-env`) — the facts are the
  ergonomic path, the id the escape hatch for a probe that needs more
  than them (`tik status \"$TIK_TICKET\" --format json`)."
  [{:keys [pos opts]}]
  (let [s (the-store)
        t (now)
        holder (store-holder)
        named (first pos)
        ;; a named ticket derives strictly (die if it cannot); the whole
        ;; store isolates per ticket, so one poison never aborts the sweep
        ctxs (if named
               [(load-ticket s named)]
               (all-ticket-ctx s))
        changed (atom 0)
        ran (atom 0)
        skipped (atom 0)]
    (doseq [{:keys [id events state process roles]} ctxs
            ;; the sweep leaves settled tickets alone; a named ticket is
            ;; probed on request, settled or not
            :when (or named
                      (not (next-lens/settled? process events t roles)))
            :let [repo (red/fact-value state [:repo])
                  repo-name (when repo (safe-name repo))
                  {^File dir :dir dir-error :error} (when repo-name
                                                      (repo-under-holder holder repo-name))
                  definition (let [f (process-file (process-name state))]
                               (when (.exists f) (read-edn-file f)))
                  probe (or (:command opts) (:probe definition))
                  ;; only a :subject the definition declares lets a ticket
                  ;; without a repository be probed
                  spath (when (some? (:subject definition))
                          (:path (subject-path definition true)))
                  subject (when (and spath (not= spath [:repo]))
                            (some-> (red/fact-value state spath) safe-name))
                  label (or repo-name subject)
                  ;; why this ticket cannot be probed AT ALL, or nil when
                  ;; it can. Distinct from "probed and nothing changed":
                  ;; conflating the two reports an unperformed measurement
                  ;; as a clean one.
                  blocked
                  (cond
                    (and (nil? repo) (nil? subject))
                    (str "no [:repo] fact"
                         (when spath (str " and no " (pr-str spath) " subject"))
                         " — a probe runs for a ticket's repository or declared subject")
                    (nil? probe)
                    (str "process " (process-name state)
                         " declares no :probe — pass --command to run one")
                    dir-error dir-error
                    :else nil)]]
      (cond
        ;; Naming an id asks for THIS ticket's probe; when it cannot run,
        ;; the answer is why, not a summary line that reads like success.
        (and blocked named) (die (str "cannot probe " (sid id) " — " blocked))
        blocked (swap! skipped inc)
        :else
        (let [^File f (resolve-file probe)
              ;; the probe runs with cwd in the repository, so a file
              ;; found relative to tik's own working directory travels
              ;; as an absolute path
              argv (if (.exists f) ["sh" (.getPath (.getAbsoluteFile f))] ["sh" "-c" probe])
              {facts :env collisions :collisions} (fact-env state)
              _ (doseq [[n paths] collisions]
                  (binding [*out* *err*]
                    (println (str (sid id) " " label ": " n
                                  " is ambiguous between "
                                  (str/join " and " (map pr-str paths))
                                  " — exported for neither"))))
              r (apply sh/sh (concat argv
                                     [:dir (str (or dir holder))
                                      ;; the reserved names land LAST: a
                                      ;; fact can add to the probe's world,
                                      ;; never rewrite which ticket it is
                                      :env (cond-> (merge (into {} (System/getenv))
                                                          facts
                                                          {"TIK_TICKET" (str id)})
                                             repo-name (assoc "TIK_REPO" repo-name)
                                             subject (assoc "TIK_SUBJECT" subject))]))
              before (stage/effective-reached process events t roles)]
          (swap! ran inc)
          (if-not (zero? (:exit r))
            (do (println (str (sid id) " " label ": probe failed — "
                              (str/trim (:err r))))
                ;; one bad repo must not abort a store sweep, but a named
                ;; ticket whose probe failed did not measure anything
                (when named (exit! 1)))
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
                (println (str (sid id) " " label ": " k " = "
                              (pr-str value))))
              (let [evs (store/events s id)
                    after (stage/effective-reached process evs (now) roles)
                    gained (sort-by str (remove before after))]
                (when (seq gained)
                  (println (str (sid id) " " label " -> "
                                (tint "32" (str/join ", " (map name gained))))))))))))
    ;; the count of facts alone cannot separate "every probe ran and the
    ;; world had not moved" from "no probe ran"; both are 0, and only one
    ;; of them measured anything
    (println (str @changed " fact(s) derived from the world — signed like"
                  " any other claim"
                  " (" @ran " probe(s) ran"
                  (when (pos? @skipped)
                    (str ", " @skipped " ticket(s) had nothing to probe"))
                  ")"))))

(defn cmd-pack
  "pack [<id>]: consolidate settled tickets' loose event files into
  one content-addressed events.<hash>.pack + index per ticket (given an id,
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
  once used one has since migrated away) and that are not the current
  version of a named definition in processes/, together with their
  publication signatures, which sign nothing once the bytes they cover
  are gone. Dry-run BY DEFAULT (ADR 0002 caution): lists candidates and
  states the one cost.

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
        ;; live = every process-hash a ticket currently pins, and the
        ;; current version of every named definition: a published
        ;; definition nobody has minted a ticket against yet is the one
        ;; the next `tik new` pins, and its archive carries the signature
        live (into (into #{}
                         (keep #(:process-hash (red/ticket-state (store/events s %))))
                         (store/ticket-ids s))
                   (map #(process/process-hash (read-edn-file (process-file %))))
                   (available-processes))
        dir (io/file (root) "processes" "by-hash")
        archives (when (.isDirectory dir)
                   (filter #(str/ends-with? (.getName ^File %) ".edn")
                           (.listFiles dir)))
        orphans (remove #(contains? live (str/replace (.getName ^File %)
                                                      #"\.edn$" ""))
                        archives)]
    (if (empty? orphans)
      (println (str "gc: no orphaned definitions — every archived process"
                    " is pinned by a ticket or is a named definition's"
                    " current version"))
      (do
        (println (str (count orphans) " orphaned definition(s), pinned by no"
                      " ticket and current in no named definition:"))
        (doseq [^File f (sort-by #(.getName ^File %) orphans)]
          (println (str "  " (str/replace (.getName f) #"\.edn$" ""))))
        (println (str "removing these keeps `verify` PASS and every current"
                      " derivation intact;"))
        (println (str "only `status --at <before a migration>` degrades to a"
                      " warning + current-"))
        (println "rules fallback for tickets that once used them.")
        (if (:apply opts)
          (let [sigs (mapcat #(sign/sidecars dir (str/replace (.getName ^File %)
                                                              #"\.edn$" ""))
                             orphans)]
            (doseq [^File f (concat orphans sigs)] (io/delete-file f))
            (println (str "removed " (count orphans) " definition(s)"
                          (when (seq sigs)
                            (str " and " (count sigs) " signature(s)"))
                          "."
                          (if (git-tracked? (root))
                            " Recoverable from git history if needed."
                            (str " This store is NOT version-controlled —"
                                 " back up first (or `git init` the store)"
                                 " if you might ever need the old rules.")))))
          (println "dry run — nothing deleted. Re-run with --apply to remove."))))))

(declare delete-tree!)

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
        db (io/file store "tik.db")
        hidden-dir (io/file here ".tik")
        fresh-hidden? (and (:hidden opts) (not (.exists hidden-dir)))]
    ;; discovery prefers .tik/ over tickets/ in the same directory, so a
    ;; second store of the other layout here would hide the first
    (when-let [existing (first (filter #(.exists ^File %)
                                       [(io/file here "tickets") (io/file here "tik.db")
                                        (io/file hidden-dir "tickets") (io/file hidden-dir "tik.db")]))]
      (die (str "already a store: " (.getParentFile ^File existing))))
    (.mkdirs store)
    (try
      (if (:sqlite opts)
        (do (sqlite/sqlite-store (str db))       ; init! writes the schema file
            (println (str "SQLite store initialized at " db)))
        (do (.mkdirs tickets)
            (println (str "store initialized at " store))))
      (catch Throwable e
        ;; a bare .tik/ left behind would win discovery as an empty store
        (when fresh-hidden? (delete-tree! hidden-dir))
        (throw e)))
    (println (str "every tik command run at or below " here
                  " now uses it — try:"))
    (println "  tik new track --title \"the first thing\"")
    (println "  tik author --template bug")))

(defn delete-tree!
  "Remove a file or directory tree, postorder (contents before the dir)."
  [^File f]
  (doseq [^File c (reverse (file-seq f))] (.delete c)))

(defn- open-destination
  "Opens the store a migration writes into, under the staging root `dir`."
  [^File dir to]
  (case to
    :sqlite (sqlite/sqlite-store (str (io/file dir "tik.db")))
    :file (fstore/file-store dir)))

(def ^:dynamic *open-destination*
  "How a migration opens its destination. A seam for tests, which wrap
  the real store to fail mid-copy."
  open-destination)

(defn- staged-entries
  "The names a backend occupies in a store root: what a migration builds
  under its staging directory and then moves into place."
  [to]
  (case to
    :sqlite ["tik.db" "tik.db-journal" "tik.db-wal" "tik.db-shm" "blobs"]
    :file ["tickets"]))

(defn cmd-store
  "store migrate --to sqlite|file: convert THIS store's event backend in
  place (ADR 0020). Reads every event AND every detached sidecar
  (signatures/witnesses) from the current backend, writes them to the
  other (append is idempotent by id), verifies the full event-id and
  sidecar-name sets carried over, then removes the source. Lossless —
  authorship and countersignatures travel with the events (both backends
  hold sidecars, ADR 0007). Blobs move to the destination's layout
  (cli-core/blob-dir) and are checked before the source goes; the actors
  registry and processes are untouched.

  The destination is built in a staging directory inside the store and
  moved into place only once it verifies. A migration that fails,
  however it fails, removes the staging directory and leaves the source
  as the store's only backend, so the store keeps working and the same
  command can run again; both backends side by side would make every
  command refuse the store as ambiguous."
  [{:keys [pos opts]}]
  (when-not (= "migrate" (first pos))
    (die "usage: tik store migrate --to sqlite|file"))
  (let [to (some-> (:to opts) str keyword)
        _ (when-not (#{:sqlite :file} to)
            (die "usage: tik store migrate --to sqlite|file"))
        r (root)
        db (io/file r "tik.db")
        tickets (io/file r "tickets")
        from (if (.isFile db) :sqlite :file)
        staging (io/file r ".tik-migrating")]
    (when (= from to)
      (die (str "this store is already " (name to) "-backed")))
    (when (.exists staging)
      (die (str staging " exists, left by an interrupted migration; remove it"
                " and run the migration again")))
    (let [src (the-store)
          ids (vec (store/ticket-ids src))
          events (vec (mapcat #(store/events src %) ids))
          src-ids (set (map :event/id events))
          sidecars (vec (for [id ids, name (store/sidecar-names src id)]
                          [id name]))
          moved? (volatile! false)]
      (.mkdirs staging)
      (try
        (let [dst (*open-destination* staging to)
              ;; blobs follow the backend's layout (cli-core/blob-dir)
              ;; and are copied by name, which is their hash
              blob-moves (vec (for [id ids
                                    :let [from-dir (blob-dir r from id)]
                                    :when (.isDirectory from-dir)
                                    ^File b (.listFiles from-dir)
                                    :when (.isFile b)]
                                [b (io/file (blob-dir staging to id) (.getName b))]))]
          ;; move bytes only — no re-signing (migration preserves
          ;; authorship, it does not re-author); events then their
          ;; endorsements
          (doseq [e events] (store/append! dst e))
          (doseq [[id name] sidecars]
            (store/put-sidecar! dst id name (store/read-sidecar src id name)))
          (doseq [[^File b ^File dest] blob-moves]
            (io/make-parents dest)
            (io/copy b dest))
          (let [dst-ids (set (mapcat #(map :event/id (store/events dst %))
                                     (store/ticket-ids dst)))
                dst-sidecars (reduce + (map #(count (store/sidecar-names dst %)) ids))
                blobs-ok (count (filter (fn [[^File b ^File dest]]
                                          (and (.isFile dest) (= (.length b) (.length dest))))
                                        blob-moves))]
            (when-not (and (= src-ids dst-ids) (= (count sidecars) dst-sidecars)
                           (= (count blob-moves) blobs-ok))
              (die (str "migration incomplete: " (count src-ids) " event(s)/"
                        (count sidecars) " sidecar(s)/" (count blob-moves) " blob(s) read, "
                        (count dst-ids) "/" dst-sidecars "/" blobs-ok
                        " written — source left intact for retry")))
            (case from
              :sqlite (do (doseq [suffix ["" "-journal" "-wal" "-shm"]]
                            (.delete (io/file (str db suffix))))
                          (delete-tree! (io/file r "blobs")))
              :file (delete-tree! tickets))
            (vreset! moved? true)
            (doseq [entry (staged-entries to)
                    :let [f (io/file staging entry)]
                    :when (.exists f)]
              (Files/move (.toPath f) (.toPath (io/file r entry))
                          (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])))
            (delete-tree! staging)
            (println (str "moved " (count blob-moves) " blob(s)"))))
        (finally
          ;; until the source is removed the staging tree holds only
          ;; copies and goes; once it is removed the staging tree holds
          ;; the store's only copy, so it stays for the operator
          (when-not @moved? (delete-tree! staging))))
      (println (str "migrated " (count ids) " ticket(s), " (count src-ids)
                    " event(s), " (count sidecars) " sidecar(s) to the "
                    (name to) " backend"))
      (println "run `tik verify` to confirm the derivation is unchanged"))))
