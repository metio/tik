;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.json-bounds-test
  "What our JSON readers accept and refuse, and what our JSON writers
  print: the same values and bytes as cheshire for every document a peer
  really sends, one reading of a document or none, and hidden code
  points escaped in output that reaches a terminal or an agent."
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli-core :as core]
            [tik.harness :as h]
            [tik.identity-trust :as identity-trust]
            [tik.jwks :as jwks]
            [tik.mcp :as mcp]
            [tik.render :as render]
            [tik.skills :as skills])
  (:import (java.security MessageDigest)
           (java.util Base64)))

(def ^:private readable
  {:title "Größe – naïve café 日本" :n 42 :ok true :none nil :xs [1 2.5 "a\nb\t\"c\""]})

;; ----------------------------------------------------------- the writers

(deftest the_json_lens_prints_what_cheshire_printed_for_readable_text
  (is (= (cheshire/generate-string readable) (render/json-encode readable)))
  (is (= (cheshire/generate-string {:at "2026-11-28T00:00:00Z" :s ["a" "b"]})
         (render/json-encode {:at (java.time.Instant/parse "2026-11-28T00:00:00Z") :s #{"a" "b"}}))))

(deftest the_json_lens_escapes_hidden_code_points
  (let [out (render/json-encode {:title "safe\u202Eevil\u200B\u0085"})]
    (is (= "{\"title\":\"safe\\u202Eevil\\u200B\\u0085\"}" out))
    (is (= {"title" "safe\u202Eevil\u200B\u0085"} (cheshire/parse-string out))
        "an escape reads back as the same character")))

(deftest the_json_lens_never_names_a_key_twice
  (testing "a keyword and a string of one name are both printed"
    (is (= {":a" 1 "\"a\"" 2} (cheshire/parse-string (render/json-encode {:a 1 "a" 2})))))
  (testing "a number key and its string likewise"
    (is (= {"1" 1 "\"1\"" 2} (cheshire/parse-string (render/json-encode {1 1 "1" 2})))))
  (testing "keys that do not clash print as before"
    (is (= "{\"a\":1,\"1\":2}" (render/json-encode (array-map :a 1 1 2))))))

(deftest an_mcp_response_escapes_hidden_code_points
  (let [resp (mcp/handle "{\"jsonrpc\":\"2.0\",\"id\":\"x\u202Ey\",\"method\":\"ping\"}")
        printed (#'mcp/json-emit resp)]
    (is (str/includes? printed "\"x\\u202Ey\""))
    (is (= "x\u202Ey" (get (cheshire/parse-string printed) "id")))))

(deftest a_thumbprint_hashes_members_as_rfc_7638_spells_them
  ;; RFC 7638 hashes each member as a JSON string with only the escapes
  ;; JSON requires, so a hidden code point is hashed raw in UTF-8
  (let [jwk {:kty "OKP" :crv "Ed25519\u200B" :x "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"}
        canon (str "{\"crv\":\"Ed25519\u200B\",\"kty\":\"OKP\","
                   "\"x\":\"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo\"}")
        want (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                              (.digest (MessageDigest/getInstance "SHA-256")
                                       (.getBytes canon "UTF-8")))]
    (is (= want (jwks/thumbprint jwk)))))

;; ----------------------------------------------------------- the readers

(deftest an_mcp_message_is_read_one_way_or_not_at_all
  (testing "a message as clients send it"
    (is (= "x" (get (mcp/handle "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"method\":\"ping\"}") :id))))
  (doseq [line ["{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}"
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"} {\"id\":2}"
                (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"p\":\""
                     (str/join (repeat (* 1024 1024) "a")) "\"}")]]
    (testing (subs line 0 (min 60 (count line)))
      (is (= -32700 (get-in (mcp/handle line) [:error :code]))))))

(defn- b64 [^String s]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes s "UTF-8")))

(deftest a_token_segment_that_names_a_member_twice_is_not_read
  (is (= {:alg "EdDSA" :kid "k"} (identity-trust/token-header (str (b64 "{\"alg\":\"EdDSA\",\"kid\":\"k\"}") ".e30.c2ln"))))
  (is (nil? (identity-trust/token-header (str (b64 "{\"alg\":\"none\",\"alg\":\"EdDSA\"}") ".e30.c2ln"))))
  (is (= {:sub "s"} (identity-trust/token-claims (str "e30." (b64 "{\"sub\":\"s\"}") ".c2ln"))))
  (is (nil? (identity-trust/token-claims (str "e30." (b64 "{\"sub\":\"a\",\"sub\":\"b\"}") ".c2ln")))))

(deftest a_key_set_is_read_strictly
  (is (= {:keys [{:kty "OKP" :kid "a"}]} (jwks/parse-jwks "{\"keys\":[{\"kty\":\"OKP\",\"kid\":\"a\"}]}")))
  (doseq [doc ["{\"keys\":[],\"keys\":[{\"kty\":\"OKP\"}]}" "{\"keys\":[]} []"]]
    (is (= :oid4vci/bad-jwks (:reason (ex-data (try (jwks/parse-jwks doc) nil
                                                    (catch clojure.lang.ExceptionInfo e e)))))
        doc)))

(deftest a_double_quoted_frontmatter_value_is_one_json_string
  (is (= [["name" "tik"] ["description" "a \"quoted\" é"]]
         (skills/frontmatter "---\nname: tik\ndescription: \"a \\\"quoted\\\" \\u00e9\"\n---\nbody\n")))
  (is (thrown? clojure.lang.ExceptionInfo
               (skills/frontmatter "---\nname: tik\ndescription: \"a\" \"b\"\n---\nbody\n"))
      "text after the string is refused rather than dropped"))

;; ------------------------------------------------------------ the cache

(deftest a_cache_of_the_measured_size_is_read_back
  (let [root (h/temp-dir! "tik-json-cache")
        ;; about 2100 entries of the 1.1 KB measured per entry
        entries (into {} (for [i (range 2100)]
                           [(str "id-" i) {:stage :review :title (str/join (repeat 1000 "t"))}]))]
    (h/with-cli-root root
      (fn []
        (reset! core/cache-state {(str root) {:entries entries :dirty? true}})
        (core/cache-flush!)
        (is (< (* 2 1024 1024) (.length (io/file root ".derived-cache.json"))))
        (reset! core/cache-state {})
        (is (= entries (core/cache-entries)))
        (testing "a file over the bound is a miss"
          (reset! core/cache-state {})
          (with-redefs-fn {#'core/max-cache-bytes 1024}
            #(is (= {} (core/cache-entries)))))
        (reset! core/cache-state {})))))
