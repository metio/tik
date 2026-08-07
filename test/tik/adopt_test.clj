;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.adopt-test
  "Adopting a process from a library: the definition's identity always
  travels, and the publisher's signature over it travels only when this
  store can already verify one — because `verify` fails a definition
  signed by nobody it recognizes, and handing back a store that fails its
  own audit is worse than arriving without the signature."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.adopt :as adopt]
            [tik.args :as args]
            [tik.canonical :as canonical]
            [tik.harness :as h]
            [tik.process :as process])
  (:import (java.io File)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- library!
  "A miniature process library: a definition, its archived canonical
  bytes, a publication signature over them, and the `actors` naming the
  publisher — the shape tik-processes publishes."
  []
  (let [root (h/temp-dir! "tik-library")
        key (io/file root "publisher")
        _ (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "pub"
                 "-f" (str key))
        src (io/file repo "processes/track.edn")
        proc (args/read-edn-file src)
        hash (process/process-hash proc)
        by-hash (doto (io/file root "processes" "by-hash") .mkdirs)
        archived (io/file by-hash (str hash ".edn"))]
    (io/copy src (io/file (doto (io/file root "processes") (.mkdirs))
                          "track.edn"))
    (spit archived (canonical/emit proc))
    (sh/sh "ssh-keygen" "-Y" "sign" "-f" (str key) "-n" "tik-process"
           (str archived))
    (.renameTo (io/file (str archived ".sig"))
               (io/file by-hash (str hash ".sig.deadbeef")))
    (spit (io/file root "actors")
          (str "publisher namespaces=\"tik-*\" "
               (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str key))))
               "\n"))
    {:root root :hash hash :definition proc
     :source (io/file root "processes" "track.edn")}))

(deftest the_definition_travels_and_the_signature_waits_for_trust
  (let [lib (library!)
        store (h/temp-dir! "tik-adopter")
        by-hash (io/file store "processes" "by-hash")
        sig-name (str (:hash lib) ".sig.deadbeef")]
    (testing "an adopter who does not know the publisher gets the rules only"
      (let [r (adopt/adopt-publication! (:definition lib)
                                        (io/file (:root lib)) store)]
        (is (:archived? r))
        (is (zero? (:signatures r)))
        (is (= 1 (:unverifiable r))
            "and is told a signature exists rather than left guessing")
        (is (.isFile (io/file by-hash (str (:hash lib) ".edn"))))
        (is (not (.exists (io/file by-hash sig-name)))
            "a signature no registered key verifies would fail this store's
             own `tik verify`, so it stays behind")))

    (testing "once the publisher is registered, the signature comes across"
      (io/copy (io/file (:root lib) "actors") (io/file store "actors"))
      (let [r (adopt/adopt-publication! (:definition lib)
                                        (io/file (:root lib)) store)]
        (is (= 1 (:signatures r)))
        (is (zero? (:unverifiable r)))
        (is (.isFile (io/file by-hash sig-name)))))))

(deftest a_forged_publication_signature_never_travels
  ;; the check is a real verification, not the presence of a file
  (let [lib (library!)
        store (h/temp-dir! "tik-adopter-forged")
        ^File sig (io/file (:root lib) "processes" "by-hash"
                           (str (:hash lib) ".sig.deadbeef"))]
    (io/copy (io/file (:root lib) "actors") (io/file store "actors"))
    (spit sig (str/replace (slurp sig) #"[A-Za-z]" "x"))
    (let [r (adopt/adopt-publication! (:definition lib)
                                      (io/file (:root lib)) store)]
      (is (zero? (:signatures r)))
      (is (= 1 (:unverifiable r)))
      (is (not (.exists (io/file store "processes" "by-hash"
                                 (str (:hash lib) ".sig.deadbeef"))))))))

(deftest a_library_without_an_archive_adopts_as_before
  (let [root (h/temp-dir! "tik-plain-library")
        src (io/file repo "processes/track.edn")
        proc (args/read-edn-file src)]
    (io/copy src (io/file (doto (io/file root "processes") (.mkdirs))
                          "track.edn"))
    (is (= {:archived? false}
           (adopt/adopt-publication! proc root (h/temp-dir! "tik-adopter-2"))))))
