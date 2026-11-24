;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.pq-keys
  "ML-DSA-65 keys made at test time, so no private key is ever committed:
  one from the JDK always, and one from OpenSSL where the host's OpenSSL
  has ML-DSA (3.5 or later), for its PKCS#8 encoding."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.harness :as h])
  (:import (java.security KeyPairGenerator)
           (java.util Base64)))

(def ^:private label
  ;; assembled, so a secret scanner reading this file finds no key header
  (str "PRIVATE" " KEY"))

(defn- pem ^String [^bytes der]
  (str "-----BEGIN " label "-----\n"
       (.encodeToString (Base64/getMimeEncoder 64 (.getBytes "\n")) der)
       "\n-----END " label "-----\n"))

(defn jdk-key
  "{:pem :public-key :public-key-b64} of a fresh JDK ML-DSA-65 key pair."
  []
  (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "ML-DSA-65"))
        pub (.getEncoded (.getPublic kp))]
    {:pem (pem (.getEncoded (.getPrivate kp)))
     :public-key pub
     :public-key-b64 (.encodeToString (Base64/getEncoder) pub)}))

(def openssl-has-ml-dsa?
  (str/includes? (str/lower-case (:out (sh/sh "sh" "-c" "openssl list -signature-algorithms 2>/dev/null")))
                 "ml-dsa-65"))

(defn openssl-key
  "{:pem :public-key :public-key-b64} of a fresh ML-DSA-65 key from
  `openssl genpkey`, or nil where the host's OpenSSL has no ML-DSA."
  []
  (when openssl-has-ml-dsa?
    (let [dir (h/temp-dir! "tik-pq-key")
          k (io/file dir "k.pem")
          d (io/file dir "pub.der")]
      (sh/sh "openssl" "genpkey" "-algorithm" "ML-DSA-65" "-out" (str k))
      (sh/sh "openssl" "pkey" "-in" (str k) "-pubout" "-outform" "DER" "-out" (str d))
      (let [pub (java.nio.file.Files/readAllBytes (.toPath d))]
        {:pem (slurp k)
         :public-key pub
         :public-key-b64 (.encodeToString (Base64/getEncoder) pub)}))))
