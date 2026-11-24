;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sshsig
  "SSHSIG verification, in process.

  OpenSSH's detached-signature format (PROTOCOL.sshsig), read and checked
  without forking `ssh-keygen`. Same wire format, same answer — the
  signatures in a store were produced by ssh-keygen and stay verifiable
  by it; this is a second reader of the same bytes.

  Why it exists: every signature check used to be a subprocess, so a host
  without OpenSSH could not judge authorship at all, and the container we
  publish is distroless. It also puts an edge or WASM implementation
  within reach, because what is left after this is a hash, a curve, and
  the fold.

  Scope is deliberately narrow: Ed25519 keys, which is what tik mints and
  what `tik actor add` documents, and Ed25519 security keys
  (`sk-ssh-ed25519@openssh.com`, a FIDO authenticator such as a YubiKey).
  Anything else — an RSA or ECDSA signer, an unknown hash — is answered
  `::unsupported` rather than false, so a caller falls back to ssh-keygen
  instead of calling an honest signature forged.

  A security-key signature counts only when the authenticator reports that
  a user was present (flag 0x01), the rule sshd applies unless told
  otherwise. The flag is inside the signed bytes, so it cannot be set after
  the fact, but `ssh-keygen -Y verify` does not look at it: there the two
  readers differ, and this one refuses. The flag is read for every
  security-key type, including the ECDSA ones this reader cannot verify,
  before anything is answered `::unsupported`, so a caller that falls back
  to ssh-keygen only ever hands it a touched signature. Fail-closed
  everywhere else: a malformed blob, a truncated
  field, a namespace that does not match, a key that is not the claimed
  actor's, all verify as false.

  Pure: bytes in, a verdict out. No files, no processes, no clock."
  (:require [clojure.string :as str])
  (:import (java.security KeyFactory MessageDigest Signature)
           (java.security.spec X509EncodedKeySpec)
           (java.util Base64)))

(def ^:private magic "SSHSIG")

(defn- u32
  "The big-endian uint32 at `off`."
  [^bytes b off]
  (if (< (alength b) (+ off 4))
    (throw (ex-info "truncated sshsig" {:reason :sshsig/malformed}))
    (bit-or (bit-shift-left (bit-and (aget b off) 0xff) 24)
            (bit-shift-left (bit-and (aget b (inc off)) 0xff) 16)
            (bit-shift-left (bit-and (aget b (+ off 2)) 0xff) 8)
            (bit-and (aget b (+ off 3)) 0xff))))

(defn- ssh-string
  "The length-prefixed field at `off` -> [bytes next-offset]. A length
  that runs past the end is a malformed blob, never a short read."
  [^bytes b off]
  (let [n (u32 b off)
        start (+ off 4)
        end (+ start n)]
    (when (or (neg? n) (< (alength b) end))
      (throw (ex-info "truncated sshsig field" {:reason :sshsig/malformed})))
    [(java.util.Arrays/copyOfRange b (int start) (int end)) end]))

(defn- put-string
  "Append a length-prefixed field to a byte sink."
  [^java.io.ByteArrayOutputStream out ^bytes b]
  (let [n (alength b)]
    (.write out (unchecked-byte (bit-shift-right n 24)))
    (.write out (unchecked-byte (bit-shift-right n 16)))
    (.write out (unchecked-byte (bit-shift-right n 8)))
    (.write out (unchecked-byte n))
    (.write out b 0 n)))

