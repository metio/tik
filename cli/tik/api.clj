;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.api
  "A small, stable library surface over one tik store, for programs that
  read tik evidence in process instead of running `tik status` per ticket.

  Contract, version 1 (`version`):

  - `(open-store root)` or `(open-store root {:limits {...}})` returns a
    read-only handle on the store whose root is `root`: a directory
    holding `tickets/` (file store) or `tik.db` (SQLite store). The
    handle is a map; :tik.api/version, :tik.api/root and
    :tik.api/backend (:file or :sqlite) are part of the contract, every
    other key is not.
  - `(resolve-id store ref)` returns the ticket uuid `ref` names: a uuid,
    the full id as a string, or a prefix of at least 8 characters that
    names exactly one ticket.
  - `(ticket store ref)` returns the ticket's events in reduction order.
  - `(derive store ref)` or `(derive store ref {:at instant})` returns
    {:ticket :title :rules {:process :version :hash} :current :reached
     :facts :missing-parents :definition :at}. :current, :reached, :facts
    and :missing-parents equal what `tik status <id> --edn` prints for
    the same ticket at the same instant. The definition must hash to the
    ticket's pin unless {:allow-unpinned-definition true} is given.
  - `(verify-ticket store ref)` returns {:ticket :ok? :failures :events
    :items}: the `tik verify <id>` ladder (L0, L1, L3, L2) as data, plus
    the pinned definition's own checks, judged by the same code as the
    CLI. Each of :events is {:event :type :actor :at :integrity
    :signature :signatures}, where :integrity is :ok or :failed and
    :signature is :verified, :delegated, :unsigned, :unchecked or
    :failed. :ok? is true only when no check failed.

  Every refusal is an ex-info whose ex-data :type is one of
  :tik.api/not-a-store, :tik.api/ambiguous-store,
  :tik.api/backend-unavailable, :tik.api/invalid-argument,
  :tik.api/invalid-id, :tik.api/unknown-ticket, :tik.api/ambiguous-id
  (with :candidates), :tik.api/too-large (with :limit and :actual),
  :tik.api/unreadable, :tik.api/definition-missing,
  :tik.api/definition-mismatch, :tik.api/malformed-roles,
  :tik.api/underivable or :tik.api/read-only. Any other exception is a
  bug in tik.

  Nothing here writes to the store, signs, runs a probe, touches the
  network or starts a process. Signatures are verified in process
  (Ed25519 and Ed25519 security keys, ML-DSA-65); a key type only
  ssh-keygen can read is :unchecked and fails its check, where the CLI
  would ask ssh-keygen. Every event, sidecar and file read is bounded by
  the handle's :limits before it is read."
  (:refer-clojure :exclude [derive])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.dag :as dag]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite :as sqlite]
            [tik.templates :as templates]
            [tik.verify :as verify])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.time Instant)))

(def version
  "The version of this namespace's contract. It changes only when a
  documented key, argument or refusal type changes meaning or goes away;
  additions keep it."
  1)

(def default-limits
  "What one call may read. A store is somebody else's data: a ticket
  directory with a gigabyte event file, or a planted symlink to a device,
  must be refused before it is read rather than exhaust the caller."
  {:max-event-bytes (* 1024 1024)
   :max-events 20000
   :max-sidecar-bytes (* 64 1024)
   :max-ticket-bytes (* 64 1024 1024)
   :max-file-bytes (* 4 1024 1024)})

(defn- refuse
  ([type msg] (refuse type msg {} nil))
  ([type msg data] (refuse type msg data nil))
  ([type msg data cause]
   (throw (ex-info msg (assoc data :type type) cause))))

(defn- api-error? [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (= "tik.api" (some-> (:type (ex-data e)) namespace))))

;; ------------------------------------------------------------- bounds

(def ^:private no-follow-links
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- regular-file?
  "A regular file, judged without following a symbolic link."
  [^File f]
  (Files/isRegularFile (.toPath f) no-follow-links))

(defn- too-large! [what limit actual data]
  (refuse ::too-large (str what " is larger than the limit (" actual " > " limit ")")
          (assoc data :limit limit :actual actual)))

