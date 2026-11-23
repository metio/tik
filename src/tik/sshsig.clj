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
  what `tik actor add` documents. Anything else — an RSA or ECDSA signer,
  an unknown hash — is answered `::unsupported` rather than false, so a
  caller falls back to ssh-keygen instead of calling an honest signature
  forged. Fail-closed everywhere else: a malformed blob, a truncated
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

(defn- ed25519-key
  "A java.security key from an `ssh-ed25519` public-key blob, or nil when
  the blob is not one."
  [^bytes pk]
  (try
    (let [[kind o1] (ssh-string pk 0)
          [point _] (ssh-string pk o1)]
      (when (and (= "ssh-ed25519" (String. ^bytes kind "UTF-8"))
                 (= 32 (alength ^bytes point)))
        (.generatePublic (KeyFactory/getInstance "Ed25519")
                         (X509EncodedKeySpec.
                          (byte-array (concat (seq ed25519-spki-prefix)
                                              (seq point)))))))
    (catch Exception _ nil)))

(defn- ed25519-signature
  "The raw 64-byte signature inside an `ssh-ed25519` signature blob."
  ^bytes [^bytes sig]
  (try
    (let [[kind o1] (ssh-string sig 0)
          [raw _] (ssh-string sig o1)]
      (when (and (= "ssh-ed25519" (String. ^bytes kind "UTF-8"))
                 (= 64 (alength ^bytes raw)))
        raw))
    (catch Exception _ nil)))

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
  (let [^java.security.PublicKey key (ed25519-key public-key)]
    (cond
      (nil? key) ::unsupported
      :else
      (if-let [hash (digest hash-algorithm message)]
        (let [raw (ed25519-signature signature)]
          (boolean
           (and raw
                (try
                  (let [v (Signature/getInstance "Ed25519")]
                    (.initVerify v key)
                    (.update v (signed-blob namespace hash-algorithm hash))
                    (.verify v raw))
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
            (not= namespace (:namespace parsed)))
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
    (if (or (nil? parsed) (not= namespace (:namespace parsed)))
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
