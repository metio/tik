;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.netmail
  "The bytes-off-a-socket primitives the mail fetchers share: a CRLF line
  reader that treats octets as latin-1 (so a message round-trips losslessly
  into the MIME decoder) and a TLS socket opener. Both IMAP and POP3 are
  line-oriented text protocols over TLS; only the verbs differ, so the
  transport read/connect lives here once."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net Socket)
           (java.nio.charset StandardCharsets)
           (javax.net.ssl SSLParameters SSLSocket SSLSocketFactory)))

(defn read-line-crlf
  "One response line without its trailing CRLF, or nil at end of stream.
  Reads octets as latin-1 so any following byte-count (an IMAP literal) is
  exact and binary payloads survive intact."
  ^String [^InputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (= b -1) (when (pos? (.size buf))
                     (String. (.toByteArray buf) StandardCharsets/ISO_8859_1))
          (= b 10) (let [^bytes arr (.toByteArray buf)
                         len (alength arr)
                         len (int (if (and (pos? len) (= 13 (aget arr (dec len)))) (dec len) len))]
                     (String. arr 0 len StandardCharsets/ISO_8859_1))
          :else (do (.write buf b) (recur)))))))

(defn loopback-host?
  "Is `host` written as a loopback address: `localhost`, an IPv4 address
  in 127.0.0.0/8 or `::1`? Judged on the text alone, without asking DNS,
  so a name that merely resolves to loopback today does not qualify."
  [host]
  (let [h (str/lower-case (str host))]
    (boolean
     (or (= "localhost" h)
         (= "::1" h) (= "[::1]" h)
         (when-let [[_ & octets] (re-matches #"127\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})" h)]
           (every? #(<= 0 (parse-long %) 255) octets))))))

(def ^:dynamic *tls-factory*
  "The factory TLS sockets come from; nil is the JVM default, which trusts
  the default trust store (or the one -Djavax.net.ssl.trustStore names).
  Tests bind one that trusts a self-signed certificate."
  nil)

(defn tls-socket
  "A TLS socket to host:port (implicit TLS, e.g. IMAPS 993, POP3S 995),
  handshaken, with the certificate checked against `host`. A raw
  SSLSocket checks only that the chain is trusted, so without the
  endpoint check any certificate a trusted CA issued for any name would
  be accepted for this host."
  ^Socket [host port default-port]
  (let [^SSLSocketFactory f (or *tls-factory* (SSLSocketFactory/getDefault))
        ^SSLSocket s (.createSocket f ^String host (int (or port default-port)))
        ^SSLParameters params (.getSSLParameters s)]
    (.setEndpointIdentificationAlgorithm params "HTTPS")
    (.setSSLParameters s params)
    (try (.startHandshake s)
         (catch java.io.IOException e
           (try (.close s) (catch Exception _ nil))
           (throw e)))
    s))

(defn plain-socket
  "A plaintext socket to host:port (IMAP 143, POP3 110), for a mailbox
  reached over a loopback path only — a local relay or a stunnel that
  terminates TLS. Any other host is refused before a byte is sent, since
  the password would cross the network in the clear."
  ^Socket [host port default-port]
  (when-not (loopback-host? host)
    (throw (ex-info (str "plaintext mail is refused for " host
                         "; only a loopback address may skip TLS (set :tls true or drop :tls)")
                    {:reason :mail/plaintext-refused :host host})))
  (Socket. ^String host (int (or port default-port))))
