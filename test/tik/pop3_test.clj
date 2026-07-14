;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pop3-test
  "The POP3 client's line protocol against in-memory scripted streams —
  UIDL/LIST parsing, dot-unstuffing of RETR payloads, and the delete
  discipline (a message is removed only after the handler confirms it,
  and never when :delete is off)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.pop3 :as pop3])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)))

(defn- streams [^String script]
  [(ByteArrayInputStream. (.getBytes script StandardCharsets/ISO_8859_1))
   (ByteArrayOutputStream.)])

(defn- sent [^ByteArrayOutputStream out] (.toString out "ISO-8859-1"))

(deftest login_uidl_and_retr_with_dot_unstuffing
  (let [script (str "+OK POP3 ready\r\n"
                    "+OK\r\n"                                   ; USER
                    "+OK\r\n"                                   ; PASS
                    "+OK\r\n1 AAA\r\n2 BBB\r\n.\r\n"            ; UIDL
                    ;; RETR 1: a body line beginning with '.' is sent doubled
                    "+OK 30 octets\r\nline1\r\n..dotted\r\nline3\r\n.\r\n"
                    "+OK\r\nsecond message\r\n.\r\n")           ; RETR 2
        [in out] (streams script)
        s (pop3/session in out)]
    (pop3/login! s "desk@x" "secret")
    (testing "UIDL yields stable per-message ids"
      (is (= [{:num 1 :uidl "AAA"} {:num 2 :uidl "BBB"}] (pop3/list-uidls s))))
    (testing "RETR reassembles the message and un-stuffs leading dots"
      (is (= "line1\r\n.dotted\r\nline3" (pop3/retr s 1)))
      (is (= "second message" (pop3/retr s 2))))
    (testing "credentials were sent"
      (is (str/includes? (sent out) "USER desk@x\r\n"))
      (is (str/includes? (sent out) "PASS secret\r\n")))))

(deftest uidl_unsupported_falls_back_to_list
  (let [script (str "+OK ready\r\n+OK\r\n+OK\r\n"
                    "-ERR UIDL not supported\r\n"               ; UIDL rejected (single line)
                    "+OK 2 messages\r\n1 120\r\n2 340\r\n.\r\n") ; LIST
        [in out] (streams script)
        s (pop3/session in out)]
    (pop3/login! s "u" "p")
    (is (= [{:num 1 :uidl "1"} {:num 2 :uidl "2"}] (pop3/list-uidls s)))
    (is (str/includes? (sent out) "UIDL\r\n"))
    (is (str/includes? (sent out) "LIST\r\n"))))

(deftest bad_password_is_a_clean_ex_info
  (let [[in out] (streams "+OK ready\r\n+OK\r\n-ERR auth failed\r\n")
        s (pop3/session in out)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PASS failed"
                          (pop3/login! s "u" "wrong")))))

(deftest process_mailbox_deletes_only_handled_messages
  (let [script (str "+OK ready\r\n+OK\r\n+OK\r\n"              ; greeting USER PASS
                    "+OK\r\n1 AAA\r\n2 BBB\r\n.\r\n"           ; UIDL
                    "+OK\r\nmsg one\r\n.\r\n"                  ; RETR 1
                    "+OK\r\n"                                  ; DELE 1
                    "+OK\r\nmsg two\r\n.\r\n"                  ; RETR 2
                    "+OK bye\r\n")                             ; QUIT (msg 2 not deleted)
        [in out] (streams script)
        seen (atom [])]
    (with-redefs [tik.pop3/connect (fn [_] {:socket (java.net.Socket.)
                                            :session (pop3/session in out)})]
      (pop3/process-mailbox
       {:user "u" :password "p" :delete true}
       ;; handle the first, REFUSE the second -> only the first is deleted
       (fn [_uidl raw] (swap! seen conj raw) (= raw "msg one"))))
    (is (= ["msg one" "msg two"] @seen) "every message reached the handler")
    (is (str/includes? (sent out) "DELE 1") "the handled message is deleted")
    (is (not (str/includes? (sent out) "DELE 2")) "the refused message is kept on the server")
    (is (str/includes? (sent out) "QUIT"))))

(deftest process_mailbox_never_deletes_when_delete_is_off
  (let [script (str "+OK ready\r\n+OK\r\n+OK\r\n"
                    "+OK\r\n1 AAA\r\n.\r\n"
                    "+OK\r\nonly\r\n.\r\n"
                    "+OK bye\r\n")
        [in out] (streams script)]
    (with-redefs [tik.pop3/connect (fn [_] {:socket (java.net.Socket.)
                                            :session (pop3/session in out)})]
      (pop3/process-mailbox {:user "u" :password "p" :delete false}
                            (fn [_ _] true)))                  ; handler says "done"
    (is (not (str/includes? (sent out) "DELE")) "nothing is deleted with :delete off")))

(deftest connect_refused_is_a_clean_ex_info
  ;; a down POP3 server is operational, not a bug — clean ex-info.
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)]
    (.close ss)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pop3: cannot connect to"
                          (pop3/process-mailbox {:host "127.0.0.1" :port port :tls false
                                                 :user "u" :password "p"}
                                                (fn [_ _] true))))))

(deftest truncated_multiline_terminates
  ;; a hostile/truncated server (no terminating dot) must not hang
  (let [[in out] (streams "+OK ready\r\n+OK\r\n+OK\r\n+OK\r\npartial line no dot")
        s (pop3/session in out)]
    (pop3/login! s "u" "p")
    (is (sequential? (pop3/list-uidls s)) "returns what it got, then stops")))
