;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mime
  "A minimal, dependency-free MIME reader for inbound mail: enough to turn
  a real-world multipart/alternative message — the near-universal shape a
  modern client sends, a text/plain part beside a base64 or
  quoted-printable text/html part — into readable text. Pure over the
  message string; the raw message is carried as ISO-8859-1 so each byte
  maps to one char and transfer/charset decoding is lossless. No I/O, no
  library: works identically on babashka and the native binary.

  `best-text` is the whole public surface — it never throws (an inbound
  message is fully attacker-controlled), returning a best effort or the
  undecoded region rather than a stack trace. The raw message stays the
  source of truth in the store; this is only the readable projection."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream)
           (java.nio.charset Charset)
           (java.util Base64)))

(defn- head-body
  "Split a MIME entity at its first blank line: [header-block body]. The
  body keeps everything after the separator verbatim (minus the blank
  line), so transfer-encoded payloads decode byte-exactly."
  [s]
  (let [m (re-matcher #"\r?\n\r?\n" s)]
    (if (.find m)
      [(subs s 0 (.start m)) (subs s (.end m))]
      [s ""])))

(defn- header-map
  "Lowercased header-name -> value over a header block, honoring folded
  continuation lines. First occurrence wins (Content-Type et al. are
  single-valued in a well-formed part)."
  [head]
  (let [lines (str/split-lines head)
        unfolded (loop [acc [] [l & more] lines]
                   (cond
                     (nil? l) acc
                     (and (seq acc) (re-matches #"^[ \t].*" l))
                     (recur (conj (pop acc) (str (peek acc) " " (str/trim l))) more)
                     :else (recur (conj acc l) more)))]
    (reduce (fn [m line]
              (if-let [[_ k v] (re-matches #"(?s)([^:\s][^:]*):\s*(.*)" line)]
                (let [k (str/lower-case (str/trim k))]
                  (if (contains? m k) m (assoc m k (str/trim v))))
                m))
            {} unfolded)))

(defn- content-type
  "The media type (lowercased) and its parameters (lowercased keys,
  de-quoted values) for a part; defaults to text/plain per RFC 2045."
  [headers]
  (let [ct (get headers "content-type" "text/plain")
        [media & params] (str/split ct #";")
        pmap (into {} (for [p params
                            :let [[k v] (str/split p #"=" 2)]
                            :when (and k v)]
                        [(str/lower-case (str/trim k))
                         (str/replace (str/trim v) #"^\"|\"$" "")]))]
    {:media (str/lower-case (str/trim (or media "text/plain"))) :params pmap}))

(defn- qp->bytes
  "Decode quoted-printable: `=XX` hex octets and `=`-soft line breaks."
  ^bytes [s]
  (let [s (str/replace s #"=\r?\n" "")
        n (count s)
        out (ByteArrayOutputStream.)]
    (loop [i 0]
      (if (< i n)
        (let [c (.charAt s i)]
          (if (and (= c \=) (< (+ i 2) (inc n))
                   (re-matches #"[0-9A-Fa-f]{2}" (subs s (inc i) (min n (+ i 3)))))
            (do (.write out (Integer/parseInt (subs s (inc i) (+ i 3)) 16))
                (recur (+ i 3)))
            (do (.write out (bit-and (int c) 0xff)) (recur (inc i)))))
        (.toByteArray out)))))

(defn- decode-charset
  ^String [^bytes b charset]
  (let [^Charset cs (try (Charset/forName charset)
                         (catch Exception _ (Charset/forName "UTF-8")))]
    (String. b cs)))

(defn- decode-body
  "A leaf part's decoded text: undo the transfer encoding to bytes, then
  read them in the part's charset (UTF-8 by default)."
  [headers ^String body]
  (let [enc (str/lower-case (str/trim (get headers "content-transfer-encoding" "7bit")))
        bytes (case enc
                "base64" (.decode (Base64/getMimeDecoder)
                                  (.getBytes (str/replace body #"\s" "") "US-ASCII"))
                "quoted-printable" (qp->bytes body)
                (.getBytes body "ISO-8859-1"))
        cs (get (:params (content-type headers)) "charset" "UTF-8")]
    (decode-charset bytes cs)))

(def ^:private entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " " "#39" "'"})

(defn- decode-entities [s]
  (-> s
      (str/replace #"&#x([0-9A-Fa-f]+);"
                   (fn [[_ h]] (str (char (Integer/parseInt h 16)))))
      (str/replace #"&#(\d+);" (fn [[_ d]] (str (char (Integer/parseInt d)))))
      (str/replace #"&([a-zA-Z]+|#\d+);" (fn [[m e]] (get entities e m)))))

(defn- html->text
  "A readable plaintext rendering of an HTML part — block tags become
  newlines, list items get a bullet, tags are stripped, entities decoded.
  Not a browser: a legible fallback when a sender offered only text/html."
  [html]
  (-> html
      (str/replace #"(?is)<(script|style)\b[^>]*>.*?</\1>" "")
      (str/replace #"(?i)<br\s*/?>" "\n")
      (str/replace #"(?i)<li[^>]*>" "- ")
      (str/replace #"(?i)</(p|div|tr|li|h[1-6]|ul|ol|table)>" "\n")
      (str/replace #"(?s)<[^>]+>" "")
      decode-entities
      (str/replace #"[ \t]+\n" "\n")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn- parse-entity
  "Recursively parse a MIME entity into {:media :params :headers} plus
  either :body (a leaf) or :parts (a multipart's children)."
  [s]
  (let [[head body] (head-body s)
        headers (header-map head)
        {:keys [media params]} (content-type headers)]
    (if (str/starts-with? media "multipart/")
      (let [boundary (get params "boundary")
            segs (when (seq boundary)
                   (->> (str/split body
                                   (re-pattern (str "(?m)^--\\Q" boundary "\\E.*$")))
                        rest                       ; drop the preamble
                        (map #(str/replace % #"^\r?\n" ""))
                        (remove str/blank?)))]
        {:media media :params params :headers headers
         :parts (mapv parse-entity segs)})
      {:media media :params params :headers headers :body body})))

(defn- leaves [entity]
  (if (contains? entity :parts)
    (mapcat leaves (:parts entity))
    [entity]))

(defn best-text
  "The readable body of a raw RFC822 message: the text/plain part if the
  sender included one, else the text/html part rendered to text, else the
  first leaf's decoded body. Total over hostile input — any decoding
  failure falls back to the undecoded body region, never a throw."
  [raw]
  (try
    (let [ls (leaves (parse-entity raw))
          by (fn [media] (first (filter #(= media (:media %)) ls)))
          pick (or (by "text/plain") (by "text/html") (first ls))]
      (cond
        (nil? pick) ""
        (= "text/html" (:media pick)) (html->text (decode-body (:headers pick) (:body pick)))
        :else (str/trim (decode-body (:headers pick) (:body pick)))))
    (catch Throwable _
      (str/trim (second (head-body (str raw)))))))
