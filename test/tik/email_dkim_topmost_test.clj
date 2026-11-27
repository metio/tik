;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.email-dkim-topmost-test
  "The DKIM gate reads only the topmost Authentication-Results header,
  the one the delivering MTA prepended above everything the sender wrote,
  and `:authserv-id` accepts anchored `{:pattern …}` ids. Header order
  survives every ingest path (stdin, IMAP, POP3), so `topmost` means the
  same thing wherever a message comes from."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.bridge :as bridge]
            [tik.cli]
            [tik.harness :as h]
            [tik.imap :as imap]
            [tik.mail :as mail]
            [tik.mailbox :as mailbox]
            [tik.pop3 :as pop3]
            [tik.store.protocol :as store])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------- fixtures

(def ^:private migadu-pattern {:pattern "mx[0-9]+\\.migadu\\.com"})

(def ^:private migadu-cfg
  {:process :track :default-actor "inbound"
   :dkim {:require true :authserv-id migadu-pattern}})

(defn- crlf [& lines] (str (str/join "\r\n" lines) "\r\n"))

(def ^:private migadu-inbound
  "The shape Migadu delivered a Gmail mail in on 2026-11-27, with the
  domains replaced by example.org and the signatures by placeholders:
  Migadu's own Authentication-Results, stamped by the receiving MX host,
  is the topmost one, above the sender's ARC set."
  (crlf "Delivered-To: security@example.org"
        "Received: from mizu0.migadu.com by soraStorage3.migadu.com with LMTP;"
        "\tThu, 27 Nov 2026 10:02:11 +0000"
        "ARC-Seal: i=2; a=rsa-sha256; d=migadu.com; s=arc; cv=pass; b=placeholder"
        "ARC-Message-Signature: i=2; a=rsa-sha256; d=migadu.com; s=arc; h=from:to; b=placeholder"
        "ARC-Authentication-Results: i=2; mx13.migadu.com;"
        "\tdkim=pass header.d=example.org header.s=s1 header.b=placeholder;"
        "\tarc=pass; spf=pass smtp.mailfrom=alice@example.org; dmarc=pass header.from=example.org"
        "Authentication-Results: mx13.migadu.com;"
        "\tdkim=pass header.d=example.org header.s=s1 header.b=placeholder;"
        "\tarc=pass (\"example.org:s=arc:i=1\");"
        "\tspf=pass (mx13.migadu.com: domain of alice@example.org designates 192.0.2.10 as permitted sender) smtp.mailfrom=alice@example.org;"
        "\tdmarc=pass (policy=none) header.from=example.org"
        "X-Migadu-Flow: FLOW_IN"
        "Received: from mail-lj1-x230.example.org by mizu0.migadu.com with ESMTPS"
        "DKIM-Signature: v=1; a=rsa-sha256; d=example.org; s=s1; h=from:to:subject; b=placeholder"
        "ARC-Seal: i=1; a=rsa-sha256; cv=none; d=example.org; s=arc; b=placeholder"
        "ARC-Authentication-Results: i=1; mx.example.org; arc=none"
        "From: Alice <alice@example.org>"
        "To: security@example.org"
        "Subject: hello"
        "Message-ID: <inbound-1@example.org>"
        "Date: Thu, 27 Nov 2026 10:02:09 +0000"
        ""
        "body"))

(defn- with-header-on-top
  "The message with `header` prepended, as an MTA delivering it would."
  [raw header]
  (str header "\r\n" raw))

(defn- with-header-at
  "The message with `header` inserted right before the From header, where
  a sender writes their own headers."
  [raw header]
  (str/replace-first raw "From: " (str header "\r\nFrom: ")))

(def ^:private forged-pass
  "Authentication-Results: mx13.migadu.com; dkim=pass header.d=example.org")

(defn- verdict [cfg raw]
  (try (mail/require-dkim! cfg (mail/parse-rfc822 raw)) :accepted
       (catch clojure.lang.ExceptionInfo _ :refused)))

