;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.corpus-test
  "The conformance corpus: directories of real event files plus expected
  derived results. Simultaneously the regression suite, the executable
  specification of the store format and derivation semantics, and the
  compatibility contract for any future second implementation. The corpus,
  not this Clojure, is the definition of tik."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [tik.canonical :as canonical]
            [tik.event :as event]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.io File)))

(deftest conformance-corpus
  (let [cases (filter #(.isDirectory ^File %)
                      (.listFiles (io/file "corpus")))]
    (is (seq cases) "corpus directories present")
    (doseq [^File dir cases]
      (testing (.getName dir)
        (let [expected (edn/read-string {:readers fstore/edn-readers}
                                        (slurp (io/file dir "expected.edn")))
              s (fstore/file-store (.getPath dir))
              tid (first (store/ticket-ids s))
              evs (store/events s tid)
              proc (edn/read-string
                    (slurp (io/file dir "processes"
                                    (str (name (:process expected)) ".edn"))))
              roles (:process/roles proc {})
              state (red/ticket-state evs)]
          ;; store-format fixture: exact canonical bytes,
          ;; filename = event id = content address (verify L0)
          (doseq [^File f (file-seq (io/file dir "tickets"))
                  :when (and (.isFile f) (.endsWith (.getName f) ".edn"))
                  :let [e (fstore/read-event f)]]
            (is (= (slurp f) (canonical/emit (dissoc e :event/id)))
                (str (.getName f) ": bytes are exactly the hashed region"))
            (is (= (str (event/event-id e) ".edn") (.getName f))
                "sha256(bytes) = filename = id"))
          ;; derivation semantics
          (is (= (:reached expected)
                 (stage/effective-reached proc evs (:now expected) roles)))
          (is (= (:current expected)
                 (stage/current-stages proc (:reached expected))))
          (is (= (:facts expected) (guard/fact-map state))))))))
