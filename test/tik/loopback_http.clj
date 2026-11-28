;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.loopback-http
  "A scripted HTTP server on the loopback interface for the tests of our
  outbound requests. Nothing here reaches beyond 127.0.0.1."
  (:require [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io OutputStream)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- request-of [^HttpExchange ex]
  {:method (.getRequestMethod ex)
   :path (.getPath (.getRequestURI ex))
   :query (.getRawQuery (.getRequestURI ex))
   :headers (into {} (for [[k vs] (.getRequestHeaders ex)]
                       [(str/lower-case k) (first vs)]))
   :body (slurp (.getRequestBody ex) :encoding "UTF-8")})

(defn- send! [^HttpExchange ex {:keys [status headers body chunked?]}]
  (doseq [[k v] headers]
    (.add (.getResponseHeaders ex) k v))
  (let [^bytes bs (cond (bytes? body) body
                        (nil? body) (byte-array 0)
                        :else (.getBytes (str body) StandardCharsets/UTF_8))]
    ;; a length of 0 makes the server send the body chunked, without a
    ;; Content-Length, which is how a body of unannounced size arrives
    (.sendResponseHeaders ex (int (or status 200)) (if chunked? 0 (long (alength bs))))
    (with-open [^OutputStream o (.getResponseBody ex)]
      (.write o bs))))

(defn with-server
  "Calls (f base-url requests) with a loopback server answering each
  request with (respond request), a map of :status, :headers, :body
  (string or bytes) and :chunked?; `requests` is an atom of every request
  seen as {:method :path :query :headers :body}. Returns what f returns."
  [respond f]
  (let [seen (atom [])
        srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (try
                          (let [req (request-of ex)]
                            (swap! seen conj req)
                            (send! ex (respond req)))
                          (catch java.io.IOException _ nil)
                          (finally (.close ex))))))
    (.start srv)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress srv))) seen)
      (finally (.stop srv 0)))))
