;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.skill-verify
  "`tik skill verify`: check what an MCP server serves as agent skills
  against the signatures our actors file accepts.

  The Skills extension's digests only say a file matches its manifest;
  whoever controls the server controls both. The manifest signature says
  which registered actor vouched for the manifest, so this command is
  the one place a skill an agent received is judged by the store's
  identity registry rather than by the server that sent it."
  (:require [briko.http :as http]
            [briko.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.cli-core :refer [die exit! root]]
            [tik.forge :as forge]
            [tik.skills :as skills])
  (:import (java.net URI)))

(def ^:private max-bytes
  "The largest answer and saved entry read. The Skills extension caps a
  skill at 16 MiB, which a resources/read answer carries base64-encoded
  as about 21.3 MiB; our own skill is 40 KB (2026-11-28)."
  (* 32 1024 1024))

(defn- json-parse [s] (json/parse-string s {:max-bytes max-bytes}))

(def ^:private modern-version "2026-07-28")

(defn- modern-params [params]
  (assoc params "_meta" {"io.modelcontextprotocol/protocolVersion" modern-version
                         "io.modelcontextprotocol/clientInfo" {"name" "tik-skill-verify" "version" "1"}
                         "io.modelcontextprotocol/clientCapabilities" {}}))

(defn- post!
  "POST one JSON-RPC message; {:status :body :session}. Connecting may
  take 20 s and the whole exchange 60 s."
  [^URI uri headers msg]
  (let [r (http/request :post (str uri)
                        {:headers (into {} (filter (comp seq val))
                                        (merge {"Content-Type" "application/json"
                                                "Accept" "application/json, text/event-stream"
                                                "User-Agent" "tik-skill-verify"}
                                               headers))
                         :body (json/write-string msg)
                         :as :text
                         :connect-timeout-ms 20000
                         :timeout-ms 60000
                         :max-bytes max-bytes})]
    {:status (:status r)
     :body (str (:body r))
     :session (http/header r "mcp-session-id")}))

(defn- answer
  "The `result` of a JSON-RPC response body; throws with the server's
  error otherwise."
  [{:keys [status body]} method]
  (let [r (try (json-parse body) (catch Exception _ nil))]
    (cond
      (and (map? r) (map? (get r "error")))
      (throw (ex-info (str method " refused: " (get-in r ["error" "message"]))
                      {:reason :skill-verify/rpc :code (get-in r ["error" "code"])}))
      (and (map? r) (map? (get r "result")) (< status 300)) (get r "result")
      :else (throw (ex-info (str method " answered HTTP " status)
                            {:reason :skill-verify/http :status status})))))

(defn- mcp-caller
  "A (method params) -> result fn over the MCP endpoint at `url`. A
  modern request goes first; a server that does not answer it as a
  modern server gets the legacy initialize handshake instead, so a
  server of either era can be checked."
  [url token]
  (let [uri (forge/require-tls! url)
        auth (when token {"Authorization" (str "Bearer " token)})
        ids (atom 0)
        msg (fn [method params]
              {"jsonrpc" "2.0" "id" (swap! ids inc) "method" method "params" params})
        modern (fn [method params]
                 (answer (post! uri (merge auth {"MCP-Protocol-Version" modern-version
                                                   "Mcp-Method" method}
                                             (when-let [n (get params "uri")] {"Mcp-Name" n}))
                                (msg method (modern-params params)))
                         method))
        ;; a server that cannot be reached is no legacy server either
        modern? (try (modern "server/discover" {}) true
                     (catch clojure.lang.ExceptionInfo e
                       (if (http/refusal? e) (throw e) false)))]
    (if modern?
      modern
      (let [init (post! uri auth (msg "initialize" {"protocolVersion" "2025-06-18"
                                                      "capabilities" {}
                                                      "clientInfo" {"name" "tik-skill-verify" "version" "1"}}))
            _ (answer init "initialize")
            session (when-let [s (:session init)] {"Mcp-Session-Id" s})
            headers (merge auth session {"MCP-Protocol-Version" "2025-06-18"})]
        (post! uri headers {"jsonrpc" "2.0" "method" "notifications/initialized"})
        (fn [method params]
          (answer (post! uri headers (msg method params)) method))))))

;; ------------------------------------------------------------- judging

(defn- frontmatter-matches
  "nil when the served SKILL.md's frontmatter equals the entry's, else
  the reason."
  [entry ^bytes skill-md]
  (try
    (let [parsed (into {} (skills/frontmatter (String. skill-md "UTF-8")))]
      (when-not (= parsed (get entry "frontmatter"))
        "the SKILL.md frontmatter differs from the entry's frontmatter"))
    (catch clojure.lang.ExceptionInfo e
      (str "the SKILL.md frontmatter cannot be read: " (ex-message e)))))