(defn- simple
  "A message whose header block is `ars` in that order, then From."
  [& ars]
  (apply crlf (concat (map #(str "Authentication-Results: " %) ars)
                      ["From: alice@example.org" "Subject: s" "" "b"])))

;; ---------------------------------------------------------------- topmost only

(deftest the_observed_migadu_shape_is_accepted_by_the_pattern
  (is (= :accepted (verdict migadu-cfg migadu-inbound)))
  (is (= :accepted (verdict {:dkim {:require true :authserv-id "mx13.migadu.com"}} migadu-inbound))
      "an exact id still works")
  (is (= :refused (verdict {:dkim {:require true :authserv-id "mx14.migadu.com"}} migadu-inbound))
      "an exact id for another host fails closed"))

(deftest a_forged_lower_header_never_counts
  (testing "an untrusted topmost header with a forged trusted one below: refused"
    (is (= :refused (verdict migadu-cfg (simple "mx.elsewhere.test; dkim=pass header.d=example.org"
                                                "mx13.migadu.com; dkim=pass header.d=example.org"))))
    (is (= :refused (verdict migadu-cfg
                             (with-header-on-top (with-header-at migadu-inbound forged-pass)
                               "Authentication-Results: relay.example.net; dkim=none")))))
  (testing "a trusted topmost dkim=fail with a forged pass below: refused"
    (is (= :refused (verdict migadu-cfg (simple "mx13.migadu.com; dkim=fail header.d=example.org"
                                                "mx13.migadu.com; dkim=pass header.d=example.org"))))
    (is (= :refused (verdict migadu-cfg (simple "mx2.migadu.com; dkim=fail header.d=example.org"
                                                "mx13.migadu.com; dkim=pass header.d=example.org")))))
  (testing "a trusted topmost pass for another domain with a forged pass below: refused"
    (is (= :refused (verdict migadu-cfg (simple "mx13.migadu.com; dkim=pass header.d=other.example"
                                                "mx13.migadu.com; dkim=pass header.d=example.org")))))
  (testing "a trusted topmost pass: accepted, whatever sits below it"
    (is (= :accepted (verdict migadu-cfg (simple "mx13.migadu.com; dkim=pass header.d=example.org"))))
    (is (= :accepted (verdict migadu-cfg (with-header-at migadu-inbound
                                           "Authentication-Results: mx.elsewhere.test; dkim=fail"))))))

(deftest the_refusal_names_the_topmost_verifier
  (let [e (try (mail/require-dkim! migadu-cfg
                                   (mail/parse-rfc822 (simple "mx.elsewhere.test; dkim=pass header.d=example.org"
                                                              "mx13.migadu.com; dkim=pass header.d=example.org")))
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :dkim/unaligned (:reason (ex-data e))))
    (is (re-find #"topmost is stamped by mx\.elsewhere\.test" (ex-message e))))
  (let [e (try (mail/require-dkim! migadu-cfg (mail/parse-rfc822 (simple)))
               (catch clojure.lang.ExceptionInfo e e))]
    (is (re-find #"the message has none" (ex-message e)))))

(deftest a_single_header_behaves_as_with_an_exact_id
  (doseq [ar ["mx.example.org; dkim=pass header.d=example.org"
              "MX.Example.ORG 1; dkim=pass header.d=Example.org"
              "mx.example.org; dkim=fail header.d=example.org"
              "evil.test; dkim=pass header.d=example.org"
              ";dkim=pass header.d=example.org"
              ";;;;"]]
    (is (= (verdict {:dkim {:require true :authserv-id "mx.example.org"}} (simple ar))
           (verdict {:dkim {:require true :authserv-id ["mx.example.org" "mx2.example.org"]}} (simple ar)))
        ar))
  (is (= :accepted (verdict {:dkim {:require true :authserv-id "mx.example.org"}}
                            (simple "mx.example.org; dkim=pass header.d=example.org")))))

;; ---------------------------------------------------------------- patterns

(deftest a_pattern_matches_the_whole_id
  (let [t (mail/authserv-matcher migadu-pattern)]
    (is (t "mx13.migadu.com"))
    (is (t "mx1.migadu.com"))
    (testing "a suffix or prefix attack never matches"
      (is (not (t "evil.mx1.migadu.com")))
      (is (not (t "mx1.migadu.com.attacker")))
      (is (not (t "mx1.migadu.com.attacker.test")))
      (is (not (t "xmx1.migadu.com")))
      (is (not (t "mx1-migadu.com")))
      (is (not (t "mx.migadu.com"))))
    (is (not (t "")))
    (is (not (t nil))))
  (testing "a suffix-attacking topmost header is refused at the gate"
    (is (= :refused (verdict migadu-cfg (simple "mx1.migadu.com.attacker.test; dkim=pass header.d=example.org"))))
    (is (= :refused (verdict migadu-cfg (simple "evil.mx1.migadu.com; dkim=pass header.d=example.org")))))
  (testing "strings and patterns mix in one collection"
    (let [t (mail/authserv-matcher ["mx.example.net" migadu-pattern])]
      (is (t "mx.example.net"))
      (is (t "mx7.migadu.com"))
      (is (not (t "mx.example.org")))))
  (testing "a group may hold alternatives"
    (let [t (mail/authserv-matcher {:pattern "(mx|smtp)[0-9]{1,3}\\.migadu\\.com"})]
      (is (t "smtp12.migadu.com"))
      (is (not (t "mx1234.migadu.com"))))))

(deftest unsound_patterns_are_refused
  (doseq [p [".*" "" " " ".+\\.migadu\\.com" "mx1\\.migadu\\.com|.*"
             "mx[0-9]+\\.migadu\\.com|evil\\.example\\.org"
             "^mx[0-9]+\\.migadu\\.com$" "mx[0-9]+\\.migadu\\.com.*"
             "[a-z]+\\.com" "mx[0-9]+" "(?i)mx\\.migadu\\.com" "(?=x)mx\\.migadu\\.com"
             "mx\\S+\\.migadu\\.com" "mx\\w+\\.migadu\\.com" "[^x]+\\.migadu\\.com"
             "mx\\.migadu\\.co(m)?" "mx\\.migadu\\.com\\." "\\Q(\\E|x\\.migadu\\.com"
             "(mx\\.migadu\\.com" "mx)\\.migadu\\.com" "*\\.migadu\\.com"
             "[z-a]\\.migadu\\.com" "mx\\\\.migadu\\.com"]]
    (is (some? (mail/pattern-problem p)) (pr-str p))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"authserv-id is refused"
                          (mail/authserv-matcher {:pattern p}))
        (pr-str p)))
  (is (some? (mail/pattern-problem nil)))
  (is (some? (mail/pattern-problem 42)))
  (testing "a pattern map carries :pattern and nothing else"
    (is (seq (mail/authserv-id-problems {:pattern "mx\\.migadu\\.com" :flags "i"})))
    (is (seq (mail/authserv-id-problems {:regex "mx\\.migadu\\.com"}))))
  (testing "an id that is neither a string nor a pattern is refused"
    (is (seq (mail/authserv-id-problems :mx)))
    (is (seq (mail/authserv-id-problems ["mx.example.org" 7]))))
  (testing "the gate fails closed on a refused pattern"
    (is (= :refused (verdict {:dkim {:require true :authserv-id {:pattern ".*"}}}
                             (simple "anything; dkim=pass header.d=example.org"))))))

(deftest sound_patterns_are_accepted
  (doseq [p ["mx[0-9]+\\.migadu\\.com" "mx\\d+\\.migadu\\.com" "mx13\\.migadu\\.com"
             "(?:mx|smtp)[0-9]*\\.mail\\.example\\.org" "mx-[a-z0-9]{2,8}\\.example\\.org"]]
    (is (nil? (mail/pattern-problem p)) (pr-str p))))

(def ^:private good-mailbox
  {:dkim {:require true :authserv-id migadu-pattern}
   :default-actor "mail"
   :mailboxes [{:id "security" :process :track
                :imap {:host "imap.example.org" :user "security@example.org"
                       :password {:command ["pass" "show" "mail/security"]}}}]})

(deftest patterns_are_checked_at_config_load
  (is (empty? (mailbox/problems good-mailbox false)))
  (is (empty? (mailbox/problems (assoc-in good-mailbox [:dkim :authserv-id]
                                          ["mx.example.org" migadu-pattern])
                                false)))
  (doseq [bad [{:pattern ".*"} {:pattern ""} {:pattern "mx1\\.migadu\\.com.attacker"}
               ["mx.example.org" {:pattern "^mx\\.migadu\\.com"}] {} []]]
    (is (seq (mailbox/problems (assoc-in good-mailbox [:dkim :authserv-id] bad) false))
        (pr-str bad)))
  (testing "a single :imap mailbox not held to the strict rules still refuses a bad pattern"
    (let [single {:imap {:host "127.0.0.1" :tls false :user "u" :password "p"}
                  :process :track :default-actor "inbound"
                  :dkim {:require true :authserv-id {:pattern ".*"}}}]
      (is (some #(str/includes? % "unescaped .") (mailbox/problems single false)))
      (is (empty? (mailbox/problems (assoc-in single [:dkim :authserv-id] "mx.example.org") false))))))

;; ---------------------------------------------------------------- header order

(def ^:private ordered
  "Three Authentication-Results headers, one folded, names in mixed case,
  with ARC results in between that must not count."
  (crlf "Authentication-Results: first.example.org;"
        "\tdkim=pass header.d=example.org"
        "ARC-Authentication-Results: i=1; arc.example.org; dkim=pass"
        "authentication-results: second.example.org; dkim=fail"
        "X-Other: x"
        "AUTHENTICATION-RESULTS: third.example.org; dkim=pass header.d=example.org"
        "From: alice@example.org"
        ""
        "body"))

(def ^:private ordered-ids ["first.example.org" "second.example.org" "third.example.org"])

(defn- ids [raw] (mapv mail/authserv-id-of (:auth-results (mail/parse-rfc822 raw))))

(deftest the_parser_keeps_header_order
  (is (= ordered-ids (ids ordered)))
  (is (= ordered-ids (ids (str/replace ordered "\r\n" "\n"))) "bare LF line ends too")
  (is (= ["mx13.migadu.com"] (ids migadu-inbound))))

(deftest mime_parts_never_supply_a_header
  (let [raw (crlf "Authentication-Results: mx13.migadu.com; dkim=fail header.d=example.org"
                  "From: alice@example.org"
                  "MIME-Version: 1.0"
                  "Content-Type: multipart/mixed; boundary=\"b1\""
                  ""
                  "--b1"
                  "Authentication-Results: mx13.migadu.com; dkim=pass header.d=example.org"
                  "Content-Type: text/plain"
                  ""
                  "Authentication-Results: mx13.migadu.com; dkim=pass header.d=example.org"
                  "--b1"
                  "Content-Type: message/rfc822"
                  ""
                  "Authentication-Results: mx13.migadu.com; dkim=pass header.d=example.org"
                  "From: alice@example.org"
                  ""
                  "inner"
                  "--b1--")]
    (is (= ["mx13.migadu.com"] (ids raw)))
    (is (= :refused (verdict migadu-cfg raw)))))

(defn- fetch-response [tag seqn uid ^String msg]
  (let [len (count (.getBytes msg StandardCharsets/ISO_8859_1))]
    (str "* " seqn " FETCH (UID " uid " BODY[] {" len "}\r\n"
         msg ")\r\n"
         tag " OK FETCH completed\r\n")))

(deftest imap_fetch_keeps_header_order
  (let [script (str "* OK IMAP ready\r\n"
                    "* SEARCH 7\r\n"
                    "a1 OK SEARCH ok\r\n"
                    (fetch-response "a2" 1 7 ordered))
        in (ByteArrayInputStream. (.getBytes ^String script StandardCharsets/ISO_8859_1))
        s (imap/session in (ByteArrayOutputStream.))
        [{:keys [raw]}] (imap/fetch-since s "ALL")]
    (is (= ordered raw) "the literal comes back byte for byte")
    (is (= ordered-ids (ids raw)))))

(deftest pop3_retr_keeps_header_order
  (let [lines (str/split-lines ordered)
        script (str "+OK ready\r\n"
                    "+OK\r\n"
                    (str/join "\r\n" (map #(if (str/starts-with? % ".") (str "." %) %) lines))
                    "\r\n.\r\n")
        in (ByteArrayInputStream. (.getBytes ^String script StandardCharsets/ISO_8859_1))
        s (pop3/session in (ByteArrayOutputStream.))
        raw (pop3/retr s 1)]
    (is (= ordered-ids (ids raw)))))

;; ---------------------------------------------------------------- ingest paths

(def ^:private forged-lower
  "A message whose topmost header is from an untrusted verifier and whose
  lower one forges a pass in the trusted id."
  (crlf "Authentication-Results: relay.example.net; dkim=none"
        "Message-ID: <forged-1@example.org>"
        "Authentication-Results: mx13.migadu.com; dkim=pass header.d=example.org"
        "From: alice@example.org"
        "Subject: forged"
        ""
        "body"))

(deftest every_ingest_path_reads_the_topmost_header
  (testing "stdin: bridge email"
    (let [{:keys [root store]} (h/temp-store!)]
      (spit (io/file root "bridge.edn") (pr-str migadu-cfg))
      (let [run (fn [raw]
                  (h/with-cli-root root
                    (fn []
                      (binding [*in* (java.io.StringReader. raw)]
                        (tik.cli/run-argv ["bridge" "email" "--config" (str (io/file root "bridge.edn"))])))))
            r (run forged-lower)]
        (is (= 1 (:exit r)))
        (is (re-find #"topmost is stamped by relay\.example\.net" (:err r)))
        (is (empty? (store/ticket-ids store)))
        (is (zero? (:exit (run migadu-inbound))))
        (is (= 1 (count (store/ticket-ids store)))))))
  (testing "IMAP: bridge imap"
    (let [{:keys [root store]} (h/temp-store!)
          cfg-file (io/file (str root) "imap.edn")]
      (spit cfg-file (pr-str (assoc migadu-cfg :imap {:host "127.0.0.1" :tls false :user "u" :password "p"})))
      (with-redefs [imap/fetch-messages (fn [_] [{:uid 1 :raw forged-lower} {:uid 2 :raw migadu-inbound}])]
        (let [r (h/with-cli-root root #(tik.cli/run-argv ["bridge" "imap" "--config" (str cfg-file)]))]
          (is (zero? (:exit r)) (:err r))
          (is (str/includes? (:err r) "skip uid 1"))
          (is (= 1 (count (store/ticket-ids store))))))))
  (testing "POP3: bridge pop3"
    (let [{:keys [root store]} (h/temp-store!)
          cfg-file (io/file (str root) "pop3.edn")
          handled (atom [])]
      (spit cfg-file (pr-str migadu-cfg))
      (with-redefs [pop3/process-mailbox (fn [_ handle]
                                           (swap! handled conj (handle "A" forged-lower))
                                           (swap! handled conj (handle "B" migadu-inbound)))]
        (let [r (h/with-cli-root root #(tik.cli/run-argv ["bridge" "pop3" "--config" (str cfg-file)]))]
          (is (zero? (:exit r)) (:err r))
          (is (= [false true] @handled))
          (is (= 1 (count (store/ticket-ids store))))))))
  (testing "the ingest core directly"
    (let [{:keys [root store]} (h/temp-store!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no dkim=pass"
                            (h/with-cli-root root #(bridge/ingest-message! store migadu-cfg forged-lower {}))))
      (is (empty? (store/ticket-ids store))))))

;; ---------------------------------------------------------------- property

(def ^:private gen-forged-header
  (gen/fmap (fn [[id result d]] (str "Authentication-Results: " id "; dkim=" result " header.d=" d))
            (gen/tuple (gen/elements ["mx13.migadu.com" "mx1.migadu.com" "mx.elsewhere.test"
                                      "MX13.MIGADU.COM" "" "mx1.migadu.com.attacker.test"])
                       (gen/elements ["pass" "fail" "none" "pass (forged)"])
                       (gen/elements ["example.org" "sub.example.org" "other.example"]))))

(def ^:private gen-other-header
  (gen/elements ["X-Spam: no" "Received: from relay.example.net by mx.example.net"
                 "ARC-Authentication-Results: i=1; mx13.migadu.com; dkim=pass header.d=example.org"
                 "Authentication-Results:" "Authentication-Results: ;;;;"]))

(def ^:private gen-topmost
  (gen/elements ["mx13.migadu.com; dkim=pass header.d=example.org"
                 "mx13.migadu.com; dkim=fail header.d=example.org"
                 "mx2.migadu.com; dkim=pass header.d=other.example"
                 "relay.example.net; dkim=pass header.d=example.org"
                 "mx13.migadu.com; dkim=none"]))

(defspec no_forged_header_below_the_topmost_changes_the_verdict 300
  (prop/for-all [top gen-topmost
                 injected (gen/vector (gen/one-of [gen-forged-header gen-other-header]) 0 6)
                 order (gen/shuffle (range 6))]
    (let [injected (vec (keep #(get injected %) order))
          base (crlf (str "Authentication-Results: " top) "From: alice@example.org" "" "b")
          forged (apply crlf (concat [(str "Authentication-Results: " top)]
                                     injected
                                     ["From: alice@example.org" "" "b"]))]
      (= (verdict migadu-cfg base) (verdict migadu-cfg forged)))))
