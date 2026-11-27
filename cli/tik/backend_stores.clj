;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.backend-stores
  "One `tik backend` serving several stores (ADR 0041): reading and
  checking a `backend.edn` with `:stores`, and opening each store's
  context before anything runs.

    {:stores
     {:metio {:root \"~/git/github.com/metio/.tik\"
              :org \"metio\"
              :actor \"claude\"
              :key {:command [\"pasejo\" \"secret\" \"show\" \"tik/metio/claude\"]}
              :mcp-token-file \"~/.config/tik/mcp-token-metio\"}
      :personal {:root \"~/git/.tik\" :org \"default\" :actor \"claude\"
                 :key {:credential \"tik-default-key\"}}}
     :serve {:host \"127.0.0.1\" :port 7777}
     :notify {:poll \"PT2S\"}
     :pipelines [{:id :github-metio :store :metio :every \"PT10M\"
                  :run [\"bridge\" \"github\" \"--config\" \"github.edn\"]}]}

  `check` is pure: it takes the parsed config and returns every problem it
  finds, so a config is judged as a whole and each refusal is tested on
  its own. `open-stores!` does the I/O: it resolves each key into a
  private runtime directory, proves the key signs as the store's actor
  against that store's own actors file, and reads the MCP tokens. Any
  problem refuses the whole backend (fail closed); see the ADR for why a
  half-up backend is not offered."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :as args]
            [tik.context :as context]
            [tik.identity :as identity]
            [tik.secret :as secret]
            [tik.sign :as sign])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(def store-name-pattern #"[a-z0-9][a-z0-9._-]{0,62}")

(def ^:private top-keys #{:stores :pipelines :serve :notify :allow-shared-keys})
(def ^:private store-keys #{:root :org :actor :key :mcp-token-file :board})
(def ^:private serve-keys #{:host :port})
(def ^:private notify-keys #{:socket :state-dir :poll :window :rate :sinks})
(def ^:private pipeline-keys #{:id :store :run :every :watch :period :as :key})

(def ^:private verbs-the-backend-owns
  "Verbs a multi-store pipeline may not run: the backend itself serves the
  board and MCP (`:serve`) and the notifier (`:notify`) for every store,
  each inside that store's context, and a nested backend or stdio MCP
  server has nobody to talk to."
  #{"serve" "backend" "mcp" "notify"})

(def ^:private flags-the-store-decides
  "Flags a multi-store pipeline may not pass: the store context decides
  the signing key, and `:as` decides the actor."
  #{:key :actor})

(def period-kinds #{:iso-week :month :quarter :day :year})

(defn pipeline-problems
  "What is wrong with one pipeline's schedule and verb, as strings; empty
  when it may run. Shared by the single-store and the multi-store
  backend."
  [{:keys [id run watch every period]}]
  (cond-> []
    (not id) (conj "missing :id")
    (not (and (sequential? run) (seq run))) (conj "needs a non-empty :run vector")
    (not (or watch every)) (conj "needs :every <ISO-8601 duration> or :watch true")
    (and every (not watch)
         (not (try (java.time.Duration/parse every) (catch Exception _ nil))))
    (conj (str ":every " (pr-str every) " is not an ISO-8601 duration (e.g. PT5M, PT1H, P1D)"))
    (and period (not (period-kinds period)))
    (conj (str ":period must be one of " period-kinds))
    ;; rollout mints tickets; without a period each replica claims its own
    ;; wall clock, and concurrent backends mint duplicate children (ADR 0021)
    (and (sequential? run) (= "rollout" (str (first run))) (not watch) (not period))
    (conj "runs rollout, which needs a :period so every replica mints the same events")))

(defn multi-store?
  "Does this parsed backend config name several stores?"
  [cfg]
  (and (map? cfg) (contains? cfg :stores)))

(defn- key-spec? [x]
  (or (and (string? x) (not (str/blank? x)))
      (and (secret/spec? x) (some #{:file :credential :command} (keys x)))))

(defn- unknown [allowed m]
  (seq (sort (map pr-str (remove allowed (keys m))))))

(defn- check-store [problems sname s]
  (let [p (str "store " (name sname))]
    (cond-> problems
      (not (and (keyword? sname) (re-matches store-name-pattern (name sname))))
      (conj (str p ": a store name is a keyword of lower-case letters, digits and ._- (it becomes the URL path /s/<name>/)"))

      (not (map? s))
      (conj (str p ": must be a map"))

      (and (map? s) (unknown store-keys s))
      (conj (str p ": unknown key(s) " (str/join ", " (unknown store-keys s))))

      (and (map? s) (not (and (string? (:root s)) (not (str/blank? (:root s))))))
      (conj (str p ": needs :root, the store's directory"))

      (and (map? s) (not (and (string? (:org s)) (not (str/blank? (:org s))))))
      (conj (str p ": needs :org, the organisation whose key signs it"))

      (and (map? s) (not (identity/principal? (:actor s))))
      (conj (str p ": needs :actor, a principal of letters, digits and ._@+-"))

      (and (map? s) (not (key-spec? (:key s))))
      (conj (str p ": needs :key, a file path, {:file …}, {:credential …} or {:command …}"
                 (when (and (map? (:key s)) (contains? (:key s) :env))
                   "; {:env …} is refused for a key")))

      (and (map? s) (some? (:mcp-token-file s))
           (not (and (string? (:mcp-token-file s)) (not (str/blank? (:mcp-token-file s))))))
      (conj (str p ": :mcp-token-file must be a path"))

      (and (map? s) (contains? s :board) (not (boolean? (:board s))))
      (conj (str p ": :board must be true or false")))))

(defn- check-pipeline [problems stores i pl]
  (let [p (str "pipeline " (if (and (map? pl) (:id pl)) (pr-str (:id pl)) (str "#" i)))]
    (if-not (map? pl)
      (conj problems (str p ": must be a map"))
      (let [run (:run pl)
            parsed (when (and (sequential? run) (every? string? run)) (args/parse-args run))]
        (cond-> (into problems (map #(str p ": " %) (pipeline-problems pl)))
          (unknown pipeline-keys pl)
          (conj (str p ": unknown key(s) " (str/join ", " (unknown pipeline-keys pl))))

          (not (contains? stores (:store pl)))
          (conj (str p ": needs :store naming one of " (str/join ", " (sort (map pr-str (keys stores))))))

          (and (sequential? run) (not (every? string? run)))
          (conj (str p ": :run must be a vector of strings"))

          (and parsed (verbs-the-backend-owns (first (:pos parsed))))
          (conj (str p ": runs " (first (:pos parsed)) ", which the backend serves itself for every store"
                     " (use :serve or :notify)"))

          (and parsed (some flags-the-store-decides (keys (:opts parsed))))
          (conj (str p ": :run may not pass "
                     (str/join ", " (map #(str "--" (name %)) (filter flags-the-store-decides (keys (:opts parsed)))))
                     "; the store decides the key and :as the actor"))

          (and (contains? pl :as) (not (identity/principal? (:as pl))))
          (conj (str p ": :as must be a principal of letters, digits and ._@+-"))

          (and (contains? pl :key) (not (key-spec? (:key pl))))
          (conj (str p ": :key must be a file path, {:file …}, {:credential …} or {:command …}")))))))

(defn- check-shared-keys [problems stores allowed]
  (cond-> problems
    (not (and (sequential? allowed)
              (every? #(and (sequential? %) (= 2 (count %)) (every? stores %)) allowed)))
    (conj ":allow-shared-keys must be a vector of pairs of store names, such as [[:a :b]]")))

(defn check
  "Every problem in a multi-store backend config, as strings; empty when
  it may start. Pure: it reads no file and resolves no secret."
  [cfg]
  (if-not (map? cfg)
    ["the backend config must be a map"]
    (let [stores (:stores cfg)
          stores-map (if (map? stores) stores {})
          pipelines (:pipelines cfg [])
          serve (:serve cfg)
          notify (:notify cfg)]
      (as-> [] problems
        (cond-> problems
          (unknown top-keys cfg)
          (conj (str "unknown key(s) " (str/join ", " (unknown top-keys cfg))))
          (not (and (map? stores) (seq stores)))
          (conj ":stores must map at least one store name to its settings"))
        (reduce-kv check-store problems stores-map)
        (let [roots (keep #(let [r (:root (val %))] (when (string? r) [(key %) r])) stores-map)
              dupes (for [[r ns] (group-by second roots) :when (< 1 (count ns))] [r (map first ns)])]
          (into problems (for [[r ns] dupes]
                           (str "stores " (str/join " and " (map name ns)) " share the root " r))))
        (let [tokens (keep #(let [t (:mcp-token-file (val %))] (when (string? t) [(key %) t])) stores-map)
              dupes (for [[t ns] (group-by second tokens) :when (< 1 (count ns))] [t (map first ns)])]
          (into problems (for [[t ns] dupes]
                           (str "stores " (str/join " and " (map name ns))
                                " share the MCP token file " t "; a token opens exactly one store"))))
        (if (sequential? pipelines)
          (let [ps (reduce (fn [acc [i pl]] (check-pipeline acc stores-map i pl))
                           problems (map-indexed vector pipelines))
                ids (keep #(when (map? %) (:id %)) pipelines)
                dupes (for [[id n] (frequencies ids) :when (< 1 n)] id)]
            (into ps (for [id dupes] (str "pipeline " (pr-str id) " is named twice"))))
          (conj problems ":pipelines must be a vector"))
        (cond-> problems
          (and (some? serve) (not (map? serve)))
          (conj ":serve must be a map such as {:host \"127.0.0.1\" :port 7777}")

          (and (map? serve) (unknown serve-keys serve))
          (conj (str ":serve has unknown key(s) " (str/join ", " (unknown serve-keys serve))))

          (and (map? serve) (contains? serve :port)
               (not (and (int? (:port serve)) (< 0 (:port serve) 65536))))
          (conj ":serve :port must be a port number")

          (and (map? serve) (contains? serve :host) (not (string? (:host serve))))
          (conj ":serve :host must be a string")

          (and (nil? serve) (some :mcp-token-file (vals stores-map)))
          (conj "a store has :mcp-token-file but the backend has no :serve to answer MCP on")

          (and (some? notify) (not (map? notify)))
          (conj ":notify must be a map of notify.edn settings")

          (and (map? notify) (unknown notify-keys notify))
          (conj (str ":notify has unknown key(s) " (str/join ", " (unknown notify-keys notify))
                     (when (contains? notify :stores) "; the notifier watches the backend's :stores"))))
        (if (contains? cfg :allow-shared-keys)
          (check-shared-keys problems stores-map (:allow-shared-keys cfg))
          problems)
        (cond-> problems
          (and (empty? pipelines) (nil? serve) (nil? notify))
          (conj "nothing to run: no :pipelines, no :serve and no :notify"))))))

;; ------------------------------------------------------------ opening stores

(defn- expand-home [s]
  (str/replace-first (str s) #"^~(?=/|$)" (System/getProperty "user.home")))

(defn private-runtime-dir!
  "A fresh directory only this user can enter, below XDG_RUNTIME_DIR when
  there is one: resolved keys live there while the backend runs. It is
  removed on exit."
  ^File []
  (let [base (or (not-empty (System/getenv "XDG_RUNTIME_DIR")) (System/getProperty "java.io.tmpdir"))
        p (Files/createTempDirectory (.toPath (io/file base)) "tik-backend."
                                     (into-array FileAttribute
                                                 [(PosixFilePermissions/asFileAttribute
                                                   (PosixFilePermissions/fromString "rwx------"))]))
        dir (.toFile p)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (doseq [^File f (.listFiles dir)] (.delete f))
                                           (.delete dir))))
    dir))

(defn- store-root?
  "Is `r` a store root: a .tik directory, or one holding tickets/ or tik.db?"
  [^File r]
  (and (.isDirectory r)
       (or (= ".tik" (.getName r))
           (.isDirectory (io/file r "tickets"))
           (.isFile (io/file r "tik.db")))))

(defn- fingerprint-of [key-path]
  (try (sign/fingerprint (sign/pubkey key-path))
       (catch Exception _ nil)))

(defn signs-as?
  "Does `key-path` produce a signature the actors file at `root` credits
  to `actor`? A challenge is signed in the event namespace and verified
  exactly as an event would be, so a store whose key its own registry
  does not grant to its actor never starts."
  [root key-path actor]
  (try
    (let [challenge (.getBytes (str "tik backend: " actor " signs for " root) "UTF-8")
          sig (sign/sign-bytes key-path challenge sign/namespace-event)]
      (boolean (sign/verify-bytes (io/file root "actors") challenge sig actor sign/namespace-event)))
    (catch Exception _ false)))

(defn- token-of
  "The MCP token in `f`, or a problem string."
  [sname f]
  (let [file (io/file (expand-home f))
        p (str "store " (name sname) ": :mcp-token-file " f)]
    (if-not (.isFile file)
      {:problem (str p " does not exist")}
      (let [perms (PosixFilePermissions/toString
                   (Files/getPosixFilePermissions (.toPath file) (make-array LinkOption 0)))
            token (str/trim (slurp file))]
        (cond
          (not= "---" (subs perms 3 6) (subs perms 6 9))
          {:problem (str p " is readable by others (" perms "); chmod 600 it")}
          (< (count token) 32)
          {:problem (str p " must hold a token of at least 32 characters")}
          :else {:token token})))))

(defn- open-store
  "Open one store: its root, its resolved key and what it is checked
  against. Returns {:ctx … :fingerprint … :token …} or {:problems […]}."
  [dir sname {:keys [root org actor key mcp-token-file board]}]
  (let [p (str "store " (name sname))
        r (.getCanonicalFile (io/file (expand-home root)))
        key-path (try (secret/materialize-file (str p " :key") key dir (str (name sname) ".key"))
                      (catch clojure.lang.ExceptionInfo e {:problem (ex-message e)}))
        key-problem (when (map? key-path) (:problem key-path))
        fpr (when-not key-problem (fingerprint-of key-path))
        tok (when mcp-token-file (token-of sname mcp-token-file))
        problems (cond-> []
                   (not (store-root? r)) (conj (str p ": " r " is not a tik store"))
                   (and (store-root? r) (not (.isFile (io/file r "actors"))))
                   (conj (str p ": " r " has no actors file, so nothing it signs could be credited"))
                   key-problem (conj key-problem)
                   (and (not key-problem) (nil? fpr))
                   (conj (str p ": its :key is not an OpenSSH private key"))
                   (and fpr (.isFile (io/file r "actors")) (not (signs-as? r key-path actor)))
                   (conj (str p ": its key is not granted to " actor " in " (io/file r "actors")))
                   (:problem tok) (conj (:problem tok)))]
    (if (seq problems)
      {:problems problems}
      {:ctx {:name (name sname) :root (str r) :key (str key-path) :actor actor :org org}
       :fingerprint fpr
       :token (:token tok)
       :board? (if (some? board) board true)})))

(defn- shared-key-problems
  "Stores that resolve to one key but declare different organisations:
  refused unless :allow-shared-keys names the pair."
  [opened allowed]
  (let [ok (set (map set allowed))]
    (for [[fpr group] (group-by (comp :fingerprint val) opened)
          :when fpr
          [a b] (for [x group y group :when (neg? (compare (name (key x)) (name (key y))))] [x y])
          :when (and (not= (:org (:ctx (val a))) (:org (:ctx (val b))))
                     (not (ok #{(key a) (key b)})))]
      (str "stores " (name (key a)) " (" (:org (:ctx (val a))) ") and " (name (key b))
           " (" (:org (:ctx (val b))) ") resolve to the same key; one organisation's key"
           " never signs another's store unless :allow-shared-keys names [" (key a) " " (key b) "]"))))

(defn- open-pipeline
  "A pipeline's context: its store's, with :as as the actor and its own
  :key when it names one, checked against that store's actors file."
  [dir stores {:keys [id store as] pkey :key :as pl}]
  (let [{:keys [ctx]} (get stores store)
        actor (or as (:actor ctx))
        p (str "pipeline " (pr-str id))
        key-path (if pkey
                   (try (secret/materialize-file (str p " :key") pkey dir (str "pipeline-" (name id) ".key"))
                        (catch clojure.lang.ExceptionInfo e {:problem (ex-message e)}))
                   (:key ctx))]
    (cond
      (map? key-path) {:problems [(:problem key-path)]}
      (not (signs-as? (:root ctx) key-path actor))
      {:problems [(str p ": its key is not granted to " actor " in store " (:name ctx) "'s actors file")]}
      :else {:pipeline (assoc pl :as actor
                              :tik/store (assoc ctx :actor actor :key (str key-path)))
             :fingerprint (fingerprint-of key-path)})))

(defn environment-problems
  "A multi-store backend refuses to start with a store variable in its
  environment: code that runs outside a store context would read it."
  [env]
  (for [n (sort context/store-variables) :when (some? (get env n))]
    (str n " is set; a multi-store backend takes every store, key and actor from its config,"
         " so unset it")))

(defn open-stores!
  "Open every store and pipeline of a checked config. Returns
  {:stores {name {:ctx :token :board?}} :pipelines [...]} or
  {:problems [...]}: one store that cannot open refuses them all."
  [cfg env]
  (let [env-problems (environment-problems env)]
    (if (seq env-problems)
      {:problems (vec env-problems)}
      (let [dir (private-runtime-dir!)
            opened (into (sorted-map) (for [[sname s] (:stores cfg)] [sname (open-store dir sname s)]))
            store-problems (mapcat (comp :problems val) opened)]
        (if (seq store-problems)
          {:problems (vec store-problems)}
          (let [pls (mapv #(open-pipeline dir opened %) (:pipelines cfg []))
                orgs (into {} (for [[n {:keys [ctx]}] opened] [n (:org ctx)]))
                key-problems (concat
                              (shared-key-problems opened (:allow-shared-keys cfg []))
                              ;; a pipeline key belongs to its store's organisation as well
                              (for [[pl {:keys [fingerprint]}] (map vector (:pipelines cfg) pls)
                                    :when fingerprint
                                    [n {other :fingerprint}] opened
                                    :when (and (= fingerprint other)
                                               (not= (orgs n) (orgs (:store pl))))]
                                (str "pipeline " (pr-str (:id pl)) " signs with the key of store "
                                     (name n) ", another organisation's")))
                problems (concat (mapcat :problems pls) key-problems)]
            (if (seq problems)
              {:problems (vec problems)}
              {:stores (into {} (for [[n o] opened] [(name n) (select-keys o [:ctx :token :board?])]))
               :pipelines (mapv :pipeline pls)})))))))