(defn- bounded-file
  "`f` when it is absent or a regular file within `limit` bytes; refuses
  a symbolic link or anything that is not a regular file, since its size
  says nothing about what reading it returns."
  [^File f limit]
  (let [p (.toPath f)]
    (cond
      (not (Files/exists p no-follow-links)) f
      (not (regular-file? f))
      (refuse ::unreadable (str f " is not a regular file") {:file (str f)})
      (> (.length f) (long limit))
      (too-large! (str f) limit (.length f) {:file (str f)})
      :else f)))

(defn- check-file-ticket! [{::keys [root limits]} id]
  (let [{:keys [max-event-bytes max-events max-sidecar-bytes max-ticket-bytes
                max-file-bytes]} limits
        dir (io/file root "tickets" (str id) "events")
        data {:ticket id}]
    (when (Files/exists (.toPath dir) no-follow-links)
      (when (Files/isSymbolicLink (.toPath dir))
        (refuse ::unreadable (str dir " is a symbolic link") data))
      (let [entries (vec (.listFiles dir))
            _ (doseq [^File f entries
                      :when (not (regular-file? f))]
                (refuse ::unreadable (str f " is not a regular file")
                        (assoc data :file (str f))))
            total (reduce + 0 (map #(.length ^File %) entries))
            loose (filterv #(str/ends-with? (.getName ^File %) ".edn") entries)
            idx (io/file dir "events.pack.idx")
            index (when (.isFile idx)
                    (bounded-file idx max-file-bytes)
                    (fstore/read-pack-index dir))
            packed (:entries index)]
        (when (> total (long max-ticket-bytes))
          (too-large! (str "ticket " id) max-ticket-bytes total data))
        (when (> (+ (count loose) (count packed)) (long max-events))
          (too-large! (str "ticket " id " (events)") max-events
                      (+ (count loose) (count packed)) data))
        (doseq [^File f entries
                :let [n (.getName f)
                      limit (cond
                              (str/ends-with? n ".edn") max-event-bytes
                              (re-find #"\.(sig|pqsig|witness|pqwitness)\." n) max-sidecar-bytes
                              :else nil)]
                :when (and limit (> (.length f) (long limit)))]
          (too-large! (str f) limit (.length f) (assoc data :file (str f))))
        (doseq [{:keys [length]} packed
                :when (or (not (integer? length)) (> (long length) (long max-event-bytes)))]
          (too-large! (str "a packed event of ticket " id) max-event-bytes length data))))))

(defn- check-sqlite-ticket! [{::keys [db limits]} id]
  (let [{:keys [max-event-bytes max-events max-sidecar-bytes max-ticket-bytes]} limits
        {:keys [events event-max event-total sidecar-max sidecar-total]} (sqlite/sizes db id)
        data {:ticket id}]
    (when (> events (long max-events))
      (too-large! (str "ticket " id " (events)") max-events events data))
    (when (> event-max (long max-event-bytes))
      (too-large! (str "an event of ticket " id) max-event-bytes event-max data))
    (when (> sidecar-max (long max-sidecar-bytes))
      (too-large! (str "a sidecar of ticket " id) max-sidecar-bytes sidecar-max data))
    (when (> (+ event-total sidecar-total) (long max-ticket-bytes))
      (too-large! (str "ticket " id) max-ticket-bytes (+ event-total sidecar-total) data))))

(def ^:private ^:dynamic *checked*
  "The tickets whose bounds this call has checked, so a ticket read many
  times in one call is measured once."
  nil)

(defn- check-ticket! [h id]
  (when-not (and *checked* (contains? @*checked* id))
    (if (= :sqlite (::backend h))
      (check-sqlite-ticket! h id)
      (check-file-ticket! h id))
    (when *checked* (swap! *checked* conj id))))

(defrecord ^:private BoundedStore [h inner]
  store/EventStore
  (append! [_ _]
    (refuse ::read-only "a tik.api store handle is read-only"))
  (events [_ ticket-id]
    (check-ticket! h ticket-id)
    (store/events inner ticket-id))
  (ticket-ids [_] (store/ticket-ids inner))
  (has-event? [_ event-id] (store/has-event? inner event-id))
  (event-bytes [_ ticket-id event-id]
    (check-ticket! h ticket-id)
    (store/event-bytes inner ticket-id event-id))
  (put-sidecar! [_ _ _ _]
    (refuse ::read-only "a tik.api store handle is read-only"))
  (sidecar-names [_ ticket-id] (store/sidecar-names inner ticket-id))
  (read-sidecar [_ ticket-id name]
    (check-ticket! h ticket-id)
    (store/read-sidecar inner ticket-id name)))

;; ------------------------------------------------------------- the handle

(defn- check-limits [limits]
  (when-not (or (nil? limits) (map? limits))
    (refuse ::invalid-argument ":limits is a map" {:limits limits}))
  (doseq [[k v] limits]
    (when-not (contains? default-limits k)
      (refuse ::invalid-argument (str "unknown limit " k) {:limit k}))
    (when-not (pos-int? v)
      (refuse ::invalid-argument (str "limit " k " is a positive integer") {:limit k :value v})))
  (merge default-limits limits))

(defn- sqlite-available? []
  (try (some? (requiring-resolve 'tik.store.sqlite-jdbc/query))
       (catch Throwable _ false)))

(defn open-store
  "A read-only handle on the store at `root` (a path or File). The root is
  the directory holding `tickets/` or `tik.db`, never a directory above
  it: nothing is discovered. `opts` takes :limits, a map overriding
  entries of `default-limits`."
  ([root] (open-store root {}))
  ([root {:keys [limits] :as opts}]
   (when-not (or (string? root) (instance? File root))
     (refuse ::invalid-argument "the store root is a path string or a File" {:root root}))
   (when-not (or (nil? opts) (map? opts))
     (refuse ::invalid-argument "options are a map" {:opts opts}))
   (when-let [unknown (seq (remove #{:limits} (keys opts)))]
     (refuse ::invalid-argument (str "unknown options " (vec unknown)) {:unknown (vec unknown)}))
   (let [limits (check-limits limits)
         dir (io/file (str root))
         _ (when-not (.isDirectory dir)
             (refuse ::not-a-store (str root " is not a directory") {:root (str root)}))
         dir (.getCanonicalFile dir)
         db (io/file dir "tik.db")
         tickets (io/file dir "tickets")
         backend (cond
                   (and (.exists db) (.exists tickets))
                   (refuse ::ambiguous-store
                           (str dir " holds both tik.db and tickets/")
                           {:root (str dir)})
                   (regular-file? db) :sqlite
                   (and (.isDirectory tickets) (not (Files/isSymbolicLink (.toPath tickets)))) :file
                   :else (refuse ::not-a-store
                                 (str dir " holds neither tickets/ nor tik.db")
                                 {:root (str dir)}))
         _ (when (and (= :sqlite backend) (not (sqlite-available?)))
             (refuse ::backend-unavailable
                     "this store is SQLite-backed and this runtime has no SQLite driver"
                     {:root (str dir)}))
         h {::version version
            ::root (str dir)
            ::backend backend
            ::limits limits
            ::db (when (= :sqlite backend) (sqlite/read-only-db db))}
         inner (if (= :sqlite backend)
                 (sqlite/read-only-store db)
                 (fstore/file-store (str dir)))]
     (assoc h ::store (->BoundedStore h inner)))))

(defn- handle! [h]
  (when-not (and (map? h) (= version (::version h)) (::store h))
    (refuse ::invalid-argument "not a store handle from tik.api/open-store" {}))
  h)

(defn- unreadable-reason? [data]
  (contains? #{"event" "pack" "store" "canonical"} (some-> (:reason data) namespace)))

(defn- call
  "Run `f` for one public call: bounds checked once per ticket, no
  ssh-keygen, and every failure as a tik.api refusal."
  [f]
  (binding [*checked* (atom #{})
            sign/*ssh-keygen-fallback* false]
    (try (f)
         (catch clojure.lang.ExceptionInfo e
           (if (api-error? e)
             (throw e)
             (let [data (ex-data e)]
               (refuse (if (unreadable-reason? data) ::unreadable ::underivable)
                       (ex-message e) (select-keys data [:reason :file :id]) e))))
         (catch java.io.IOException e
           (refuse ::unreadable (str "store unreadable: " (ex-message e)) {} e)))))

;; ------------------------------------------------------------- tickets

(def ^:private full-uuid
  #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn- resolve* [h ref]
  (let [ids (store/ticket-ids (::store h))
        s (cond
            (uuid? ref) (str ref)
            (string? ref) (str/lower-case ref)
            :else (refuse ::invalid-id "a ticket reference is a uuid or a string" {:ref ref}))]
    (when-not (re-matches #"[0-9a-f-]{8,36}" s)
      (refuse ::invalid-id
              (str "a ticket reference is a uuid or a prefix of at least 8 of its characters: "
                   (pr-str ref))
              {:ref ref}))
    (let [hits (if (re-matches full-uuid s)
                 (filterv #(= s (str %)) ids)
                 (filterv #(str/starts-with? (str %) s) ids))]
      (case (count hits)
        0 (refuse ::unknown-ticket (str "no ticket " s) {:ref ref})
        1 (first hits)
        (refuse ::ambiguous-id (str s " names " (count hits) " tickets")
                {:ref ref :candidates (vec (sort-by str hits))})))))

(defn resolve-id
  "The uuid of the ticket `ref` names (see the namespace docstring)."
  [store ref]
  (let [h (handle! store)]
    (call #(resolve* h ref))))

(defn- events* [h id]
  (vec (red/ordered (store/events (::store h) id))))

(defn ticket
  "The events of the ticket `ref` names, in reduction order."
  [store ref]
  (let [h (handle! store)]
    (call #(events* h (resolve* h ref)))))

;; ------------------------------------------------------------- definitions

(defn- read-edn [h ^File f]
  (bounded-file f (:max-file-bytes (::limits h)))
  (try (canonical/parse (slurp f))
       (catch Exception e
         (refuse ::unreadable (str "malformed EDN in " f ": " (ex-message e))
                 {:file (str f)} e))))

(defn- process-name [state]
  (let [p (:process state)]
    (when-not (or (keyword? p) (string? p) (symbol? p))
      (refuse ::underivable (str "the ticket's process is not a name: " (pr-str p))
              {:process p}))
    (name p)))

(defn- load-definition
  "The definition a ticket derives under, found as `tik status` finds it:
  the archived bytes of its pin, else processes/<name>.edn, else the
  built-in track process. Reads only; the CLI materializes the built-in
  into the store, this never does."
  [h state]
  (let [root (::root h)
        pin (:process-hash state)
        archived (when (and (string? pin) (re-matches #"sha256-[0-9a-f]{64}" pin))
                   (io/file root "processes" "by-hash" (str pin ".edn")))
        pname (process-name state)
        named (when (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" pname)
                (io/file root "processes" (str pname ".edn")))]
    (cond
      (and archived (.exists archived)) {:process (read-edn h archived) :source :archive}
      (and named (.exists named)) {:process (read-edn h named) :source :named}
      (= "track" pname) {:process templates/track-process :source :builtin}
      :else (refuse ::definition-missing (str "no definition for process " pname)
                    {:process pname :pinned pin}))))

(defn- role-register [h]
  (let [f (io/file (::root h) "roles.edn")]
    (when (.exists f)
      (let [r (try (read-edn h f)
                   (catch clojure.lang.ExceptionInfo e
                     (refuse ::malformed-roles (str "malformed role register " f)
                             {:file (str f)} e)))]
        (when-not (process/valid-role-bindings? r)
          (refuse ::malformed-roles (str "malformed role register " f) {:file (str f)}))
        r))))

(defn- instant! [at]
  (cond
    (nil? at) (Instant/now)
    (instance? Instant at) at
    (inst? at) (Instant/ofEpochMilli (inst-ms at))
    :else (refuse ::invalid-argument ":at is an instant" {:at at})))

(defn- option-keys! [opts allowed]
  (when-not (or (nil? opts) (map? opts))
    (refuse ::invalid-argument "options are a map" {:opts opts}))
  (when-let [unknown (seq (remove allowed (keys opts)))]
    (refuse ::invalid-argument (str "unknown options " (vec unknown)) {:unknown (vec unknown)})))

(defn- derive* [h id {:keys [at allow-unpinned-definition]}]
  (let [t (instant! at)
        evs (store/events (::store h) id)
        state (red/ticket-state evs)
        {proc :process source :source} (load-definition h state)
        pin (:process-hash state)
        actual (process/process-hash proc)
        _ (when (and (not= pin actual) (not allow-unpinned-definition))
            (refuse ::definition-mismatch
                    (str "the definition found for ticket " id " hashes to " actual
                         ", not to the pinned " (pr-str pin))
                    {:ticket id :pinned pin :actual actual :source source}))
        roles (process/resolve-roles (:process/roles proc {}) (role-register h))
        reached (stage/effective-reached proc evs t roles)
        current (stage/current-stages proc reached)
        absent (dag/missing-parents evs)]
    {:ticket id
     :title (or (red/fact-value state [:title]) (:title state))
     :rules {:process (or (:process/id proc) (:process state))
             :version (or (:process/version proc) (:process-version state))
             :hash pin}
     :current (vec current)
     :reached (vec (sort-by str reached))
     :facts (into {} (for [[path _] (:facts state)
                           :when (not (or (= :link (first path)) (= [:title] path)))]
                       [path (select-keys (red/fact-status state path)
                                          [:status :value :by :note])]))
     :missing-parents (when (seq absent) (vec (sort absent)))
     :definition {:pinned pin :derived-under actual :source source}
     :at t}))

(defn derive
  "The derived view of the ticket `ref` names, at `(:at opts)` or now.
  Options: :at (an Instant), :allow-unpinned-definition (derive under a
  definition that does not hash to the pin, as `tik status` does with a
  warning; :definition says which one was used)."
  ([store ref] (derive store ref {}))
  ([store ref opts]
   (let [h (handle! store)]
     (option-keys! opts #{:at :allow-unpinned-definition})
     (call #(derive* h (resolve* h ref) opts)))))

;; ------------------------------------------------------------- verification

(def ^:private config-files
  "Files below the root the ladder reads whole."
  ["actors" "pq-signers" "signatures.edn" "oidc.edn" "roles.edn"])

(defn- check-config! [h]
  (let [limit (:max-file-bytes (::limits h))
        root (::root h)]
    (doseq [n config-files] (bounded-file (io/file root n) limit))
    (let [jwks (io/file root "jwks")]
      (when (.isDirectory jwks)
        (doseq [f (.listFiles jwks)] (bounded-file f limit))))))

(defn- event-summary [items e]
  (let [id (:event/id e)
        mine (filterv #(= id (:event %)) items)
        l0 (filterv #(= :l0 (:level %)) mine)
        sigs (filterv #(= :signature (:check %)) mine)
        verdicts (mapv :verdict sigs)]
    {:event id
     :type (:event/type e)
     :actor (:event/actor e)
     :at (:event/at e)
     :integrity (if (every? :ok? l0) :ok :failed)
     :signature (cond
                  (empty? sigs) :unsigned
                  (some #{:failed} verdicts) :failed
                  (some #{:unchecked} verdicts) :unchecked
                  (every? #{:verified} verdicts) :verified
                  :else :delegated)
     :signatures (mapv #(into {} (remove (comp nil? val))
                              (select-keys % [:sidecar :verdict :delegation]))
                       sigs)}))

(defn- verify* [h id {:keys [at]}]
  (let [t (instant! at)
        s (::store h)
        _ (check-config! h)
        _ (check-ticket! h id)
        items (verify/ticket-ladder
               {:root (::root h) :store s :db (::db h) :id id
                :registry-events (verify/registry-events s)
                :load-process #(:process (load-definition h %))
                :role-register #(role-register h)
                :now (constantly t)})
        state (red/ticket-state (store/events s id))
        pin (:process-hash state)
        items (into items
                    (when (and (string? pin) (re-matches #"sha256-[0-9a-f]{64}" pin))
                      (let [f (io/file (::root h) "processes" "by-hash" (str pin ".edn"))]
                        (when (.exists f)
                          (bounded-file f (:max-file-bytes (::limits h)))
                          (doseq [sig (sign/sidecars (.getParentFile f) pin)]
                            (bounded-file sig (:max-sidecar-bytes (::limits h))))
                          (verify/definition-items (::root h) pin)))))
        failed (verify/failures items)]
    {:ticket id
     :ok? (empty? failed)
     :failures (count failed)
     :events (mapv #(event-summary items %) (events* h id))
     :items items}))

(defn verify-ticket
  "The `tik verify` ladder for the ticket `ref` names, as data (see the
  namespace docstring). Options: :at, the verifier's clock (now by
  default)."
  ([store ref] (verify-ticket store ref {}))
  ([store ref opts]
   (let [h (handle! store)]
     (option-keys! opts #{:at})
     (call #(verify* h (resolve* h ref) opts)))))
