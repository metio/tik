;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.serve
  "tik serve: the live board over HTTP, read-only. Serves the board HTML
  (query/cmd-board), /tickets.edn (query/cmd-ls), and /explain/<id>.edn
  (inspect/cmd-explain) for tools — every response is a rendering of the
  same derivation, so nothing here is authoritative. httpkit is resolved
  lazily so the namespace loads on babashka."
  (:require [tik.cli-core :refer [die resolve-id-soft the-store]]
            [tik.inspect :as inspect]
            [tik.query :as query]))
(defn cmd-serve
  "serve [--port N]: the live board over HTTP, read-only. GET / renders
  the same HTML as `tik board` — freshly derived per request, because
  derivation is cheap and caches lie eventually. /tickets.edn and
  /explain/<id>.edn expose the ADR 0016 data for tools. httpkit ships
  inside babashka: zero new dependencies, and READ-ONLY on purpose —
  writes stay with the signed CLI/bridge/MCP surfaces where authorship
  is enforced."
  [{:keys [opts]}]
  (let [run-server (requiring-resolve 'org.httpkit.server/run-server)
        port (if-let [p (:port opts)]
               (or (parse-long (str p))
                   (die (str "serve --port must be a number, got " p)))
               7777)
        handler
        ;; a server request must NEVER reach a die/System-exit path —
        ;; one hostile GET would take the board down for everyone. Ids
        ;; resolve softly (404, not exit) and everything else that
        ;; raises answers 500 with words, request-scoped
        (fn [{:keys [uri]}]
          (try
            (cond
              (= uri "/")
              {:status 200
               :headers {"Content-Type" "text/html; charset=utf-8"}
               :body (with-out-str (query/cmd-board {:pos []}))}

              (= uri "/tickets.edn")
              {:status 200
               :headers {"Content-Type" "application/edn"}
               :body (with-out-str
                       (query/cmd-ls {:opts {:edn true :all true}}))}

              (re-matches #"/explain/[0-9a-f-]+\.edn" uri)
              (let [prefix (second (re-matches #"/explain/([0-9a-f-]+)\.edn"
                                               uri))]
                (if-let [id (resolve-id-soft (the-store) prefix)]
                  {:status 200
                   :headers {"Content-Type" "application/edn"}
                   :body (with-out-str
                           (inspect/cmd-explain {:pos [(str id)] :opts {:edn true}}))}
                  {:status 404
                   :headers {"Content-Type" "text/plain; charset=utf-8"}
                   :body (str "tik: no unique ticket matching '" prefix
                              "'\n")}))

              :else {:status 404 :body "tik: not found\n"})
            (catch Throwable e
              {:status 500
               :headers {"Content-Type" "text/plain; charset=utf-8"}
               :body (str "tik: " (or (ex-message e)
                                      (.getName (class e))) "\n")})))]
    (run-server handler {:port port})
    (println (str "tik board live at http://127.0.0.1:" port
                  "  (read-only; ctrl-c stops)"))
    @(promise)))
