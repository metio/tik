;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sk-fixture
  "Security-key (`sk-ssh-ed25519@openssh.com` and
  `sk-ecdsa-sha2-nistp256@openssh.com`) signatures made in software, byte
  for byte as a FIDO authenticator and ssh-keygen produce them, so the
  verifier can be tested without hardware. The authenticator signs
  SHA-256(application) || flags || counter || SHA-256(SSHSIG signed data);
  `flags` and `counter` are chosen here, which is what lets a test produce
  a signature no touch ever authorised."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream)
           (java.security KeyPairGenerator MessageDigest Signature)
           (java.security.spec ECGenParameterSpec)
           (java.util Base64)))

(def key-type "sk-ssh-ed25519@openssh.com")

(def ecdsa-key-type "sk-ecdsa-sha2-nistp256@openssh.com")

(def flag-user-present 0x01)
(def flag-user-verified 0x04)

(defn- put-u32 [^ByteArrayOutputStream out n]
  (doseq [s [24 16 8 0]] (.write out (unchecked-byte (bit-shift-right n s)))))

(defn- put-string [^ByteArrayOutputStream out ^bytes b]
  (put-u32 out (alength b))
  (.write out b 0 (alength b)))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(defn- sha256 ^bytes [^bytes b] (.digest (MessageDigest/getInstance "SHA-256") b))

(defn- sha512 ^bytes [^bytes b] (.digest (MessageDigest/getInstance "SHA-512") b))

(defn- bytes-of [f]
  (let [out (ByteArrayOutputStream.)] (f out) (.toByteArray out)))

(defn signer
  "A fresh software stand-in for a security key bound to `application`."
  ([] (signer "ssh:"))
  ([application]
   (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
         spki (.getEncoded (.getPublic kp))
         point (java.util.Arrays/copyOfRange spki (- (alength spki) 32) (alength spki))]
     {:pair kp
      :key-type key-type
      :application application
      :blob (bytes-of #(do (put-string % (utf8 key-type))
                           (put-string % point)
                           (put-string % (utf8 application))))})))

(defn- fixed-32
  "A non-negative coordinate as exactly 32 big-endian bytes."
  ^bytes [^java.math.BigInteger x]
  (let [b (.toByteArray x) n (alength b)]
    (cond (= n 32) b
          (> n 32) (java.util.Arrays/copyOfRange b (- n 32) n)
          :else (byte-array (concat (repeat (- 32 n) 0) b)))))

(defn ecdsa-signer
  "A fresh software stand-in for a P-256 security key bound to `application`."
  ([] (ecdsa-signer "ssh:"))
  ([application]
   (let [kp (.generateKeyPair (doto (KeyPairGenerator/getInstance "EC")
                                (.initialize (ECGenParameterSpec. "secp256r1"))))
         w (.getW ^java.security.interfaces.ECPublicKey (.getPublic kp))
         q (byte-array (concat [4] (fixed-32 (.getAffineX w)) (fixed-32 (.getAffineY w))))]
     {:pair kp
      :key-type ecdsa-key-type
      :application application
      :blob (bytes-of #(do (put-string % (utf8 ecdsa-key-type))
                           (put-string % (utf8 "nistp256"))
                           (put-string % q)
                           (put-string % (utf8 application))))})))

(defn public-line
  "The OpenSSH public key line, `sk-ssh-ed25519@openssh.com AAAA…`."
  [{:keys [blob] :as signer}]
  (str (:key-type signer key-type) " " (.encodeToString (Base64/getEncoder) ^bytes blob)))

(defn- der->ssh-ecdsa
  "A DER ECDSA signature as SSH writes it: mpint r, mpint s."
  ^bytes [^bytes der]
  (let [in (java.io.ByteArrayInputStream. der)
        read-int (fn [] (.read in)
                   (let [b (byte-array (.read in))] (.read in b) b))]
    (.read in)
    (let [l (.read in)] (when (> l 127) (dotimes [_ (- l 128)] (.read in))))
    (let [r (read-int) s (read-int)]
      (bytes-of #(do (put-string % r) (put-string % s))))))

(defn- armor [^bytes blob]
  (let [b64 (.encodeToString (Base64/getEncoder) blob)]
    (str "-----BEGIN SSH SIGNATURE-----\n"
         (str/join "\n" (map str/join (partition-all 70 b64)))
         "\n-----END SSH SIGNATURE-----\n")))

(defn sign
  "An armored SSHSIG over `message` for `namespace`, as the security key
  would make it with these `flags` and `counter`."
  [{:keys [pair application blob] :as signer} namespace ^bytes message
   & {:keys [flags counter] :or {flags flag-user-present counter 1}}]
  (let [signed-data (bytes-of #(do (.write ^ByteArrayOutputStream % (utf8 "SSHSIG"))
                                   (put-string % (utf8 namespace))
                                   (put-string % (byte-array 0))
                                   (put-string % (utf8 "sha512"))
                                   (put-string % (sha512 message))))
        covered (bytes-of #(do (.write ^ByteArrayOutputStream % (sha256 (utf8 application)))
                               (.write ^ByteArrayOutputStream % (int flags))
                               (put-u32 % counter)
                               (.write ^ByteArrayOutputStream % (sha256 signed-data))))
        kt (:key-type signer key-type)
        ecdsa? (= kt ecdsa-key-type)
        raw (.sign (doto (Signature/getInstance (if ecdsa? "SHA256withECDSA" "Ed25519"))
                     (.initSign (.getPrivate ^java.security.KeyPair pair))
                     (.update ^bytes covered)))
        raw (if ecdsa? (der->ssh-ecdsa raw) raw)
        sig-blob (bytes-of #(do (put-string % (utf8 kt))
                                (put-string % raw)
                                (.write ^ByteArrayOutputStream % (int flags))
                                (put-u32 % counter)))]
    (armor (bytes-of #(do (.write ^ByteArrayOutputStream % (utf8 "SSHSIG"))
                          (put-u32 % 1)
                          (put-string % blob)
                          (put-string % (utf8 namespace))
                          (put-string % (byte-array 0))
                          (put-string % (utf8 "sha512"))
                          (put-string % sig-blob))))))
