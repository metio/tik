;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.forge-test
  "The forge JSON getter and JSON-RPC caller against a loopback server:
  transient server errors are repeated, anything else fails at once, and
  an RPC error member becomes an ex-info."
  (:require [clojure.test :refer [deftest is]]
            [tik.forge :as forge])
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
