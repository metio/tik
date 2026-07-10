;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mcp
  "An MCP server over stdio (babashka): the process definition as the
  agent's authorization boundary, spoken in the Model Context Protocol.

  Tools: tik_board, tik_explain, tik_actions, tik_assert, tik_attest.
  tik_assert/tik_attest are GATED — an action outside what the frontier
  admits for the agent's actor is refused with the admissible set, so
  boundary enforcement never depends on the prompt (H7's kill criterion
  says exactly that). The agent's actor comes from TIK_ACTOR; with
  TIK_KEY set every accepted action lands as a signed event — the
  accountability trail is the ticket itself (PLAN §12/§13).

  Run: TIK_ROOT=… TIK_ACTOR=agent-x TIK_KEY=… bb mcp"
  (:require [clojure.java.shell :as sh]))

;; cheshire ships inside babashka; resolved lazily so JVM tooling can
;; load this namespace without the dependency
(defn- json-parse [s] ((requiring-resolve 'cheshire.core/parse-string) s))
(defn- json-emit [x] ((requiring-resolve 'cheshire.core/generate-string) x))

(defn- tik [& args]
  (let [r (apply sh/sh "bb" "tik" (map str args))]
    (if (zero? (:exit r))
      {:ok (:out r)}
      {:err (str (:out r) (:err r))})))

(def ^:private tools
  [{:name "tik_board"
    :description "All open tickets with derived stages (EDN)."
    :inputSchema {:type "object" :properties {}}}
   {:name "tik_explain"
    :description "What evidence a ticket is missing, per frontier stage (EDN, ADR 0016 contract)."
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

(defn- call-tool [name {:strs [ticket key value claim]}]
  (case name
    "tik_board" (tik "ls" "--edn")
    "tik_explain" (tik "explain" ticket "--edn")
    "tik_actions" (tik "agent" "actions" ticket "--actor" (actor))
    "tik_assert" (tik "agent" "set" ticket (str key "=" value)
                      "--actor" (actor))
    "tik_attest" (tik "agent" "attest" ticket claim "--actor" (actor))
    {:err (str "unknown tool " name)}))

(defn- respond [id result]
  (println (json-emit
            {:jsonrpc "2.0" :id id :result result})))

(defn -main [& _]
  (loop []
    (when-let [line (read-line)]
      (let [{:strs [id method params]} (json-parse line)]
        (case method
          "initialize"
          (respond id {:protocolVersion "2024-11-05"
                       :capabilities {:tools {}}
                       :serverInfo {:name "tik" :version "1"}})
          "notifications/initialized" nil
          "tools/list" (respond id {:tools tools})
          "tools/call"
          (let [{:keys [ok err]} (call-tool (get params "name")
                                            (get params "arguments"))]
            (respond id {:content [{:type "text" :text (or ok err)}]
                         :isError (boolean err)}))
          (when id (respond id {})))
        (recur)))))
