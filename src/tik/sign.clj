;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sign
  "SSH signature sidecars — identity ladder rung 1 (PLAN §9).

  Signatures are detached endorsements of stored bytes, never fields
  (ADR 0007): `<event-id>.sig.<fingerprint>` next to the event file,
  produced and checked by `ssh-keygen -Y` so the whole trust path is
  OpenSSH + coreutils — no crypto code of our own, ever.

  A signature establishes AUTHORSHIP, not authorization (ADR 0010):
  verify L1 checks each sidecar against the event's claimed actor via
  the store's `actors` file (OpenSSH allowed-signers format), which is
  the first identity registry. Whether that actor was *allowed* to make
  the claim stays a derivation question (guards, roles), never a
  signature question.

  The fingerprint is the first 16 hex chars of sha256 over the public
  key blob — filesystem-safe and coreutils-checkable:
  `awk '{print $2}' key.pub | base64 -d | sha256sum`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.canonical :as canonical])
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

(defn- sh! [& args]
  (let [r (apply sh/sh args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "command failed: " (first args))
                      {:args args :exit (:exit r) :err (:err r)})))
    r))

(defn pubkey
  "The OpenSSH public key line for a private key file."
  [key-file]
  (str/trim (:out (sh! "ssh-keygen" "-y" "-f" (str key-file)))))

(defn fingerprint
  "First 16 hex chars of sha256 over the key blob of an OpenSSH public
  key line."
  [pubkey-line]
  (let [blob (.decode (Base64/getDecoder)
                      ^String (second (str/split pubkey-line #"\s+")))]
    (subs (canonical/sha256-hex-bytes blob) 0 16)))

(defn sig-file
  "The sidecar path for an event signed by the key with `fpr`."
  ^java.io.File [events-dir event-id fpr]
  (io/file events-dir (str event-id ".sig." fpr)))

;; ---------------------------------------------------------------------------
;; ssh-keygen speaks only files, but a signature endorses BYTES (an event
;; file, a temp materialized from a SQLite BLOB — the same bytes either
;; way, so a signature is backend-independent). These three cores hold the
;; exact ssh-keygen argument vectors once; the file and byte variants below
;; only differ in where the signed/verified bytes and the sig come from.

(defn- write-temp ^java.io.File [prefix ^bytes b]
  (let [tmp (java.io.File/createTempFile prefix ".tik")]
    (with-open [o (io/output-stream tmp)] (.write o b))
    tmp))

(defn- ssh-sign->bytes
  "Sign `target` in place; ssh-keygen writes <target>.sig — read and
  remove it, returning the detached signature bytes (ASCII SSHSIG armor)."
  ^bytes [key-file ^java.io.File target sig-namespace]
  (sh! "ssh-keygen" "-Y" "sign" "-f" (str key-file)
       "-n" sig-namespace (str target))
  (let [produced (io/file (str target ".sig"))]
    (try (with-open [is (io/input-stream produced)] (.readAllBytes is))
         (finally (.delete produced)))))

(defn- ssh-verify [allowed-signers in ^java.io.File sig actor sig-namespace]
  (zero? (:exit (sh/sh "ssh-keygen" "-Y" "verify"
                       "-f" (str allowed-signers) "-I" actor
                       "-n" sig-namespace "-s" (str sig) :in in))))

(defn- ssh-principals [allowed-signers in ^java.io.File sig sig-namespace]
  (let [r (sh/sh "ssh-keygen" "-Y" "find-principals"
                 "-f" (str allowed-signers)
                 "-n" sig-namespace "-s" (str sig) :in in)]
    (if (zero? (:exit r))
      (vec (remove str/blank? (str/split-lines (:out r))))
      [])))

;; -------------------------------------------------- file variants (sidecars)

(defn sign!
  "Sign `file` (the exact stored bytes) with `key-file`; write the sidecar
  next to it; return the sidecar File. Namespace defaults to event signing;
  pass namespace-process for definitions."
  ([key-file file artifact-id] (sign! key-file file artifact-id namespace-event))
  ([key-file ^java.io.File file artifact-id sig-namespace]
   (let [target (sig-file (.getParentFile file) artifact-id
                          (fingerprint (pubkey key-file)))]
     (io/copy (ssh-sign->bytes key-file file sig-namespace) target)
     target)))

(defn verify
  "Does `sig` verify `file`'s bytes as authored by `actor`, per the
  allowed-signers registry? Pure exit-code check; no output parsing."
  ([allowed-signers file sig actor]
   (verify allowed-signers file sig actor namespace-event))
  ([allowed-signers ^java.io.File file ^java.io.File sig actor sig-namespace]
   (ssh-verify allowed-signers (slurp file) sig actor sig-namespace)))

(defn find-principals
  "Which registered principals could have produced `sig` over `file`?
  Empty when the key is not in the registry."
  [allowed-signers ^java.io.File file ^java.io.File sig sig-namespace]
  (ssh-principals allowed-signers (slurp file) sig sig-namespace))

;; -------------------------------------------- byte variants (the store seam)

(defn sign-bytes
  "Sign the exact `bytes` with `key-file`; return the detached signature
  bytes. Namespace defaults to event signing."
  (^bytes [key-file bytes] (sign-bytes key-file bytes namespace-event))
  (^bytes [key-file ^bytes bytes sig-namespace]
   (let [tmp (write-temp "tik-sign" bytes)]
     (try (ssh-sign->bytes key-file tmp sig-namespace)
          (finally (.delete tmp))))))

(defn verify-bytes
  "Does `sig-bytes` verify `event-bytes` as authored by `actor`?"
  ([allowed-signers event-bytes sig-bytes actor]
   (verify-bytes allowed-signers event-bytes sig-bytes actor namespace-event))
  ([allowed-signers ^bytes event-bytes ^bytes sig-bytes actor sig-namespace]
   (let [sig (write-temp "tik-verify" sig-bytes)]
     (try (ssh-verify allowed-signers event-bytes sig actor sig-namespace)
          (finally (.delete sig))))))

(defn find-principals-bytes
  "Which registered principals could have produced `sig-bytes` over
  `event-bytes`? Empty when the key is not in the registry."
  [allowed-signers ^bytes event-bytes ^bytes sig-bytes sig-namespace]
  (let [sig (write-temp "tik-fp" sig-bytes)]
    (try (ssh-principals allowed-signers event-bytes sig sig-namespace)
         (finally (.delete sig)))))

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
  restricted so the binding cannot endorse anything but tik signatures."
  [actor pubkey-line]
  (str actor " namespaces=\"tik-*\" " pubkey-line))
