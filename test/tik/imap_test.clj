;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.imap-test
  "The IMAP client's line-plus-literal protocol, driven against in-memory
  streams — no socket, no server. A scripted server response exercises
  LOGIN/SELECT/SEARCH/FETCH and the {n} literal splicing that carries a
  raw message's exact bytes; the commands the client emits are asserted
  too."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.imap :as imap])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)))

(defn- fetch-response
  "A `* n FETCH (UID u BODY[] {len}<msg>)` response with the byte-exact
  literal count, followed by its tagged OK."
  [tag seqn uid ^String msg]
  (let [len (alength (.getBytes msg StandardCharsets/ISO_8859_1))]
    (str "* " seqn " FETCH (UID " uid " BODY[] {" len "}\r\n"
         msg ")\r\n"
         tag " OK FETCH completed\r\n")))

(defn- run
  "Run f against a scripted server: returns [result commands-sent]."
  [server-script f]
  (let [in (ByteArrayInputStream. (.getBytes ^String server-script StandardCharsets/ISO_8859_1))
        out (ByteArrayOutputStream.)
        s (imap/session in out)
        result (f s)]
    [result (String. (.toByteArray out) StandardCharsets/ISO_8859_1)]))

(deftest fetch_since_reads_literals_byte_exact
  (let [msg1 "From: a@b\r\nSubject: one\r\n\r\nbody one"
        msg2 "From: c@d\r\nSubject: two\r\n\r\nbody two with a } brace and {5} decoy"
        script (str "* OK ready\r\n"
                    "a1 OK LOGIN ok\r\n"
                    "a2 OK [READ-WRITE] SELECT ok\r\n"
                    "* SEARCH 1 3\r\n"
                    "a3 OK SEARCH ok\r\n"
                    (fetch-response "a4" 1 1 msg1)
                    (fetch-response "a5" 3 3 msg2))
        [msgs cmds] (run script
                         (fn [s]
                           (imap/login! s "user" "pass")
                           (imap/select! s "INBOX")
                           (imap/fetch-since s "UNSEEN")))]
    (testing "both messages fetched, raw bodies byte-exact"
      (is (= [1 3] (map :uid msgs)))
      (is (= msg1 (:raw (first msgs))))
      (is (= msg2 (:raw (second msgs))) "a } or {5} inside the body is not mistaken for a literal"))
    (testing "the client issued the expected tagged commands"
      (is (str/includes? cmds "a1 LOGIN \"user\" \"pass\"\r\n"))
      (is (str/includes? cmds "a2 SELECT \"INBOX\"\r\n"))
      (is (str/includes? cmds "a3 UID SEARCH UNSEEN\r\n"))
      (is (str/includes? cmds "a4 UID FETCH 1 (BODY.PEEK[])\r\n"))
      (is (str/includes? cmds "a5 UID FETCH 3 (BODY.PEEK[])\r\n")))))

(deftest empty_search_fetches_nothing
  (let [script (str "* OK ready\r\n"
                    "a1 OK LOGIN ok\r\n"
                    "a2 OK SELECT ok\r\n"
                    "* SEARCH\r\n"
                    "a3 OK SEARCH ok\r\n")
        [msgs _] (run script
                      (fn [s]
                        (imap/login! s "u" "p")
                        (imap/select! s "INBOX")
                        (imap/fetch-since s "UNSEEN")))]
    (is (= [] msgs))))

(deftest login_failure_is_a_clean_ex_info
  (let [script (str "* OK ready\r\n"
                    "a1 NO [AUTHENTICATIONFAILED] bad creds\r\n")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"LOGIN failed"
                          (run script (fn [s] (imap/login! s "u" "wrong")))))))

(deftest select_failure_is_a_clean_ex_info
  (let [script (str "* OK ready\r\n"
                    "a1 OK LOGIN ok\r\n"
                    "a2 NO mailbox not found\r\n")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot SELECT"
                          (run script (fn [s]
                                        (imap/login! s "u" "p")
                                        (imap/select! s "NoSuch")))))))
