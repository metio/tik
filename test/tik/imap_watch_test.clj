;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.imap-watch-test
  "`bridge imap --watch` over a list of mailboxes against a loopback IMAP
  server with IDLE: each mailbox on its own connection and process, mail
  credited only when DKIM passes at the pinned verifier, pushed mail
  ingested on the push, and the transport rules — a certificate must be
  trusted and name the host, plaintext only to loopback, a dropped
  connection says why and never shows the password."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]
            [tik.fake-imap :as fake]
            [tik.harness :as h]
            [tik.imap :as imap]
            [tik.netmail :as net]
            [tik.store.protocol :as store])
  (:import (java.io FileInputStream StringWriter)
           (java.security KeyStore)
           (javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory)))

(defn- mail [{:keys [from subject id authserv d]}]
  (str (when authserv
         (str "Authentication-Results: " authserv "; dkim=pass header.d=" d "\r\n"))
       "From: " from "\r\n"
       "To: support@metio.wtf\r\n"
       "Subject: " subject "\r\n"
       "Date: Fri, 27 Nov 2026 10:00:00 +0000\r\n"
       "Message-ID: <" id "@example.org>\r\n"
       "Content-Type: text/plain; charset=utf-8\r\n"
       "\r\n"
       "Please help.\r\n"))

(defn- config [port]
  {:dkim {:require true :authserv-id "mx.test"}
   :default-actor "mail"
   :mailboxes [{:id "support-metio-wtf" :process :track
                :imap {:host "127.0.0.1" :port port :tls false :user "support" :poll-ms 1000
                       :password {:command ["printf" "%s" "pw-support"]}}}
               {:id "support-klimpi-app" :process :track
                :imap {:host "127.0.0.1" :port port :tls false :user "klimpi" :poll-ms 1000
                       :password {:command ["printf" "%s" "pw-klimpi"]}}}]})

