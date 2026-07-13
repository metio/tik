;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.main
  "The AOT entry point for the native binary. Its one job is closing
  the dynamic seams: the CLI reaches cheshire, http-client and httpkit
  via requiring-resolve (so babashka loads them lazily from its
  builtins), but GraalVM's closed world needs every namespace on the
  table at build time — required here, resolvable there."
  (:require [babashka.http-client]
            [cheshire.core]
            [org.httpkit.server]
            [tik.cli :as cli]
            [tik.mcp]
            [tik.store.sqlite-jdbc])
  (:gen-class))

(defn -main [& args]
  (apply cli/-main args)
  ;; httpkit's thread pool (tik serve) is non-daemon; every other
  ;; command must exit when -main returns instead of hanging on it
  (when-not (= "serve" (first args))
    (System/exit 0)))
