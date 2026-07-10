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
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'tik.main})))
