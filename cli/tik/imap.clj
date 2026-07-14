;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.imap
  "A minimal IMAP-over-TLS client: exactly the subset needed to fetch new
  mail — LOGIN, SELECT, UID SEARCH, UID FETCH BODY.PEEK[] — and nothing
  more. No mail library: jakarta.mail's Java classes are absent on
  babashka and fight GraalVM native-image, whereas an SSLSocket and a
  line-plus-literal protocol run identically on both. MIME/DKIM/routing
  are handled downstream by the existing bridge; this layer only moves
  raw RFC822 bytes off the server.

  The protocol runs against a plain input/output stream pair (`session`,
  `fetch-since`), so it is testable end-to-end against in-memory streams;
  `connect` supplies the real TLS socket. Bytes are read as ISO-8859-1
  (each octet -> one char) so a message round-trips losslessly into the
  MIME decoder."
  (:require [clojure.string :as str]
            [tik.netmail :as net :refer [read-line-crlf]])
  (:import (java.io ByteArrayOutputStream InputStream OutputStream
                    OutputStreamWriter)
           (java.net Socket)
           (java.nio.charset StandardCharsets)))

(defn- read-n
  "Exactly n octets as a latin-1 string — an IMAP literal payload."
  ^String [^InputStream in n]
  (let [buf (ByteArrayOutputStream.)]
    (loop [left n]
      (if (pos? left)
        (let [b (.read in)]
          (if (= b -1)
            (String. (.toByteArray buf) StandardCharsets/ISO_8859_1)
            (do (.write buf b) (recur (dec left)))))
        (String. (.toByteArray buf) StandardCharsets/ISO_8859_1)))))

(defn- read-response
  "Read one full tagged response: every untagged `*` line — splicing in
  any `{n}` literal payloads — until the line beginning with `tag`.
  Returns {:status :ok|:no|:bad :lines [line…] :literals [payload…]}."
  [^InputStream in tag]
  (loop [lines [] literals []]
    (let [line (read-line-crlf in)]
      (if (nil? line)
        {:status :bad :lines lines :literals literals :eof true}
        (let [lit (when-let [[_ n] (re-find #"\{(\d+)\}$" line)]
                    (read-n in (parse-long n)))
              lines' (conj lines line)
              literals' (cond-> literals lit (conj lit))]
          (if (str/starts-with? line (str tag " "))
            {:status (cond (str/starts-with? line (str tag " OK")) :ok
                           (str/starts-with? line (str tag " NO")) :no
                           :else :bad)
             :lines lines' :literals literals'}
            (recur lines' literals')))))))

(defprotocol Session
  (cmd [_ line] "Send one tagged command; block for and return its response map."))

(defn session
  "An IMAP command session over an already-open input/output stream pair.
  Tags commands `a1`, `a2`, … and blocks for each tagged completion; the
  server greeting is consumed on construction."
  [^InputStream in ^OutputStream out]
  (let [w (OutputStreamWriter. out StandardCharsets/ISO_8859_1)
        n (atom 0)]
    (read-line-crlf in)                                   ; greeting
    (reify Session
      (cmd [_ line]
        (let [tag (str "a" (swap! n inc))]
          (.write w (str tag " " line "\r\n"))
          (.flush w)
          (read-response in tag))))))

(defn login! [s user pass]
  (let [{:keys [status] :as r} (cmd s (str "LOGIN " (pr-str (str user)) " " (pr-str (str pass))))]
    (when-not (= :ok status)
      (throw (ex-info "imap: LOGIN failed (check user/password)"
                      {:reason :imap/login :response (last (:lines r))})))
    s))

(defn select! [s mailbox]
  (let [{:keys [status] :as r} (cmd s (str "SELECT " (pr-str (str mailbox))))]
    (when-not (= :ok status)
      (throw (ex-info (str "imap: cannot SELECT " mailbox)
                      {:reason :imap/select :response (last (:lines r))})))
    s))

(defn search-uids
  "UIDs matching an IMAP search key (e.g. \"UNSEEN\", \"ALL\", \"UID
  n:*\"). Parses the untagged `* SEARCH …` line; [] on a NO/BAD."
  [s search-key]
  (let [{:keys [status lines]} (cmd s (str "UID SEARCH " search-key))]
    (if (not= :ok status)
      []
      (->> lines
           (some #(second (re-matches #"(?i)\* SEARCH\s*(.*)" %)))
           str
           (re-seq #"\d+")
           (mapv parse-long)))))

(defn fetch-raw
  "The raw RFC822 message for one UID via BODY.PEEK[] — PEEK so the
  \\Seen flag is untouched and the fetch stays a pure, repeatable read.
  Returns the latin-1 message string, or nil if no literal came back."
  [s uid]
  (first (:literals (cmd s (str "UID FETCH " uid " (BODY.PEEK[])")))))

(defn fetch-since
  "Fetch every message matching `search-key` (default \"UNSEEN\") from an
  open, selected session. Returns [{:uid n :raw \"…\"} …] in UID order.
  Read-only: BODY.PEEK never sets \\Seen, so re-running re-fetches the
  same set and downstream content-addressing dedups it."
  ([s] (fetch-since s "UNSEEN"))
  ([s search-key]
   (->> (search-uids s search-key)
        (keep (fn [uid] (when-let [raw (fetch-raw s uid)] {:uid uid :raw raw})))
        vec)))

(defn connect
  "Open a TLS session to an IMAP server (implicit TLS, port 993 by
  default). Returns {:session … :socket …}; the caller LOGINs/SELECTs and
  closes the socket when done."
  [{:keys [host port]}]
  (let [sock ^Socket (net/tls-socket host port 993)]
    {:socket sock
     :session (session (.getInputStream sock) (.getOutputStream sock))}))

(defn fetch-messages
  "The whole client: connect, LOGIN, SELECT, fetch, LOGOUT, close. Returns
  [{:uid :raw} …]. Network/auth failures surface as ex-info (fail-well);
  the socket is always closed."
  [{:keys [mailbox search] :as conn}]
  (let [{:keys [^Socket socket session]} (connect conn)]
    (try
      (login! session (:user conn) (:password conn))
      (select! session (or mailbox "INBOX"))
      (let [msgs (fetch-since session (or search "UNSEEN"))]
        (try (cmd session "LOGOUT") (catch Exception _ nil))
        msgs)
      (finally
        (try (.close socket) (catch Exception _ nil))))))
