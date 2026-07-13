;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.backend-selection-test
  "A store's backend is DERIVED from its own shape (ADR 0020) — a tik.db
  means SQLite, a tickets/ tree means the file store — chosen at init and
  switched by `tik store migrate`. There is no ambient override, so
  working in one store never reroutes another. (These tests shell out to
  sqlite3, present in the devShell, like the store-contract tests.)"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tik.cli]
            [tik.harness :as h]))

(defn- uuid-in [s]
  (first (re-seq #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                 (str s))))

(deftest backend-derived-from-shape-not-env
  (let [{:keys [root]} (h/temp-store!)
        db-path @#'tik.cli/db-path]
    (h/with-cli-root root
      (fn []
        (testing "no tik.db -> the file store (db-path nil)"
          (is (nil? (db-path))))
        (testing "a tik.db beside the root -> the SQLite backend"
          (spit (io/file root "tik.db") "x")
          (is (= (str (io/file root "tik.db")) (db-path))))))
    (testing "both a tik.db and a tickets/ -> a clean refusal, not a guess"
      (.mkdirs (io/file root "tickets"))
      (h/with-cli-root root
        (fn []
          (let [r (tik.cli/run-argv ["ls"])]
            (is (= 1 (:exit r)))
            (is (re-find #"ambiguous" (:err r)))))))))

(deftest migrate-round-trips-events-and-verifies
  ;; the derivation must be identical across backends: migrate carries
  ;; every event, removes the source, and `verify` still passes.
  (let [{:keys [root]} (h/temp-store!)]
    (h/with-cli-root root
      (fn []
        (tik.cli/run-argv ["new" "track" "--title" "m"])
        (let [id (uuid-in (:out (tik.cli/run-argv ["ls" "--edn"])))]
          (tik.cli/run-argv ["set" id "note=hi"])
          (let [before (:out (tik.cli/run-argv ["ls" "--edn"]))]
            (testing "file -> sqlite"
              (let [r (tik.cli/run-argv ["store" "migrate" "--to" "sqlite"])]
                (is (zero? (:exit r)) (:err r))
                (is (.isFile (io/file root "tik.db")) "tik.db created")
                (is (not (.isDirectory (io/file root "tickets"))) "tickets/ removed")
                (is (= before (:out (tik.cli/run-argv ["ls" "--edn"])))
                    "the board derives identically from the sqlite store")
                (is (zero? (:exit (tik.cli/run-argv ["verify"]))))))
            (testing "sqlite -> file (already-there is refused)"
              (is (re-find #"already sqlite-backed"
                           (:err (tik.cli/run-argv ["store" "migrate" "--to" "sqlite"]))))
              (let [r (tik.cli/run-argv ["store" "migrate" "--to" "file"])]
                (is (zero? (:exit r)) (:err r))
                (is (.isDirectory (io/file root "tickets")) "tickets/ restored")
                (is (not (.isFile (io/file root "tik.db"))) "tik.db removed")
                (is (= before (:out (tik.cli/run-argv ["ls" "--edn"]))))
                (is (zero? (:exit (tik.cli/run-argv ["verify"]))))))))))))

(deftest migrate-to-sqlite-refuses-a-store-with-sidecars
  ;; the SQLite backend holds only event bytes; a file store's detached
  ;; signature/witness sidecars would be dropped, so the migration is
  ;; refused and the store is left untouched.
  (let [{:keys [root]} (h/temp-store!)]
    (h/with-cli-root root
      (fn []
        (tik.cli/run-argv ["new" "track" "--title" "s"])
        (let [id (uuid-in (:out (tik.cli/run-argv ["ls" "--edn"])))
              evdir (io/file root "tickets" id "events")]
          (spit (io/file evdir "sha256-deadbeef.sig.cafebabecafebabe") "detached-sig")
          (let [r (tik.cli/run-argv ["store" "migrate" "--to" "sqlite"])]
            (is (= 1 (:exit r)))
            (is (re-find #"sidecar" (:err r)))
            (is (.isDirectory (io/file root "tickets")) "the file store is untouched")
            (is (not (.isFile (io/file root "tik.db"))))))))))
