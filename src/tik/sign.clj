;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sign
  "SSH signature sidecars — identity ladder rung 1 (PLAN §9).

  Signatures are detached endorsements of stored bytes, never fields
  (ADR 0007): `<event-id>.sig.<fingerprint>` next to the event file,
  produced by `ssh-keygen -Y sign` (briko.ssh-sign). Verification runs
  in process through briko.sshsig: the cryptography (SHA-512, Ed25519) is
  the JDK's, and what is ours is the parsing of the SSHSIG wire format and
  of the allowed-signers registry. A key type briko.sshsig does not
  implement is checked by `ssh-keygen -Y verify`, so a third party can
  re-check every signature with OpenSSH and coreutils alone.

  A signature establishes AUTHORSHIP, not authorization (ADR 0010):
  verify L1 checks each sidecar against the event's claimed actor via
  the store's `actors` file (OpenSSH allowed-signers format), which is
  the first identity registry. Whether that actor was *allowed* to make
  the claim stays a derivation question (guards, roles), never a
  signature question.

  The fingerprint is the first 16 hex chars of sha256 over the public
  key blob — filesystem-safe and coreutils-checkable:
  `awk '{print $2}' key.pub | base64 -d | sha256sum`."
  (:require [briko.ssh-sign :as ssh-sign]
            [briko.sshsig :as sshsig]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.identity :as identity])
  (:import (java.util Base64)))

(def namespace-event
  "The ssh-keygen -Y namespace for event signatures. Domain-separated so
  a tik event signature can never be replayed as a git commit signature
  or vice versa; each sidecar kind gets its own namespace."
  "tik-event")

(def namespace-process
  "Namespace for process-definition publication signatures (ADR 0015):
  a definition endorsement can never masquerade as an event signature."
  "tik-process")

(def namespace-witness
  "Namespace for head countersignatures: a witness observation over an
  entire ancestry (ADR 0004 — one signature timestamps everything the
  head commits to). Sidecar: <head>.witness.<fingerprint> (v6
  endorsement algebra: .sig authorship, .witness observation, .ots
  anchoring)."
  "tik-witness")

(defn pubkey
  "The OpenSSH public key line for a private key file, read by
  `ssh-keygen -y` within briko.ssh-sign's deadline."
  [key-file]
  (ssh-sign/pubkey (str key-file)))

