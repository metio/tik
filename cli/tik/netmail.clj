;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.netmail
  "The bytes-off-a-socket primitives the mail fetchers share: a CRLF line
  reader that treats octets as latin-1 (so a message round-trips losslessly
  into the MIME decoder) and a TLS socket opener. Both IMAP and POP3 are
  line-oriented text protocols over TLS; only the verbs differ, so the
  transport read/connect lives here once."
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net Socket)
           (java.nio.charset StandardCharsets)
           (javax.net.ssl SSLSocketFactory)))

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

(defn tls-socket
  "A TLS socket to host:port (implicit TLS, e.g. IMAPS 993, POP3S 995)."
  ^Socket [host port default-port]
  (.createSocket (SSLSocketFactory/getDefault) ^String host (int (or port default-port))))

(defn plain-socket
  "A plaintext socket to host:port (IMAP 143, POP3 110). Only for a mailbox
  reached over a trusted local path — a loopback relay or an in-pod
  gateway/stunnel sidecar that terminates TLS — never the open internet."
  ^Socket [host port default-port]
  (Socket. ^String host (int (or port default-port))))
