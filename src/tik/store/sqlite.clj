;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store.sqlite
  "SQLite-backed EventStore (ADR 0020): the single-file operational
  shape. The contract, honored literally:

  - events(id TEXT PRIMARY KEY, ticket TEXT, bytes BLOB): the bytes are
    the EXACT canonical hashed region, stored and returned verbatim —
    never a parsed-columns schema that re-serializes on read (that is a
    hash fork waiting to happen). The ticket column exists only as an
    index; reads always parse the bytes.
  - append is idempotent by id (INSERT OR IGNORE — union semantics).
  - reads are unordered (the reducer orders).

  The Xerial sqlite-jdbc driver is embedded (its bundled native library
  ships inside the native binary), so the shipped tik carries its own
  version-pinned SQLite with no external `sqlite3` on PATH. The java.sql
  layer lives in tik.store.sqlite-jdbc — babashka has no java.sql, so this
  namespace touches none of it and stays loadable everywhere, resolving
  the jdbc functions lazily (present on the JVM and in the native image,
  absent under bb → a clean runtime message). Event bytes cross as a bound
  BLOB parameter (the EXACT canonical hashed region), verify reads them
  back as hex; ids are fixed-charset asserted.

  The file store remains the auditor-grade interchange format
  (sha256sum(file) = filename needs files); `tik export` materializes
  any store as one."
  (:require [tik.canonical :as canonical]
            [tik.store.file :as fstore]
            [tik.store.protocol :as p]))

(defn hex->bytes ^bytes [^String h]
  (let [n (quot (count h) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte
                   (Integer/parseInt (subs h (* 2 i) (+ 2 (* 2 i))) 16))))
    out))

