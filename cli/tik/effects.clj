;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.effects
  "The outbound alerts bridge (ADR 0019): derived stage transitions
  fanned to slack/discord/webhook/email/command/... sinks. Each sink is a
  pure payload mapping; the :command sink pipes the webhook JSON to any
  program; secrets in any sink field resolve through tik.secret at send
  time. Delivery state is disposable — the ledger makes it at-least-once,
  and truth is never touched."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.args :refer [read-edn-file]]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [all-ticket-ctx die display-title exit! now
                                  root the-store]]
            [tik.explain :as explain]
            [tik.render :refer [sid]]
            [tik.secret :as secret]
            [tik.stage :as stage]
            [tik.text :refer [safe-name]]))

(defn render-template
  "{{ticket}} {{short}} {{title}} {{stage}} in the sink's own words —
  every sink may carry a :template; the default reads like a log line."
  [template {:keys [ticket title stage]}]
  (-> template
      (str/replace "{{ticket}}" (str ticket))
      (str/replace "{{short}}" (sid ticket))
      (str/replace "{{title}}" (str title))
      (str/replace "{{stage}}" (str stage))))

(defn effect-payload
  "One derived transition in each service's native shape. Every
  adapter is a pure data mapping over the same {ticket title stage};
  credentials, addressing and message :template come from the sink's
  own config. The fallback shape is the stable :webhook contract."
  [{:keys [type] :as sink} {:keys [ticket title stage] :as tr}]
  (let [text (render-template
              (or (:template sink)
                  "tik: \"{{title}}\" reached {{stage}} ({{short}})")
              tr)]
    (case type
      :slack {"text" text}
      :discord {"content" text}
      :matrix {"msgtype" "m.text" "body" text}
      :mattermost {"text" text}
      :rocketchat {"text" text}
      :googlechat {"text" text}
      :teams {"type" "message" "text" text}
      :ntfy {"topic" (:topic sink) "title" (str "tik: " title)
             "message" (if (:template sink) text (str "reached " stage))}
      :gotify {"title" (str "tik: " title)
               "message" (if (:template sink) text (str "reached " stage))
               "priority" 5}
      :pushover {"token" (:token sink) "user" (:user sink)
                 "message" text}
      :telegram {"chat_id" (:chat-id sink) "text" text}
      :opsgenie {"message" text
                 "alias" (str ticket ":" stage)
                 "source" "tik"}
      :alertmanager [{"labels" {"alertname" "tik_stage_reached"
                                "ticket" (str ticket)
                                "stage" (str stage)}
                      "annotations" {"summary" text}}]
      :pagerduty {"payload" {"summary" text
                             "source" "tik"
                             "severity" "info"}
                  "event_action" "trigger"}
      {"ticket" (str ticket) "title" title
       "stage" (str stage) "text" text})))

(defn email-message
  "RFC822 text asking a human for what the stage needs. Association is
  carried three ways, robust first: a tik-shaped Message-ID (the
  sender's client threads on it via In-Reply-To/References, so a plain
  reply routes back with no cooperation), an explicit X-Tik-Ticket
  header (for filters/clients), and the [tik <id>] subject tag (the
  human-visible fallback). The body renders explain and teaches the
  reply convention — the email IS a capability-scoped view of the same
  derivation."
  [{:keys [to from]} {:keys [ticket title stage]} explain-text]
  (let [;; a header value must carry no CR/LF — else a hostile title
        ;; injects a new header (Bcc: attacker@…) that `sendmail -t`
        ;; honors (RFC 5322 header injection). Collapse them.
        h #(str/replace (str %) #"[\r\n]+" " ")
        title (h title)]
   (str "To: " (h to) "\r\n"
       "From: " (h (or from "tik")) "\r\n"
       ;; the ticket id is ENCODED in the Message-ID (per stage, so each
       ;; notification is a distinct message), never a stored map — a
       ;; reply's In-Reply-To carries it straight back to ticket-ref-of
       "Message-ID: <tik." ticket "." (name stage) "@tik.local>\r\n"
       "X-Tik-Ticket: " ticket "\r\n"
       ;; loop citizenship: this IS an automatic message (RFC 3834), so a
       ;; well-behaved recipient MUST NOT auto-reply to it — and X-Tik-Loop
       ;; makes our own mail unmistakable if it ever returns to the inbox.
       "Auto-Submitted: auto-generated\r\n"
       "X-Tik-Loop: " ticket "\r\n"
       "Subject: [tik " ticket "] " title " — " stage "\r\n"
       "\r\n"
       "\"" title "\" reached " stage " and needs something only you"
       " can provide.\r\n\r\n"
       explain-text
       "\r\nReply to this email. Lines like\r\n\r\n"
       "  tik> key=value\r\n\r\n"
       "become facts on the ticket (everything else is kept as a"
       " comment), and the process moves on the moment the facts"
       " arrive.\r\n")))

