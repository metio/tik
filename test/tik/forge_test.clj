;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.forge-test
  "The forge JSON getter against a loopback server that answers from a
  script of statuses: transient server errors are repeated, anything else
  fails at once."
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