(defn unarmor
  "The base64 body between the BEGIN/END lines, decoded. nil when the
  armor is absent or the body is not base64 — an unreadable sidecar is
  not a signature."
  ^bytes [s]
  (try
    (let [lines (str/split-lines (str s))
          body (->> lines
                    (drop-while #(not (str/starts-with? % "-----BEGIN SSH SIGNATURE")))
                    rest
                    (take-while #(not (str/starts-with? % "-----END SSH SIGNATURE")))
                    (map str/trim)
                    str/join)]
      (when-not (str/blank? body)
        (.decode (Base64/getMimeDecoder) body)))
    (catch Exception _ nil)))

(defn parse
  "An SSHSIG blob -> {:public-key :namespace :hash-algorithm :signature},
  each a byte array except the two strings. nil when it is not one."
  [^bytes blob]
  (try
    (when (and blob (< 6 (alength blob))
               (= magic (String. blob 0 6 "US-ASCII")))
      (let [version (u32 blob 6)
            [pk o1] (ssh-string blob 10)
            [ns* o2] (ssh-string blob o1)
            [_reserved o3] (ssh-string blob o2)
            [halg o4] (ssh-string blob o3)
            [sig _] (ssh-string blob o4)]
        (when (= 1 version)
          {:public-key pk
           :namespace (String. ^bytes ns* "UTF-8")
           :hash-algorithm (String. ^bytes halg "UTF-8")
           :signature sig})))
    (catch Exception _ nil)))

(defn- digest
  "The message hash the signer committed to, or nil for an algorithm this
  reader does not implement."
  ^bytes [alg ^bytes message]
  (case alg
    "sha512" (.digest (MessageDigest/getInstance "SHA-512") message)
    "sha256" (.digest (MessageDigest/getInstance "SHA-256") message)
    nil))

(defn- signed-blob
  "The bytes an SSHSIG signature actually covers: the preamble, the
  namespace it was made for, the reserved field, the hash name, and the
  hash of the message. The namespace is IN here, which is what stops a
  signature made for one purpose from verifying for another."
  ^bytes [namespace hash-algorithm ^bytes message-hash]
  (let [out (java.io.ByteArrayOutputStream.)]
    (.write out (.getBytes ^String magic "US-ASCII"))
    (put-string out (.getBytes ^String namespace "UTF-8"))
    (put-string out (byte-array 0))
    (put-string out (.getBytes ^String hash-algorithm "UTF-8"))
    (put-string out message-hash)
    (.toByteArray out)))

(def ^:private ed25519-spki-prefix
  "The fixed SubjectPublicKeyInfo header for a raw Ed25519 point —
  X509EncodedKeySpec is the one key spec every runtime here exposes."
  (byte-array (map unchecked-byte
                   [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00])))

(def ^:private sk-ed25519 "sk-ssh-ed25519@openssh.com")

(def ^:private sk-user-present
  "The authenticator flag saying a user touched it while it signed."
  0x01)

(defn- ed25519-point->key [^bytes point]
  (.generatePublic (KeyFactory/getInstance "Ed25519")
                   (X509EncodedKeySpec.
                    (byte-array (concat (seq ed25519-spki-prefix) (seq point))))))

(defn- key-of
  "The key in an `ssh-ed25519` or `sk-ssh-ed25519@openssh.com` public-key
  blob, as {:type :key} plus the :application a security key is bound to,
  or nil when the blob is neither or has bytes left over."
  [^bytes pk]
  (try
    (let [[kind o1] (ssh-string pk 0)
          kind (String. ^bytes kind "UTF-8")
          [point o2] (ssh-string pk o1)]
      (when (= 32 (alength ^bytes point))
        (case kind
          "ssh-ed25519"
          (when (= o2 (alength pk))
            {:type kind :key (ed25519-point->key point)})
          "sk-ssh-ed25519@openssh.com"
          (let [[app o3] (ssh-string pk o2)]
            (when (= o3 (alength pk))
              {:type kind :key (ed25519-point->key point) :application app}))
          nil)))
    (catch Exception _ nil)))

(defn- signature-parts
  "The raw 64-byte signature inside a signature blob of `type`, with the
  authenticator's flags and counter for a security key. nil when the blob
  is of another type or has bytes left over."
  [type ^bytes sig]
  (try
    (let [[kind o1] (ssh-string sig 0)
          [raw o2] (ssh-string sig o1)]
      (when (and (= type (String. ^bytes kind "UTF-8"))
                 (= 64 (alength ^bytes raw)))
        (if (= type sk-ed25519)
          (when (= (alength sig) (+ o2 5))
            {:raw raw
             :flags (bit-and (aget sig o2) 0xff)
             :counter (java.util.Arrays/copyOfRange sig (int (inc o2)) (int (+ o2 5)))})
          (when (= o2 (alength sig))
            {:raw raw}))))
    (catch Exception _ nil)))

(defn- covered-bytes
  "What the Ed25519 signature is over: the SSHSIG signed data for a plain
  key, and for a security key SHA-256(application) || flags || counter ||
  SHA-256(signed data), which is what the authenticator signs."
  ^bytes [{:keys [type application]} {:keys [flags counter]} ^bytes signed]
  (if (= type sk-ed25519)
    (let [out (java.io.ByteArrayOutputStream.)
          sha (fn [^bytes b] (.digest (MessageDigest/getInstance "SHA-256") b))]
      (.write out ^bytes (sha application))
      (.write out (int flags))
      (.write out ^bytes counter)
      (.write out ^bytes (sha signed))
      (.toByteArray out))
    signed))

(defn- untouched?
  "Is the SSHSIG `parsed` made by a security key (`sk-…`) without a
  touch? A security-key signature blob is its type, the signature, a
  flags byte and a four-byte counter, whatever the curve; one that does
  not read in that shape counts as untouched, because its flag cannot be
  read."
  [{:keys [public-key signature]}]
  (let [kind (try (String. ^bytes (first (ssh-string public-key 0)) "UTF-8")
                  (catch Exception _ nil))]
    (when (some-> kind (str/starts-with? "sk-"))
      (let [^bytes sig signature
            flags (try
                    (let [[_ o1] (ssh-string sig 0)
                          [_ o2] (ssh-string sig o1)]
                      (when (= (alength sig) (+ o2 5))
                        (bit-and (aget sig o2) 0xff)))
                    (catch Exception _ nil))]
        (not (and flags (pos? (bit-and sk-user-present flags))))))))

(defn signer-key-type
  "The key type of the public key an armored SSHSIG `sidecar` (bytes or
  text) carries, such as \"sk-ssh-ed25519@openssh.com\", or nil when it is
  not a signature. Says nothing about whether the signature verifies."
  [sidecar]
  (let [text (if (bytes? sidecar) (String. ^bytes sidecar "UTF-8") (str sidecar))]
    (when-let [{:keys [public-key]} (some-> (unarmor text) parse)]
      (try (String. ^bytes (first (ssh-string public-key 0)) "UTF-8")
           (catch Exception _ nil)))))

(defn key-blob
  "The wire bytes of an `ssh-…` public key line — the part after the
  type, base64-decoded. nil when the line carries none."
  ^bytes [line]
  (try
    (when-let [b64 (some (fn [f] (when (re-matches #"AAAA[A-Za-z0-9+/=]+" f) f))
                         (str/split (str line) #"\s+"))]
      (.decode (Base64/getMimeDecoder) ^String b64))
    (catch Exception _ nil)))

(defn- pattern-matches?
  "OpenSSH's match_pattern: `*` is any run of characters, `?` exactly one."
  [pat s]
  (boolean
   (re-matches (re-pattern
                (str/join (map (fn [c]
                                 (case c
                                   \* ".*"
                                   \? "."
                                   (java.util.regex.Pattern/quote (str c))))
                               pat)))
               s)))

(defn- pattern-list-allows?
  "OpenSSH's match_pattern_list over a comma-separated list: a matching
  negated pattern (`!pat`) refuses whatever else matches, otherwise any
  matching pattern allows."
  [pats s]
  (let [ps (remove str/blank? (str/split pats #","))
        hits (filter (fn [p] (pattern-matches? (str/replace p #"^!" "") s)) ps)]
    (and (seq hits) (not-any? #(str/starts-with? % "!") hits))))

(defn- key-type? [token]
  (boolean (re-matches #"(ssh-(ed25519|rsa|dss|ed448)|ecdsa-sha2-[a-z0-9]+|sk-[a-z0-9@.-]+|[a-z0-9@.-]+-cert-v01@openssh\.com)"
                       (str token))))

(defn- read-options
  "The options field at the start of `s`, up to its first unquoted
  whitespace: [[name value-or-nil] ...] and the rest of the line. An
  option whose name cannot be read (`=x`) keeps its whole text as the
  name, so it counts as an unknown option, which refuses the line as
  OpenSSH does."
  [^String s]
  (loop [i 0, quoted? false]
    (if (or (= i (count s)) (and (not quoted?) (Character/isWhitespace (.charAt s i))))
      [(vec (for [opt (re-seq #"(?:[^,\"]|\"[^\"]*\")+" (subs s 0 i))
                  :let [[_ k v] (re-matches #"([^=]+)(?:=\"?(.*?)\"?)?" opt)]]
              (if k [(str/lower-case k) v] [opt nil])))
       (str/triml (subs s i))]
      (recur (inc i) (if (= \" (.charAt s i)) (not quoted?) quoted?)))))

(defn parse-signer-line
  "One allowed-signers line as OpenSSH reads it: principals, optional
  options, key type, base64 key, optional comment. nil for a blank or
  comment line, or one whose key cannot be read. Only the fields are
  read, so text in the comment never counts as an option or a key."
  [line]
  (let [l (str/trim (str line))]
    (when-not (or (str/blank? l) (str/starts-with? l "#"))
      (let [[principals rest*] (str/split l #"\s+" 2)
            rest* (str rest*)
            [options after] (if (key-type? (first (str/split rest* #"\s+" 2)))
                              [[] rest*]
                              (read-options rest*))
            [ktype b64] (str/split after #"\s+" 3)
            blob (when (and (key-type? ktype) b64 (re-matches #"AAAA[A-Za-z0-9+/=]+" b64))
                   (try (.decode (Base64/getDecoder) ^String b64) (catch Exception _ nil)))]
        (when blob
          {:principals (vec (remove str/blank? (str/split principals #",")))
           :options options
           :key-type ktype
           :blob blob})))))

(defn principals
  "The principal names an allowed-signers line names."
  [line]
  (or (:principals (parse-signer-line line)) []))

(def ^:private clock-options
  "Options whose answer depends on the time of the check. This reader has
  no clock, so a line carrying one defers to ssh-keygen."
  #{"valid-before" "valid-after"})

(defn- grant
  "What one parsed line says about `public-key` signing for `namespace`:
  :grants, :refuses, or ::unsupported. A cert-authority line accepts only
  certificates, never a direct signature by the CA key, and OpenSSH
  rejects a line with an option it does not know."
  [{:keys [options blob]} ^bytes public-key namespace]
  (let [names (set (map first options))
        ns-pats (some (fn [[k v]] (when (= "namespaces" k) v)) options)]
    (cond
      (or (not= (seq public-key) (seq blob))
          (seq (remove #{"namespaces" "cert-authority" "valid-before" "valid-after"} names))
          (names "cert-authority")
          (and ns-pats (not (pattern-list-allows? ns-pats namespace))))
      :refuses
      (some clock-options names) ::unsupported
      :else :grants)))

(defn- signer-lines
  "The parsed allowed-signers lines that grant `namespace` to a key,
  optionally narrowed to those naming `principal`, or ::unsupported when
  a line for that key needs a judgement this reader cannot make."
  [allowed-signers-text ^bytes public-key namespace principal]
  (let [verdicts (for [l (str/split-lines (str allowed-signers-text))
                       :let [parsed (parse-signer-line l)]
                       :when (and parsed
                                  (or (nil? principal)
                                      (some #{principal} (:principals parsed))))]
                   [parsed (grant parsed public-key namespace)])
        granting (keep (fn [[p v]] (when (= :grants v) p)) verdicts)]
    (cond
      (seq granting) granting
      (some #(= ::unsupported (second %)) verdicts) ::unsupported
      :else [])))

(defn- check
  "The cryptographic half, once a line has been found to grant the key."
  [{:keys [public-key hash-algorithm signature]} ^bytes message namespace]
  (let [pk (key-of public-key)]
    (cond
      (nil? pk) ::unsupported
      :else
      (if-let [hash (digest hash-algorithm message)]
        (let [parts (signature-parts (:type pk) signature)]
          (boolean
           (and parts
                ;; a security key that signed without a touch vouches for
                ;; nobody having been there
                (or (not= sk-ed25519 (:type pk))
                    (pos? (bit-and sk-user-present (:flags parts))))
                (try
                  (let [v (Signature/getInstance "Ed25519")]
                    (.initVerify v ^java.security.PublicKey (:key pk))
                    (.update v (covered-bytes pk parts
                                              (signed-blob namespace hash-algorithm hash)))
                    (.verify v ^bytes (:raw parts)))
                  (catch Exception _ false)))))
        ::unsupported))))

(defn verify
  "Does `signature` (armored SSHSIG, bytes or text) endorse `message` for
  `namespace`, under a key that `allowed-signers-text` grants to
  `principal`?

  true, false, or `::unsupported` — the last meaning this reader cannot
  judge (a key type or hash it does not implement), so a caller may fall
  back rather than treat honest evidence as forged."
  [allowed-signers-text ^bytes message signature principal namespace]
  (let [text (if (bytes? signature) (String. ^bytes signature "UTF-8") (str signature))
        parsed (some-> (unarmor text) parse)]
    (if (or (nil? parsed)
            ;; the namespace is inside the signed blob, so a mismatch here
            ;; is a signature made for another purpose
            (not= namespace (:namespace parsed))
            (untouched? parsed))
      false
      (let [lines (signer-lines allowed-signers-text (:public-key parsed)
                                namespace principal)]
        (cond
          (= ::unsupported lines) ::unsupported
          (empty? lines) false
          :else (check parsed message namespace))))))

(defn find-principals
  "Which registered principals could have produced `signature` over
  `message` for `namespace` — the set ssh-keygen's `-Y find-principals`
  reports. `::unsupported` when the key type is one this reader does not
  implement."
  [allowed-signers-text ^bytes message signature namespace]
  (let [text (if (bytes? signature) (String. ^bytes signature "UTF-8") (str signature))
        parsed (some-> (unarmor text) parse)]
    (if (or (nil? parsed) (not= namespace (:namespace parsed)) (untouched? parsed))
      []
      (let [verdict (check parsed message namespace)]
        (cond
          (= ::unsupported verdict) ::unsupported
          (not verdict) []
          :else (let [lines (signer-lines allowed-signers-text
                                          (:public-key parsed)
                                          namespace nil)]
                  (if (= ::unsupported lines)
                    ::unsupported
                    (vec (mapcat :principals lines)))))))))
