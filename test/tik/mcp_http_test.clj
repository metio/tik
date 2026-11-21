;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mcp-http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tik.mcp :as mcp]))

(def ^:private config {:token "0123456789abcdef0123456789abcdef" :port 7777})

(defn- post [body & [headers]]
  (mcp/http-response config {:request-method :post
                             :headers (merge {"authorization" (str "Bearer " (:token config))} headers)
                             :body body}))

(deftest initialize-answers-in-the-clients-revision
  (let [{:keys [status body]} (post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}")]
    (is (= 200 status))
    (is (str/includes? body "\"protocolVersion\":\"2025-06-18\""))))

(deftest an-unknown-revision-gets-the-newest
  (is (str/includes? (:body (post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}"))
                     "\"protocolVersion\":\"2025-06-18\"")))

(deftest a-notification-is-accepted-without-a-body
  (is (= 202 (:status (post "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")))))

(deftest tools-are-listed
  (is (str/includes? (:body (post "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")) "tik_explain")))

(deftest the-bearer-token-is-required
  (let [body "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"]
    (is (= 401 (:status (post body {"authorization" nil}))))
    (is (= 401 (:status (post body {"authorization" "Bearer wrong"}))))))

(deftest a-foreign-origin-is-refused
  (let [body "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"]
    (is (= 403 (:status (post body {"origin" "http://evil.example"}))))
    (is (= 200 (:status (post body {"origin" "http://127.0.0.1:7777"}))))))

(deftest only-post-carries-messages
  (is (= 405 (:status (mcp/http-response config {:request-method :get
                                                  :headers {"authorization" (str "Bearer " (:token config))}})))))

(deftest unreadable-input-answers-a-parse-error
  (is (str/includes? (:body (post "not json")) "-32700")))
