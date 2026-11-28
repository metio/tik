;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mcp-skills-test
  "The Skills extension over tik's MCP server, in both protocol eras, and
  `tik skill verify` against it over HTTP."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]
            [tik.harness :as h]
            [tik.mcp :as mcp]
            [tik.skills :as skills])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def ^:private modern-meta
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientInfo" {"name" "test" "version" "1"}
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- rpc
  "Send one message through the stdio handler; the parsed response."
  [method params & [id]]
  (some-> (mcp/handle (json/generate-string {"jsonrpc" "2.0" "id" (or id 1) "method" method "params" params}))
          json/generate-string
          json/parse-string))

(defn- modern [method params]
  (rpc method (assoc params "_meta" modern-meta)))

(defn- unsigned [f]
  (with-redefs-fn {#'mcp/signer (constantly {:key nil :actor "agent"})} f))

(defn- sha256 [^bytes b]
  (str "sha256:" (str/join (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") b)))))

(def ^:private skill-md (java.nio.file.Files/readAllBytes (.toPath (io/file "skills" "tik" "SKILL.md"))))

;; ----------------------------------------------------- legacy clients

(deftest initialize-declares-the-skills-extension-next-to-resources
  (let [r (get (rpc "initialize" {"protocolVersion" "2025-06-18"}) "result")]
    (is (= {} (get-in r ["capabilities" "resources"])))
    (is (= {} (get-in r ["capabilities" "extensions" "io.modelcontextprotocol/skills"])))
    (is (= {} (get-in r ["capabilities" "tools"])) "tools stay")
    (is (str/includes? (get r "instructions") "skill://tik/SKILL.md")
        "a client without the extension learns where the skill is")))

(deftest an-old-client-still-gets-its-tools-and-sees-the-skill-as-a-resource
  (is (= "2025-03-26" (get-in (rpc "initialize" {"protocolVersion" "2025-03-26"}) ["result" "protocolVersion"])))
  (is (some #(= "tik_explain" (get % "name")) (get-in (rpc "tools/list" {}) ["result" "tools"])))
  (is (true? (get-in (rpc "tools/call" {"name" "tik_explain" "arguments" {}}) ["result" "isError"]))
      "a tool call still answers in the tool result, not as a protocol error")
  (let [listed (get-in (rpc "resources/list" {}) ["result" "resources"])
        md (some #(when (= "skill://tik/SKILL.md" (get % "uri")) %) listed)]
    (is (= {"uri" "skill://tik/SKILL.md" "name" "tik" "mimeType" "text/markdown" "size" (alength ^bytes skill-md)}
           (dissoc md "description")))
    (is (str/starts-with? (get md "description") "Drive the tik CLI")))
  (is (= [] (get-in (rpc "resources/templates/list" {}) ["result" "resourceTemplates"])))
  (let [c (first (get-in (rpc "resources/read" {"uri" "skill://tik/SKILL.md"}) ["result" "contents"]))]
    (is (= "text/markdown" (get c "mimeType")))
    (is (= (seq skill-md) (seq (.getBytes ^String (get c "text") StandardCharsets/UTF_8))))))

;; ----------------------------------------------------- the extension

(deftest skills-list-returns-complete-entries-with-their-manifest
  (unsigned
   (fn []
     (doseq [[era r] [["legacy" (get (rpc "skills/list" {}) "result")]
                      ["modern" (get (modern "skills/list" {}) "result")]]]
       (testing era
         (is (= "complete" (get r "resultType")))
         (is (pos-int? (get r "ttlMs")))
         (is (= "public" (get r "cacheScope")))
         (is (nil? (get r "nextCursor")))
         (let [[entry & more] (get r "skills")]
           (is (empty? more))
           (is (= "skill://tik/SKILL.md" (get entry "uri")))
           (is (= "tik" (get-in entry ["frontmatter" "name"])))
           (is (str/starts-with? (get-in entry ["frontmatter" "description"]) "Drive the tik CLI"))
           (is (= [{"uri" "skill://tik/SKILL.md" "digest" (sha256 skill-md) "size" (alength ^bytes skill-md)}]
                  (get entry "resources")))
           (is (false? (get-in entry ["_meta" skills/meta-key "signed"])) "unsigned, and saying so")))))))

(deftest skills-get-answers-by-uri-and-refuses-what-it-does-not-serve
  (unsigned
   (fn []
     (let [r (get (modern "skills/get" {"uri" "skill://tik/SKILL.md"}) "result")]
       (is (= "complete" (get r "resultType")))
       (is (= (get-in (modern "skills/list" {}) ["result" "skills" 0]) (get r "skill"))
           "the same entry the listing holds"))
     (doseq [uri ["skill://nope/SKILL.md" "skill://tik/README.md" "skill://tik/../tik/SKILL.md"]]
       (is (= -32602 (get-in (modern "skills/get" {"uri" uri}) ["error" "code"])) uri))
     (is (= -32602 (get-in (modern "skills/get" {}) ["error" "code"])))
     (is (= -32602 (get-in (modern "skills/list" {"cursor" "abc"}) ["error" "code"]))))))

(deftest resources-read-serves-the-manifested-bytes
  (let [r (get (modern "resources/read" {"uri" "skill://tik/SKILL.md"}) "result")
        c (first (get r "contents"))
        bytes (.getBytes ^String (get c "text") StandardCharsets/UTF_8)]
    (is (= "complete" (get r "resultType")))
    (is (= "skill://tik/SKILL.md" (get c "uri")))
    (is (= (sha256 skill-md) (sha256 bytes)))
    (is (= (alength ^bytes skill-md) (alength bytes)))))

(deftest a-binary-file-is-served-as-a-blob-of-the-same-bytes
  (let [raw (byte-array [0 1 (unchecked-byte 0xff) 10])
        c (skills/read-contents "skill://x/a.bin" {:bytes raw :mime "application/octet-stream"})]
    (is (nil? (:text c)))
    (is (= (seq raw) (seq (skills/content-bytes (json/parse-string (json/generate-string c))))))))

(deftest traversal-and-foreign-uris-are-refused
  (doseq [uri ["skill://tik/../tik/SKILL.md" "skill://tik/./SKILL.md" "skill://tik/%2e%2e/SKILL.md"
               "skill://tik%2FSKILL.md" "skill://tik/..%2FSKILL.md" "skill:///etc/passwd"
               "skill://tik//etc/passwd" "skill://tik/" "skill://../../etc/passwd"]]
    (let [e (get (rpc "resources/read" {"uri" uri}) "error")]
      (is (= -32602 (get e "code")) uri)
      (is (str/includes? (get e "message") "invalid skill URI") uri)))
  (doseq [uri ["file:///etc/passwd" "/etc/passwd" "skill://tik/missing.md" "skill://other/SKILL.md"]]
    (let [e (get (rpc "resources/read" {"uri" uri}) "error")]
      (is (= -32602 (get e "code")) uri)
      (is (str/includes? (get e "message") "no resource at") uri)))
  (is (= -32602 (get-in (rpc "resources/read" {"uri" 7}) ["error" "code"])))
  (is (= -32602 (get-in (rpc "resources/read" {}) ["error" "code"]))))

;; ----------------------------------------------------- modern protocol

(deftest server-discover-names-versions-capabilities-and-the-skill
  (let [r (get (modern "server/discover" {}) "result")]
    (is (= "complete" (get r "resultType")))
    (is (= ["2026-07-28"] (get r "supportedVersions")))
    (is (= {} (get-in r ["capabilities" "extensions" "io.modelcontextprotocol/skills"])))
    (is (= {"name" "tik" "version" "1"} (get-in r ["_meta" "io.modelcontextprotocol/serverInfo"])))
    (is (str/includes? (get r "instructions") "skill://tik/SKILL.md"))))

(deftest a-modern-request-names-its-version-and-capabilities
  (let [e (get (rpc "tools/list" {"_meta" (assoc modern-meta "io.modelcontextprotocol/protocolVersion" "1900-01-01")}) "error")]
    (is (= -32022 (get e "code")))
    (is (= {"supported" ["2026-07-28"] "requested" "1900-01-01"} (get e "data"))))
  (is (= -32602 (get-in (rpc "tools/list" {"_meta" (dissoc modern-meta "io.modelcontextprotocol/clientCapabilities")})
                        ["error" "code"])))
  (is (= -32601 (get-in (modern "nope/nothing" {}) ["error" "code"])))
  (is (= "complete" (get-in (modern "tools/list" {}) ["result" "resultType"]))))

(def ^:private config {:token "0123456789abcdef0123456789abcdef" :port 7777})

(defn- post [method params & [headers]]
  (let [params (assoc params "_meta" modern-meta)
        base {"authorization" (str "Bearer " (:token config))
              "mcp-protocol-version" "2026-07-28"
              "mcp-method" method}
        base (if-let [n (or (get params "uri") (get params "name"))] (assoc base "mcp-name" n) base)
        resp (mcp/http-response config {:request-method :post
                                        :headers (merge base headers)
                                        :body (json/generate-string {"jsonrpc" "2.0" "id" 1 "method" method "params" params})})]
    (assoc resp :json (try (json/parse-string (:body resp)) (catch Exception _ nil)))))

(deftest modern-http-requests-mirror-their-body-into-headers
  (is (= 200 (:status (post "skills/list" {}))))
  (is (= 200 (:status (post "resources/read" {"uri" "skill://tik/SKILL.md"}))))
  (let [b64 (str "=?base64?" (.encodeToString (Base64/getEncoder) (.getBytes "skill://tik/SKILL.md" "UTF-8")) "?=")]
    (is (= 200 (:status (post "resources/read" {"uri" "skill://tik/SKILL.md"} {"mcp-name" b64})))
        "a base64-sentinel Mcp-Name is decoded before it is compared"))
  (doseq [[why headers params method] [["version mismatch" {"mcp-protocol-version" "2025-06-18"} {} "skills/list"]
                                       ["version missing" {"mcp-protocol-version" nil} {} "skills/list"]
                                       ["method mismatch" {"mcp-method" "tools/list"} {} "skills/list"]
                                       ["name mismatch" {"mcp-name" "skill://tik/other.md"} {"uri" "skill://tik/SKILL.md"} "resources/read"]
                                       ["name missing" {"mcp-name" nil} {"uri" "skill://tik/SKILL.md"} "resources/read"]]]
    (let [{:keys [status json]} (post method params headers)]
      (is (= 400 status) why)
      (is (= -32020 (get-in json ["error" "code"])) why)))
  (is (= 400 (:status (mcp/http-response config {:request-method :post
                                                 :headers {"authorization" (str "Bearer " (:token config))
                                                           "mcp-protocol-version" "1900-01-01" "mcp-method" "tools/list"}
                                                 :body (json/generate-string {"jsonrpc" "2.0" "id" 1 "method" "tools/list"
                                                                              "params" {"_meta" (assoc modern-meta "io.modelcontextprotocol/protocolVersion" "1900-01-01")}})}))))
  (is (= 404 (:status (post "nope/nothing" {}))))
  (testing "a modern request missing a required _meta field is malformed: 400 with -32602"
    (doseq [[why meta] [["no clientCapabilities" (dissoc modern-meta "io.modelcontextprotocol/clientCapabilities")]
                        ["no protocolVersion" (dissoc modern-meta "io.modelcontextprotocol/protocolVersion")]
                        ["no _meta at all" nil]]]
      (let [resp (mcp/http-response config {:request-method :post
                                            :headers {"authorization" (str "Bearer " (:token config))
                                                      "mcp-protocol-version" "2026-07-28" "mcp-method" "tools/list"}
                                            :body (json/generate-string {"jsonrpc" "2.0" "id" 1 "method" "tools/list"
                                                                         "params" (if meta {"_meta" meta} {})})})]
        (is (= 400 (:status resp)) why)
        (is (= -32602 (get-in (json/parse-string (:body resp)) ["error" "code"])) why))))
  (testing "an unknown skill is an error in the body, not an HTTP failure"
    (let [{:keys [status json]} (post "skills/get" {"uri" "skill://nope/SKILL.md"})]
      (is (= 200 status))
      (is (= -32602 (get-in json ["error" "code"])))))
  (testing "a legacy request over HTTP keeps answering 200"
    (is (= 200 (:status (mcp/http-response config {:request-method :post
                                                   :headers {"authorization" (str "Bearer " (:token config))
                                                             "mcp-protocol-version" "2025-06-18"}
                                                   :body "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bogus\"}"})))))
  (testing "the bearer token still guards the modern era"
    (is (= 401 (:status (post "skills/list" {} {"authorization" nil}))))))

;; ----------------------------------------------------- tik skill verify

(defn- key! [dir name]
  (let [k (io/file dir name)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" name "-f" (str k))
    {:name name :key (str k) :pub (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str k))))}))

(defn- with-mcp-server
  "Calls (f url) with tik's MCP endpoint served on a loopback port,
  passing every response body through `tamper`."
  [tamper f]
  (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/mcp"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [ex ^HttpExchange ex
                              headers (into {} (for [[k vs] (.getRequestHeaders ex)] [(str/lower-case k) (first vs)]))
                              resp (mcp/http-response config {:request-method (keyword (str/lower-case (.getRequestMethod ex)))
                                                              :headers headers
                                                              :body (slurp (.getRequestBody ex))})
                              body (.getBytes ^String (tamper (str (:body resp))) StandardCharsets/UTF_8)]
                          (.sendResponseHeaders ex (int (:status resp)) (if (zero? (alength body)) -1 (long (alength body))))
                          (with-open [o (.getResponseBody ex)] (.write o body))))))
    (.start srv)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress srv)) "/mcp"))
         (finally (.stop srv 0)))))

