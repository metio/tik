;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.serve-guard-test
  (:require [clojure.test :refer [deftest is]]
            [tik.cli-core :as cli-core]
            [tik.query :as query]
            [tik.serve :as serve]))

(defn- request [host] {:headers (if host {"host" host} {})})

(deftest a-loopback-server-answers-only-to-its-loopback-names
  (is (nil? (serve/loopback-refusal "127.0.0.1" 7777 (request "127.0.0.1:7777"))))
  (is (nil? (serve/loopback-refusal "127.0.0.1" 7777 (request "localhost:7777"))))
  (is (= 421 (:status (serve/loopback-refusal "127.0.0.1" 7777 (request "rebound.example:7777"))))
      "a rebinding page sends its own host name")
  (is (= 421 (:status (serve/loopback-refusal "127.0.0.1" 7777 (request nil)))))
  (is (= 421 (:status (serve/loopback-refusal "127.0.0.1" 7777 (request "127.0.0.1:8888"))))))

(deftest every-loopback-spelling-is-guarded
  (is (= 421 (:status (serve/loopback-refusal "127.0.0.2" 7777 (request "rebound.example:7777")))))
  (is (nil? (serve/loopback-refusal "127.0.0.2" 7777 (request "127.0.0.2:7777"))))
  (is (= 421 (:status (serve/loopback-refusal "::1" 7777 (request "rebound.example:7777")))))
  (is (nil? (serve/loopback-refusal "::1" 7777 (request "[::1]:7777"))))
  (is (= 421 (:status (serve/loopback-refusal "localhost" 7777 (request "rebound.example:7777"))))))

(deftest a-server-for-other-hosts-is-not-judged
  (is (nil? (serve/loopback-refusal "0.0.0.0" 7777 (request "tik.example.org"))))
  (is (nil? (serve/loopback-refusal "10.0.0.5" 7777 (request "10.0.0.5:7777")))))

(deftest no-request-reaches-the-process-exit
  ;; a route whose command calls die answers 500; the exit the server
  ;; process was started with is never called
  (let [exited (atom nil)
        handler (serve/request-handler {:host "0.0.0.0" :port 7777 :board? true})]
    (binding [cli-core/*exit-fn* (fn [code] (reset! exited code) (throw (ex-info "exited" {})))]
      (with-redefs [query/cmd-board (fn [_] (cli-core/die "no process named 'gone'"))]
        (let [r (handler {:uri "/" :headers {"host" "tik.example.org"}})]
          (is (= 500 (:status r)))
          (is (re-find #"tik exit 1" (:body r))))))
    (is (nil? @exited))))
