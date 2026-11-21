;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.serve
  "tik serve: the live board over HTTP, read-only. Serves the board HTML
  (query/cmd-board), /tickets.edn (query/cmd-ls), and /explain/<id>.edn
  (inspect/cmd-explain) for tools — every response is a rendering of the
  same derivation, so nothing here is authoritative. With
  --mcp-token-file it also speaks MCP on /mcp (tik.mcp/http-response), the
  one writing surface, gated by the frontier like `tik mcp`. httpkit is
  resolved lazily so the namespace loads on babashka."
  (:require [clojure.string]
            [tik.cli-core :refer [die resolve-id-soft the-store]]
            [tik.inspect :as inspect]
            [tik.query :as query]))
(defn loopback-refusal
  "A refusal for a request a loopback-bound server must not answer, else
  nil. A web page can reach 127.0.0.1 by making its own host name resolve
  there (DNS rebinding); the browser then sends that name as Host and lets
  the page read the answer, which here is a private store's board. A server
  bound to loopback answers only requests that name it by a loopback
  address; one bound elsewhere was meant for other hosts and is not judged."
  [host port {:keys [headers]}]
  (let [loopback #{"127.0.0.1" "localhost" "::1" "[::1]"}]
    (when (and (loopback host)
               (not (contains? (set (for [h ["127.0.0.1" "localhost" "[::1]"]] (str h ":" port)))
                               (get headers "host"))))
      {:status 421
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "tik: this server answers only to its own loopback address\n"})))

(defn cmd-serve
  "serve [--port N] [--host H] [--mcp-token-file F] [--surfaces http,mcp]:
  the live board over HTTP, and MCP on /mcp. --surfaces names what to
  serve: http (the board and its EDN) unless given, plus mcp when a token
  file is; a surface left out answers 404. --host is the address it
  listens on: 127.0.0.1 unless given when MCP is served, since /mcp writes
  over plain HTTP, else 0.0.0.0. GET / renders
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
        token-file (:mcp-token-file opts)
        surfaces (if-let [given (:surfaces opts)]
                   (set (clojure.string/split (str given) #","))
                   (cond-> #{"http"} token-file (conj "mcp")))
        _ (when-let [unknown (seq (remove #{"http" "mcp"} surfaces))]
            (die (str "serve --surfaces knows http and mcp, not "
                      (clojure.string/join "," unknown))))
        _ (when (and (surfaces "mcp") (not token-file))
            (die "serve --surfaces mcp needs --mcp-token-file"))
        board? (contains? surfaces "http")
        ;; /mcp writes as TIK_ACTOR over plain HTTP, so without an explicit
        ;; --host it stays on this machine; the read-only board alone keeps
        ;; listening everywhere, as it always did.
        host (or (:host opts) (if (surfaces "mcp") "127.0.0.1" "0.0.0.0"))
        mcp (when-let [f (when (surfaces "mcp") token-file)]
              (let [token (clojure.string/trim (slurp (str f)))]
                (when (< (count token) 32)
                  (die "serve --mcp-token-file must hold a token of at least 32 characters"))
                {:token token :port port}))
        mcp-response (when mcp (requiring-resolve 'tik.mcp/http-response))
        handler
        ;; a server request must NEVER reach a die/System-exit path —
        ;; one hostile GET would take the board down for everyone. Ids
        ;; resolve softly (404, not exit) and everything else that
        ;; raises answers 500 with words, request-scoped
        (fn [{:keys [uri] :as request}]
          (try
            (cond
              (loopback-refusal host port request)
              (loopback-refusal host port request)

              (and mcp (= uri "/mcp"))
              (mcp-response mcp request)

              (not board?)
              {:status 404 :body "tik: not found\n"}

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
    (run-server handler {:port port :ip host})
    (println (str "tik serves on http://" host ":" port ":"
                  (when board? " the board (read-only)")
                  (when (and board? mcp) " and")
                  (when mcp " MCP on /mcp")
                  "; ctrl-c stops"))
    @(promise)))
