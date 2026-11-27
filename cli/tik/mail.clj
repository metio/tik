;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mail
  "Inbound-mail parsing for the email bridge: a minimal RFC822 reader,
  the sender's true addr-spec, robust ticket association, and the DKIM
  gate. Pure over the message text — tik does not re-implement DKIM's
  fragile canonicalization; it CONSUMES the standard Authentication-
  Results verdict of the MTA it runs behind (RFC 8601): the topmost
  header only, stamped by a trusted authserv-id. A leaf: no store, no process control (the DKIM
  refusal throws an ex-info dispatch-guarded turns into `tik: …` + exit 1)."
  (:require [clojure.string :as str]
            [tik.mime :as mime])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)))

(defn strip-phrases
  "A header value with its RFC 5322 quoted-strings and (nesting) comments
  removed, backslash escapes honored. Both constructs are free-form and
  entirely sender-chosen, and both may contain angle brackets — so an
  address search that runs before this one reads the SENDER'S CHOICE of
  address out of the display name (`\"<victim@corp>\" <me@evil>`) or out
  of a comment (`(<victim@corp>) me@evil`). Removing them first is what
  makes the remaining angle brackets the real routable address."
  [^String s]
  (let [n (count s)
        out (StringBuilder.)]
    (loop [i 0 quoted? false comment-depth 0]
      (if (>= i n)
        (str out)
        (let [c (.charAt s i)]
          (cond
            quoted?
            (case c
              \\ (recur (+ i 2) true comment-depth)
              \" (recur (inc i) false comment-depth)
              (recur (inc i) true comment-depth))

            (pos? comment-depth)
            (case c
              \\ (recur (+ i 2) false comment-depth)
              \( (recur (inc i) false (inc comment-depth))
              \) (recur (inc i) false (dec comment-depth))
              (recur (inc i) false comment-depth))

            (= c \") (recur (inc i) true comment-depth)
            (= c \() (recur (inc i) false 1)
            :else (do (.append out c) (recur (inc i) false comment-depth))))))))

(defn addr-spec
  "The RFC 5322 addr-spec of a From/To header value: the address inside
  the angle brackets when present, else the bare address — NEVER an
  address harvested from the display-name phrase or a comment, both of
  which the sender fully controls (`\"admin@corp.com\" <bob@corp.com>` is
  bob, not admin, and so is `\"<admin@corp.com>\" <bob@corp.com>`).

  nil when the value carries MORE THAN ONE routable address — a group
  address, or the two-bracket spoof `<victim@corp> <me@evil>`. A header
  with no single sender has no actor to attribute to, and guessing which
  one an upstream verifier authenticated is exactly the ambiguity that
  gets exploited; the caller's unknown-sender path is the right answer."
  [header-value]
  (when header-value
    (let [addr #"[\w.+-]+@[\w.-]+"
          bare (strip-phrases header-value)
          bracketed (map second (re-seq #"<([^>]*)>" bare))]
      (cond
        ;; `<a@x> <b@y>` — two routable addresses, no way to tell which
        (< 1 (count bracketed)) nil
        (= 1 (count bracketed)) (some-> (re-find addr (first bracketed))
                                        str/lower-case)
        ;; unbracketed: a bare address is fine, a group list is not
        :else (let [addrs (re-seq addr bare)]
                (when (= 1 (count addrs))
                  (str/lower-case (first addrs))))))))

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
    {;; RFC 5322 permits EXACTLY ONE From. A message carrying two is
     ;; malformed, and verifiers disagree about which one they
     ;; authenticate — the classic way a DKIM-passing message is made to
     ;; display, and here to ATTRIBUTE, as somebody else. With no single
     ;; sender there is nobody to attribute to, so the ingest falls to
     ;; its unknown-sender path (and the DKIM gate, which reads the same
     ;; nil domain, refuses).
     :from (let [from-headers (vec (header-vals "From"))]
             (when (= 1 (count from-headers)) (addr-spec (first from-headers))))
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

;; ---------------------------------------------------------------- authserv-id
;; `:authserv-id` names the verifier whose Authentication-Results tik
;; trusts: a string (exact, case-insensitive), a `{:pattern "…"}` map, or
;; a collection of both. A pattern is matched against the WHOLE id, never
;; a part of it, and is held to a small grammar that can only describe
;; host names under a fixed domain: literal letters, digits and `-`, `\.`,
;; `\d`, character classes of letters, digits and `-`, groups with `|`
;; inside them, and the quantifiers `? * + {n} {n,} {n,m}`. It must end in
;; a literal `\.label\.label`, so every id it accepts ends in that domain.
;; `.`, `^`, `$`, a top-level `|`, inline flags and every other construct
;; are refused: each would let a pattern accept ids nobody meant to trust.

(defn- pattern-tokens
  "The tokens of a pattern source under the grammar above, or a string
  saying what is refused. Each token is [kind text], kind one of :lit
  (one literal character, an escaped dot read as `.`), :class, :quant, :open,
  :close and :alt."
  [^String src]
  (let [n (count src)
        lit? #(re-matches #"[A-Za-z0-9-]" (str %))]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [c (.charAt src i)]
          (cond
            (lit? c) (recur (inc i) (conj acc [:lit (str c)]))

            (= c \\)
            (case (when (< (inc i) n) (.charAt src (inc i)))
              \. (recur (+ i 2) (conj acc [:lit "."]))
              \d (recur (+ i 2) (conj acc [:class "\\d"]))
              (str "the escape " (subs src i (min n (+ i 2))) " is not allowed; only \\. and \\d are"))

            (= c \[)
            (let [close (str/index-of src "]" i)
                  body (when close (subs src (inc i) close))]
              (if (and body (re-matches #"[A-Za-z0-9-]+" body))
                (recur (inc close) (conj acc [:class (subs src i (inc close))]))
                "a character class may hold only letters, digits and ranges, as in [0-9]"))

            (#{\? \* \+} c) (recur (inc i) (conj acc [:quant (str c)]))

            (= c \{)
            (if-let [[q] (re-find #"^\{[0-9]+(,[0-9]*)?\}" (subs src i))]
              (recur (+ i (count q)) (conj acc [:quant q]))
              "a { must start a quantifier such as {1,3}")

            (= c \()
            (if (str/starts-with? (subs src i) "(?:")
              (recur (+ i 3) (conj acc [:open "(?:"]))
              (if (= (when (< (inc i) n) (.charAt src (inc i))) \?)
                "inline flags and look-arounds are not allowed; use (…) or (?:…)"
                (recur (inc i) (conj acc [:open "("]))))

            (= c \)) (recur (inc i) (conj acc [:close ")"]))
            (= c \|) (recur (inc i) (conj acc [:alt "|"]))
            (#{\^ \$} c) (str c " is not allowed; a pattern always matches the whole id")
            (= c \.) "an unescaped . matches any character; write \\. for a dot"
            :else (str "the character " (pr-str (str c)) " is not allowed")))))))

(defn- structure-problem
  "What is wrong with a token list's structure: unbalanced groups, a
  top-level `|`, a quantifier with nothing to repeat, or a tail that does
  not fix the domain."
  [tokens]
  (let [depths (reductions (fn [d [k]] (case k :open (inc d) :close (dec d) d)) 0 tokens)]
    (cond
      (some neg? depths) "a ) closes no group"
      (not (zero? (last depths))) "a ( is never closed"
      (some (fn [[[k] d]] (and (= k :alt) (zero? d))) (map vector tokens depths))
      "a top-level | would accept either side on its own; put the alternatives in a group"
      (some (fn [[[a] [b]]] (and (= b :quant) (#{:quant :open :alt} a)))
            (partition 2 1 (cons [:open] tokens)))
      "a quantifier must follow a character, a class or a group"
      :else
      (let [tail (->> (reverse tokens)
                      (take-while (fn [[k]] (= k :lit)))
                      reverse
                      (map second)
                      str/join)]
        (when-not (re-matches #"[A-Za-z0-9-]*\.[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+" tail)
          (str "a pattern must end in a literal domain such as \\.example\\.org,"
               " so every id it accepts ends in that domain"))))))

(defn pattern-problem
  "Why a `{:pattern …}` source is refused as an authserv-id pattern, or
  nil when it is sound."
  [src]
  (cond
    (not (string? src)) ":pattern must be a string"
    (str/blank? src) ":pattern must not be blank"
    :else
    (let [tokens (pattern-tokens src)
          problem (if (string? tokens) tokens (structure-problem tokens))
          compiled (when-not problem
                     (try (java.util.regex.Pattern/compile src java.util.regex.Pattern/CASE_INSENSITIVE)
                          (catch java.util.regex.PatternSyntaxException e (ex-message e))))]
      (cond
        problem (str ":pattern " (pr-str src) ": " problem)
        (string? compiled) (str ":pattern " (pr-str src) " does not compile: " compiled)
        (.matches (.matcher ^java.util.regex.Pattern compiled ""))
        (str ":pattern " (pr-str src) " matches the empty id")))))

(defn- id-specs
  "The entries of an `:authserv-id` value: a collection's elements, or the
  value itself. A `{:pattern …}` map is one entry, not a collection."
  [a]
  (if (and (coll? a) (not (map? a))) (vec a) [a]))

(defn authserv-id-problems
  "Every problem of an `:authserv-id` value, as strings; empty when it may
  be used. Strict: a blank or missing id is a problem, as is anything
  that is neither a string nor a sound `{:pattern …}` map."
  [a]
  (let [specs (id-specs a)]
    (if (or (nil? a) (empty? specs))
      [":dkim needs :authserv-id, the id the receiving MX stamps on Authentication-Results"]
      (vec (distinct
            (keep (fn [spec]
                    (cond
                      (string? spec)
                      (when (str/blank? spec)
                        ":dkim needs :authserv-id, the id the receiving MX stamps on Authentication-Results")

                      (map? spec)
                      (if (not= #{:pattern} (set (keys spec)))
                        (str ":authserv-id map " (pr-str spec) " must be {:pattern \"…\"} and nothing else")
                        (pattern-problem (:pattern spec)))

                      :else
                      (str ":authserv-id " (pr-str spec)
                           " must be a string, a {:pattern \"…\"} map or a collection of them")))
                  specs))))))

(defn authserv-matcher
  "A predicate over an authserv-id (already lower-cased) that holds when
  the id is trusted by an `:authserv-id` value, or nil when the value
  names no id at all. Blank strings are dropped, so a nil or blank id
  never survives as a match for a forged blank one. A refused pattern or
  a value of the wrong kind throws: the gate fails closed on a config it
  cannot read."
  [a]
  (let [specs (remove #(and (string? %) (str/blank? %)) (when (some? a) (id-specs a)))
        bad (seq (remove #(re-find #"needs :authserv-id" %) (authserv-id-problems specs)))]
    (when bad
      (throw (ex-info (str "the :dkim :authserv-id is refused: " (str/join "; " bad))
                      {:reason :dkim/bad-authserv-id})))
    (when (seq specs)
      (let [exact (into #{} (comp (filter string?) (map str/lower-case)) specs)
            patterns (mapv #(java.util.regex.Pattern/compile
                             (:pattern %) java.util.regex.Pattern/CASE_INSENSITIVE)
                           (filter map? specs))]
        (fn [id]
          (boolean
           (and (string? id) (not (str/blank? id))
                (or (contains? exact id)
                    (some #(.matches (.matcher ^java.util.regex.Pattern % ^String id)) patterns)))))))))

(defn authserv-id-of
  "The authserv-id of one Authentication-Results value, lower-cased: the
  first whitespace token before the first `;` (RFC 8601 allows a version
  token after it). nil for a blank or all-`;` header."
  [ar]
  (some-> (first (str/split (str ar) #";")) str/trim (str/split #"\s+")
          first not-empty str/lower-case))

(defn dkim-passing-domains
  "The domains that pass DKIM per the TOPMOST `Authentication-Results`
  header of the message, when its authserv-id is trusted (`trusted?`, a
  predicate over the lower-cased id; a set of ids works as one).

  Only the topmost header counts, the first in header order: the MTA
  that delivered the message prepended it after every header the sender
  wrote, so a sender cannot place one above it. A header lower down may
  claim any id, including ours, and is never read; when the topmost one
  is not trusted the message has no verdict, whatever sits below it.
  This holds only when the receiving MTA stamps every message it
  delivers; one that delivers some mail without a header of its own
  leaves the sender's forgery on top."
  [{:keys [auth-results]} trusted?]
  (let [top (first auth-results)
        authserv (authserv-id-of top)]
    (if-not (and authserv (trusted? authserv))
      #{}
      (set
       (for [method (rest (str/split (str top) #";"))
             :when (re-find #"(?i)\bdkim\s*=\s*pass\b" method)
             :let [d (second (re-find #"(?i)header\.d\s*=\s*([\w.-]+)" method))]
             :when d]
         (str/lower-case d))))))

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
  must be DKIM-authenticated by the topmost Authentication-Results header,
  stamped by a trusted verifier, else refuse — the sender→actor binding
  becomes cryptographic, not header-trusting."
  [{:keys [dkim]} {:keys [from] :as msg}]
  (when (:require dkim)
    (let [trusted? (authserv-matcher (:authserv-id dkim))
          from-domain (some-> from (str/split #"@") second str/lower-case)]
      (when-not trusted?
        (throw (ex-info ":dkim {:require true} needs :authserv-id (your MTA's verifier id)"
                        {:reason :dkim/no-authserv-id})))
      (let [passing (dkim-passing-domains msg trusted?)
            top (authserv-id-of (first (:auth-results msg)))]
        (when-not (and from-domain (dkim-aligned? from-domain passing))
          (throw (ex-info (str "refusing " (or from "an unsigned sender") ": no dkim=pass"
                               " for " (or from-domain "its domain")
                               " in the topmost Authentication-Results from a trusted verifier "
                               (pr-str (:authserv-id dkim))
                               (cond
                                 (nil? top) " (the message has none)"
                                 (not (trusted? top)) (str " (the topmost is stamped by " top ")")
                                 (seq passing) (str " (passing: " passing ")")))
                          {:reason :dkim/unaligned :from from})))))))
