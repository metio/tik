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
  (cmd [_ line] "Send one tagged command; block for and return its response map.")
  (idle! [_] "Run one IMAP IDLE cycle; see the idle! wrapper for semantics."))

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
          (read-response in tag)))
      ;; One IMAP IDLE cycle (RFC 2177): announce IDLE, wait for the
      ;; server to PUSH activity — returning the new `* n EXISTS` count —
      ;; or for the socket read to time out (nil, a refresh signal), then
      ;; DONE and consume the completion. The caller bounds the wait with
      ;; the socket read timeout so IDLE is re-issued before the ~29-min
      ;; server drop. Correctness never rides on the push: a nil wake still
      ;; drives a cursor fetch, so a missed notification only costs latency.
      (idle! [_]
        (let [tag (str "a" (swap! n inc))]
          (.write w (str tag " IDLE\r\n")) (.flush w)
          (read-line-crlf in)                             ; "+ idling"
          (let [exists (loop []
                         (let [l (try (read-line-crlf in)
                                      (catch java.net.SocketTimeoutException _ ::timeout))]
                           (cond
                             (= l ::timeout) nil
                             (nil? l) nil
                             :else (if-let [m (re-find #"(?i)^\* (\d+) EXISTS" l)]
                                     (parse-long (second m))
                                     (recur)))))]         ; ignore RECENT etc., keep waiting
            (.write w "DONE\r\n") (.flush w)
            (loop []                                       ; drain to the tagged completion
              (let [l (read-line-crlf in)]
                (when (and l (not (str/starts-with? l (str tag " ")))) (recur))))
            exists))))))

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

(defn select-count!
  "SELECT a mailbox and return its current message count — the `* n
  EXISTS` line — which is the sequence-number baseline a watch grows from."
  [s mailbox]
  (let [{:keys [status lines] :as r} (cmd s (str "SELECT " (pr-str (str mailbox))))]
    (when-not (= :ok status)
      (throw (ex-info (str "imap: cannot SELECT " mailbox)
                      {:reason :imap/select :response (last (:lines r))})))
    (or (some #(some-> (re-find #"(?i)^\* (\d+) EXISTS" %) second parse-long) lines) 0)))

(defn fetch-seq-range
  "Fetch a SEQUENCE-number range lo:hi (as `* n EXISTS` reports), each
  message's UID and raw bytes: [{:uid n :raw \"…\"} …]. BODY.PEEK keeps
  the mailbox untouched. Used by the watch to pull only what arrived."
  [s lo hi]
  (let [{:keys [lines literals]} (cmd s (str "FETCH " lo ":" hi " (UID BODY.PEEK[])"))
        uids (keep #(when (re-find #"\{\d+\}$" %)
                      (some-> (re-find #"(?i)UID (\d+)" %) second parse-long))
                   lines)]
    (mapv (fn [uid raw] {:uid uid :raw raw}) uids literals)))

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

(def ^:dynamic *watch-cycles*
  "nil in production (idle forever); a positive integer in tests caps the
  number of IDLE iterations so the loop terminates."
  nil)

(defn watch
  "Hold a persistent IMAP connection and deliver mail as it arrives, via
  IMAP IDLE — the long-running counterpart to fetch-messages. On connect
  it LOGINs, SELECTs, and sweeps the existing backlog (calling `(handle
  uid raw)` per message), then loops: IDLE until the server pushes a new
  `* n EXISTS` (or `:poll-ms`, default 25 min, elapses — re-issuing IDLE
  before the ~29-min server drop), then FETCH the newly-arrived sequence
  range and hand each message off. Any failure closes the socket and
  reconnects with exponential backoff, re-sweeping on reconnect. Runs
  until the process is killed. Correctness is the caller's `handle`
  (idempotent, loop-safe); this only decides WHEN to deliver, so a
  dropped connection or a missed push never loses or double-books mail —
  the re-sweep and the cursor fetch cover it, and dedup absorbs overlap."
  [{:keys [mailbox search poll-ms] :as conn} handle]
  (let [poll (int (or poll-ms (* 25 60 1000)))
        remaining (atom *watch-cycles*)
        more? #(or (nil? @remaining) (pos? @remaining))]
    (loop [backoff 1000]
      (when (more?)
        (let [reconnect?
              (try
                (let [{:keys [^Socket socket session]} (connect conn)]
                  (try
                    (login! session (:user conn) (:password conn))
                    (let [seen0 (select-count! session (or mailbox "INBOX"))]
                      (doseq [{:keys [uid raw]} (fetch-since session (or search "UNSEEN"))]
                        (handle uid raw))
                      (.setSoTimeout socket poll)
                      (loop [seen seen0]
                        (if (more?)
                          (do (when @remaining (swap! remaining dec))
                              (let [n (idle! session)]
                                (recur (if (and n (> (long n) (long seen)))
                                         (do (doseq [{:keys [uid raw]}
                                                     (fetch-seq-range session (inc (long seen)) n)]
                                               (handle uid raw))
                                             n)
                                         seen))))
                          false))                        ; cycles exhausted: clean exit
                      false)
                    (finally
                      (try (.close socket) (catch Exception _ nil)))))
                (catch Exception _ true))]               ; drop/auth failure: reconnect
          (when (and reconnect? (more?))
            (Thread/sleep backoff)
            (recur (min (* backoff 2) 60000))))))))