(defn- verify-run [root & argv]
  (h/with-cli-root root #(cli/run-argv (into ["skill" "verify"] (map str argv)))))

(deftest skill-verify-checks-signature-and-bytes-over-http
  (let [root (h/temp-dir! "tik-skill-verify")
        alice (key! root "alice")
        token-file (io/file root "token")]
    (spit (io/file root "actors") (str "alice namespaces=\"tik-*\" " (:pub alice) "\n"))
    (spit token-file (:token config))
    (with-redefs-fn {#'mcp/signer (constantly {:key (:key alice) :actor "alice"})}
      (fn []
        (with-mcp-server identity
          (fn [url]
            (let [r (verify-run root url "--token-file" token-file)]
              (is (zero? (:exit r)) (str (:out r) (:err r)))
              (is (str/includes? (:out r) "signature verified for alice"))
              (is (str/includes? (:out r) "1 file(s) match the manifest")))
            (testing "without the token the server refuses and the check fails"
              (is (= 1 (:exit (verify-run root url)))))))
        (testing "a server that changes a file after signing is caught"
          (with-mcp-server #(str/replace % "a process system, not a ticket system" "a ticket system")
            (fn [url]
              (let [r (verify-run root url "--token-file" token-file)]
                (is (= 1 (:exit r)))
                (is (str/includes? (:out r) "manifest says"))))))
        (testing "a server that rewrites the manifest breaks its signature"
          (with-mcp-server #(str/replace % #"\"size\":(\d+)" "\"size\":1")
            (fn [url]
              (let [r (verify-run root url "--token-file" token-file)]
                (is (= 1 (:exit r)))
                (is (str/includes? (:out r) "BAD signature"))))))
        (testing "an unregistered signer fails against this store's actors"
          (let [other (h/temp-dir! "tik-skill-verify-other")]
            (spit (io/file other "actors") "")
            (with-mcp-server identity
              (fn [url]
                (is (= 1 (:exit (verify-run other url "--token-file" token-file))))))))))
    (with-redefs-fn {#'mcp/signer (constantly {:key nil :actor "agent"})}
      (fn []
        (with-mcp-server identity
          (fn [url]
            (let [r (verify-run root url "--token-file" token-file)]
              (is (= 1 (:exit r)) "an unsigned manifest never passes")
              (is (str/includes? (:out r) "UNSIGNED")))))))))

(deftest skill-verify-reads-a-saved-entry
  (let [root (h/temp-dir! "tik-skill-verify-file")
        alice (key! root "alice")
        saved (io/file root "list.json")]
    (spit (io/file root "actors") (str "alice namespaces=\"tik-*\" " (:pub alice) "\n"))
    (with-redefs-fn {#'mcp/signer (constantly {:key (:key alice) :actor "alice"})}
      #(spit saved (json/generate-string (mcp/handle "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"skills/list\"}"))))
    (let [r (verify-run root saved)]
      (is (zero? (:exit r)) (str (:out r) (:err r)))
      (is (str/includes? (:out r) "files not checked")))
    (let [doc (json/parse-string (slurp saved))]
      (spit saved (json/generate-string (assoc-in doc ["result" "skills" 0 "resources" 0 "size"] 1)))
      (is (= 1 (:exit (verify-run root saved)))))
    (spit saved "{}")
    (is (= 1 (:exit (verify-run root saved))))
    (is (= 1 (:exit (verify-run root (io/file root "missing.json")))))
    (is (= 1 (:exit (h/with-cli-root root #(cli/run-argv ["skill" "frobnicate"])))))))

(deftest skill-verify-names-an-unreachable-server
  (let [root (h/temp-dir! "tik-skill-verify-down")
        port (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
               (.getLocalPort s))]
    (spit (io/file root "actors") "")
    (let [r (verify-run root (str "http://127.0.0.1:" port "/mcp"))]
      (is (= 1 (:exit r)))
      (is (str/includes? (str (:out r) (:err r)) "could not be opened")))))

(deftest skill-verify-refuses-a-saved-entry-that-names-a-key-twice
  (let [root (h/temp-dir! "tik-skill-verify-dup")
        saved (io/file root "entry.json")]
    (spit (io/file root "actors") "")
    (spit saved "{\"uri\":\"skill://a\",\"resources\":[],\"resources\":[]}")
    (let [r (verify-run root saved)]
      (is (= 1 (:exit r)))
      (is (str/includes? (str (:out r) (:err r)) "names an object key twice")))))