(defn json-str
  "Tiny JSON emitter for the flat payloads above — no dependency."
  [x]
  (cond
    (map? x) (str "{" (str/join "," (for [[k v] x]
                                      (str (json-str (str k)) ":" (json-str v))))
                  "}")
    (sequential? x) (str "[" (str/join "," (map json-str x)) "]")
    (string? x) (str "\"" (-> x (str/replace "\\" "\\\\")
                               (str/replace "\"" "\\\"")
                               ;; RFC 8259: EVERY control char U+0000–U+001F
                               ;; must be escaped, not just \n — a raw CR/TAB
                               ;; in a hostile title else emits invalid JSON.
                               (str/replace #"[\u0000-\u001f]"
                                            (fn [c]
                                              (case c
                                                "\n" "\\n" "\r" "\\r" "\t" "\\t"
                                                (format "\\u%04x"
                                                        (int (.charAt ^String c 0)))))))
                     "\"")
    :else (str x)))

(defn redact-url
  "scheme://host[:port] of a sink URL — a webhook path/query often IS the
  secret (Slack/Discord tokens live in the path), so the full URL must
  never reach a printed error or the ledger; only the endpoint identity."
  [url]
  (try (let [u (java.net.URI/create (str url))]
         (str (.getScheme u) "://" (.getHost u)
              (when (pos? (.getPort u)) (str ":" (.getPort u)))))
       (catch Exception _ "the sink URL")))

