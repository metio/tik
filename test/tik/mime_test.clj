;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mime-test
  "The inbound MIME reader turns the shapes real mail clients send —
  multipart/alternative, base64 and quoted-printable payloads, HTML-only
  bodies, non-ASCII charsets — into readable text, and stays TOTAL over
  hostile input (an inbound message is fully attacker-controlled)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.mime :as mime])
  (:import (java.util Base64)))

(defn- b64 [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(deftest plain_message_passes_through
  (testing "a non-MIME message is its body, unchanged"
    (is (= "just some text"
           (mime/best-text "From: a@b\nSubject: hi\n\njust some text")))))

(deftest quoted_printable_and_charset
  (testing "quoted-printable =XX octets and soft breaks decode to UTF-8"
    (is (= "Grüße — Zürich"
           (mime/best-text
            (str "Content-Type: text/plain; charset=UTF-8\n"
                 "Content-Transfer-Encoding: quoted-printable\n\n"
                 "Gr=C3=BC=C3=9Fe =E2=80=94 Z=\n=C3=BCrich"))))))

(deftest base64_decodes
  (testing "a base64 text/plain part decodes"
    (is (= "hello base64 world"
           (mime/best-text
            (str "Content-Type: text/plain; charset=UTF-8\n"
                 "Content-Transfer-Encoding: base64\n\n"
                 (b64 "hello base64 world")))))))

(deftest multipart_alternative_prefers_plain
  (let [raw (str "Content-Type: multipart/alternative; boundary=\"XYZ\"\n\n"
                 "preamble ignored\n"
                 "--XYZ\n"
                 "Content-Type: text/plain; charset=UTF-8\n\n"
                 "the plain version\n"
                 "--XYZ\n"
                 "Content-Type: text/html; charset=UTF-8\n\n"
                 "<p>the <b>html</b> version</p>\n"
                 "--XYZ--\n")]
    (testing "text/plain is chosen when both are offered"
      (is (= "the plain version" (mime/best-text raw))))))

(deftest html_only_is_rendered_to_text
  (testing "an HTML-only body is stripped to legible text with entities"
    (let [raw (str "Content-Type: text/html; charset=UTF-8\n\n"
                   "<html><head><style>p{color:red}</style></head><body>"
                   "<p>Hi&nbsp;there</p><p>line&amp;two</p>"
                   "<ul><li>one</li><li>two</li></ul></body></html>")
          out (mime/best-text raw)]
      (is (str/includes? out "Hi there"))
      (is (str/includes? out "line&two"))
      (is (str/includes? out "- one"))
      (is (str/includes? out "- two"))
      (is (not (str/includes? out "<")) "no tags survive")
      (is (not (str/includes? out "color:red")) "style contents dropped"))))

(deftest base64_html_multipart_end_to_end
  (testing "the realistic case: multipart/alternative, base64 HTML, no plain"
    (let [raw (str "Subject: real world\n"
                   "Content-Type: multipart/alternative; boundary=b1\n\n"
                   "--b1\n"
                   "Content-Type: text/html; charset=UTF-8\n"
                   "Content-Transfer-Encoding: base64\n\n"
                   (b64 "<div>Please <b>approve</b> the release.</div>") "\n"
                   "--b1--\n")]
      (is (= "Please approve the release." (mime/best-text raw))))))

(deftest nested_multipart_mixed_with_attachment
  (testing "multipart/mixed wrapping alternative + an attachment: text wins"
    (let [raw (str "Content-Type: multipart/mixed; boundary=OUT\n\n"
                   "--OUT\n"
                   "Content-Type: multipart/alternative; boundary=IN\n\n"
                   "--IN\n"
                   "Content-Type: text/plain\n\nthe message body\n"
                   "--IN\n"
                   "Content-Type: text/html\n\n<p>the message body</p>\n"
                   "--IN--\n"
                   "--OUT\n"
                   "Content-Type: application/pdf; name=doc.pdf\n"
                   "Content-Transfer-Encoding: base64\n\n"
                   (b64 "%PDF-1.4 not really") "\n"
                   "--OUT--\n")]
      (is (= "the message body" (mime/best-text raw))))))

(defspec best_text_is_total_over_hostile_input 300
  (prop/for-all [text (gen/one-of
                       [gen/string
                        gen/string-ascii
                        (gen/fmap #(str/join "\n" %)
                                  (gen/vector
                                   (gen/elements
                                    ["Content-Type: multipart/alternative; boundary=b"
                                     "Content-Type: text/html"
                                     "Content-Transfer-Encoding: base64"
                                     "Content-Transfer-Encoding: quoted-printable"
                                     "--b" "--b--" "=C3" "=" "<p>" "</p>" "&#;"
                                     "&#xZZ;" "" "\t" ";;;" "boundary=" "charset=bogus"])
                                   0 12))])]
    (string? (mime/best-text text))))
