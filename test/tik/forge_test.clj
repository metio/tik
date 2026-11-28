;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.forge-test
  "The forge JSON getter and JSON-RPC caller against a loopback server:
  transient server errors are repeated, anything else fails at once, and
  an RPC error member becomes an ex-info."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.forge :as forge]
            [tik.loopback-http :as lh])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- with-server
  "Calls `f` with a getter over a loopback server answering successive
  requests with `statuses` (the last one repeats), and returns
  [result-or-exception request-count]."
  [statuses f]
  (let [hits (atom 0)
        srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [n (swap! hits inc)
                              status (nth statuses (min (dec n) (dec (count statuses))))
                              body (.getBytes "{\"ok\":true}" StandardCharsets/UTF_8)]
                          (.sendResponseHeaders ^HttpExchange ex (int status) (long (alength body)))
                          (with-open [o (.getResponseBody ^HttpExchange ex)]
                            (.write o body))))))
    (.start srv)
    (try
      (let [get-json (forge/json-getter "Test API"
                                        (str "http://127.0.0.1:" (.getPort (.getAddress srv)))
                                        {})
            result (binding [forge/*retry-pauses* [0 0]]
                     (try (f get-json) (catch clojure.lang.ExceptionInfo e e)))]
        [result @hits])
      (finally (.stop srv 0)))))

(deftest a-transient-server-error-is-repeated-until-it-answers
  (let [[result hits] (with-server [502 500 200] #(% "/runs" {}))]
    (is (= {:ok true} result))
    (is (= 3 hits))))

(deftest a-server-error-that-persists-fails-after-the-repeats
  (let [[result hits] (with-server [503] #(% "/runs" {}))]
    (is (= {:reason :forge/http :status 503 :path "/runs"} (ex-data result)))
    (is (= 3 hits))))

(deftest a-client-error-is-not-repeated
  (let [[result hits] (with-server [404] #(% "/runs" {}))]
    (is (= 404 (:status (ex-data result))))
    (is (= 1 hits))))

(defn- with-rpc-server
  "Calls `f` with a JSON-RPC `call` over a loopback server answering
  every request with `status` and `reply`; returns [result-or-exception
  request-bodies]."
  [status reply f]
  (let [seen (atom [])
        srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (swap! seen conj (slurp (.getRequestBody ^HttpExchange ex)))
                        (let [body (.getBytes ^String reply StandardCharsets/UTF_8)]
                          (.sendResponseHeaders ^HttpExchange ex (int status) (long (alength body)))
                          (with-open [o (.getResponseBody ^HttpExchange ex)]
                            (.write o body))))))
    (.start srv)
    (try
      (let [call (forge/json-rpc-caller "Test RPC"
                                        (str "http://127.0.0.1:" (.getPort (.getAddress srv)) "/jsonrpc"))
            result (binding [forge/*retry-pauses* [0 0]]
                     (try (f call) (catch clojure.lang.ExceptionInfo e e)))]
        [result @seen])
      (finally (.stop srv 0)))))

(deftest a-json-rpc-call-sends-service-method-and-args
  (let [[result [body]] (with-rpc-server 200 "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":45}"
                          #(% "common" "login" "db" "u" "p"))]
    (is (= 45 result))
    (is (re-find #"\"service\":\"common\"" body))
    (is (re-find #"\"args\":\[\"db\",\"u\",\"p\"\]" body))))

(deftest a-json-rpc-call-without-arguments-sends-an-empty-args-list
  (let [[result [body]] (with-rpc-server 200 "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
                          #(% "common" "version"))]
    (is (= {} result))
    (is (re-find #"\"args\":\[\]" body) "Odoo spreads args into the method, so null would fail")))

(deftest a-json-rpc-error-is-an-ex-info-with-its-message
  (let [[result] (with-rpc-server 200 (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
                                           "{\"message\":\"Odoo Server Error\","
                                           "\"data\":{\"message\":\"Access denied\"}}}")
                   #(% "object" "execute_kw"))]
    (is (= :forge/rpc (:reason (ex-data result))))
    (is (re-find #"Access denied" (ex-message result)))))

(deftest a-json-rpc-server-error-that-persists-fails-after-the-repeats
  (let [[result seen] (with-rpc-server 502 "bad gateway" #(% "object" "execute_kw"))]
    (is (= 502 (:status (ex-data result))))
    (is (= 3 (count seen)))))

(defn- closed-port
  "A loopback port nothing listens on."
  []
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))]
    (.getLocalPort s)))

(defn- with-non-tls-server
  "Calls `f` with the port of a loopback server that answers every
  connection with plain text, so a TLS client fails its handshake."
  [f]
  (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getLoopbackAddress))
        worker (future
                 (try
                   (loop []
                     (with-open [c (.accept ss)]
                       (doto (.getOutputStream c)
                         (.write (.getBytes "this is not TLS\r\n" StandardCharsets/US_ASCII))
                         (.flush)))
                     (recur))
                   (catch java.io.IOException _ nil)))]
    (try (f (.getLocalPort ss))
         (finally (.close ss) (future-cancel worker)))))

(defn- attempt [f]
  (binding [forge/*retry-pauses* [0 0]]
    (try (f) nil (catch clojure.lang.ExceptionInfo e e))))

(deftest a-refused-connection-is-an-ex-info-naming-the-api-and-address
  (let [port (closed-port)
        e (attempt #((forge/json-getter "Test API" (str "http://127.0.0.1:" port) {}) "/runs" {}))]
    (is (= :forge/unreachable (:reason (ex-data e))))
    (is (= (str "Test API unreachable at http://127.0.0.1:" port ": ConnectException")
           (ex-message e))
        "the class names the cause even when the exception carries no message")
    (is (instance? java.net.ConnectException (ex-cause e)))))

(deftest a-refused-json-rpc-connection-is-an-ex-info-naming-the-api
  (let [port (closed-port)
        e (attempt #((forge/json-rpc-caller "Test RPC" (str "http://127.0.0.1:" port "/jsonrpc"))
                     "common" "version"))]
    (is (= :forge/unreachable (:reason (ex-data e))))
    (is (re-find #"^Test RPC unreachable at http://127\.0\.0\.1:\d+: ConnectException" (ex-message e)))))

(deftest a-failed-tls-handshake-is-an-ex-info-naming-the-api
  (with-non-tls-server
    (fn [port]
      (let [e (attempt #((forge/json-getter "Test API" (str "https://127.0.0.1:" port) {}) "/runs" {}))]
        (is (= :forge/unreachable (:reason (ex-data e))))
        (is (= (str "https://127.0.0.1:" port "/runs") (:url (ex-data e))))
        (is (instance? javax.net.ssl.SSLException (ex-cause e)))
        (is (re-find #"^Test API unreachable at https://127\.0\.0\.1:\d+: SSL" (ex-message e)))))))

;; ------------------------------------------------------------ bounds

(defn- runs-page
  "A page of `n` workflow runs of about 15 KB each, the size the GitHub
  API answered on 2026-11-28."
  [n]
  (str "{\"total_count\":" n ",\"workflow_runs\":["
       (str/join "," (for [i (range n)]
                       (str "{\"id\":" i ",\"head_commit\":{\"message\":\""
                            (str/join (repeat 15000 "m")) "\"}}")))
       "]}"))

(deftest a-page-of-the-measured-size-is-read
  (let [page (runs-page 140)]
    (is (< (* 2 1024 1024) (count page)) "larger than any page measured")
    (lh/with-server
      (constantly {:status 200 :body page})
      (fn [base _]
        (let [r ((forge/json-getter "Test API" base {}) "/runs" {})]
          (is (= 140 (count (:workflow_runs r))))
          (is (= 139 (:id (last (:workflow_runs r))))))))))

(deftest every-request-carries-the-user-agent-and-no-empty-header
  (lh/with-server
    (constantly {:status 200 :body "{}"})
    (fn [base seen]
      ((forge/json-getter "Test API" base {"Authorization" nil "X-Empty" "" "X-Key" "k"}) "/runs" {:page 2})
      (let [{:keys [headers query]} (first @seen)]
        (is (= "tik-bridge" (get headers "user-agent")))
        (is (= "k" (get headers "x-key")))
        (is (not (contains? headers "authorization")))
        (is (not (contains? headers "x-empty")))
        (is (= "page=2" query))))))

(deftest an-empty-success-body-reads-as-nil
  (lh/with-server
    (constantly {:status 200 :body ""})
    (fn [base _]
      (is (nil? ((forge/json-getter "Test API" base {}) "/runs" {}))))))

(deftest an-answer-larger-than-the-limit-is-refused
  (let [huge (byte-array (inc (* 16 1024 1024)) (byte 32))]
    (doseq [chunked? [false true]]
      (testing (if chunked? "of unannounced length" "with a Content-Length")
        (lh/with-server
          (constantly {:status 200 :chunked? chunked? :body huge})
          (fn [base _]
            (let [e (attempt #((forge/json-getter "Test API" base {}) "/runs" {}))]
              (is (= :forge/too-large (:reason (ex-data e))))
              (is (= (* 16 1024 1024) (:limit (ex-data e)))))))))))

(deftest a-document-that-names-a-key-twice-is-not-json
  (doseq [body ["{\"a\":1,\"a\":2}" "{\"a\":1} {\"b\":2}" "{\"a\":1e400}"]]
    (testing body
      (lh/with-server
        (constantly {:status 200 :body body})
        (fn [base _]
          (let [e (attempt #((forge/json-getter "Test API" base {}) "/runs" {}))]
            (is (= {:reason :forge/json :path "/runs"} (ex-data e)))))))))

(deftest a-json-rpc-request-escapes-hidden-characters-and-keeps-readable-text
  (let [[_ [body]] (with-rpc-server 200 "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1}"
                     #(% "object" "execute_kw" "Größe" "a\u202Eb"))]
    (is (str/includes? body "\"Größe\""))
    (is (str/includes? body "\"a\\u202Eb\""))))
