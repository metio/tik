;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mcp
  "An MCP server over stdio, and over HTTP inside `tik serve`: the process
  definition as the agent's authorization boundary, spoken in the Model
  Context Protocol.

  Tools: tik_board, tik_explain, tik_actions, tik_new, tik_assert,
  tik_attest. tik_new mints only under a definition somebody other than
  the agent published, and takes the ticket's repository as an argument:
  the server's working directory says nothing about the ticket, so it is
  never read.
  tik_assert/tik_attest are GATED — an action outside what the frontier
  admits for the agent's actor is refused with the admissible set, so
  boundary enforcement never depends on the prompt (H7's kill criterion
  says exactly that). The agent's actor comes from TIK_ACTOR; with
  TIK_KEY set every accepted action lands as a signed event — the
  accountability trail is the ticket itself (PLAN §12/§13).

  Run: TIK_ROOT=… TIK_ACTOR=agent-x TIK_KEY=… tik mcp
  (or `bb mcp` from the dev shell — the same loop on either runtime), or
  `tik serve --mcp-token-file F` for one shared server on /mcp."
  (:require [clojure.string :as str]
            [tik.cli :as cli]))

;; cheshire is resolved lazily so JVM tooling can load this namespace
;; without the dependency; babashka ships it as a builtin, and the
;; native image force-requires cheshire.core in tik.main, so the resolve
;; succeeds on both runtimes.
(defn- json-parse [s] ((requiring-resolve 'cheshire.core/parse-string) s))
(defn- json-emit [x] ((requiring-resolve 'cheshire.core/generate-string) x))

(defn- tik
  "Run a tik CLI invocation IN-PROCESS (cli/run-argv): same dispatch the
  binary uses, output captured, exit trapped — no subprocess, so an MCP
  tool call costs a function call, not a fresh babashka startup."
  [& args]
  (let [{:keys [exit out err]} (cli/run-argv (mapv str args))]
    (if (zero? exit)
      {:ok out}
      {:err (str out err)})))

(def ^:private tools
  [{:name "tik_board"
    :description "All open tickets with derived stages (JSON)."
    :inputSchema {:type "object" :properties {}}}
   {:name "tik_explain"
    :description "What evidence a ticket is missing, per frontier stage (JSON, ADR 0016 contract)."
    :inputSchema {:type "object"
                  :properties {:ticket {:type "string"}}
                  :required ["ticket"]}}
   {:name "tik_actions"
    :description "The actions the frontier admits for THIS agent on a ticket — the authorization boundary, derived."
    :inputSchema {:type "object"
                  :properties {:ticket {:type "string"}}
                  :required ["ticket"]}}
   {:name "tik_new"
    :description "Mint a ticket of a process, signed as this agent. Refused unless the process definition is published (signed) by somebody other than this agent. Name the git repository directly below the store that the work happens in as repo; omit it for work without one. Returns the new id, the context facts it inherited and similar open tickets (JSON)."
    :inputSchema {:type "object"
                  :properties {:process {:type "string"}
                               :title {:type "string"}
                               :repo {:type "string"}}
                  :required ["process" "title"]}}
   {:name "tik_assert"
    :description "Assert a fact (dotted key, EDN value). Refused unless the frontier admits it for this agent."
    :inputSchema {:type "object"
                  :properties {:ticket {:type "string"}
                               :key {:type "string"}
                               :value {:type "string"}}
                  :required ["ticket" "key" "value"]}}
   {:name "tik_attest"
    :description "Record an attestation claim (EDN). Refused unless admitted."
    :inputSchema {:type "object"
                  :properties {:ticket {:type "string"}
                               :claim {:type "string"}}
                  :required ["ticket" "claim"]}}])

(defn- actor [] (or (System/getenv "TIK_ACTOR") "agent"))

(defn- need
  "The named string arguments, or an :err when any is blank/missing —
  a required argument the client omitted is a client error, answered
  with words, never a shell-out with an empty placeholder."
  [args ks]
  (let [missing (remove #(let [v (get args %)]
                           (and (string? v) (not (str/blank? v))))
                        ks)]
    (when (seq missing)
      {:err (str "missing required argument(s): "
                 (str/join ", " missing))})))

;; every tool speaks JSON: an MCP client is a JSON transport, so the tool
;; payload is JSON too (--format json), not tik's native EDN.
(defn- call-tool [name {:strs [ticket key value claim process title repo] :as args}]
  (case name
    "tik_new" (or (need args ["process" "title"])
                  (when (and (some? repo) (not (string? repo)))
                    {:err "repo must be a string"})
                  (apply tik "new" process "--title" title
                         (concat (if (and (string? repo) (not (str/blank? repo)))
                                   ["--repo" repo]
                                   ["--no-repo"])
                                 ["--published-only" "--actor" (actor) "--format" "json"])))
    "tik_board" (tik "ls" "--format" "json")
    "tik_explain" (or (need args ["ticket"])
                      (tik "explain" ticket "--format" "json"))
    "tik_actions" (or (need args ["ticket"])
                      (tik "agent" "actions" ticket "--actor" (actor)
                           "--format" "json"))
    "tik_assert" (or (need args ["ticket" "key" "value"])
                     (tik "agent" "set" ticket (str key "=" value)
                          "--actor" (actor) "--format" "json"))
    "tik_attest" (or (need args ["ticket" "claim"])
                     (tik "agent" "attest" ticket claim
                          "--actor" (actor) "--format" "json"))
    {:err (str "unknown tool " name)}))

(def ^:private protocol-versions
  "The MCP revisions this server speaks; initialize answers in the
  client's revision when it is one of them, else in the newest."
  ["2025-06-18" "2025-03-26" "2024-11-05"])

(defn- result [id r] {:jsonrpc "2.0" :id id :result r})

(defn- error [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn handle
  "The response to one JSON-RPC message, or nil for a notification. Each
  message is fully isolated: one that is not JSON, not a map, or whose
  handling raises answers with a JSON-RPC error object, so the session
  survives whatever the client sends."
  [line]
  (let [msg (try (json-parse line)
                 (catch Exception _ ::unparseable))]
    (if (or (= ::unparseable msg) (not (map? msg)))
      (error nil -32700 "parse error: not a JSON object")
      (let [{:strs [id method params]} msg]
        (try
          (case method
            "initialize"
            (result id {:protocolVersion (let [asked (get params "protocolVersion")]
                                           (if (some #{asked} protocol-versions)
                                             asked
                                             (first protocol-versions)))
                        :capabilities {:tools {}}
                        :serverInfo {:name "tik" :version "1"}})
            "tools/list" (result id {:tools tools})
            "tools/call"
            (let [args (get params "arguments")
                  {:keys [ok err]} (call-tool (get params "name")
                                              (if (map? args) args {}))]
              (result id {:content [{:type "text" :text (or ok err)}]
                          :isError (boolean err)}))
            (when id (result id {})))
          (catch Throwable e
            (error id -32603
                   (str "internal error: "
                        (or (ex-message e)
                            (.getName (class e)))))))))))

(defn- token-matches? [authorization token]
  (and (string? authorization)
       (java.security.MessageDigest/isEqual
        (.getBytes ^String authorization "UTF-8")
        (.getBytes (str "Bearer " token) "UTF-8"))))

(defn- plain [status body & [headers]]
  {:status status
   :headers (merge {"Content-Type" "text/plain; charset=utf-8"} headers)
   :body body})

(defn http-response
  "The response to an HTTP request on /mcp: MCP's streamable HTTP
  transport in its request-response form, one JSON-RPC message per POST
  answered with JSON and no event stream. `headers` are lower-cased, as
  httpkit hands them over.

  The calls write as TIK_ACTOR, so the bearer token is what stands
  between anything that can reach the port and that actor's signature. A
  request carrying an Origin other than the server's own is refused, so a
  web page cannot drive the server through DNS rebinding. Calls run one
  at a time: the CLI they reuse was written for one invocation per
  process."
  [{:keys [token port]} {:keys [request-method headers body]}]
  (let [origin (get headers "origin")]
    (cond
      (and origin (not (#{(str "http://127.0.0.1:" port) (str "http://localhost:" port)} origin)))
      (plain 403 "tik: foreign origin\n")

      (not (token-matches? (get headers "authorization") token))
      (plain 401 "tik: a bearer token is required\n" {"WWW-Authenticate" "Bearer"})

      (not= :post request-method)
      (plain 405 "" {"Allow" "POST"})

      :else
      (let [line (if (string? body) body (slurp (or body (java.io.ByteArrayInputStream. (byte-array 0)))))]
        (if-let [response (locking handle (handle line))]
          {:status 200 :headers {"Content-Type" "application/json"} :body (json-emit response)}
          {:status 202 :headers {"Content-Type" "application/json"} :body ""})))))

(defn -main [& _]
  (loop []
    (when-let [line (read-line)]
      (when-let [response (handle line)]
        (println (json-emit response)))
      (recur))))
