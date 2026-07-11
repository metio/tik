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

  Implementation shells out to the sqlite3 binary (in the devShell), so
  the same code runs under babashka and the JVM with zero new
  dependencies. Values cross the boundary as hex (X'…' in, hex() out):
  quoting-proof regardless of what strings an event body contains. Ids
  and ticket ids are fixed-charset and asserted before interpolation.

  The file store remains the auditor-grade interchange format
  (sha256sum(file) = filename needs files); `tik export` materializes
  any store as one."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.store.file :as fstore]
            [tik.store.protocol :as p]))

(defn hex->bytes ^bytes [^String h]
  (let [n (quot (count h) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte
                   (Integer/parseInt (subs h (* 2 i) (+ 2 (* 2 i))) 16))))
    out))

(defn- bytes->hex ^String [^bytes b]
  (str/join (map #(format "%02x" %) b)))

(defn- safe-id [s]
  (when-not (re-matches #"[A-Za-z0-9-]+" (str s))
    (throw (ex-info "unsafe identifier" {:value s})))
  (str s))

(defn- q!
  "Run one SQL string against the db; return rows as vectors of columns
  (pipe-separated is safe: every selected column is hex or fixed
  charset)."
  [db sql]
  (let [r (sh/sh "sqlite3" "-batch" "-noheader" (str db) sql)]
    (when-not (zero? (:exit r))
      (throw (ex-info "sqlite3 failed" {:sql sql :err (:err r)})))
    (->> (str/split-lines (:out r))
         (remove str/blank?)
         (mapv #(str/split % #"\|")))))

(defn- init! [db]
  (q! db (str "CREATE TABLE IF NOT EXISTS events ("
              "id TEXT PRIMARY KEY, ticket TEXT NOT NULL,"
              "bytes BLOB NOT NULL);"
              "CREATE INDEX IF NOT EXISTS events_ticket"
              " ON events(ticket);")))

(defn raw-rows
  "[[id hex-bytes] …] for verification: the stored bytes exactly as they
  are, so verify can recompute hash(bytes) = id without trusting this
  namespace's parsing."
  ([db] (q! db "SELECT id, hex(bytes) FROM events;"))
  ([db ticket-id]
   (q! db (str "SELECT id, hex(bytes) FROM events WHERE ticket='"
               (safe-id ticket-id) "';"))))

(defn- row->event
  "Rows are written only through append!, but a hostile or corrupted db
  can hold anything; garbage bytes fail WELL, naming the row's id."
  [[id hex]]
  (let [parsed (try (let [text (String. (hex->bytes hex) "UTF-8")]
                      (canonical/check-nesting text)
                      (edn/read-string {:readers fstore/edn-readers} text))
                    (catch Exception e
                      (throw (ex-info "unreadable event row"
                                      {:reason :event/unreadable :id id}
                                      e))))]
    (when-not (map? parsed)
      (throw (ex-info "event row does not hold an event map"
                      {:reason :event/unreadable :id id
                       :read (pr-str parsed)})))
    (assoc parsed :event/id id)))

(defrecord SqliteStore [db]
  p/EventStore
  (append! [_ event]
    (let [bytes (.getBytes (canonical/emit (dissoc event :event/id))
                           "UTF-8")]
      (q! db (str "INSERT OR IGNORE INTO events(id,ticket,bytes) VALUES('"
                  (safe-id (:event/id event)) "','"
                  (safe-id (:event/ticket event)) "',X'"
                  (bytes->hex bytes) "');"))
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
          (q! db "SELECT DISTINCT ticket FROM events;")))
  (has-event? [_ event-id]
    (boolean (seq (q! db (str "SELECT 1 FROM events WHERE id='"
                              (safe-id event-id) "';"))))))

(defn sqlite-store [db-path]
  (init! db-path)
  (->SqliteStore (str db-path)))
