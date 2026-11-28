;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mcp-wire-test
  "The JSON-RPC wire of tik's MCP server: what every message answers,
  over stdio, through handle-message with HTTP headers, and over /mcp."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]
            [tik.harness :as h]
            [tik.mcp :as mcp])
  (:import (java.io BufferedReader ByteArrayInputStream StringReader)))

(def ^:private modern-meta
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientInfo" {"name" "test" "version" "1"}
   "io.modelcontextprotocol/clientCapabilities" {}})

(def ^:private limit (* 1024 1024))

(defn- wire
  "The response to `line` as a client reads it: written, then parsed."
  [line]
  (some-> (mcp/handle line) (#'mcp/json-emit) json/parse-string))

(defn- msg [m] (json/generate-string m))

(defn- request [method params & [id]]
  (msg (cond-> {"jsonrpc" "2.0" "id" (or id 1) "method" method}
         (some? params) (assoc "params" params))))

(defn- with-store [f] (h/with-cli-root (h/temp-dir! "tik-mcp-wire") f))

(defn- counting-tool-calls
  "f run with call-tool counted; [result calls]."
  [f]
  (let [calls (atom 0)
        real @#'mcp/call-tool]
    [(with-redefs-fn {#'mcp/call-tool (fn [& args] (swap! calls inc) (apply real args))} f)
     @calls]))

;; --------------------------------------------- behaviour that stays

(deftest initialize-answers-the-handshake
  (let [r (get (wire (request "initialize" {"protocolVersion" "2025-03-26"})) "result")]
    (is (= "2025-03-26" (get r "protocolVersion")))
    (is (= {"name" "tik" "version" "1"} (get r "serverInfo")))
    (is (= {} (get-in r ["capabilities" "tools"])))
    (is (str/includes? (get r "instructions") "skill://tik/SKILL.md")))
  (is (= "2025-06-18" (get-in (wire (request "initialize" {"protocolVersion" "1999-01-01"})) ["result" "protocolVersion"]))))

(deftest ping-answers-an-empty-result-in-both-eras
  (is (= {"jsonrpc" "2.0" "id" 7 "result" {}} (wire (request "ping" nil 7))))
  (let [r (wire (request "ping" {"_meta" modern-meta} 8))]
    (is (= 8 (get r "id")))
    (is (= "complete" (get-in r ["result" "resultType"])))
    (is (= {"name" "tik" "version" "1"} (get-in r ["result" "_meta" "io.modelcontextprotocol/serverInfo"])))))

(deftest tools-list-names-the-six-tools-and-nothing-else
  (let [tools (get-in (wire (request "tools/list" nil)) ["result" "tools"])]
    (is (= ["tik_board" "tik_explain" "tik_actions" "tik_new" "tik_assert" "tik_attest"]
           (mapv #(get % "name") tools)))
    (is (every? #(= #{"name" "description" "inputSchema"} (set (keys %))) tools))))

(deftest a-tool-answers-in-its-result
  (with-store
    (fn []
      (let [r (get (wire (request "tools/call" {"name" "tik_board" "arguments" {}})) "result")]
        (is (false? (get r "isError")))
        (is (= "text" (get-in r ["content" 0 "type"]))))
      (let [r (get (wire (request "tools/call" {"name" "tik_explain" "arguments" {}})) "result")]
        (is (true? (get r "isError")))
        (is (= "missing required argument(s): ticket" (get-in r ["content" 0 "text"])))))))

(deftest server-discover-answers-a-modern-client
  (let [r (get (wire (request "server/discover" {"_meta" modern-meta})) "result")]
    (is (= ["2026-07-28"] (get r "supportedVersions")))
    (is (= 300000 (get r "ttlMs")))
    (is (= "public" (get r "cacheScope")))
    (is (= "complete" (get r "resultType")))
    (is (str/includes? (get r "instructions") "skill://tik/SKILL.md"))))

(deftest modern-refusals-carry-their-status
  (testing "an unknown method"
    (let [{:keys [response status]} (mcp/handle-message (request "nope/nothing" {"_meta" modern-meta}))]
      (is (= 404 status))
      (is (= -32601 (get-in response [:error :code])))
      (is (= 1 (:id response)))))
  (testing "an unsupported revision"
    (let [{:keys [response status]} (mcp/handle-message
                                     (request "ping" {"_meta" (assoc modern-meta "io.modelcontextprotocol/protocolVersion" "2099-01-01")}))]
      (is (= 400 status))
      (is (= -32022 (get-in response [:error :code])))
      (is (= {:supported ["2026-07-28"] :requested "2099-01-01"} (get-in response [:error :data])))))
  (testing "headers that differ from the body"
    (let [line (request "tools/list" {"_meta" modern-meta})
          headers {"mcp-protocol-version" "2026-07-28" "mcp-method" "tools/call"}
          {:keys [response status]} (mcp/handle-message line headers)]
      (is (= 400 status))
      (is (= -32020 (get-in response [:error :code])))))
  (testing "matching headers, the name mirrored in the Base64 sentinel form"
    (let [line (request "resources/read" {"_meta" modern-meta "uri" "skill://tik/SKILL.md"})
          b64 (.encodeToString (java.util.Base64/getEncoder) (.getBytes "skill://tik/SKILL.md" "UTF-8"))
          headers {"mcp-protocol-version" "2026-07-28" "mcp-method" "resources/read"
                   "mcp-name" (str "=?base64?" b64 "?=")}
          {:keys [response status]} (mcp/handle-message line headers)]
      (is (= 200 status))
      (is (seq (get-in response [:result :contents]))))))

(deftest a-legacy-notification-writes-nothing
  (is (nil? (mcp/handle (msg {"jsonrpc" "2.0" "method" "notifications/initialized"})))))

(deftest a-message-up-to-one-mebibyte-is-read
  (let [head "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"p\":\""
        tail "\"}"
        pad (- limit (count head) (count tail))
        exact (str head (str/join (repeat pad "a")) tail)
        over (str head (str/join (repeat (inc pad) "a")) tail)]
    (is (= limit (count (.getBytes exact "UTF-8"))))
    (is (= {} (:result (mcp/handle exact))) "exactly the limit is read")
    (is (= -32700 (get-in (mcp/handle over) [:error :code])) "one byte more is refused")))

(deftest stdio-answers-one-line-per-request-and-survives-garbage
  (with-store
    (fn []
      (binding [*in* (BufferedReader.
                      (StringReader.
                       (str (request "initialize" {} 1) "\n"
                            "not json {{{\n"
                            (msg {"jsonrpc" "2.0" "method" "notifications/initialized"}) "\n"
                            (request "tools/list" nil 99) "\n")))]
        (let [{:keys [exit out]} (cli/run-argv ["mcp"])
              lines (remove str/blank? (str/split-lines out))]
          (is (zero? exit))
          (is (= 3 (count lines)) "the notification writes nothing")
          (is (= [1 nil 99] (mapv #(get (json/parse-string %) "id") lines)))
          (is (= -32700 (get-in (json/parse-string (second lines)) ["error" "code"]))))))))

;; ------------------------------------------- behaviour of briko.mcp

(deftest a-notification-runs-no-tool
  (with-store
    (fn []
      (let [[resp calls] (counting-tool-calls
                          #(mcp/handle (msg {"jsonrpc" "2.0" "method" "tools/call"
                                             "params" {"name" "tik_board" "arguments" {}}})))]
        (is (nil? resp) "a notification is never answered")
        (is (zero? calls) "and runs nothing")))))

(deftest an-unknown-method-is-not-found-in-the-legacy-era-too
  (let [r (wire (request "prompts/list" nil 5))]
    (is (= 5 (get r "id")))
    (is (= -32601 (get-in r ["error" "code"])))
    (is (nil? (get r "result")))))

(deftest an-unknown-tool-or-non-object-arguments-are-invalid-params
  (let [[r calls] (counting-tool-calls #(wire (request "tools/call" {"name" "no_such_tool"})))]
    (is (= -32602 (get-in r ["error" "code"])))
    (is (str/includes? (get-in r ["error" "message"]) "no_such_tool"))
    (is (zero? calls)))
  (is (= -32602 (get-in (wire (request "tools/call" {"name" "tik_explain" "arguments" "x"})) ["error" "code"])))
  (is (= -32602 (get-in (wire (request "tools/call" {"arguments" {}})) ["error" "code"]))))

(deftest a-malformed-envelope-is-an-invalid-request
  (testing "a JSON value that is not an object"
    (doseq [line ["[1,2,3]" "5" "null" "\"s\""]]
      (let [r (wire line)]
        (is (= -32600 (get-in r ["error" "code"])) line)
        (is (nil? (get r "id")) line))))
  (testing "no jsonrpc 2.0"
    (is (= -32600 (get-in (wire (msg {"id" 1 "method" "ping"})) ["error" "code"]))))
  (testing "a method that is not a string"
    (is (= -32600 (get-in (wire (msg {"jsonrpc" "2.0" "id" 1 "method" 9})) ["error" "code"]))))
  (testing "an id that is neither string nor integer is answered with a null id"
    (let [r (wire (msg {"jsonrpc" "2.0" "id" true "method" "ping"}))]
      (is (= -32600 (get-in r ["error" "code"])))
      (is (contains? r "id"))
      (is (nil? (get r "id")))))
  (testing "params that are not an object"
    (is (= -32602 (get-in (wire (msg {"jsonrpc" "2.0" "id" 1 "method" "ping" "params" [1]})) ["error" "code"])))))

(deftest a-parse-error-names-the-rule-and-quotes-no-input
  (let [marker (str "m" (random-uuid))]
    (doseq [[line rule] [[(str "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"" marker "\"}") "duplicate-key"]
                         [(str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" marker "\"} {}") "trailing-value"]
                         [(str "{\"" marker "\"") "malformed"]]]
      (let [r (wire line)]
        (is (= -32700 (get-in r ["error" "code"])) line)
        (is (= rule (get-in r ["error" "data" "rule"])) line)
        (is (str/starts-with? (get-in r ["error" "message"]) "parse error: ") line)
        (is (not (str/includes? (json/generate-string r) marker)) line)))))

(deftest stdio-refuses-an-over-long-line-and-reads-the-next
  (binding [*in* (BufferedReader.
                  (StringReader.
                   (str "{\"p\":\"" (str/join (repeat (+ limit 10) "a")) "\"}\n"
                        (request "ping" nil 2) "\n")))]
    (let [{:keys [out]} (cli/run-argv ["mcp"])
          [first-line second-line] (map json/parse-string (remove str/blank? (str/split-lines out)))]
      (is (= -32700 (get-in first-line ["error" "code"])))
      (is (= "too-large" (get-in first-line ["error" "data" "rule"])))
      (is (= 2 (get second-line "id")))
      (is (= {} (get second-line "result"))))))

(def ^:private config {:token (str/join (repeat 4 (str (random-uuid)))) :port 7777})

(defn- post [body]
  (mcp/http-response config {:request-method :post
                             :headers {"authorization" (str "Bearer " (:token config))}
                             :body body}))

(deftest an-http-body-over-the-limit-is-refused-without-reading-it-whole
  (let [bytes-read (atom 0)
        body (proxy [java.io.InputStream] []
               (read
                 ([] (swap! bytes-read inc) (int \a))
                 ([^bytes b] (java.util.Arrays/fill b (byte (int \a))) (swap! bytes-read + (alength b)) (alength b))
                 ([^bytes b off len]
                  (java.util.Arrays/fill b (int off) (int (+ off len)) (byte (int \a)))
                  (swap! bytes-read + len)
                  len)))
        {:keys [status] :as resp} (post body)
        parsed (json/parse-string (:body resp))]
    (is (= 200 status))
    (is (= -32700 (get-in parsed ["error" "code"])))
    (is (= "too-large" (get-in parsed ["error" "data" "rule"])))
    (is (<= @bytes-read (+ limit 65536)) "an endless body is read only past the limit")))

(deftest an-http-body-within-the-limit-is-answered
  (let [{:keys [status body]} (post (ByteArrayInputStream. (.getBytes ^String (request "ping" nil 3) "UTF-8")))]
    (is (= 200 status))
    (is (= {"jsonrpc" "2.0" "id" 3 "result" {}} (json/parse-string body)))))
