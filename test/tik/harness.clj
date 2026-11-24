;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.harness
  "Shared test plumbing: temp stores, the `bb tik` shell runner, and the
  clean-output predicate. Every integration test builds the same three
  things — a temp dir, an env map carrying TIK_ROOT/TIK_ACTOR, and a
  stack-trace regex — and each had drifted apart across files; one home
  keeps the contract (and especially the forbidden-output token list)
  from skewing between suites."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.event :as event]
            [tik.process :as process]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def sample-register
  "Who is in a role, for tests that drive the sample processes.

  The definitions ship their roles EMPTY: who is in one is organisation
  state, not a rule of the process, so it lives in a store's register
  (`roles.edn`) rather than inside the hash a ticket pinned. A test of a
  signature stage therefore supplies the staffing it needs, exactly as
  the register does — taking it from the definition only ever worked by
  accident of the definition also being this store's own copy."
  {:triager    {:members ["seb"]}
   :billing    {:members ["billing"]}
   :maintainer {:members ["seb"]}})

(defn sample-roles
  "`proc`'s roles as this suite staffs them."
  [proc]
  (process/resolve-roles (:process/roles proc {}) sample-register))

;; ---- temp directories: one root per run, removed whatever the outcome ----
;;
;; Every temp directory and file a test makes lives under one root directory
;; per JVM, so a run adds at most one entry to java.io.tmpdir and removing
;; that entry removes everything the run made. The root goes when the JVM
;; exits (a shutdown hook, which runs after a failing or throwing test as
;; well as a passing one) and when kaocha finishes a run (`post-run`, for a
;; REPL that keeps the JVM alive); the next temp-dir! makes a fresh one.

(defn delete-tree!
  "Remove `f` and everything below it. Symbolic links are removed, never
  followed, so a test that plants one cannot make this delete its target."
  [^File f]
  (when (.isDirectory f)
    (when-not (Files/isSymbolicLink (.toPath f))
      (doseq [^File c (.listFiles f)] (delete-tree! c))))
  (.delete f))

(defonce ^:private root-state (atom nil))

(defn temp-root!
  "This run's temp root as a File, made on first use. Subprocesses the
  suite starts get it as their temp directory (run-tik!)."
  ^File []
  (locking root-state
    (let [^File r @root-state]
      (if (and r (.isDirectory r))
        r
        (let [r (.toFile (Files/createTempDirectory "tik-test-run" (make-array FileAttribute 0)))]
          (reset! root-state r)
          r)))))

(defn delete-temp-root!
  "Remove this run's temp root and everything any test put below it."
  []
  (locking root-state
    (when-let [r @root-state]
      (delete-tree! r)
      (reset! root-state nil))))

(defonce shutdown-hook
  ;; the JVM's exit removes the root whatever the run's outcome
  (let [t (Thread. ^Runnable delete-temp-root!)]
    (.addShutdownHook (Runtime/getRuntime) t)
    t))

(defn post-run
  "kaocha post-run hook: the run is over, so is its temp root."
  [result]
  (delete-temp-root!)
  result)

(defn temp-dir!
  "A fresh temp directory as a File, below this run's temp root."
  (^File [] (temp-dir! "tik-test"))
  (^File [prefix]
   (.toFile (Files/createTempDirectory (.toPath (temp-root!)) prefix
                                       (make-array FileAttribute 0)))))

(defn temp-file!
  "A fresh empty temp file as a File, below this run's temp root."
  ^File [prefix suffix]
  (File/createTempFile prefix suffix (temp-root!)))

(defn temp-store!
  "A fresh file store in a temp dir: {:root File :store EventStore}."
  []
  (let [root (temp-dir! "tik-store")]
    {:root root :store (fstore/file-store (str root))}))

(defn seed-ticket!
  "Append a create event (and nothing else) for a new ticket; returns
  the created event. `opts` may carry :ticket :actor :at :title :process."
  [s {:keys [ticket actor at title process]
      :or {actor "seb" title "seeded" process :track}}]
  (store/append! s (event/create-ticket
                    {:ticket (or ticket (random-uuid)) :actor actor
                     :at at :title title :process process})))

(defn subprocess-env
  "This process's environment with TMPDIR pointing at the run's temp root,
  so `mktemp`, ssh-keygen and the shell scripts a test starts leave their
  temp files where the run removes them."
  []
  (assoc (into {} (System/getenv)) "TMPDIR" (str (temp-root!))))

(defn bb-command
  "The argv that starts babashka with `args`. babashka reads its temp
  directory from java.io.tmpdir and ignores TMPDIR, so the run's temp root
  goes in as a system property."
  [& args]
  (into ["bb" (str "-Djava.io.tmpdir=" (temp-root!))] args))