(defn judge-entry
  "The verdict on one skill entry, and, given `read-file` (uri -> raw
  bytes), on every file its manifest lists:

    {:uri :verdict :actor :reason :files-checked :file-problems}

  :ok? is true only for a verified signature with every file matching."
  [signers entry read-file]
  (let [sig (skills/verify-entry signers entry)
        uris (keep #(when (map? %) (get % "uri")) (get entry "resources"))
        problems (when (and read-file (= :verified (:verdict sig)))
                   (vec (keep (fn [u]
                                (try
                                  (let [b (read-file u)]
                                    (or (skills/check-file entry u b)
                                        (when (= u (get entry "uri"))
                                          (frontmatter-matches entry b))))
                                  (catch Exception e
                                    (str u ": " (or (ex-message e) (.getName (class e)))))))
                              uris)))]
    (merge {:uri (get entry "uri")}
           sig
           (when read-file {:files-checked (if (= :verified (:verdict sig)) (count uris) 0)
                            :file-problems (or problems [])})
           {:ok? (and (= :verified (:verdict sig)) (empty? problems))})))

(defn- entries-of
  "The skill entries in a parsed JSON document: a JSON-RPC response, a
  skills/list or skills/get result, or one entry."
  [doc]
  (let [r (if (and (map? doc) (map? (get doc "result"))) (get doc "result") doc)]
    (cond
      (and (map? r) (sequential? (get r "skills"))) (vec (get r "skills"))
      (and (map? r) (map? (get r "skill"))) [(get r "skill")]
      (and (map? r) (contains? r "resources")) [r]
      :else nil)))

(defn verify-source
  "Judge every skill `src` (an http(s) MCP endpoint or a JSON file)
  holds against the allowed-signers text `signers`."
  [src signers {:keys [token]}]
  (if (re-find #"^https?://" src)
    (let [call (mcp-caller src token)
          entries (vec (get (call "skills/list" {}) "skills"))
          read-file (fn [uri]
                      (let [items (get (call "resources/read" {"uri" uri}) "contents")
                            item (some #(when (= uri (get % "uri")) %) items)]
                        (when-not item
                          (throw (ex-info "resources/read returned no contents for it" {})))
                        (skills/content-bytes item)))]
      (mapv #(judge-entry signers % read-file) entries))
    (let [doc (try (json/parse-file src {:max-bytes max-bytes})
                   (catch Exception e
                     (throw (ex-info (str src " is not JSON: " (ex-message e)) {}))))]
      (mapv #(judge-entry signers % nil)
            (or (entries-of doc)
                (throw (ex-info (str src " holds no skill entry") {})))))))

(defn- describe [{:keys [uri verdict actor reason files-checked file-problems ok?]}]
  (str uri ": "
       (case verdict
         :verified (str "signature verified for " actor)
         :unsigned (str "UNSIGNED (" reason ")")
         :missing "NO manifest signature in _meta"
         :malformed (str "MALFORMED manifest (" reason ")")
         :bad-signature (str "BAD signature for " (pr-str actor))
         (str verdict))
       (cond
         (nil? files-checked) "; files not checked (a JSON file carries no contents)"
         (seq file-problems) (str "; " (str/join "; " file-problems))
         ok? (str "; " files-checked " file(s) match the manifest")
         :else "")))

(defn cmd-skill
  "skill verify <mcp-url|entry.json> [--token-file F] [--signers F] [--edn]"
  [{:keys [pos opts]}]
  (let [[sub src] pos]
    (when-not (and (= "verify" sub) src)
      (die "usage: tik skill verify <mcp-url|entry.json> [--token-file F] [--signers actors] [--edn]"))
    (let [signers-file (io/file (or (:signers opts) (io/file (root) "actors")))
          _ (when-not (.isFile signers-file)
              (die (str "no allowed-signers file at " signers-file " - pass --signers")))
          token (some-> (:token-file opts) slurp str/trim)
          results (try (verify-source src (slurp signers-file) {:token token})
                       (catch Exception e
                         (die (str "tik: " (or (ex-message e) (.getName (class e)))))))]
      (if (or (:edn opts) (= "edn" (:format opts)))
        (prn results)
        (doseq [r results] (println (describe r))))
      (exit! (if (and (seq results) (every? :ok? results)) 0 1)))))
