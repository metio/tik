;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns build
  "Uberjar for the native binary: clojure -T:build uber"
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/tik.jar")

(defn uber [_]
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:native]})]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src" "cli"] :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :ns-compile '[tik.main]
                    :class-dir class-dir})
    ;; The skills join the class dir only after compilation: tik.skills
    ;; lists a skill's files from its directory at compile time, and
    ;; skills/tik copied in first would share target/classes/tik/ with
    ;; every tik namespace, so the listing would serve the sources too.
    ;; Compiled first, the listing sees skills/ alone (a basis path).
    (b/copy-dir {:src-dirs ["skills"] :target-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'tik.main})))
