;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.main
  "The AOT entry point for the native binary. Its one job is closing
  the dynamic seams: the CLI reaches cheshire, http-client and httpkit,
  and briko.json reaches cheshire.core and cheshire.factory, via
  requiring-resolve (so babashka loads them lazily from its builtins),
  but GraalVM's closed world needs every namespace on the table at build
  time — required here, resolvable there."
  (:require [babashka.http-client]
            [briko.http]
            [briko.json]
            [cheshire.core]
            [cheshire.factory]
            [org.httpkit.server]
            [tik.backend]
            [tik.cli :as cli]
            [tik.cli-core :refer [process-exit!]]
            [tik.mcp]
            [tik.notifier]
            [tik.process-tree]
            [tik.serve]
            [tik.skill-verify]
            [tik.store.sqlite-jdbc])
  (:gen-class))

(defn -main [& args]
  (apply cli/-main args)
  ;; httpkit's thread pool (tik serve) is non-daemon; every other
  ;; command must exit when -main returns instead of hanging on it
  (if (= "serve" (first args))
    (flush)
    (process-exit! 0)))
