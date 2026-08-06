;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bridge
  "Inbound identity/message bridges (ADR 0019): email (a sender maps to an
  actor, a [tik <id>] subject or threaded Message-ID routes to a ticket,
  tik> lines become facts), OIDC device/password login (a key-binding
  attestation on the registry ticket), and OID4VCI verifiable-credential
  ingest. Each turns an external fact into a bridge-SIGNED attestation;
  verification thereafter never calls the IdP/issuer. Porcelain over
  tik.cli-core plus the pure parsers in tik.mail / tik.oidc / tik.oid4vci."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [actor parse-key read-edn-file slurp-existing typed-value]]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [append!* archive-process! cache-flush! claimed-at
                                  die exit! load-process now resolve-id root
                                  the-store ticket-ctx]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.identity-trust :as identity-trust]
            [tik.imap :as imap]
            [tik.jwks :as jwks]
            [tik.mail :refer [auto-generated? own-message? parse-date parse-rfc822
                              require-dkim! ticket-ref-of]]
            [tik.oid4vci :as oid4vci]
            [tik.oidc :as oidc]
            [tik.pop3 :as pop3]
            [tik.render :refer [shash sid]]
            [tik.secret :as secret]
            [tik.store.protocol :as store]
            [tik.text :refer [safe-name]]))

(defn resolve-oidc-password
  "The resource-owner password, from the most secure source available,
  through the unified secret resolver (tik.secret): --password-command (a
  shell command line run through a password manager — pass/passage/gopass/
  `op read` — whose first stdout line is the secret), then --password-file,
  then the TIK_OIDC_PASSWORD environment variable, and last a literal
  --password (visible to other users in the process list, so it earns a
  warning). Returns nil when none is given — the caller then falls back to
  the device flow. Only the RETRIEVAL command rides in argv, never the
  secret itself."
  [opts]
  (cond
    (:password-command opts) (secret/resolve1 "password" {:command (:password-command opts)})
    (:password-file opts)    (secret/resolve1 "password" {:file (:password-file opts)})
    (System/getenv "TIK_OIDC_PASSWORD") (System/getenv "TIK_OIDC_PASSWORD")
    (:password opts) (do (binding [*out* *err*]
                           (println (str "warning: --password is visible to other"
                                         " users via the process list; prefer"
                                         " --password-command, --password-file,"
                                         " or TIK_OIDC_PASSWORD")))
                         (:password opts))
    :else nil))

