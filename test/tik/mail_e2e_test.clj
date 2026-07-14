;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mail-e2e-test
  "End-to-end mail ingestion against a REAL IMAP/POP3 server (GreenMail,
  in-process) — the confidence the in-memory protocol tests can't give:
  tik's actual client LOGINs, SEARCHes, and FETCHes off a genuine mailbox,
  and the full pipeline mints signed events. Plaintext over loopback
  (`:tls false`) so no self-signed-cert wrangling is needed; the protocol
  logic under test is transport-agnostic, and the TLS wrapper is a one-line
  JDK call the unit tests already stub. JVM suite only — GreenMail (and its
  jakarta.mail) never reach the native binary or babashka."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]
            [tik.cli-core :as cli-core]
            [tik.harness :as h]
            [tik.imap]
            [tik.reduce :as red]
            [tik.store.protocol :as store])
  (:import (com.icegreen.greenmail.util GreenMail ServerSetup ServerSetupTest)
           (jakarta.mail Session)
           (jakarta.mail.internet MimeMessage)
           (java.io ByteArrayInputStream)
           (java.util Properties)))

(defn- greenmail
  "A GreenMail server for the given ServerSetups (the array hint resolves the
  varargs constructor without reflection)."
  ^GreenMail [& setups]
  (GreenMail. ^"[Lcom.icegreen.greenmail.util.ServerSetup;" (into-array ServerSetup setups)))

(defn- deliver!
  "Put a raw RFC822 message straight into a GreenMail user's mailbox (no SMTP
  needed). The login is the local-part of the address; password is 'secret'."
  [^GreenMail gm to raw]
  (let [user (.setUser gm to (first (str/split to #"@")) "secret")
        session (Session/getInstance (Properties.))
        msg (MimeMessage. session (ByteArrayInputStream. (.getBytes ^String raw "UTF-8")))]
    (.deliver user msg)))

(defn- run [root & argv]
  (h/with-cli-root root (fn [] (cli/run-argv (mapv str argv)))))

(defn- ticket-state [root store id]
  (h/with-cli-root root #(:state (cli-core/ticket-ctx store id))))

(deftest imap_e2e_new_ticket_then_reply_with_facts
  (let [gm (greenmail ServerSetupTest/IMAP)]
    (.start gm)
    (try
      (let [{:keys [root store]} (h/temp-store!)
            port (.getPort (.getImap gm))
            cfg (str "{:imap {:host \"127.0.0.1\" :port " port " :tls false"
                     " :user \"desk\" :password \"secret\" :mailbox \"INBOX\" :search \"UNSEEN\"}"
                     " :process :track :default-actor \"inbound\"}")
            _ (spit (io/file root "imap.edn") cfg)
            poll #(run root "bridge" "imap" "--config" (str (io/file root "imap.edn")))]
        (testing "a fresh email fetched off the real server opens a ticket"
          (deliver! gm "desk@example.com"
                    (str "From: alice@customer.example\r\nSubject: cannot log in\r\n"
                         "Message-ID: <e2e-open@customer.example>\r\n\r\nplease help\r\n"))
          (let [r (poll)]
            (is (zero? (:exit r)) (:err r))
            (is (= 1 (count (store/ticket-ids store))))))
        (let [id (first (store/ticket-ids store))
              short (subs (str id) 0 8)]
          (is (= "cannot log in" (:title (ticket-state root store id))))
          (testing "re-polling the same mailbox dedups (no second ticket)"
            (is (zero? (:exit (poll))))
            (is (= 1 (count (store/ticket-ids store)))))
          (testing "a reply carrying a tik> line becomes a signed fact"
            (deliver! gm "desk@example.com"
                      (str "From: alice@customer.example\r\nSubject: Re: [tik " short "] cannot log in\r\n"
                           "Message-ID: <e2e-reply@customer.example>\r\n\r\nmore\r\ntik> sev=high\r\n"))
            (is (zero? (:exit (poll))))
            (is (= 1 (count (store/ticket-ids store))) "the reply comments the existing ticket")
            (is (= :high (red/fact-value (ticket-state root store id) [:sev]))
                "tik> sev=high applied — the full pipeline over a real IMAP fetch"))))
      (finally (.stop gm)))))

(deftest pop3_e2e_ingests_a_real_message
  (let [gm (greenmail ServerSetupTest/POP3)]
    (.start gm)
    (try
      (deliver! gm "desk@example.com"
                (str "From: bob@vendor.example\r\nSubject: pop3 works\r\n"
                     "Message-ID: <e2e-pop@vendor.example>\r\n\r\nbody over pop3\r\n"))
      (let [{:keys [root store]} (h/temp-store!)
            port (.getPort (.getPop3 gm))
            cfg (str "{:pop3 {:host \"127.0.0.1\" :port " port " :tls false"
                     " :user \"desk\" :password \"secret\"}"
                     " :process :track :default-actor \"inbound\"}")]
        (spit (io/file root "pop3.edn") cfg)
        (let [r (run root "bridge" "pop3" "--config" (str (io/file root "pop3.edn")))]
          (is (zero? (:exit r)) (:err r))
          (is (= 1 (count (store/ticket-ids store))) "POP3 RETR off the real server → one ticket")
          (is (= "pop3 works" (:title (ticket-state root store (first (store/ticket-ids store))))))))
      (finally (.stop gm)))))

(deftest imap_watch_e2e_ingests_the_backlog
  ;; --watch against a real server: the initial backlog sweep ingests the
  ;; waiting message, then one IDLE cycle times out (poll-ms) and the loop
  ;; exits (*watch-cycles* caps it so the test can't hang).
  (let [gm (greenmail ServerSetupTest/IMAP)]
    (.start gm)
    (try
      (deliver! gm "desk@example.com"
                (str "From: alice@customer.example\r\nSubject: watched\r\n"
                     "Message-ID: <e2e-watch@customer.example>\r\n\r\nhello over IDLE\r\n"))
      (let [{:keys [root store]} (h/temp-store!)
            port (.getPort (.getImap gm))
            cfg (str "{:imap {:host \"127.0.0.1\" :port " port " :tls false"
                     " :user \"desk\" :password \"secret\" :mailbox \"INBOX\""
                     " :search \"UNSEEN\" :poll-ms 400}"
                     " :process :track :default-actor \"inbound\"}")]
        (spit (io/file root "imap.edn") cfg)
        (binding [tik.imap/*watch-cycles* 1]
          (let [r (run root "bridge" "imap" "--watch" "--config" (str (io/file root "imap.edn")))]
            (is (zero? (:exit r)) (:err r))
            (is (= 1 (count (store/ticket-ids store)))
                "the watch backlog sweep ingested the waiting message"))))
      (finally (.stop gm)))))
