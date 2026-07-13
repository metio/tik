;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mcp
  "An MCP server over stdio: the process definition as the agent's
  authorization boundary, spoken in the Model Context Protocol.

  Tools: tik_board, tik_explain, tik_actions, tik_assert, tik_attest.
  tik_assert/tik_attest are GATED — an action outside what the frontier
  admits for the agent's actor is refused with the admissible set, so
  boundary enforcement never depends on the prompt (H7's kill criterion
  says exactly that). The agent's actor comes from TIK_ACTOR; with
  TIK_KEY set every accepted action lands as a signed event — the
  accountability trail is the ticket itself (PLAN §12/§13).

  Run: TIK_ROOT=… TIK_ACTOR=agent-x TIK_KEY=… tik mcp
  (or `bb mcp` from the dev shell — the same loop on either runtime)."
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
(defn- call-tool [name {:strs [ticket key value claim] :as args}]
  (case name
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

(defn- respond [id result]
  (println (json-emit
            {:jsonrpc "2.0" :id id :result result})))

(defn- respond-error [id code message]
  (println (json-emit
            {:jsonrpc "2.0" :id id
             :error {:code code :message message}})))

(defn- handle-line
  "One request, fully isolated: a line that is not JSON, not a map, or
  whose handling raises answers with a JSON-RPC error object — the
  session survives whatever the client sends."
  [line]
  (let [msg (try (json-parse line)
                 (catch Exception _ ::unparseable))]
    (if (or (= ::unparseable msg) (not (map? msg)))
      (respond-error nil -32700 "parse error: not a JSON object")
      (let [{:strs [id method params]} msg]
        (try
          (case method
            "initialize"
            (respond id {:protocolVersion "2024-11-05"
                         :capabilities {:tools {}}
                         :serverInfo {:name "tik" :version "1"}})
            "notifications/initialized" nil
            "tools/list" (respond id {:tools tools})
            "tools/call"
            (let [args (get params "arguments")
                  {:keys [ok err]} (call-tool (get params "name")
                                              (if (map? args) args {}))]
              (respond id {:content [{:type "text" :text (or ok err)}]
                           :isError (boolean err)}))
            (when id (respond id {})))
          (catch Throwable e
            (respond-error id -32603
                           (str "internal error: "
                                (or (ex-message e)
                                    (.getName (class e)))))))))))

(defn -main [& _]
  (loop []
    (when-let [line (read-line)]
      (handle-line line)
      (recur))))