(defn cmd-bridge-oidc
  "bridge oidc: identity rung 2 (PLAN §9). Login against the config's
  issuer — device flow by default, or the resource-owner password grant
  when --user and a password are given (headless onboarding; supply the
  password via --password-command <pass show …>, --password-file, or
  TIK_OIDC_PASSWORD rather than a literal --password) — and append the
  signed key-binding attestation to the registry ticket. Verification of
  the binding never calls the IdP."
  [opts]
  (let [cfg-file (or (:config opts) (str (io/file (root) "oidc.edn")))
        cfg (if (.exists (io/file cfg-file))
              (read-edn-file (io/file cfg-file)) {})
        issuer (or (:issuer opts) (:issuer cfg)
                   (die "no OIDC issuer — pass --issuer or put :issuer in oidc.edn"))
        client-id (or (:client-id opts) (:client-id cfg) "tik")
        registry-ref (or (:registry opts) (:registry cfg)
                         (die (str "no registry ticket — mint one with\n"
                                   "  tik new identity-registry --title 'identity registry'\n"
                                   "then pass --registry <its id>")))
        who (actor opts)
        key-file (or (:key opts)
                     (some-> (System/getenv "TIK_KEY") (str ".pub"))
                     (die "which key binds to this login? --key <pubkey.pub> or set TIK_KEY"))
        public-key (str/trim (slurp-existing "key" key-file))
        s (the-store)
        registry-id (resolve-id s registry-ref)
        endpoints (oidc/discover oidc/http-get issuer)
        password (resolve-oidc-password opts)
        response (if (and (:user opts) password)
                   (oidc/password-flow oidc/http-post endpoints client-id
                                       (:user opts) password)
                   (let [{:keys [prompt poll]} (oidc/device-flow
                                                oidc/http-post endpoints
                                                client-id #(Thread/sleep (long %)))]
                     (println prompt)
                     (loop [] (or (poll) (recur)))))
        claim (try (oidc/token->binding response {:actor who
                                                  :public-key public-key
                                                  :issuer issuer})
                   (catch clojure.lang.ExceptionInfo e (die (ex-message e))))
        heads (dag/heads (store/events s registry-id))
        e (event/add-attestation {:ticket registry-id :actor who :at (now)
                                  :parents heads :claim claim})]
    ;; as in `workload`: --key is the key being bound, not the signer
    (append!* s e (dissoc opts :key))
    (println (str "bound " (:identity/issuer claim) " subject "
                  (:identity/subject claim) " (" (:identity/username claim)
                  ") to actor '" who "' — attestation " (shash (:event/id e))
                  "… on " registry-id))
    (exit! 0)))

(defn cmd-bridge-jwks
  "bridge jwks --issuer <url>: pin an issuer's signing keys.

  Rung 2's trust anchor. Verification never calls the IdP (ADR 0023), so
  the keys have to be in the store before a binding can count — fetched
  once by an operator, committed like the actors registry, and read
  offline forever after.

  Re-pinning MERGES by `kid` rather than replacing: an issuer that
  rotates publishes the new key beside the old, and dropping the old one
  would stop verifying every binding it signed."
  [opts]
  (let [cfg-file (or (:config opts) (str (io/file (root) "oidc.edn")))
        cfg (if (.exists (io/file cfg-file))
              (read-edn-file (io/file cfg-file)) {})
        issuer (or (:issuer opts) (:issuer cfg)
                   (die "no OIDC issuer — pass --issuer or put :issuer in oidc.edn"))
        json (cond
               (:jwks opts) (slurp-existing "jwks" (:jwks opts))
               (:jwks-url opts) (oidc/http-get (:jwks-url opts))
               :else (let [uri (:jwks (oidc/discover oidc/http-get issuer))]
                       (when-not uri
                         (die (str "the issuer's discovery document names no"
                                   " jwks_uri; pass --jwks-url or --jwks")))
                       (oidc/http-get uri)))
        fetched (try (jwks/parse-jwks json)
                     (catch clojure.lang.ExceptionInfo e (die (ex-message e))))
        f (identity-trust/jwks-file (root) issuer)
        existing (identity-trust/pinned-jwks (root) issuer)
        merged (if existing (identity-trust/merge-jwks existing fetched) fetched)]
    (io/make-parents f)
    (spit f ((requiring-resolve 'cheshire.core/generate-string) merged))
    (println (str "pinned " (count (:keys merged)) " key(s) for " issuer
                  (when existing
                    (str " (" (count (:keys existing)) " already held)"))
                  " -> " (.getPath f)))
    (println "commit this file: it is what makes a binding checkable offline")
    (exit! 0)))

(defn- workload-token
  "The pre-issued OIDC token a workload presents. No interactive flow:
  a pipeline already holds a token, and the point of rung 2 for CI is
  that no long-lived secret exists to steal."
  [opts]
  (str/trim
   (cond
     (:token-file opts) (slurp-existing "token" (:token-file opts))

     (:token-env opts)
     (or (System/getenv (:token-env opts))
         (die (str "no token in $" (:token-env opts))))

     (:github opts)
     ;; GitHub Actions mints one on request, per workflow run, for the
     ;; audience the job asks for; the request token is itself short-lived
     ;; and never leaves the runner
     (let [url (or (System/getenv "ACTIONS_ID_TOKEN_REQUEST_URL")
                   (die (str "not in a GitHub Actions job with id-token"
                             " permission — ACTIONS_ID_TOKEN_REQUEST_URL is"
                             " unset (add `permissions: id-token: write`)")))
           rt (or (System/getenv "ACTIONS_ID_TOKEN_REQUEST_TOKEN")
                  (die "ACTIONS_ID_TOKEN_REQUEST_TOKEN is unset"))
           aud (or (:audience opts) "tik")
           body (oidc/http-get-with-auth
                 (str url "&audience=" (java.net.URLEncoder/encode aud "UTF-8"))
                 (str "Bearer " rt))]
       (or (:value ((requiring-resolve 'cheshire.core/parse-string) body true))
           (die "the Actions token endpoint returned no value")))

     :else (die (str "how should the token arrive? --github, --token-file <f>,"
                     " or --token-env <VAR>")))))

(defn cmd-bridge-workload
  "bridge workload: bind a key to a WORKLOAD identity (identity rung 2
  for machines, PLAN §9).

  A pipeline generates a keypair per run, presents the OIDC token its
  platform already issues it, and binds the two — so nothing long-lived
  is stored anywhere and a leaked key stops mattering when the job ends.
  The binding is what makes the run's signatures verifiable afterwards.

  The token is verified BEFORE the binding is written, against keys
  already pinned in the store: minting a binding that `verify` would
  refuse helps nobody, and it would put an unremovable record in a log
  that never deletes."
  [opts]
  (let [cfg-file (or (:config opts) (str (io/file (root) "oidc.edn")))
        cfg (if (.exists (io/file cfg-file))
              (read-edn-file (io/file cfg-file)) {})
        registry-ref (or (:registry opts) (:registry cfg)
                         (die (str "no registry ticket — mint one with\n"
                                   "  tik new identity-registry --title 'identity registry'\n"
                                   "then pass --registry <its id>")))
        who (actor opts)
        key-file (or (:public-key opts) (:key opts)
                     (some-> (System/getenv "TIK_KEY") (str ".pub"))
                     (die (str "which key binds to this workload?"
                               " --public-key <key.pub> or set TIK_KEY")))
        public-key (str/trim (slurp-existing "key" key-file))
        token (workload-token opts)
        claims (try (oidc/decode-jwt-payload token)
                    (catch clojure.lang.ExceptionInfo e (die (ex-message e))))
        issuer (or (:iss claims)
                   (die "the token carries no issuer (iss)"))
        s (the-store)
        registry-id (resolve-id s registry-ref)
        at (now)
        claim (oidc/binding-claim {:claims claims :actor who
                                   :public-key public-key
                                   :id-token token :issuer issuer})
        ;; judged exactly as `verify` will judge it later, with this
        ;; event's own instant, so a refusal surfaces here and not in
        ;; somebody's audit months from now
        status (identity-trust/binding-status
                (root) {:issuer issuer :subject (:sub claims)
                        :id-token token :at at})]
    (when-not (= :trusted status)
      (die (str "refusing to write a binding `verify` would refuse ("
                (name (:reason status)) ")"
                (when (= :identity/no-pinned-jwks (:reason status))
                  (str " — pin the issuer's keys first:\n"
                       "  tik bridge jwks --issuer " issuer)))))
    (let [heads (dag/heads (store/events s registry-id))
          e (event/add-attestation {:ticket registry-id :actor who :at at
                                    :parents heads :claim claim})]
      ;; --key/--public-key names the key being BOUND; the attestation is
      ;; signed by this actor's own signing key (TIK_KEY), so the bound
      ;; public key must not be mistaken for it
      (append!* s e (dissoc opts :key :public-key))
      (println (str "bound " issuer " subject " (:sub claims)
                    " to actor '" who "' — attestation " (shash (:event/id e))
                    "… on " registry-id))
      (exit! 0))))

(defn cmd-bridge-oid4vci
  "bridge oid4vci: ingest a Verifiable Credential (an OID4VCI issuance
  output — JWT-VC or SD-JWT-VC) as a bridge-signed ATTESTATION on the
  registry ticket. A VC is a signed attestation with an external issuer
  (docs/IDEAS.md): the issuer signature is verified at INGEST against the
  issuer's JWKS (fetched over TLS from `<issuer>/.well-known/jwks.json`,
  or `--jwks-url`, or a local `--jwks <file>`), then the bridge signs its
  OWN attestation carrying the credential (raw included for re-audit).
  Verification thereafter never calls the issuer — offline-forever, the
  same trust model as the OIDC/email bridges (ADR 0019). No kernel
  change: the credential becomes one more signed attestation."
  [opts]
  (let [cfg-file (or (:config opts) (str (io/file (root) "oid4vci.edn")))
        cfg (if (.exists (io/file cfg-file)) (read-edn-file (io/file cfg-file)) {})
        registry-ref (or (:registry opts) (:registry cfg)
                         (die (str "no registry ticket — mint one with\n"
                                   "  tik new identity-registry --title 'identity registry'\n"
                                   "then pass --registry <its id>")))
        cred-str (str/trim (if-let [f (:credential opts)]
                             (slurp-existing "credential" f)
                             (slurp *in*)))
        who (actor opts)
        cred (oid4vci/parse-credential cred-str)
        issuer (or (:issuer opts) (:issuer cred)
                   (die "credential carries no issuer (iss); pass --issuer"))
        jwks-json (cond
                    (:jwks opts) (slurp-existing "jwks" (:jwks opts))
                    (:jwks-url opts) (oidc/http-get (:jwks-url opts))
                    :else (oidc/http-get (str (str/replace issuer #"/$" "")
                                              "/.well-known/jwks.json")))
        verifier (jwks/verifier (jwks/parse-jwks jwks-json))
        ;; enforce the credential's validity window/audience at ingest:
        ;; a signature-valid but EXPIRED (or wrong-audience) credential
        ;; must not become a fresh, current bridge-signed attestation
        verified (try (oid4vci/verify cred-str verifier
                                      {:now (.getEpochSecond ^java.time.Instant (now))
                                       :audience (or (:audience opts) (:audience cfg))})
                      (catch clojure.lang.ExceptionInfo e (die (ex-message e))))
        s (the-store)
        registry-id (resolve-id s registry-ref)
        claim (oid4vci/credential-claim verified who)
        heads (dag/heads (store/events s registry-id))
        e (event/add-attestation {:ticket registry-id :actor who :at (now)
                                  :parents heads :claim claim})]
    (append!* s e opts)
    (println (str "ingested credential from " (:credential/issuer claim)
                  " for subject " (:credential/subject claim)
                  " (" (:credential/type claim) ") as actor '" who
                  "' — attestation " (shash (:event/id e)) "… on " registry-id))
    (exit! 0)))

(defn- mail-ticket-id
  "A deterministic ticket id for a fresh inbound message, keyed by its
  Message-ID — so re-polling the same first message resolves to the same
  ticket (content-addressed idempotency, ADR 0021) rather than spawning a
  duplicate the way a random id would.

  SHA-256, like every other id in the store (ADR 0006's one-algorithm
  policy), shaped into a well-formed UUIDv8. The Message-ID is chosen by
  the sender, so the derivation must not be one where two chosen inputs
  can be driven to the same output — which is exactly what UUIDv3's MD5
  no longer resists."
  [msgid]
  (let [hex (canonical/sha256-hex (str "tik/mail " msgid))
        msb (Long/parseUnsignedLong (subs hex 0 16) 16)
        lsb (Long/parseUnsignedLong (subs hex 16 32) 16)]
    (java.util.UUID. (bit-or (bit-and msb (bit-not 0xF000)) 0x8000)   ; version 8
                     (bit-or (bit-and lsb 0x3FFFFFFFFFFFFFFF)         ; variant 10x
                             Long/MIN_VALUE))))

(defn- legacy-mail-ticket-id
  "The UUIDv3/MD5 id mail tickets were minted under before the switch to
  SHA-256. Consulted on LOOKUP only, never to mint: without it, re-polling
  a message ingested by an older build would derive a different id, find
  no events, and open a duplicate ticket for mail already on record."
  [msgid]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str "tik/mail " msgid) "UTF-8")))

(defn- resolve-or-nil [s ref]
  (try (resolve-id s ref) (catch Exception _ nil)))

(defn- mail-artifact
  "The artifact this ticket already holds at mail/<message-id>, or nil."
  [s id msgid]
  (some-> (try (ticket-ctx s id) (catch Exception _ nil))
          :state :artifacts (get (str "mail/" msgid))))

(defn- already-have?
  "Has this message already been ingested onto this ticket? The artifact
  path mail/<message-id> is the dedup key; its presence in derived state
  means a prior poll recorded it — so re-polling is a no-op."
  [s id msgid]
  (boolean (mail-artifact s id msgid)))

(defn- refuse-msgid-collision!
  "A Message-ID is sender-chosen, so two senders can present the same one.
  When the stored artifact for this id holds DIFFERENT bytes, the message
  is not a re-poll — treating it as one would silently swallow it, which
  is how a crafted duplicate id suppresses somebody else's mail. Refuse
  loudly instead; the operator sees a message that needs a decision."
  [s id msgid text]
  (when-let [prior (mail-artifact s id msgid)]
    (let [hash (str "sha256-" (canonical/sha256-hex-bytes
                               (.getBytes ^String text "UTF-8")))]
      (when (not= hash (:hash prior))
        (throw (ex-info (str "Message-ID " msgid " is already on ticket " id
                             " with different content — refusing to treat"
                             " this as a re-poll (a sender-chosen id collided"
                             " or was reused)")
                        {:reason :mail/msgid-collision
                         :msgid msgid :ticket id}))))))

(defn- mail-blob!
  "Store TEXT as a content-addressed blob under the ticket and attach it
  as an artifact at PATH (mail/<message-id>) — that path is both the human
  comment and the dedup marker."
  [s id actor at path text opts]
  (let [bytes (.getBytes ^String text "UTF-8")
        hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
        dest (io/file (root) "tickets" (str id) "blobs" hash)]
    (io/make-parents dest)
    (spit dest text)
    (append!* s (event/attach-artifact
                 {:ticket id :actor actor :at at
                  :parents (dag/heads (store/events s id))
                  :path path :hash hash})
              opts)))

(defn- tik-facts
  "The `tik> key=value` reply lines of a body as typed [path value] pairs
  — the info-request loop the email sink teaches. NEVER derived from an
  auto-generated message (that is how a loop starts)."
  [proc body]
  (for [line (str/split-lines (or body ""))
        :let [[_ k v] (re-matches #"\s*tik>\s*([^=\s]+)=(.*)" line)]
        :when k
        :let [path (parse-key k)]]
    [path (typed-value proc path (str/trim v))]))

(defn ingest-message!
  "Ingest ONE raw RFC822 message as signed events, with the loop guards
  that keep an email desk from melting down. Returns {:outcome … :id …
  :msgid … :facts …}; a DKIM refusal or unknown sender throws ex-info —
  the caller decides whether that aborts (the stdin bridge) or just skips
  this one message (the IMAP poll). Outcomes, in guard order:

  - :own   — OUR OWN mail returned to us (matching the outbound Message-ID
             shape): dropped, nothing written. The hardest loop stop.
  - :dup   — already ingested (mail/<message-id> artifact present): a
             no-op, so re-polling and overlap are safe.
  - :auto  — an auto-reply / bulk / list / bounce (RFC 3834 et al.):
             recorded as a comment so a human sees it, but NO tik> facts
             are applied — so it drives no derivation and provokes no
             reply. This is the loop break.
  - :comment / :ticket — a genuine message: a comment on its ticket (plus
             any tik> facts) or a new ticket under :process."
  [s cfg raw opts]
  (let [{:keys [from subject body message-id] :as msg} (parse-rfc822 raw)
        msgid (or (not-empty message-id)
                  (str "sha256-" (canonical/sha256-hex-bytes (.getBytes ^String raw "UTF-8"))))]
    (if (own-message? msg)
      {:outcome :own :msgid msgid}
      (do
        (require-dkim! cfg msg)
        (let [actor-name (or (get-in cfg [:from->actor from])
                             (:default-actor cfg)
                             (throw (ex-info (str "unknown sender " (or from "<none>")
                                                  " and no :default-actor")
                                             {:reason :mail/unknown-sender :from from})))
              opts (assoc opts :actor actor-name)
              auto? (auto-generated? msg)
              ;; the sender's Date: header is attacker-controlled and we
              ;; are the actor signing these events — a future-dated
              ;; claim is clamped to our own clock (cli-core/claimed-at)
              at (claimed-at (parse-date (:date msg)) (now))
              path (str "mail/" msgid)
              ref (ticket-ref-of msg)
              existing (when ref (resolve-or-nil s ref))]
          (cond
            (and existing (already-have? s existing msgid))
            (do (refuse-msgid-collision! s existing msgid
                                         (str subject "\n\n" body))
                {:outcome :dup :id existing :msgid msgid})

            existing
            (do
              (mail-blob! s existing actor-name at path (str subject "\n\n" body) opts)
              (let [facts (when-not auto? (tik-facts (:process (ticket-ctx s existing)) body))]
                (doseq [[p v] facts]
                  (append!* s (event/assert-fact
                               {:ticket existing :actor actor-name :at at
                                :parents (dag/heads (store/events s existing))
                                :path p :value v})
                            opts))
                {:outcome (if auto? :auto :comment) :id existing :msgid msgid
                 :facts (count facts) :actor actor-name}))

            :else
            (let [id (mail-ticket-id msgid)
                  ;; the deterministic id IS the dedup key here: if this
                  ;; message already opened its ticket, re-polling finds
                  ;; the create event and stops — robust even for a blank
                  ;; body. The legacy MD5 id is consulted too, so mail
                  ;; ingested by an older build re-polls to its existing
                  ;; ticket instead of opening a second one.
                  prior (first (filter #(seq (store/events s %))
                                       [id (legacy-mail-ticket-id msgid)]))]
              (if prior
                (do (refuse-msgid-collision! s prior msgid
                                             (str subject "\n\n" body))
                    {:outcome :dup :id prior :msgid msgid})
                (let [proc-name (safe-name (or (:process cfg)
                                               (throw (ex-info "no :process in config for a new-ticket mail"
                                                               {:reason :mail/no-process}))))
                      proc (load-process proc-name)
                      e (event/create-ticket {:ticket id :actor actor-name :at at
                                              :title (or (not-empty subject) "(no subject)")
                                              :process (keyword proc-name)
                                              :version (:process/version proc)
                                              :process-hash (archive-process! proc)})]
                  (append!* s e opts)
                  ;; always attach the mail artifact — the readable comment
                  ;; and the mail/<message-id> marker, present even when the
                  ;; body is empty
                  (mail-blob! s id actor-name at path (str subject "\n\n" body) opts)
                  {:outcome (if auto? :auto :ticket) :id id :msgid msgid :actor actor-name})))))))))

(defn- ingest-one!
  "Ingest one FETCHED message in isolation: record its outcome in `tally`,
  print a line, and return TRUE when it was handled (so a POP3 caller may
  delete it) or FALSE on a skip/error (leave it on the server for retry or
  an admin's eyes). NEVER throws — a refusal or crash is logged and
  swallowed so a batch poll continues past one bad message. This is the
  atom-bomb-proof heart shared by every mailbox protocol."
  [s cfg opts uid raw tally]
  (let [bump #(swap! tally update % (fnil inc 0))]
    (try
      (let [{:keys [outcome id msgid facts]} (ingest-message! s cfg raw opts)]
        (bump outcome)
        (println (case outcome
                   :own (str "uid " uid ": dropped our own mail (loop guard)")
                   :dup (str "uid " uid ": already ingested " msgid)
                   :auto (str "uid " uid ": recorded auto-reply -> " (sid id) " (no cascade)")
                   :comment (str "uid " uid ": comment -> " (sid id)
                                 (when (pos? (long (or facts 0))) (str " (+" facts " fact(s))")))
                   :ticket (str "uid " uid ": new ticket " (sid id))
                   (str "uid " uid ": " outcome)))
        true)
      (catch clojure.lang.ExceptionInfo e
        (bump :skip)
        (binding [*out* *err*] (println (str "skip uid " uid ": " (ex-message e))))
        false)
      (catch Throwable e
        (bump :error)
        (binding [*out* *err*] (println (str "error uid " uid ": " (ex-message e))))
        false))))

(defn- report! [tally]
  (cache-flush!)
  (println (str "ingest complete: " (into (sorted-map) @tally)))
  (exit! 0))

(defn cmd-bridge-imap
  "bridge imap [--config imap.edn]
  Poll an IMAP mailbox over TLS and ingest new mail — the inbound
  complement to the email sink. Config at the store root:

    {:imap {:host \"imap.example.com\" :user \"desk@example.com\"
            :password {:credential \"imap-pw\"}  ; or {:command …}/{:file …}/{:env …}
            :mailbox \"INBOX\" :search \"UNSEEN\"}
     :process :support :default-actor \"inbound\"
     :from->actor {\"vip@corp.com\" \"vip\"}
     :dkim {:require true :authserv-id \"mx.example.com\"}}

  Every message is ingested in ISOLATION (see ingest-one!): loop-safe,
  idempotent (BODY.PEEK never marks \\Seen; content addressing dedups a
  re-fetch), so a cron/timer can run it as often as you like.

  With --watch it does NOT return: it holds the connection open and uses
  IMAP IDLE (RFC 2177) to ingest mail the instant it arrives, reconnecting
  with backoff on any drop — the long-running mode for a service unit.
  Same isolated, idempotent, loop-safe ingest; only the delivery timing
  differs."
  [{:keys [opts]}]
  (let [cfg-file (or (:config opts) (str (io/file (root) "imap.edn")))
        _ (when-not (.exists (io/file cfg-file))
            (die (str "no imap config at " cfg-file
                      " (need {:imap {:host … :user … :password …} :process … :default-actor …})")))
        cfg (read-edn-file (io/file cfg-file))
        imapcfg (secret/resolve-secrets (:imap cfg))
        s (the-store)
        tally (atom {})
        handle (fn [uid raw] (let [r (ingest-one! s cfg opts uid raw tally)] (cache-flush!) r))]
    (if (:watch opts)
      (do (println (str "watching " (:host imapcfg) " over IMAP IDLE (Ctrl-C to stop) …"))
          (imap/watch imapcfg handle)
          (report! tally))                                ; reached only if watch returns
      (do (doseq [{:keys [uid raw]} (imap/fetch-messages imapcfg)]
            (handle uid raw))
          (report! tally)))))

(defn cmd-bridge-pop3
  "bridge pop3 [--config pop3.edn]
  Poll a POP3 mailbox over TLS and ingest new mail — same routing, DKIM
  gate, MIME decoding, and loop guards as `bridge imap`, for mailboxes
  that only speak POP3. Config mirrors imap.edn under a :pop3 key:

    {:pop3 {:host \"pop.example.com\" :user \"desk@example.com\"
            :password {:credential \"pop-pw\"} :delete false}
     :process :support :default-actor \"inbound\" …}

  With :delete false (the default, safest) messages are LEFT on the
  server and re-fetched each poll — harmless because content addressing
  dedups them. Set :delete true for a busy mailbox: a message is removed
  only AFTER it was ingested cleanly, so nothing is lost to a mid-poll
  crash (the next poll re-fetches and dedups, then deletes)."
  [{:keys [opts]}]
  (let [cfg-file (or (:config opts) (str (io/file (root) "pop3.edn")))
        _ (when-not (.exists (io/file cfg-file))
            (die (str "no pop3 config at " cfg-file
                      " (need {:pop3 {:host … :user … :password …} :process … :default-actor …})")))
        cfg (read-edn-file (io/file cfg-file))
        s (the-store)
        tally (atom {})]
    (pop3/process-mailbox (secret/resolve-secrets (:pop3 cfg))
                          (fn [uidl raw] (ingest-one! s cfg opts uidl raw tally)))
    (report! tally)))

(defn cmd-bridge
  "bridge email [--config bridge.edn] < message
  One RFC822 message on stdin — MTA-agnostic: procmail, fetchmail,
  maildrop, or a paste all work. The bridge is an ACTOR (ADR 0019
  inbound): the sender maps to an actor via the config's :from->actor
  (unknown senders use :default-actor or are rejected), a subject
  containing [tik <id-prefix>] comments that ticket, anything else
  opens a new ticket under :process with the body as first comment.
  Loop-safe and idempotent — our own mail and auto-replies never cascade,
  and re-feeding a message is a no-op (see ingest-message!). Set TIK_KEY
  so the bridge's claims are signed like anyone else's."
  [{:keys [pos opts]}]
  (when (= "oidc" (first pos)) (cmd-bridge-oidc opts))
  (when (= "jwks" (first pos)) (cmd-bridge-jwks opts))
  (when (= "workload" (first pos)) (cmd-bridge-workload opts))
  (when (= "oid4vci" (first pos)) (cmd-bridge-oid4vci opts))
  (when (= "imap" (first pos)) (cmd-bridge-imap {:opts opts}))
  (when (= "pop3" (first pos)) (cmd-bridge-pop3 {:opts opts}))
  (when-not (= "email" (first pos))
    (die (str "usage: tik bridge email [--config bridge.edn] < message\n"
              "       tik bridge imap [--config imap.edn]\n"
              "       tik bridge pop3 [--config pop3.edn]\n"
              "       tik bridge jwks --issuer <url>\n"
              "       tik bridge workload --github|--token-file F|--token-env VAR\n"
              "       tik bridge oidc [--config oidc.edn] [--registry ID] [--actor A]\n"
              "       tik bridge oid4vci --credential vc.jwt --registry ID"
              " [--jwks-url URL | --jwks FILE]")))
  (let [cfg-file (or (:config opts) (str (io/file (root) "bridge.edn")))
        cfg (if (.exists (io/file cfg-file))
              (read-edn-file (io/file cfg-file))
              {})
        {:keys [outcome id msgid facts actor]} (ingest-message! (the-store) cfg (slurp *in*) opts)]
    (cache-flush!)
    (println (case outcome
               :own "dropped: our own message (loop guard)"
               :dup (str "already ingested " msgid)
               :auto (str "recorded auto-reply -> " (sid id) " (no cascade)")
               :comment (str "comment -> " (sid id) " as " actor
                             (when (pos? (long (or facts 0))) (str " (+ " facts " fact(s))")))
               :ticket (str id)))))

;; ---------------------------------------------------------------- effects
;; ADR 0019: effects observe derivation. Delivery lives entirely outside
;; the log; the sent-ledger is a DISPOSABLE cache (ADR 0013) — deleting
;; it can only cause a resend, never wrong truth.
