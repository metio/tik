;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pop3
  "A minimal POP3-over-TLS client: USER/PASS, UIDL (stable per-message
  ids), RETR, and optional DELE — the smaller sibling of tik.imap for the
  many mailboxes that only speak POP3. Same stance: no mail library, an
  SSLSocket and a line protocol that run on babashka and native alike.
  Feeds the same raw RFC822 bytes into the shared ingestion core.

  Delete is coupled to ingest by design: RETR message numbers are only
  valid within one session, so `process-mailbox` interleaves fetch,
  a caller-supplied handler, and DELE — a message is removed ONLY after
  the handler confirms it was dealt with, and QUIT commits the deletes."
  (:require [clojure.string :as str]
            [tik.netmail :as net :refer [read-line-crlf]])
  (:import (java.io InputStream OutputStream OutputStreamWriter)
           (java.net Socket)
           (java.nio.charset StandardCharsets)))

(defn- ok? [line] (some-> line (str/starts-with? "+OK")))

(defn- read-until-dot
  "The data lines of a POP3 multi-line response: read until a line that is
  exactly `.`, undoing dot-stuffing (a data line beginning with `.` was
  sent doubled). Lines come back with their CRLFs already stripped."
  [^InputStream in]
  (loop [acc []]
    (let [line (read-line-crlf in)]
      (cond
        (nil? line) acc                                    ; truncated: stop, don't hang
        (= line ".") acc                                   ; terminator
        :else (recur (conj acc (if (str/starts-with? line ".") (subs line 1) line)))))))

(defprotocol Pop3
  (line-cmd [_ s] "Send a command; read one +OK/-ERR status line.")
  (multi-cmd [_ s] "Send a command; on +OK read its data lines to the dot."))

(defn session
  "A POP3 session over an open input/output stream pair; the +OK greeting
  is consumed on construction."
  [^InputStream in ^OutputStream out]
  (let [w (OutputStreamWriter. out StandardCharsets/ISO_8859_1)
        send! (fn [s] (.write w (str s "\r\n")) (.flush w))]
    (read-line-crlf in)                                    ; greeting
    (reify Pop3
      (line-cmd [_ s] (send! s) (read-line-crlf in))
      (multi-cmd [_ s]
        (send! s)
        (let [status (read-line-crlf in)]
          (if (ok? status)
            {:status :ok :lines (read-until-dot in)}
            {:status :err :line status}))))))

(defn login! [s user pass]
  (when-not (ok? (line-cmd s (str "USER " user)))
    (throw (ex-info "pop3: server rejected USER" {:reason :pop3/user})))
  (when-not (ok? (line-cmd s (str "PASS " pass)))
    (throw (ex-info "pop3: PASS failed (check user/password)" {:reason :pop3/pass})))
  s)

(defn list-uidls
  "Every waiting message as {:num n :uidl id}. UIDL gives a server-stable
  id; if the server lacks it, fall back to LIST with the message number as
  the id (dedup ultimately keys on the message's own Message-ID anyway)."
  [s]
  (let [{:keys [status lines]} (multi-cmd s "UIDL")]
    (if (= :ok status)
      (for [l lines
            :let [[_ n uid] (re-matches #"\s*(\d+)\s+(\S+)\s*" l)]
            :when n]
        {:num (parse-long n) :uidl uid})
      (for [l (:lines (multi-cmd s "LIST"))
            :let [[_ n] (re-matches #"\s*(\d+)\s+.*" l)]
            :when n]
        {:num (parse-long n) :uidl n}))))

(defn retr
  "The raw RFC822 message for a message number, reassembled from its
  dot-unstuffed lines (latin-1, CRLF-joined) — byte-faithful for MIME."
  [s num]
  (let [{:keys [status lines]} (multi-cmd s (str "RETR " num))]
    (when (= :ok status) (str/join "\r\n" lines))))

(defn connect
  "Open a session to a POP3 server — implicit TLS on port 995 by default,
  or plaintext (port 110) when the config sets `:tls false` for a
  loopback/trusted-relay mailbox. Returns {:session … :socket …}."
  [{:keys [host port] :as conn}]
  (let [tls? (not (false? (:tls conn true)))]
    (try
      (let [sock ^Socket (if tls? (net/tls-socket host port 995) (net/plain-socket host port 110))]
        {:socket sock
         :session (session (.getInputStream sock) (.getOutputStream sock))})
      ;; a down / unresolvable server is operational, not a bug — clean ex-info.
      (catch java.io.IOException e
        (throw (ex-info (str "pop3: cannot connect to " host ":" (or port (if tls? 995 110))
                             " — " (ex-message e))
                        {:reason :pop3/connect :host host} e))))))

(defn process-mailbox
  "Open POP3-over-TLS, LOGIN, and for each waiting message call
  `(handle uidl raw)`. If it returns truthy AND `:delete` is set, DELE
  that message; QUIT commits the deletes and closes. Handler failures
  never abort the run — that policy lives in the handler. The socket is
  always closed."
  [{:keys [delete] :as conn} handle]
  (let [{:keys [^Socket socket session]} (connect conn)]
    (try
      (login! session (:user conn) (:password conn))
      (doseq [{:keys [num uidl]} (list-uidls session)]
        (when-let [raw (retr session num)]
          (when (and (handle uidl raw) delete)
            (line-cmd session (str "DELE " num)))))
      (try (line-cmd session "QUIT") (catch Exception _ nil))
      (finally
        (try (.close socket) (catch Exception _ nil))))))
