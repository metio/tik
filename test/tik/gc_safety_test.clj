;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.gc-safety-test
  "Formalizes the invariant that makes `tik gc` sound, so it cannot
  silently erode. An archived process definition that NO ticket
  currently pins — every ticket that once used it has migrated away — is
  NOT load-bearing: removing it leaves `tik verify` PASS and every
  CURRENT derivation byte-identical. Only historical time-travel
  (`status --at <before the migration>`) degrades, and gracefully.

  This is a deliberate design choice (verify derives CURRENT state under
  the CURRENT pin, ADR 0002), not an accident. A future change that made
  verify re-derive full history under as-of pins would make orphans
  load-bearing — and would break this test, which is the whole point:
  the possibility of GC is now a tested contract."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private repo (System/getProperty "user.dir"))

(def ^:private v1
  (str "{:process/id :gct :process/version 1 :process/guard-vocab 1"
       " :lint {:runbooks :off}"
       " :process/stages [{:stage/id :a :guards []}]}"))

(def ^:private v2
  (str "{:process/id :gct :process/version 2 :process/guard-vocab 1"
       " :lint {:runbooks :off}"
       " :process/facts {[:x] :string}"
       " :process/stages [{:stage/id :a :guards []}"
       "                  {:stage/id :b :after [:a] :guards [[:fact [:x]]]}]}"))

(defn- tik [store & args]
  (apply sh/sh (concat ["bb" "--config" (str repo "/bb.edn") "tik"]
                       (map str args)
                       [:dir (str store)
                        :env (assoc (into {} (System/getenv))
                                    "TIK_ROOT" (str store)
                                    "TIK_ACTOR" "seb"
                                    "NO_COLOR" "1")])))

(deftest orphaned_definitions_are_collectable_without_touching_verify
  (let [store (.toFile (Files/createTempDirectory
                        "tik-gc" (make-array FileAttribute 0)))
        procs (doto (io/file store "processes") .mkdirs)]
    (.mkdirs (io/file store "tickets"))
    ;; a ticket under v1, then the named file moves on to v2 and the
    ;; ticket migrates — exactly the metio renovate flow that orphaned v1
    (spit (io/file procs "gct.edn") v1)
    (let [id (str/trim (:out (tik store "new" "gct" "--title" "x")))]
      (spit (io/file procs "gct.edn") v2)
      (is (zero? (:exit (tik store "migrate" id "processes/gct.edn" "--apply")))
          "migrate v1 -> v2 succeeds")

      (testing "gc names the orphaned v1 definition, dry-run by default"
        (let [r (tik store "gc")]
          (is (re-find #"orphaned definition" (:out r)))
          (is (re-find #"dry run" (:out r)))))

      (let [verify-before (:out (tik store "verify"))
            status-before (:out (tik store "status" id))
            by-hash (io/file store "processes" "by-hash")
            archives-before (count (.listFiles by-hash))]
        (is (re-find #"verify: PASS" verify-before))
        (is (= 2 archives-before) "both v1 and v2 are archived")

        (testing "gc --apply removes the orphan"
          (let [r (tik store "gc" "--apply")]
            (is (re-find #"removed 1 definition" (:out r))))
          (is (= 1 (count (.listFiles by-hash)))
              "one archive (the live v2) remains"))

        (testing "verify is UNCHANGED after collecting the orphan"
          (is (re-find #"verify: PASS" (:out (tik store "verify")))))

        (testing "current derivation is byte-identical after collection"
          (is (= status-before (:out (tik store "status" id)))
              "the ticket derives exactly the same with v1 gone"))

        (testing "a second gc finds nothing — v2 is live"
          (is (re-find #"no orphaned definitions" (:out (tik store "gc")))))))))
