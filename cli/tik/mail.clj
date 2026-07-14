;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mail
  "Inbound-mail parsing for the email bridge: a minimal RFC822 reader,
  the sender's true addr-spec, robust ticket association, and the DKIM
  gate. Pure over the message text — tik does not re-implement DKIM's
  fragile canonicalization; it CONSUMES the standard Authentication-
  Results verdict of the MTA it runs behind (RFC 8601), pinned to a
  trusted authserv-id. A leaf: no store, no process control (the DKIM
  refusal throws an ex-info dispatch-guarded turns into `tik: …` + exit 1)."
  (:require [clojure.string :as str]
            [tik.mime :as mime])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)))

(defn addr-spec
  "The RFC 5322 addr-spec of a From/To header value: the address inside
  the angle brackets when present, else the bare address — NEVER an
  address harvested from the display-name phrase, which the sender fully
  controls (`\"admin@corp.com\" <bob@corp.com>` is bob, not admin)."
  [header-value]
  (when header-value
    (let [bracketed (second (re-find #"<([^>]*)>" header-value))]
      (some-> (re-find #"[\w.+-]+@[\w.-]+" (or bracketed header-value))
              str/lower-case))))

(defn parse-rfc822
  "Minimal RFC822: headers to the first blank line, body after.
  Header folding (continuation lines) honored. From/Subject drive the
  human path; In-Reply-To/References/X-Tik-Ticket drive robust
  ticket association (see ticket-ref-of)."
  [text]
  (let [lines (str/split-lines text)
        head (take-while #(not (str/blank? %)) lines)
        headers (loop [hs [] [l & more] head]
                  (cond
                    (nil? l) hs
                    (and (seq hs) (re-matches #"^[ \t].*" l))
                    (recur (conj (pop hs) (str (peek hs) " " (str/trim l))) more)
                    :else (recur (conj hs l) more)))
        header-vals (fn [k]
                      (keep #(when-let [[_ v] (re-matches
                                               (re-pattern (str "(?i)^" k ":\\s*(.*)$")) %)]
                               (str/trim v))
                            headers))
        header (fn [k] (first (header-vals k)))]
    {:from (addr-spec (header "From"))
     :subject (or (header "Subject") "")
     :message-id (some->> (header "Message-ID")
                          (re-find #"<([^>]+)>")
                          second)
     :date (header "Date")
     :in-reply-to (header "In-Reply-To")
     :references (header "References")
     :x-tik-ticket (header "X-Tik-Ticket")
     ;; loop-prevention signals (RFC 3834 and de-facto): an auto-reply,
     ;; a bulk/list send, or a null return-path must never provoke another
     ;; auto-reply — the ingest records them but sets no cascading facts.
     :auto-submitted (header "Auto-Submitted")
     :precedence (header "Precedence")
     :list-id (header "List-Id")
     :auto-response-suppress (header "X-Auto-Response-Suppress")
     :return-path (header "Return-Path")
     ;; every Authentication-Results header (there can be several) — the
     ;; DKIM verdict tik's own MTA stamped; the actor gate reads these
     :auth-results (vec (header-vals "Authentication-Results"))
     ;; the readable body: MIME-decoded (multipart/alternative, base64,
     ;; quoted-printable, HTML→text). A plain message passes through.
     :body (mime/best-text text)}))

(defn parse-date
  "The message's Date header as an Instant (RFC 1123 / 2822), or nil when
  absent or unparsable — a deterministic `:at` for content-addressed
  ingest, so re-polling the same message re-mints the same event."
  [date-header]
  (when date-header
    (try
      (Instant/from (.parse DateTimeFormatter/RFC_1123_DATE_TIME (str/trim date-header)))
      (catch Exception _ nil))))

(def ^:private our-message-id
  ;; our outbound sink stamps Message-ID `<tik.<ticket>.<stage>@…>`; an
  ;; inbound message whose OWN id matches is our mail returned to us.
  #"^tik\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.")

(defn own-message?
  "Is this inbound message one WE sent, come back to us (a bounce, a
  self-subscribed list, a misrouted copy)? The hardest loop stop: our
  outbound Message-ID shape is unmistakable, and a genuine human reply
  never reuses it as its OWN id (it threads on it via In-Reply-To)."
  [{:keys [message-id]}]
  (boolean (and message-id (re-find our-message-id message-id))))

(defn auto-generated?
  "Is this an automatic message that must NOT trigger an auto-reply?
  RFC 3834 `Auto-Submitted` (anything but `no`), a bulk/list/junk
  `Precedence`, a mailing-list `List-Id`, a Microsoft
  `X-Auto-Response-Suppress`, or a null `Return-Path: <>` (bounces and
  auto-responders). Recording such a message is fine; replying to it is
  how a loop is born."
  [{:keys [auto-submitted precedence list-id auto-response-suppress return-path]}]
  (boolean
   (or (and auto-submitted (not (re-matches #"(?i)\s*no\s*" auto-submitted)))
       (and precedence (re-matches #"(?i)\s*(bulk|list|junk)\s*" precedence))
       (not (str/blank? list-id))
       (not (str/blank? auto-response-suppress))
       (and return-path (contains? #{"" "<>"} (str/trim return-path))))))

(defn ticket-ref-of
  "Which ticket an inbound message is about, most reliable source first:
  the explicit `X-Tik-Ticket` header, then a tik-shaped Message-ID the
  reply threads on (`In-Reply-To`/`References` — set automatically by the
  sender's mail client from what the outbound sink stamped), then the
  `[tik <id>]` subject tag as the human-visible fallback. The id is
  ENCODED in the Message-ID, never stored in a lookup table — the
  association is derived, like everything else."
  [{:keys [x-tik-ticket in-reply-to references subject]}]
  (or (some-> x-tik-ticket str/trim not-empty)
      (some->> (str in-reply-to " " references)
               (re-find #"tik\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
               second)
      (second (re-find #"\[tik ([0-9a-f-]+)\]" (str subject)))))

(defn dkim-passing-domains
  "The domains that pass DKIM per the message's `Authentication-Results`
  headers, considering ONLY headers stamped by a trusted verifier
  (authserv-id ∈ `trusted`). tik does not re-implement DKIM's fragile
  canonicalization — it consumes the standard verdict of the MTA it runs
  behind (RFC 8601). SECURITY: that MTA MUST strip inbound A-R headers
  bearing its own authserv-id (RFC 8601 §5), or an attacker forges the
  verdict; pinning `trusted` to your MTA's id is what makes this sound."
  [{:keys [auth-results]} trusted]
  (set
   (for [ar auth-results
         :let [fields (str/split (str ar) #";")
               ;; RFC 8601: `authserv-id [version]` before the first ';',
               ;; so the id is the FIRST whitespace token — not the whole
               ;; field (a version token would else reject legit mail). An
               ;; all-`;`/empty header splits to no fields; guard the nil.
               authserv (some-> (first fields) str/trim (str/split #"\s+")
                                first str/lower-case)]
         :when (and (not (str/blank? authserv)) (contains? trusted authserv))
         method (rest fields)
         :when (re-find #"(?i)\bdkim\s*=\s*pass\b" method)
         :let [d (second (re-find #"(?i)header\.d\s*=\s*([\w.-]+)" method))]
         :when d]
     (str/lower-case d))))

(defn dkim-aligned?
  "Is `from-domain` authenticated by one of the `passing` DKIM domains —
  exact, or a subdomain (relaxed DMARC-style alignment)? Total over a nil
  from-domain (a From with no @domain). NOTE: relaxed alignment trusts
  `header.d` as a registered domain — it does not consult the public
  suffix list, which is sound here because `header.d` comes from a DKIM
  signature the MTA actually verified (nobody can sign as a bare TLD)."
  [from-domain passing]
  (boolean (when from-domain
             (some #(or (= from-domain %) (str/ends-with? from-domain (str "." %)))
                   passing))))

(defn require-dkim!
  "When `bridge.edn` carries `:dkim {:require true :authserv-id …}`, gate
  the sender BEFORE its From→actor mapping is trusted: the From domain
  must be DKIM-authenticated by a trusted verifier, else refuse — the
  sender→actor binding becomes cryptographic, not header-trusting."
  [{:keys [dkim]} {:keys [from] :as msg}]
  (when (:require dkim)
    (let [;; a nil/blank :authserv-id must NOT survive as #{""} — that
          ;; would fail OPEN (matching a blank authserv in a forged A-R).
          ;; Drop blanks so a missing id yields #{} and the guard fires.
          trusted (into #{} (comp (map (comp str/lower-case str))
                                  (remove str/blank?))
                        (let [a (:authserv-id dkim)] (if (coll? a) a [a])))
          from-domain (some-> from (str/split #"@") second str/lower-case)]
      (when (empty? trusted)
        (throw (ex-info ":dkim {:require true} needs :authserv-id (your MTA's verifier id)"
                        {:reason :dkim/no-authserv-id})))
      (let [passing (dkim-passing-domains msg trusted)]
        (when-not (and from-domain (dkim-aligned? from-domain passing))
          (throw (ex-info (str "refusing " (or from "an unsigned sender") ": no dkim=pass"
                               " for " (or from-domain "its domain") " from a trusted"
                               " verifier " trusted
                               (when (seq passing) (str " (passing: " passing ")")))
                          {:reason :dkim/unaligned :from from})))))))