(defn post!
  "POST JSON; babashka's built-in http client, resolved lazily so the
  namespace loads on the JVM too. Extra headers come from the sink's
  :headers — enough for every token-authenticated service (opsgenie's
  GenieKey, gotify's X-Gotify-Key, bearer tokens) without tik ever
  storing credentials anywhere but the operator's own config or, via
  {:env \"NAME\"} values, the process environment."
  ;; `headers` arrive already secret-resolved (the whole sink is resolved
  ;; before dispatch), so this only merges the content type.
  [url body headers]
  (let [post (requiring-resolve 'babashka.http-client/post)
        r (post url {:headers (merge {"Content-Type" "application/json"} headers)
                     :body body
                     :throw false})]
    ;; a non-2xx (429/400/5xx) is a DELIVERY FAILURE — throw so the caller
    ;; counts it failed and leaves the ledger unmarked (retry next run),
    ;; not silently ledger it as sent (at-least-once, ADR 0019). The
    ;; message carries only the redacted endpoint — the full URL can hold
    ;; a webhook secret and this message is printed to stderr on failure.
    (when-not (<= 200 (long (or (:status r) 0)) 299)
      (throw (ex-info (str "POST " (redact-url url) " → HTTP " (:status r))
                      {:status (:status r) :host (redact-url url)})))
    r))

(defn cmd-effects
  "effects run [--config effects.edn] [--dry-run]
  Fire configured sinks for every newly derived stage transition.
  Config: {:sinks [{:type :slack :url \"…\"} …] :stages #{:landed}}
  (:stages optional — default every transition). Idempotent via
  content-hashed effect keys in .effects-sent; at-least-once on ledger
  loss, exactly what ADR 0019 promises."
  [{:keys [pos opts]}]
  (when-not (= "run" (first pos))
    (die "usage: tik effects run [--config effects.edn] [--dry-run]"))
  (let [cfg-file (or (:config opts) (str (io/file (root) "effects.edn")))
        _ (when-not (.exists (io/file cfg-file))
            (die "no effects config:" cfg-file))
        {:keys [sinks stages]} (read-edn-file (io/file cfg-file))
        ledger-file (io/file (root) ".effects-sent")
        sent (if (.exists ledger-file)
               (set (str/split-lines (slurp ledger-file)))
               #{})
        s (the-store)
        fired (atom 0)
        failed (atom 0)]
    (doseq [{:keys [id events state process roles]} (all-ticket-ctx s)
            :let [timeline (:timeline (stage/evolve process events roles))
                  transitions
                  (distinct
                   (for [[prev entry] (map vector (cons nil timeline) timeline)
                         stage-id (sort-by str (remove (:reached prev #{})
                                                       (:reached entry)))
                         :when (or (nil? stages) (contains? stages stage-id))]
                     {:ticket id :title (display-title state) :stage stage-id}))]
            tr transitions
            sink sinks
            :let [key (canonical/sha256-hex
                       (pr-str [(:ticket tr) (:stage tr)
                                (:type sink) (:url sink) (:to sink)
                                (:command sink) (:topic sink)
                                (:chat-id sink)]))]
            :when (not (contains? sent key))]
      (if (:dry-run opts)
        (println "would send" (safe-name (:type sink)) "<-"
                 (str (sid (:ticket tr)) " " (:stage tr)))
        ;; one dead endpoint must not abandon the other sinks: failures
        ;; report and count, the ledger stays unmarked (retry next run),
        ;; the loop continues — at-least-once, per sink
        (try
          ;; resolve secrets ONLY now, at real send: any sink field (a
          ;; webhook :url, pushover :token/:user, a header value) may be a
          ;; {:env}/{:command}/{:file} spec (tik.secret). The dedup key
          ;; above hashes the UNRESOLVED sink, so a rotated secret keeps
          ;; the same effect identity and a dry-run never calls `pass`.
          (let [sink (secret/resolve-secrets sink)]
          (case (:type sink)
              ;; sendmail-compatible: the :command reads RFC822 on stdin
              ;; (default sendmail -t); MTA-agnostic like the inbound
              ;; bridge — procmail in, sendmail out, tik stays porcelain
              :email
              (let [text (email-message
                          sink tr
                          (explain/render
                           (explain/explain process events (now) roles)))
                    cmdv (or (:command sink) ["sendmail" "-t"])
                    r (apply sh/sh (concat cmdv [:in text]))]
                ;; throw, don't die: die -> System/exit would abort the
                ;; WHOLE run (remaining sinks/tickets) on the native binary;
                ;; the catch below counts this one failed and continues
                (when-not (zero? (:exit r))
                  (throw (ex-info (str "email sink failed: " (:err r))
                                  {:sink :email}))))
              ;; the universal escape hatch: the webhook JSON on stdin
              ;; to ANY program — notify-send wrappers, SMS gateways,
              ;; syslog, a shop's existing paging script
              :command
              (let [r (apply sh/sh (concat (:command sink)
                                           [:in (json-str
                                                 (effect-payload
                                                  (assoc sink :type :webhook)
                                                  tr))]))]
                (when-not (zero? (:exit r))
                  (throw (ex-info (str "command sink failed: " (:err r))
                                  {:sink :command}))))
              (post! (:url sink) (json-str (effect-payload sink tr))
                     (:headers sink))))
              (spit ledger-file (str key "\n") :append true)
              (swap! fired inc)
              (println "sent" (safe-name (:type sink)) "<-"
                       (str (sid (:ticket tr)) " "
                            (:stage tr)))
          (catch Exception e
            (swap! failed inc)
            (binding [*out* *err*]
              (println (str "failed " (safe-name (:type sink)) " <- "
                            (sid (:ticket tr)) " " (:stage tr)
                            ": " (ex-message e)
                            " — will retry next run")))))))
    (when-not (:dry-run opts)
      (println @fired "effect(s) fired — delivery state is disposable,"
               "truth untouched")
      (when (pos? @failed)
        (println @failed "delivery failure(s) — unmarked in the ledger,"
                 "next run retries")
        (exit! 1)))))

(declare verify-roots)