(deftest a_list_of_mailboxes_is_watched_each_mailbox_on_its_own
  (let [{:keys [port deliver! log stop!]} (fake/start! {:users {"support" "pw-support" "klimpi" "pw-klimpi"}})
        {:keys [root store]} (h/temp-store!)
        f (io/file root "imap.edn")]
    (try
      (spit f (pr-str (config port)))
      (deliver! "support" (mail {:from "alice@example.org" :subject "printer on fire" :id "m1"
                                 :authserv "mx.test" :d "example.org"}))
      (deliver! "support" (mail {:from "boss@example.org" :subject "wire money" :id "m2"
                                 :authserv "mx.attacker.test" :d "example.org"}))
      (deliver! "klimpi" (mail {:from "bob@example.org" :subject "app crashes" :id "m3"
                                :authserv "mx.test" :d "example.org"}))
      (let [run (future (binding [imap/*watch-cycles* 3]
                          (h/with-cli-root root #(cli/run-argv ["bridge" "imap" "--watch" "--config" (str f)]))))]
        (Thread/sleep 600)
        (deliver! "support" (mail {:from "carol@example.org" :subject "pushed while idling" :id "m4"
                                   :authserv "mx.test" :d "example.org"}))
        (let [{:keys [exit out err]} (deref run 30000 {:exit :timeout})]
          (is (zero? exit) err)
          (is (re-find #"support-metio-wtf: uid 1: new ticket" out) out)
          (is (re-find #"support-klimpi-app: uid 3: new ticket" out) "the second mailbox ran too")
          (is (re-find #"support-metio-wtf: skip uid 2: refusing boss@example.org: no dkim=pass" err)
              "a verdict stamped by another verifier is not trusted")
          (is (re-find #"support-metio-wtf: uid 4: new ticket" out) "mail pushed during IDLE was ingested")
          (is (some #(str/starts-with? % "IDLE") @log) "over the IDLE path")
          (is (not (str/includes? (str out err) "pw-support")) "the password never shows")
          (is (= 3 (count (store/ticket-ids store))) "three tickets, the forged mail none")))
      (finally (stop!)))))

(deftest one_mailbox_can_be_picked
  (let [{:keys [port deliver! stop!]} (fake/start! {:users {"support" "pw-support" "klimpi" "pw-klimpi"}})
        {:keys [root store]} (h/temp-store!)
        f (io/file root "imap.edn")]
    (try
      (spit f (pr-str (config port)))
      (deliver! "support" (mail {:from "a@example.org" :subject "s" :id "p1" :authserv "mx.test" :d "example.org"}))
      (deliver! "klimpi" (mail {:from "b@example.org" :subject "k" :id "p2" :authserv "mx.test" :d "example.org"}))
      (let [r (h/with-cli-root root #(cli/run-argv ["bridge" "imap" "--config" (str f) "--mailbox" "support-klimpi-app"]))]
        (is (zero? (:exit r)) (:err r))
        (is (= 1 (count (store/ticket-ids store))))
        (is (str/includes? (:out r) "support-klimpi-app: uid 2: new ticket")))
      (let [r (h/with-cli-root root #(cli/run-argv ["bridge" "imap" "--config" (str f) "--mailbox" "nope"]))]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "no mailbox \"nope\"")))
      (finally (stop!)))))

(deftest a_mailbox_list_without_dkim_does_not_start
  (let [{:keys [root]} (h/temp-store!)
        f (io/file root "imap.edn")]
    (spit f (pr-str (dissoc (config 1) :dkim)))
    (let [r (h/with-cli-root root #(cli/run-argv ["bridge" "imap" "--watch" "--config" (str f)]))]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "needs :dkim {:require true :authserv-id …}")))))

;; ------------------------------------------------------------ TLS

(defn- keytool [& args]
  (let [r (apply sh/sh "keytool" args)]
    (when-not (zero? (:exit r)) (throw (ex-info "keytool failed" r)))))

(defn- tls-pair
  "A server key store for a self-signed certificate naming `san`, and a
  trust store holding only that certificate."
  [dir san]
  (let [ks (str (io/file dir "server.p12")) crt (str (io/file dir "server.crt")) ts (str (io/file dir "trust.p12"))]
    (keytool "-genkeypair" "-keyalg" "EC" "-groupname" "secp256r1" "-alias" "s" "-dname" "CN=fake-imap"
             "-ext" (str "san=" san) "-validity" "1" "-keystore" ks "-storetype" "PKCS12" "-storepass" "changeit")
    (keytool "-exportcert" "-alias" "s" "-keystore" ks "-storepass" "changeit" "-file" crt)
    (keytool "-importcert" "-noprompt" "-alias" "s" "-file" crt "-keystore" ts "-storetype" "PKCS12"
             "-storepass" "changeit")
    {:server ks :trust ts}))

(defn- load-store ^KeyStore [path]
  (doto (KeyStore/getInstance "PKCS12")
    (.load (FileInputStream. ^String path) (.toCharArray "changeit"))))

(defn- server-context ^SSLContext [ks]
  (let [kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init (load-store ks) (.toCharArray "changeit")))]
    (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers kmf) nil nil))))

(defn- client-factory [ts]
  (let [tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
              (.init (load-store ts)))]
    (.getSocketFactory (doto (SSLContext/getInstance "TLS") (.init nil (.getTrustManagers tmf) nil)))))

(defn- fetch [port]
  (imap/fetch-messages {:host "127.0.0.1" :port port :user "support" :password "pw"}))

(deftest implicit_tls_needs_a_trusted_certificate_that_names_the_host
  (let [dir (h/temp-dir! "tik-tls")
        good (tls-pair (io/file (doto (io/file dir "good") .mkdirs)) "ip:127.0.0.1")
        other (tls-pair (io/file (doto (io/file dir "other") .mkdirs)) "dns:imap.example.org")]
    (testing "a self-signed certificate is refused by the default trust store"
      (let [{:keys [port stop!]} (fake/start! {:users {"support" "pw"} :ssl-context (server-context (:server good))})]
        (try
          (let [e (try (fetch port) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :imap/connect (:reason (ex-data e))))
            (is (re-find #"(?i)certification path|PKIX|handshake" (ex-message e))))
          (finally (stop!)))))
    (testing "a trust store holding it completes the handshake and the session"
      (let [{:keys [port deliver! stop!]} (fake/start! {:users {"support" "pw"} :ssl-context (server-context (:server good))})]
        (try
          (deliver! "support" (mail {:from "a@example.org" :subject "over tls" :id "t1"}))
          (is (= 1 (count (binding [net/*tls-factory* (client-factory (:trust good))] (fetch port)))))
          (finally (stop!)))))
    (testing "a trusted certificate for another name is refused"
      (let [{:keys [port stop!]} (fake/start! {:users {"support" "pw"} :ssl-context (server-context (:server other))})]
        (try
          (let [e (try (binding [net/*tls-factory* (client-factory (:trust other))] (fetch port)) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :imap/connect (:reason (ex-data e))) "the endpoint check fails the handshake")
            (is (re-find #"(?i)no subject alternative|certificate|handshake" (str (ex-message e)))))
          (finally (stop!)))))))

;; ------------------------------------------------------------ plaintext and drops

(deftest plaintext_to_a_remote_host_is_refused_and_not_retried
  (let [t0 (System/currentTimeMillis)
        e (try (imap/watch {:host "imap.example.org" :tls false :user "u" :password "p"} (fn [_ _]))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :mail/plaintext-refused (:reason (ex-data e))))
    (is (< (- (System/currentTimeMillis) t0) 5000) "at once, without backing off")))

(deftest a_dropped_watch_says_why_and_never_shows_the_password
  (let [{:keys [port stop!]} (fake/start! {:users {"support" "right"}})
        err (StringWriter.)
        w (future (binding [*err* err]
                    (imap/watch {:host "127.0.0.1" :port port :tls false :user "support"
                                 :password "wrong-password-xyz"}
                                (fn [_ _]))))]
    (try
      (Thread/sleep 2500)                                 ; tries at 0 s and 1 s
      (future-cancel w)
      (let [e (str err)]
        (is (re-find #"imap: LOGIN failed \(check user/password\); reconnecting" e))
        (is (<= 2 (count (re-seq #"reconnecting" e))) "it retried, with backoff")
        (is (not (str/includes? e "wrong-password-xyz"))))
      (finally (stop!)))))