(defn run-tik!
  "Shell `bb tik <args>` against a store; the raw sh/sh map
  {:exit :out :err}. Always passes --config with the repo's bb.edn, so
  :dir may point anywhere (store-discovery tests cd INTO stores).

  opts: :root  the TIK_ROOT (nil scrubs it from the env — discovery
               tests must find stores without it)
        :actor TIK_ACTOR (default \"fuzz\")
        :dir   cwd for the process (default the repo)
        :env   extra env entries (merged last)
        :in    stdin for the process
        :tmpdir its temp directory (default the run's temp root)
  Args are str-coerced, so ids/files can be passed as-is."
  [{:keys [root actor dir env in tmpdir]} & args]
  (let [repo (System/getProperty "user.dir")
        tmp (str (or tmpdir (temp-root!)))]
    (apply sh/sh (concat ["bb" (str "-Djava.io.tmpdir=" tmp)
                          "--config" (str repo "/bb.edn") "tik"]
                         (map str args)
                         (when in [:in in])
                         [:dir (str (or dir repo))
                          :env (merge (cond-> (assoc (subprocess-env) "TMPDIR" tmp)
                                        true (dissoc "TIK_ROOT")
                                        root (assoc "TIK_ROOT" (str root)))
                                      {"TIK_ACTOR" (or actor "fuzz")}
                                      env)]))))

(defn tik-runner
  "A curried run-tik!: (tik-runner root) -> (fn [& args] …). The shape
  every suite's local `run` fn had."
  [root]
  (fn [& args] (apply run-tik! {:root root} args)))

(defn tik!
  "run-tik! that THROWS on a non-zero exit — for test setup steps whose
  failure should abort the test, not silently produce an empty store."
  [opts & args]
  (let [r (apply run-tik! opts args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "tik " (first args) " failed")
                      {:args args :exit (:exit r) :err (:err r)})))
    r))

;; ---- addressing tickets in a store WITHOUT depending on listing order ----
;;
;; `.listFiles`/`.list` return entries in filesystem order, which is arbitrary
;; and OS-dependent — so `(first (.listFiles tickets))` picks a NON-deterministic
;; ticket. That is a real bug when a test plants a bomb/sidecar meaning to hit a
;; SPECIFIC ticket in a multi-ticket store (it once corrupted the healthy
;; neighbor on some filesystems). Address tickets by id, or assert there is
;; exactly one, so the assumption is checked rather than silently violated.

(defn events-dir
  "The events/ directory of a ticket, built from its id — no directory
  listing, so it never depends on filesystem order."
  ^File [root id]
  (io/file (str root) "tickets" (str id) "events"))

(defn new-ticket!
  "Run `tik new …` via a tik-runner `run` and return the CREATED ticket's id
  (the UUID new prints on its first line). The deterministic way to target a
  SPECIFIC ticket in a multi-ticket store — never `(first (.listFiles …))`."
  [run & args]
  (-> (apply run "new" args) :out str/split-lines first str/trim))

(defn- sole-ticket-dir
  ^File [root]
  (let [dirs (filter #(.isDirectory ^File %)
                     (.listFiles (io/file (str root) "tickets")))]
    (when-not (= 1 (count dirs))
      (throw (ex-info (str "expected exactly one ticket in the store, found "
                           (count dirs))
                      {:count (count dirs)})))
    (first dirs)))

(defn sole-ticket-events-dir
  "The events/ dir of the store's ONLY ticket, asserting exactly one exists —
  so a test relying on it fails LOUDLY the moment it grows a second ticket,
  instead of silently picking an arbitrary one."
  ^File [root]
  (io/file (sole-ticket-dir root) "events"))

(defn sole-ticket-fingerprint
  "[<ticket-dir-name> <sorted event filenames>] for a store with exactly one
  ticket — the deterministic identity two independent stores are compared on.
  Asserts one ticket (the comparison is meaningless otherwise) and sorts the
  event names so the result never depends on listing order."
  [root]
  (let [^File d (sole-ticket-dir root)]
    [(.getName d)
     (sort (map #(.getName ^File %) (.listFiles (io/file d "events"))))]))

(def forbidden-output
  "Tokens that must never reach a user, whatever the input: raw stack
  frames, JVM class names, cast/overflow leakage, and the CLI's own
  'this is a bug' apology. One audited list — the per-file copies had
  drifted (some checked ClassCast, others StackOverflow, few both)."
  #"Exception in thread|\tat |clojure\.lang\.|StackTrace|StackOverflow|ClassCast|DateTimeParse|bug in tik|----- Error")

(defn clean-output?
  "No forbidden token anywhere in the string (typically (str out err))."
  [s]
  (not (re-find forbidden-output (str s))))

(defn with-cli-root
  "Run f with tik.cli's store root pinned to `root` — the in-process
  (run-argv) analogue of TIK_ROOT."
  [root f]
  (with-redefs-fn {(requiring-resolve 'tik.cli-core/root) (constantly (str root))}
    f))