(defn- safe-id [s]
  (when-not (re-matches #"[A-Za-z0-9-]+" (str s))
    (throw (ex-info "unsafe identifier" {:value s})))
  (str s))

(defn- jdbc
  "Resolve a tik.store.sqlite-jdbc function at call time. It is present on
  the JVM and force-required into the native image; under babashka (no
  java.sql, no driver) the namespace cannot load, which IS the
  missing-capability answer — a clean message, not a raw throw."
  [sym]
  (or (try (requiring-resolve sym) (catch Throwable _ nil))
      (throw (ex-info (str "this store is SQLite-backed but no SQLite driver"
                           " is available on this runtime — use the native"
                           " tik binary, or a file-backed store"
                           " (`tik store migrate --to file`)")
                      {:reason :store/sqlite-unavailable}))))

(defn- query [db sql & params]
  (apply (jdbc 'tik.store.sqlite-jdbc/query) db sql params))

(defn- query-blob [db sql & params]
  (apply (jdbc 'tik.store.sqlite-jdbc/query-blob) db sql params))

(defn- exec! [db sql & params]
  (apply (jdbc 'tik.store.sqlite-jdbc/exec!) db sql params))

(defn- init! [db]
  (exec! db (str "CREATE TABLE IF NOT EXISTS events ("
                 "id TEXT PRIMARY KEY, ticket TEXT NOT NULL, bytes BLOB NOT NULL)"))
  (exec! db "CREATE INDEX IF NOT EXISTS events_ticket ON events(ticket)")
  ;; detached endorsements inline (the file store keeps them as sidecar
  ;; files): name is the sidecar filename (<id>.sig.<fpr> / <head>.witness
  ;; .<fpr>), globally unique, so it is the key; INSERT OR REPLACE keeps
  ;; put idempotent by name.
  (exec! db (str "CREATE TABLE IF NOT EXISTS sidecars ("
                 "name TEXT PRIMARY KEY, ticket TEXT NOT NULL, bytes BLOB NOT NULL)"))
  (exec! db "CREATE INDEX IF NOT EXISTS sidecars_ticket ON sidecars(ticket)"))

(defn raw-rows
  "[[id hex-bytes] …] for verification: the stored bytes exactly as they
  are, so verify can recompute hash(bytes) = id without trusting this
  namespace's parsing."
  ([db] (query db "SELECT id, hex(bytes) FROM events"))
  ([db ticket-id]
   (query db "SELECT id, hex(bytes) FROM events WHERE ticket=?"
          (safe-id ticket-id))))

(defn- row->event
  "Rows are written only through append!, but a hostile or corrupted db
  can hold anything; garbage bytes fail WELL, naming the row's id."
  [[id hex]]
  (fstore/parse-event (String. ^bytes (hex->bytes hex) "UTF-8") id {:id id}))

(defrecord SqliteStore [db]
  p/EventStore
  (append! [_ event]
    (let [bytes (.getBytes (canonical/emit (dissoc event :event/id))
                           "UTF-8")]
      (exec! db "INSERT OR IGNORE INTO events(id,ticket,bytes) VALUES(?,?,?)"
             (safe-id (:event/id event)) (safe-id (:event/ticket event)) bytes)
      event))
  (events [_ ticket-id]
    (mapv row->event (raw-rows db ticket-id)))
  (ticket-ids [_]
    (mapv (fn [[t]]
            (try (java.util.UUID/fromString t)
                 (catch Exception e
                   (throw (ex-info "ticket column does not hold a uuid"
                                   {:reason :store/corrupt :value t}
                                   e)))))
          (query db "SELECT DISTINCT ticket FROM events")))
  (has-event? [_ event-id]
    (boolean (seq (query db "SELECT 1 FROM events WHERE id=?"
                         (safe-id event-id)))))
  (event-bytes [_ _ticket-id event-id]
    (query-blob db "SELECT bytes FROM events WHERE id=?" (safe-id event-id)))
  (put-sidecar! [_ ticket-id name bytes]
    (exec! db "INSERT OR REPLACE INTO sidecars(name,ticket,bytes) VALUES(?,?,?)"
           name (safe-id ticket-id) bytes))
  (sidecar-names [_ ticket-id]
    (mapv first (query db "SELECT name FROM sidecars WHERE ticket=?"
                       (safe-id ticket-id))))
  (read-sidecar [_ ticket-id name]
    (query-blob db "SELECT bytes FROM sidecars WHERE ticket=? AND name=?"
                (safe-id ticket-id) name)))

(defn sqlite-store [db-path]
  (init! db-path)
  (->SqliteStore (str db-path)))

(defn read-only-db
  "The database spec that opens `db-path` read-only: an SQLite URI with
  mode=ro, so the connection can neither create the file nor change it.
  java.io.File/toURI percent-encodes the path, so a `?` or `#` in a
  directory name stays part of the path."
  [db-path]
  (str (.toURI (.getAbsoluteFile (java.io.File. (str db-path)))) "?mode=ro"))

(defn read-only-store
  "A SqliteStore over an existing `db-path` that never creates a table or
  writes a row: the connection itself is read-only (read-only-db)."
  [db-path]
  (->SqliteStore (read-only-db db-path)))

(defn sizes
  "What ticket `ticket-id` holds in `db`, as stored:
  {:events n :event-max bytes :event-total bytes
   :sidecars n :sidecar-max bytes :sidecar-total bytes}. Read before the
  rows themselves, so a caller can refuse a ticket larger than it will
  load."
  [db ticket-id]
  (let [row (fn [table]
              (let [[[n mx total]] (query db (str "SELECT count(*), coalesce(max(length(bytes)),0),"
                                                  " coalesce(sum(length(bytes)),0) FROM " table
                                                  " WHERE ticket=?")
                                          (safe-id ticket-id))]
                (mapv #(Long/parseLong (str %)) [n mx total])))
        [en emax etotal] (row "events")
        [sn smax stotal] (row "sidecars")]
    {:events en :event-max emax :event-total etotal
     :sidecars sn :sidecar-max smax :sidecar-total stotal}))
