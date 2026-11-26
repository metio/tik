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

  Resources: the agent skill in the repository's `skills/` tree, served
  through the Skills extension (skills/list, skills/get, and
  resources/read under skill://), with a manifest signed by TIK_KEY; see
  tik.skills. A client without the extension sees the same files as
  ordinary resources, and the server instructions name the SKILL.md URI.

  Both eras of the protocol are spoken: the legacy initialize handshake,
  and the modern revisions whose every request carries its version and
  capabilities in `_meta` and is served statelessly.

  Run: TIK_ROOT=… TIK_ACTOR=agent-x TIK_KEY=… tik mcp
  (or `bb mcp` from the dev shell — the same loop on either runtime), or
  `tik serve --mcp-token-file F` for one shared server on /mcp."
  (:require [clojure.string :as str]
            [tik.cli :as cli]
            [tik.skills :as skills]))

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
;;
;; The agent's text never reaches the flag parser: flag values travel as
;; --flag=value, which is split once and never re-read, and positionals
;; follow a lone --, after which nothing is a flag. Otherwise a title of
;; "--edn" or a ticket argument of "--key=…" would be read as an option.
(defn- call-tool [name {:strs [ticket key value claim process title repo] :as args}]
  (let [json-as (fn [& flags] (concat flags [(str "--actor=" (actor)) "--format=json"]))]
    (case name
      "tik_new" (or (need args ["process" "title"])
                    (when (and (some? repo) (not (string? repo)))
                      {:err "repo must be a string"})
                    (apply tik "new"
                           (concat (json-as (str "--title=" title)
                                            (if (and (string? repo) (not (str/blank? repo)))
                                              (str "--repo=" repo)
                                              "--no-repo")
                                            "--published-only")
                                   ["--" process])))
      "tik_board" (tik "ls" "--format=json")
      "tik_explain" (or (need args ["ticket"])
                        (tik "explain" "--format=json" "--" ticket))
      "tik_actions" (or (need args ["ticket"])
                        (apply tik "agent" (concat (json-as) ["--" "actions" ticket])))
      "tik_assert" (or (need args ["ticket" "key" "value"])
                       (apply tik "agent" (concat (json-as) ["--" "set" ticket (str key "=" value)])))
      "tik_attest" (or (need args ["ticket" "claim"])
                       (apply tik "agent" (concat (json-as) ["--" "attest" ticket claim])))
      {:err (str "unknown tool " name)})))

(def ^:private protocol-versions
  "The legacy MCP revisions this server speaks (an initialize handshake);
  initialize answers in the client's revision when it is one of them,
  else in the newest."
  ["2025-06-18" "2025-03-26" "2024-11-05"])

(def ^:private modern-versions
  "The modern revisions this server speaks: every request carries its
  version, identity and capabilities in `_meta`, and no handshake comes
  first. Claude Code lists a server's skills only over one of these."
  ["2026-07-28"])

(def ^:private server-info {:name "tik" :version "1"})

(def ^:private capabilities
  {:tools {}
   :resources {}
   :extensions {skills/extension-id {}}})

(def ^:private instructions
  (str "tik serves its agent skill as MCP resources. Before working with a tik store, "
       "read skill://tik/SKILL.md with resources/read and resolve the files it names "
       "relative to skill://tik/. Each skill entry carries its manifest signature in "
       "_meta under " skills/meta-key "; `tik skill verify` checks it against the "
       "store's actors file."))

(def ^:private cache-hints {:ttlMs 300000 :cacheScope "public"})

(defn- result [id r] {:jsonrpc "2.0" :id id :result r})

(defn- error
  ([id code message] {:jsonrpc "2.0" :id id :error {:code code :message message}})
  ([id code message data] (assoc-in (error id code message) [:error :data] data)))

(defn- rpc-error!
  "Answer the request with a JSON-RPC error instead of a result."
  ([code message] (rpc-error! code message nil))
  ([code message data]
   (throw (ex-info message {::code code ::data data}))))

