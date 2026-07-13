;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store.sqlite-jdbc
  "The java.sql layer for the SQLite backend, split out so tik.store.sqlite
  LOADS on every runtime — babashka has no java.sql classes at all, so an
  `(:import (java.sql …))` there breaks the whole CLI. The Xerial driver
  (declared in deps.edn, force-required into the native image by tik.main)
  provides SQLite in-process, with its bundled native library embedded in
  the binary. babashka has neither java.sql nor the driver, so
  tik.store.sqlite resolves these functions lazily and fails well when
  they cannot be loaded (bb is the dev harness; SQLite is a native
  feature)."
  (:import (java.sql Connection DriverManager PreparedStatement SQLException)))

(defn- run
  "Open a connection to `db` and pass it to `f`, turning EVERY SQLException
  — opening the connection or running a statement — into a data-carrying
  ex-info. A missing driver is a distinct missing-capability message;
  anything else is a db error. Neither is a raw throw."
  [db f]
  (try
    (with-open [c (DriverManager/getConnection (str "jdbc:sqlite:" db))]
      (f c))
    (catch SQLException e
      (if (re-find #"(?i)no suitable driver" (str (.getMessage e)))
        (throw (ex-info (str "this store is SQLite-backed but no SQLite driver"
                             " is available on this runtime — use the native"
                             " tik binary, or a file-backed store"
                             " (`tik store migrate --to file`)")
                        {:reason :store/sqlite-unavailable} e))
        (throw (ex-info (str "SQLite error: " (.getMessage e))
                        {:reason :store/error} e))))))

(defn- bind! [^PreparedStatement ps params]
  (doseq [[i v] (map-indexed vector params)]
    (if (bytes? v)
      (.setBytes ps (int (inc i)) ^bytes v)
      (.setString ps (int (inc i)) (str v)))))

(defn query
  "Run a SELECT, returning rows as vectors of string columns — the shape
  every caller already parses (hex bytes, uuids, fixed-charset ids)."
  [db sql & params]
  (run db
       (fn [^Connection c]
         (with-open [ps (.prepareStatement c ^String sql)]
           (bind! ps params)
           (with-open [rs (.executeQuery ps)]
             (let [n (.getColumnCount (.getMetaData rs))]
               (loop [rows []]
                 (if (.next rs)
                   (recur (conj rows (mapv #(.getString rs (int %))
                                           (range 1 (inc n)))))
                   rows))))))))

(defn exec!
  "Run one DDL/DML statement with bound params."
  [db sql & params]
  (run db
       (fn [^Connection c]
         (with-open [ps (.prepareStatement c ^String sql)]
           (bind! ps params)
           (.executeUpdate ps)))))
