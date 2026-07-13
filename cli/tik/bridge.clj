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
            [tik.cli-core :refer [append!* archive-process! die exit!
                                  load-process now resolve-id root the-store ticket-ctx]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.jwks :as jwks]
            [tik.mail :refer [parse-rfc822 require-dkim! ticket-ref-of]]
            [tik.oid4vci :as oid4vci]
            [tik.oidc :as oidc]
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
    (append!* s e opts)
    (println (str "bound " (:identity/issuer claim) " subject "
                  (:identity/subject claim) " (" (:identity/username claim)
                  ") to actor '" who "' — attestation " (shash (:event/id e))
                  "… on " registry-id))
    (exit! 0)))

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

(defn cmd-bridge
  "bridge email [--config bridge.edn] < message
  One RFC822 message on stdin — MTA-agnostic: procmail, fetchmail,
  maildrop, or a paste all work. The bridge is an ACTOR (ADR 0019
  inbound): the sender maps to an actor via the config's :from->actor
  (unknown senders use :default-actor or are rejected), a subject
  containing [tik <id-prefix>] comments that ticket, anything else
  opens a new ticket under :process with the body as first comment.
  Set TIK_KEY so the bridge's claims are signed like anyone else's."
  [{:keys [pos opts]}]
  (when (= "oidc" (first pos)) (cmd-bridge-oidc opts))
  (when (= "oid4vci" (first pos)) (cmd-bridge-oid4vci opts))
  (when-not (= "email" (first pos))
    (die (str "usage: tik bridge email [--config bridge.edn] < message\n"
              "       tik bridge oidc [--config oidc.edn] [--registry ID] [--actor A]\n"
              "       tik bridge oid4vci --credential vc.jwt --registry ID"
              " [--jwks-url URL | --jwks FILE]")))
  (let [cfg-file (or (:config opts) (str (io/file (root) "bridge.edn")))
        cfg (if (.exists (io/file cfg-file))
              (read-edn-file (io/file cfg-file))
              {})
        {:keys [from subject body] :as msg} (parse-rfc822 (slurp *in*))
        ;; if configured, the From must be DKIM-authenticated before we
        ;; trust it enough to attribute events to an actor
        _ (require-dkim! cfg msg)
        actor-name (or (get-in cfg [:from->actor from])
                       (:default-actor cfg)
                       (die (str "unknown sender " from
                                 " and no :default-actor in " cfg-file)))
        opts (assoc opts :actor actor-name)
        s (the-store)
        ticket-ref (ticket-ref-of msg)]
    (if ticket-ref
      (let [id (resolve-id s ticket-ref)
            at (now)
            text (str subject "\n\n" body)
            bytes (.getBytes ^String text "UTF-8")
            hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
            dest (io/file (root) "tickets" (str id) "blobs" hash)]
        (io/make-parents dest)
        (spit dest text)
        (append!* s (event/attach-artifact
                     {:ticket id :actor actor-name :at at
                      :parents (dag/heads (store/events s id))
                      :path (str "comment/" at) :hash hash})
                  opts)
        ;; the reply convention: `tik> key=value` lines become signed
        ;; facts — the other half of the info-request loop (the email
        ;; sink teaches exactly this syntax)
        (let [proc (:process (ticket-ctx s id))
              facts (for [line (str/split-lines (or body ""))
                          :let [[_ k v] (re-matches #"\s*tik>\s*([^=\s]+)=(.*)" line)]
                          :when k
                          :let [path (parse-key k)]]
                      [path (typed-value proc path (str/trim v))])]
          (doseq [[path value] facts]
            (append!* s (event/assert-fact
                         {:ticket id :actor actor-name :at (now)
                          :parents (dag/heads (store/events s id))
                          :path path :value value})
                      opts))
          (println (str "comment -> " (sid id) " as " actor-name
                        (when (seq facts)
                          (str " (+ " (count facts) " fact(s))"))))))
      (let [proc-name (safe-name (or (:process cfg) (die (str "no :process in " cfg-file))))
            proc (load-process proc-name)
            id (random-uuid)
            e (event/create-ticket {:ticket id :actor actor-name :at (now)
                                    :title subject
                                    :process (keyword proc-name)
                                    :version (:process/version proc)
                                    :process-hash (archive-process! proc)})]
        (append!* s e opts)
        (when-not (str/blank? body)
          (let [at (now)
                bytes (.getBytes ^String body "UTF-8")
                hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
                dest (io/file (root) "tickets" (str id) "blobs" hash)]
            (io/make-parents dest)
            (spit dest body)
            (append!* s (event/attach-artifact
                         {:ticket id :actor actor-name :at at
                          :parents (dag/heads (store/events s id))
                          :path (str "comment/" at) :hash hash})
                      opts)))
        (println (str id))))))

;; ---------------------------------------------------------------- effects
;; ADR 0019: effects observe derivation. Delivery lives entirely outside
;; the log; the sent-ledger is a DISPOSABLE cache (ADR 0013) — deleting
;; it can only cause a resend, never wrong truth.
