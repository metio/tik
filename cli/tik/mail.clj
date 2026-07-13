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
  (:require [clojure.string :as str]))

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
        [head body] (split-with #(not (str/blank? %)) lines)
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
     :in-reply-to (header "In-Reply-To")
     :references (header "References")
     :x-tik-ticket (header "X-Tik-Ticket")
     ;; every Authentication-Results header (there can be several) — the
     ;; DKIM verdict tik's own MTA stamped; the actor gate reads these
     :auth-results (vec (header-vals "Authentication-Results"))
     :body (str/trim (str/join "\n" (rest body)))}))

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