(defn fingerprint
  "First 16 hex chars of sha256 over the key blob of an OpenSSH public
  key line."
  [pubkey-line]
  (let [blob (.decode (Base64/getDecoder)
                      ^String (second (str/split pubkey-line #"\s+")))]
    (subs (canonical/sha256-hex-bytes blob) 0 16)))

(def ^:dynamic *ssh-keygen-fallback*
  "Whether a key type briko.sshsig cannot read may be judged by ssh-keygen.
  True for the CLI. tik.api binds it false, because a library call must
  not start a process: such a signature is then neither verified nor
  forged (nil), and the audit ladder counts it as not verified."
  true)

(defn verifier-available?
  "Is `ssh-keygen` here to check a signature with?

  Only asked for key types briko.sshsig does not implement; Ed25519, what
  tik mints, is read in process. briko.ssh-sign asks once per run and
  remembers the answer."
  []
  (ssh-sign/ssh-keygen-available?))

(defn sig-file
  "The sidecar path for an event signed by the key with `fpr`."
  ^java.io.File [events-dir event-id fpr]
  (io/file events-dir (str event-id ".sig." fpr)))

;; ---------------------------------------------------------------------------
;; A signature endorses BYTES (an event file, a SQLite BLOB: the same bytes
;; either way, so a signature is backend-independent). Signing hands them
;; to `ssh-keygen -Y sign` on stdin through briko.ssh-sign; verifying reads
;; them in process through briko.sshsig, and only a key type it does not
;; implement goes to ssh-keygen, from a private temporary directory.

(defn- file-bytes ^bytes [^java.io.File f]
  (java.nio.file.Files/readAllBytes (.toPath f)))

;; -------------------------------------------- byte variants (the store seam)

(defn sign-bytes
  "Sign the exact `bytes` with `key-file`; return the detached signature
  bytes (ASCII SSHSIG armor). Namespace defaults to event signing."
  (^bytes [key-file bytes] (sign-bytes key-file bytes namespace-event))
  (^bytes [key-file ^bytes bytes sig-namespace]
   (ssh-sign/sign-bytes (str key-file) bytes sig-namespace)))

(defn- registry-text
  "The allowed-signers file as text, or nil when there is none to read."
  [allowed-signers]
  (let [f (io/file (str allowed-signers))]
    (when (.isFile f) (slurp f :encoding "UTF-8"))))

(defn- in-process
  "briko.sshsig's verdict where it can judge, else nil: the answer of a
  call that may not start ssh-keygen."
  [verdict]
  (when-not (or (nil? verdict) (sshsig/unsupported? verdict)) verdict))

(defn- named-principal?
  "Whether `actor` names somebody a signature could be verified as."
  [actor]
  (and (string? actor) (not (str/blank? actor))))

(defn verify-bytes
  "Does `sig-bytes` verify `event-bytes` as authored by `actor`?

  Read in process (briko.sshsig) for the Ed25519 keys tik mints, so a host
  without OpenSSH still judges authorship, which the distroless image we
  publish has none of. A key type that reader does not implement falls
  back to ssh-keygen rather than being called forged; nil when ssh-keygen
  could not judge or fallback is off.

  An `actor` that is not a non-blank string is false before either reader
  is asked: ssh-keygen matches `-I \"\"` against a `*` principal pattern,
  and a missing actor names nobody to verify as."
  ([allowed-signers event-bytes sig-bytes actor]
   (verify-bytes allowed-signers event-bytes sig-bytes actor namespace-event))
  ([allowed-signers ^bytes event-bytes ^bytes sig-bytes actor sig-namespace]
   (cond
     (not (named-principal? actor)) false
     *ssh-keygen-fallback* (ssh-sign/verify-bytes (str allowed-signers) event-bytes
                                                  sig-bytes actor sig-namespace)
     :else (when-let [text (registry-text allowed-signers)]
             (in-process (sshsig/verify text event-bytes sig-bytes actor sig-namespace))))))

(defn verify-text
  "verify-bytes over allowed-signers given as text rather than a file, for
  a caller that narrowed the registry to some of its lines. ssh-keygen
  reads a private temporary copy only for a key type briko.sshsig does not
  read. An `actor` that is not a non-blank string is false, as in
  verify-bytes."
  [^String text ^bytes event-bytes ^bytes sig-bytes actor sig-namespace]
  (cond
    (not (named-principal? actor)) false
    *ssh-keygen-fallback* (ssh-sign/verify-text text event-bytes sig-bytes actor sig-namespace)
    :else (in-process (sshsig/verify text event-bytes sig-bytes actor sig-namespace))))

(defn find-principals-bytes
  "Which registered principals could have produced `sig-bytes` over
  `event-bytes`? Empty when the key is not in the registry; nil when no
  reader could judge."
  [allowed-signers ^bytes event-bytes ^bytes sig-bytes sig-namespace]
  (if *ssh-keygen-fallback*
    (ssh-sign/find-principals (str allowed-signers) event-bytes sig-bytes sig-namespace)
    (when-let [text (registry-text allowed-signers)]
      (in-process (sshsig/find-principals text event-bytes sig-bytes sig-namespace)))))

;; -------------------------------------------------- file variants (sidecars)

(defn sign!
  "Sign `file` (the exact stored bytes) with `key-file`; write the sidecar
  next to it; return the sidecar File. Namespace defaults to event signing;
  pass namespace-process for definitions."
  ([key-file file artifact-id] (sign! key-file file artifact-id namespace-event))
  ([key-file ^java.io.File file artifact-id sig-namespace]
   (let [target (sig-file (.getParentFile file) artifact-id
                          (fingerprint (pubkey key-file)))]
     (io/copy (sign-bytes key-file (file-bytes file) sig-namespace) target)
     target)))

(defn verify
  "Does `sig` verify `file`'s bytes as authored by `actor`, per the
  allowed-signers registry? The file variant of `verify-bytes`, and the
  same reader behind it: a signature endorses BYTES, so where they came
  from changes nothing."
  ([allowed-signers file sig actor]
   (verify allowed-signers file sig actor namespace-event))
  ([allowed-signers ^java.io.File file ^java.io.File sig actor sig-namespace]
   (boolean (verify-bytes allowed-signers (file-bytes file) (file-bytes sig)
                          actor sig-namespace))))

(defn find-principals
  "Which registered principals could have produced `sig` over `file`?
  Empty when the key is not in the registry."
  [allowed-signers ^java.io.File file ^java.io.File sig sig-namespace]
  (or (find-principals-bytes allowed-signers (file-bytes file) (file-bytes sig)
                             sig-namespace)
      []))

(defn sidecars
  "All signature sidecar Files for an event id in `events-dir`."
  [events-dir event-id]
  (let [prefix (str event-id ".sig.")]
    (->> (.listFiles (io/file events-dir))
         (filter (fn [^java.io.File f]
                   (and (.isFile f) (str/starts-with? (.getName f) prefix))))
         (sort-by (fn [^java.io.File f] (.getName f))))))

(defn allowed-signers-line
  "One `actors` registry line binding `actor` to a public key, namespace
  restricted so the binding cannot endorse anything but tik signatures.
  Refuses an actor that is not a plain principal and a key that is not one
  OpenSSH public key (tik.identity/principal?, public-key-line?): this is
  the one place either reaches an allowed-signers file, and a line break
  or a pattern there grants keys to other actors."
  [actor pubkey-line]
  (when-not (identity/principal? actor)
    (throw (ex-info (str "an actor in an allowed-signers line is letters, digits and ._@+- only: "
                         (pr-str actor))
                    {:reason :sign/bad-actor :actor actor})))
  (when-not (identity/public-key-line? pubkey-line)
    (throw (ex-info (str "not one OpenSSH public key: " (pr-str pubkey-line))
                    {:reason :sign/bad-public-key})))
  (str actor " namespaces=\"tik-*\" " pubkey-line))
