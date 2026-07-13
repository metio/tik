;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.backend-selection-test
  "A store's backend is DERIVED from its own shape (ADR 0020) — a tik.db
  means SQLite, a tickets/ tree means the file store — chosen at init and
  switched by `tik store migrate`. There is no ambient override, so
  working in one store never reroutes another. (These tests shell out to
  sqlite3, present in the devShell, like the store-contract tests.)"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
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

(deftest export-carries-the-signature-sidecars
  ;; `tik export` is the auditor-grade interchange format — it must carry
  ;; the detached signatures/witnesses, or the exported store cannot verify
  ;; authorship of anything (verify would only note "unsigned").
  (let [{:keys [root]} (h/temp-store!)
        keyf (io/file root "id_ed25519")
        _ (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" (str keyf))
        pub (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str keyf))))]
    (spit (io/file root "actors") (str "seb namespaces=\"tik-*\" " pub "\n"))
    (h/with-cli-root root
      (fn []
        (tik.cli/run-argv ["new" "track" "--title" "e" "--actor" "seb" "--key" (str keyf)])
        (let [id (uuid-in (:out (tik.cli/run-argv ["ls" "--edn"])))
              dst (str (io/file root "exp"))]
          (tik.cli/run-argv ["set" id "note=hi" "--actor" "seb" "--key" (str keyf)])
          (let [r (tik.cli/run-argv ["export" dst])]
            (is (zero? (:exit r)) (:err r))
            (is (re-find #"[1-9]\d* sidecar" (:out r)) "reports carrying sidecars")
            (is (seq (filter #(str/includes? (.getName ^java.io.File %) ".sig.")
                             (file-seq (io/file dst))))
                "the exported store holds the signature sidecar files")))))))

(deftest cache-horizon-set-for-elapsed-since-inside-or
  ;; a stage gated by [:or [:fact …] [:elapsed-since …]] is time-DEPENDENT;
  ;; compute-row must set a valid-until (the due) so the board does not
  ;; cache a stale pre-due row past the gate. The :due sits nested in the
  ;; :or's :options and must still be collected.
  (let [{:keys [root]} (h/temp-store!)]
    (.mkdirs (io/file root "processes"))
    (spit (io/file root "processes" "grace.edn")
          (str "{:process/id :grace :process/version 1 :process/guard-vocab 2"
               " :process/stages [{:stage/id :open :guards []}"
               " {:stage/id :done :after [:open]"
               "  :guards [[:or [:fact [:override]]"
               "                 [:elapsed-since :ticket/create \"PT24H\"]]]}]}"))
    (h/with-cli-root root
      (fn []
        (tik.cli/run-argv ["new" "grace" "--title" "g"])
        (let [id (java.util.UUID/fromString
                  (uuid-in (:out (tik.cli/run-argv ["ls" "--edn"]))))
              compute-row @#'tik.cli/compute-row
              the-store @#'tik.cli/the-store]
          (is (number? (:valid-until (compute-row (the-store)
                                                  (java.time.Instant/now) id)))
              "a time-gated row expires at its due, not cached forever (nil)"))))))

(deftest signatures-verify-on-either-backend-and-survive-migration
  ;; a signature endorses the stored bytes, not a file — so it must verify
  ;; on the SQLite backend as well as the file store, and migration must
  ;; carry the sidecars losslessly (both directions), verify still green.
  (let [{:keys [root]} (h/temp-store!)
        keyf (io/file root "id_ed25519")
        _ (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" (str keyf))
        pub (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str keyf))))]
    (spit (io/file root "actors") (str "seb namespaces=\"tik-*\" " pub "\n"))
    (h/with-cli-root root
      (fn []
        (tik.cli/run-argv ["new" "track" "--title" "signed"
                           "--actor" "seb" "--key" (str keyf)])
        (let [id (uuid-in (:out (tik.cli/run-argv ["ls" "--edn"])))
              k (str keyf)]
          (tik.cli/run-argv ["set" id "note=hi" "--actor" "seb" "--key" k])
          (testing "file store: the signed events verify as seb"
            (let [r (tik.cli/run-argv ["verify" id])]
              (is (zero? (:exit r)) (:err r))
              (is (re-find #"signed by seb" (:out r)))))
          (testing "migrate to SQLite carries the signatures; verify still green"
            (let [m (tik.cli/run-argv ["store" "migrate" "--to" "sqlite"])]
              (is (zero? (:exit m)) (:err m))
              (is (re-find #"sidecar" (:out m)))
              (is (.isFile (io/file root "tik.db"))))
            (let [r (tik.cli/run-argv ["verify" id])]
              (is (zero? (:exit r)) (:err r))
              (is (re-find #"signed by seb" (:out r)))))
          (testing "and back to the file store, still verifying"
            (is (zero? (:exit (tik.cli/run-argv ["store" "migrate" "--to" "file"]))))
            (let [r (tik.cli/run-argv ["verify" id])]
              (is (zero? (:exit r)) (:err r))
              (is (re-find #"signed by seb" (:out r))))))))))