(defn- signer [] {:key (System/getenv "TIK_KEY") :actor (actor)})

(defn- skill-resource [skill uri {:keys [path bytes mime]}]
  (cond-> {:uri uri :name path :mimeType mime :size (count bytes)}
    (= uri (:uri skill)) (assoc :name (:skill skill)
                                :description (get (into {} (:frontmatter skill)) "description"))))

(defn- skills-list [params]
  (when (some? (get params "cursor"))
    (rpc-error! -32602 "invalid cursor: skills/list answers in one page"))
  (merge cache-hints
         {:resultType "complete"
          :skills (mapv #(skills/wire-entry % (signer)) skills/catalog)}))

(defn- skills-get [params]
  (let [uri (get params "uri")]
    (when-not (string? uri) (rpc-error! -32602 "skills/get needs a uri"))
    (if-let [skill (skills/by-uri skills/catalog uri)]
      (merge cache-hints {:resultType "complete" :skill (skills/wire-entry skill (signer))})
      (rpc-error! -32602 (str "no skill at " uri)))))

(defn- resources-list [_params]
  {:resources (vec (for [skill skills/catalog
                         {:keys [uri]} (:resources skill)]
                     (skill-resource skill uri (get (:files skill) uri))))})

(defn- resources-read [params]
  (let [uri (get params "uri")]
    (cond
      (not (string? uri)) (rpc-error! -32602 "resources/read needs a uri")
      (and (str/starts-with? uri "skill://") (nil? (skills/parse-uri uri)))
      (rpc-error! -32602 (str "invalid skill URI: " uri))
      :else
      (if-let [file (skills/file-at skills/catalog uri)]
        (merge cache-hints {:contents [(skills/read-contents uri file)]})
        (rpc-error! -32602 (str "no resource at " uri))))))

(defn- call-tool-result [params]
  (let [args (get params "arguments")
        {:keys [ok err]} (call-tool (get params "name")
                                    (if (map? args) args {}))]
    {:content [{:type "text" :text (or ok err)}]
     :isError (boolean err)}))

(def ^:private handlers
  "The methods both eras answer, by name, to the result of their params."
  {"tools/list" (fn [_] {:tools tools})
   "tools/call" call-tool-result
   "resources/list" resources-list
   "resources/templates/list" (fn [_] {:resourceTemplates []})
   "resources/read" resources-read
   "skills/list" skills-list
   "skills/get" skills-get
   "ping" (fn [_] {})})

(def ^:private meta-version "io.modelcontextprotocol/protocolVersion")
(def ^:private meta-capabilities "io.modelcontextprotocol/clientCapabilities")

(defn- header-value
  "A mirrored header value as the client meant it: the Base64 sentinel
  form `=?base64?…?=` decoded, anything else as sent."
  [v]
  (if-let [[_ b64] (and (string? v) (re-matches #"=\?base64\?([A-Za-z0-9+/=]*)\?=" v))]
    (try (String. (.decode (java.util.Base64/getDecoder) ^String b64) "UTF-8")
         (catch IllegalArgumentException _ ::undecodable))
    v))

(defn- check-headers!
  "The modern Streamable HTTP rule: the version, method and name the
  body carries must also stand in the headers, unchanged, so nothing
  routing on the headers sees another request than we execute."
  [headers method params version]
  (let [mismatch #(rpc-error! -32020 (str "header mismatch: " %))]
    (when-not (= version (get headers "mcp-protocol-version"))
      (mismatch "MCP-Protocol-Version must equal the request's protocol version"))
    (when-not (= method (get headers "mcp-method"))
      (mismatch "Mcp-Method must equal the request's method"))
    (when-let [field ({"tools/call" "name" "resources/read" "uri" "prompts/get" "name"} method)]
      (when-not (= (get params field) (header-value (get headers "mcp-name")))
        (mismatch (str "Mcp-Name must equal params." field))))))

(defn- modern-result
  "A modern result: complete, naming the server, whatever it answered."
  [r]
  (-> r
      (assoc :resultType (or (:resultType r) "complete"))
      (assoc-in [:_meta "io.modelcontextprotocol/serverInfo"] server-info)))

(defn- modern
  "One request of a modern revision, served statelessly: its own `_meta`
  says which version, client and capabilities it speaks, and nothing a
  previous request said is consulted. `headers` is nil on stdio."
  [id method params headers]
  (let [version (get-in params ["_meta" meta-version])]
    (when-not (some #{version} modern-versions)
      (rpc-error! -32022 "Unsupported protocol version"
                  {:supported modern-versions :requested version}))
    (when-not (map? (get-in params ["_meta" meta-capabilities]))
      (rpc-error! -32602 (str "the request's _meta needs " meta-capabilities)))
    (when headers (check-headers! headers method params version))
    (when id
      (cond
        (= "server/discover" method)
        (result id (modern-result (merge cache-hints
                                         {:supportedVersions modern-versions
                                          :capabilities capabilities
                                          :instructions instructions})))
        (contains? handlers method)
        (result id (modern-result ((get handlers method) params)))
        :else (rpc-error! -32601 (str "method not found: " method))))))

(defn- legacy [id method params]
  (case method
    "initialize"
    (result id {:protocolVersion (let [asked (get params "protocolVersion")]
                                   (if (some #{asked} protocol-versions)
                                     asked
                                     (first protocol-versions)))
                :capabilities capabilities
                :serverInfo server-info
                :instructions instructions})
    (if-let [f (get handlers method)]
      (result id (f params))
      (when id (result id {})))))

(def ^:private http-status
  "The HTTP status a modern error answers with; anything else is 200."
  {-32022 400 -32020 400 -32601 404})

(defn handle-message
  "{:response r :status s} for one JSON-RPC message: r is nil for a
  notification, s the HTTP status it calls for. A message whose `_meta`
  names a protocol version is a modern request; everything else,
  initialize first, is the legacy handshake era. Each message is fully
  isolated: one that is not JSON, not a map, or whose handling raises
  answers with a JSON-RPC error object, so the session survives whatever
  the client sends. `headers` (lower-cased) are the HTTP request's, nil
  on stdio."
  ([line] (handle-message line nil))
  ([line headers]
   (let [msg (try (json-parse line)
                  (catch Exception _ ::unparseable))]
     (if (or (= ::unparseable msg) (not (map? msg)))
       {:response (error nil -32700 "parse error: not a JSON object") :status 200}
       (let [{:strs [id method params]} msg
             params (if (map? params) params {})
             modern? (and (not= "initialize" method)
                          (some? (get-in params ["_meta" meta-version])))]
         (try
           {:response (if modern?
                        (modern id method params headers)
                        (legacy id method params))
            :status 200}
           (catch Throwable e
             (let [{::keys [code data]} (ex-data e)]
               (if code
                 {:response (if data (error id code (ex-message e) data) (error id code (ex-message e)))
                  :status (if modern?
                            (get http-status code
                                 (if (and (= -32602 code)
                                          (str/includes? (ex-message e) meta-capabilities))
                                   400 200))
                            200)}
                 {:response (error id -32603
                                   (str "internal error: "
                                        (or (ex-message e)
                                            (.getName (class e)))))
                  :status 200})))))))))

(defn handle
  "The response to one JSON-RPC message on stdio, or nil for a
  notification."
  [line]
  (:response (handle-message line)))

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
      (let [line (if (string? body) body (slurp (or body (java.io.ByteArrayInputStream. (byte-array 0)))))
            {:keys [response status]} (locking handle (handle-message line headers))]
        (if response
          {:status status :headers {"Content-Type" "application/json"} :body (json-emit response)}
          {:status 202 :headers {"Content-Type" "application/json"} :body ""})))))

(defn -main [& _]
  (loop []
    (when-let [line (read-line)]
      (when-let [response (handle line)]
        (println (json-emit response)))
      (recur))))
