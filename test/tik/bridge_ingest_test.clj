;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bridge-ingest-test
  "Email ingestion must be atom-bomb proof: loop-safe (our own mail and
  auto-replies never provoke another reply — the Odoo failure mode),
  idempotent (re-polling a message is a no-op), and TOTAL over hostile
  input (every message is attacker-controlled; a bad one skips with a
  clean message, never a crash or a cascade).

  Example tests pin each guard; the property tests hammer the invariants
  over thousands of synthetic emails. Scale the soak with
  TIK_MAIL_FUZZ_N (default 200) — set it to 40000 for a real pounding."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check :as tc]
            [tik.bridge :as bridge]
            [tik.cli]
            [tik.cli-core]
            [tik.harness :as h]
            [tik.imap]
            [tik.pop3]
            [tik.mail :as mail]
            [tik.store.protocol :as store]))

(defn- fuzz-n []
  (or (some-> (System/getenv "TIK_MAIL_FUZZ_N") parse-long) 200))

(def ^:private cfg {:process :track :default-actor "inbound"})
(def ^:private dkim-cfg
  {:process :track :default-actor "inbound"
   :dkim {:require true :authserv-id "mx.example"}})

(defn- ingest [root store config raw]
  (h/with-cli-root root (fn [] (bridge/ingest-message! store config raw {}))))

(defn- event-count [store]
  (reduce + 0 (map #(count (store/events store %)) (store/ticket-ids store))))

(defn- artifacts-for [root store id]
  (h/with-cli-root root #(:artifacts (:state (tik.cli-core/ticket-ctx store id)))))

;; ---------------------------------------------------------------- fixtures

(def ^:private our-mid "<tik.12af5062-fe68-44e2-9727-da6e33cbee52.received@tik.local>")

(defn- email
  "Assemble a raw RFC822 message from named parts (nil parts are omitted)."
  [{:keys [from subject msgid date irt xtik ar body loop-hdrs ctype cte]}]
  (str/join "\r\n"
            (concat
             (when from [(str "From: " from)])
             (when subject [(str "Subject: " subject)])
             (when msgid [(str "Message-ID: " msgid)])
             (when date [(str "Date: " date)])
             (when irt [(str "In-Reply-To: " irt)])
             (when xtik [(str "X-Tik-Ticket: " xtik)])
             (when ar [(str "Authentication-Results: " ar)])
             (when ctype [(str "Content-Type: " ctype)])
             (when cte [(str "Content-Transfer-Encoding: " cte)])
             (or loop-hdrs [])
             ["" (or body "")])))

;; ---------------------------------------------------------------- examples

(deftest own_mail_returned_is_dropped
  (let [{:keys [root store]} (h/temp-store!)
        r (ingest root store cfg
                  (email {:from "desk@example.com" :msgid our-mid :body "loop?"}))]
    (is (= :own (:outcome r)))
    (is (zero? (event-count store)) "nothing is written for our own returned mail")))

(deftest auto_reply_is_recorded_but_sets_no_facts
  (doseq [hdr [["Auto-Submitted: auto-replied"]
               ["Precedence: bulk"]
               ["List-Id: <list.example.com>"]
               ["X-Auto-Response-Suppress: All"]
               ["Return-Path: <>"]]]
    (let [{:keys [root store]} (h/temp-store!)
          r (ingest root store cfg
                    (email {:from "bot@vacation.example" :subject "Out of office"
                            :loop-hdrs hdr
                            :body "I am away.\ntik> sev=high\ntik> category=urgent"}))]
      (is (= :auto (:outcome r)) (str "auto signal " hdr))
      (is (zero? (:facts r 0)) (str "no facts cascade from " hdr))
      (let [id (:id r)]
        (is (= 1 (count (artifacts-for root store id))) "the message is still recorded for a human")))))

(deftest a_genuine_reply_with_the_same_lines_DOES_set_facts
  ;; the control for the auto test: identical tik> lines, no auto header
  (let [{:keys [root store]} (h/temp-store!)
        r (ingest root store cfg
                  (email {:from "alice@customer.example" :subject "help"
                          :body "please\ntik> sev=high\ntik> category=urgent"}))]
    (is (= :ticket (:outcome r)))
    (let [r2 (ingest root store cfg
                     (email {:from "alice@customer.example"
                             :subject "more" :xtik (str (:id r))
                             :body "update\ntik> sev=low"}))]
      (is (= :comment (:outcome r2)))
      (is (= 1 (:facts r2)) "a real reply's tik> line becomes a fact"))))

(deftest redelivery_is_deduplicated
  (let [{:keys [root store]} (h/temp-store!)
        msg (email {:from "alice@customer.example" :subject "help"
                    :msgid "<abc-123@customer.example>" :body "first"})
        r1 (ingest root store cfg msg)
        n1 (event-count store)
        r2 (ingest root store cfg msg)]
    (is (= :ticket (:outcome r1)))
    (is (= :dup (:outcome r2)) "the very same message re-fetched is a no-op")
    (is (= n1 (event-count store)) "no second event written on redelivery")))

(deftest two_independent_stores_mint_identical_events
  ;; leaderless ingest: two backends polling the same mailbox mint
  ;; byte-identical events for the same message (deterministic id + a
  ;; Date-derived :at), so a union merge keeps ONE ticket.
  (let [msg (email {:from "alice@customer.example" :subject "cannot log in"
                    :msgid "<xyz-9@customer.example>"
                    :date "Tue, 14 Jul 2026 09:00:00 +0000"
                    :body "help please"})
        fp (fn []
             (let [{:keys [root store]} (h/temp-store!)]
               (ingest root store cfg msg)
               ;; deterministic identity (asserts one ticket, sorts events) —
               ;; never `(first (.list …))`, whose order is filesystem-defined.
               (h/sole-ticket-fingerprint root)))]
    (is (= (fp) (fp)) "same message -> identical ticket id and event ids across nodes")))

(deftest routing_reaches_the_right_ticket_every_way
  (let [{:keys [root store]} (h/temp-store!)
        r (ingest root store cfg
                  (email {:from "alice@customer.example" :subject "bug"
                          :msgid "<orig@customer.example>" :body "first report"}))
        id (:id r)]
    (testing "X-Tik-Ticket header routes back"
      (is (= id (:id (ingest root store cfg
                             (email {:from "alice@customer.example" :subject "re"
                                     :msgid "<a@x>" :xtik (str id) :body "more"}))))))
    (testing "a tik-shaped In-Reply-To threads back"
      (is (= id (:id (ingest root store cfg
                             (email {:from "alice@customer.example" :subject "re"
                                     :msgid "<b@x>"
                                     :irt (str "<tik." id ".received@tik.local>")
                                     :body "even more"}))))))
    (testing "the [tik <id>] subject tag is the human fallback"
      (is (= id (:id (ingest root store cfg
                             (email {:from "alice@customer.example"
                                     :msgid "<c@x>"
                                     :subject (str "Re: [tik " id "] bug")
                                     :body "still here"}))))))))

(deftest html_email_becomes_a_readable_comment
  (let [{:keys [root store]} (h/temp-store!)
        b64 (.encodeToString (java.util.Base64/getEncoder)
                             (.getBytes "<div>Please <b>approve</b> the release.</div>" "UTF-8"))
        r (ingest root store cfg
                  (email {:from "alice@customer.example" :subject "approve"
                          :ctype "multipart/alternative; boundary=B"
                          :body (str "--B\r\nContent-Type: text/html; charset=UTF-8\r\n"
                                     "Content-Transfer-Encoding: base64\r\n\r\n"
                                     b64 "\r\n--B--\r\n")}))
        id (:id r)
        [path {:keys [hash]}] (first (artifacts-for root store id))
        blob (slurp (io/file (str root "/tickets/" id "/blobs") hash))]
    (is (str/starts-with? path "mail/"))
    (is (str/includes? blob "Please approve the release.") "the HTML part is decoded to text")
    (is (not (str/includes? blob "<div>")) "no raw HTML in the comment")))

(deftest unknown_sender_without_default_is_refused
  (let [{:keys [root store]} (h/temp-store!)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"unknown sender"
         (ingest root store {:process :track}                ; no :default-actor
                 (email {:from "stranger@nowhere.example" :body "hi"}))))
    (is (zero? (event-count store)))))

(deftest dkim_gate_refuses_at_ingest
  (let [{:keys [root store]} (h/temp-store!)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no dkim=pass"
         (ingest root store dkim-cfg
                 (email {:from "alice@customer.example" :subject "x" :body "b"}))))
    (is (zero? (event-count store)) "an unverified sender writes nothing")))

(deftest batch_isolation_one_bad_message_never_stops_the_poll
  ;; the IMAP command ingests each fetched message in isolation: a
  ;; refusal/crash skips ONE and the batch continues. Drive it with a
  ;; stubbed fetch so no network is touched.
  (let [{:keys [root store]} (h/temp-store!)
        ar "mx.example; dkim=pass header.d=customer.example"
        good1 (email {:from "alice@customer.example" :subject "one"
                      :ar ar :msgid "<g1@x>" :body "hello"})
        bad   (email {:from "alice@customer.example" :subject "boom"  ; no Authentication-Results
                      :msgid "<b@x>" :body "boom"})
        good2 (email {:from "alice@customer.example" :subject "two"
                      :ar ar :msgid "<g2@x>" :body "world"})]
    (spit (io/file (str root) "imap.edn")
          (pr-str {:process :track :default-actor "inbound"
                   :dkim {:require true :authserv-id "mx.example"}}))
    (with-redefs [tik.imap/fetch-messages
                  (fn [_] [{:uid 1 :raw good1} {:uid 2 :raw bad} {:uid 3 :raw good2}])]
      (let [r (h/with-cli-root root
                #(tik.cli/run-argv ["bridge" "imap"
                                    "--config" (str (io/file (str root) "imap.edn"))]))]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:err r) "skip uid 2") "the bad message is skipped with a clean line")
        (is (= 2 (count (store/ticket-ids store))) "both good messages ingested despite the bad one")))))

(deftest pop3_ingests_through_the_shared_core_and_signals_delete
  ;; `bridge pop3` drives the same isolated ingest as imap: a good message
  ;; ingests (handler returns truthy -> caller may delete it), a bad one
  ;; is skipped (handler returns falsey -> kept on the server).
  (let [{:keys [root store]} (h/temp-store!)
        ar "mx.example; dkim=pass header.d=customer.example"
        good (email {:from "alice@customer.example" :subject "hi" :ar ar
                     :msgid "<g@x>" :body "hello"})
        bad  (email {:from "alice@customer.example" :subject "no-auth"  ; no A-R -> DKIM refusal
                     :msgid "<b@x>" :body "boom"})
        results (atom [])]
    (spit (io/file (str root) "pop3.edn")
          (pr-str {:process :track :default-actor "inbound"
                   :dkim {:require true :authserv-id "mx.example"}}))
    (with-redefs [tik.pop3/process-mailbox
                  (fn [_conn handle]
                    (swap! results conj (handle "AAA" good))
                    (swap! results conj (handle "BBB" bad)))]
      (let [r (h/with-cli-root root
                #(tik.cli/run-argv ["bridge" "pop3"
                                    "--config" (str (io/file (str root) "pop3.edn"))]))]
        (is (zero? (:exit r)) (:err r))
        (is (= [true false] @results) "handled -> deletable; refused -> kept on server")
        (is (str/includes? (:err r) "skip uid BBB"))
        (is (= 1 (count (store/ticket-ids store))) "only the good message became a ticket")))))

;; ---------------------------------------------------------------- generators

(def ^:private g-from
  (gen/one-of [(gen/return nil)
               (gen/return "alice@customer.example")
               (gen/return "\"Bob\" <bob@corp.example>")
               (gen/return "garbage-no-at")
               (gen/fmap #(str % "@x.example") gen/string-alphanumeric)]))

(def ^:private g-msgid
  (gen/one-of [(gen/return nil)
               (gen/return our-mid)                                  ; our own -> :own
               (gen/fmap #(str "<" % "@ex.example>") gen/string-alphanumeric)]))

(def ^:private g-loop
  (gen/vector (gen/elements ["Auto-Submitted: auto-replied" "Auto-Submitted: no"
                             "Precedence: bulk" "Precedence: list" "List-Id: <l@x>"
                             "Return-Path: <>" "X-Auto-Response-Suppress: All"])
              0 3))

(def ^:private g-body
  (gen/one-of [gen/string
               (gen/return "plain body")
               (gen/return "reply\ntik> sev=high\ntik> category=urgent")
               (gen/fmap #(str "--B\r\nContent-Type: text/plain\r\n\r\n" % "\r\n--B--") gen/string)
               (gen/return "=C3=A9 partial =")]))

(def ^:private g-email
  (gen/fmap email
            (gen/hash-map
             :from g-from
             :subject (gen/one-of [(gen/return nil) gen/string-alphanumeric])
             :msgid g-msgid
             :date (gen/elements [nil "Tue, 14 Jul 2026 09:00:00 +0000" "not-a-date"])
             :irt (gen/elements [nil "<x@y>" "<tik.deadbeef@tik.local>"])
             :ar (gen/elements [nil "mx.example; dkim=pass header.d=customer.example"])
             :loop-hdrs g-loop
             :body g-body)))

;; ---------------------------------------------------------------- properties

(defn- check [prop] (:pass? (tc/quick-check (fuzz-n) prop)))

(deftest ingest_is_total_over_hostile_mail
  ;; a value with a valid outcome, or a clean ex-info refusal — NEVER a
  ;; raw Throwable, over thousands of attacker-shaped messages.
  (let [{:keys [root store]} (h/temp-store!)
        valid #{:own :dup :auto :comment :ticket}]
    (is (check
         (prop/for-all [raw g-email]
           (try
             (contains? valid (:outcome (ingest root store cfg raw)))
             (catch clojure.lang.ExceptionInfo _ true)          ; refusal is allowed
             (catch Throwable _ false)))))))                     ; a raw throw is not

(deftest ingest_is_idempotent_over_generated_mail
  ;; ingesting any genuine message twice is a no-op the second time — the
  ;; second call reports :dup on the same message id. O(1) per trial so
  ;; the soak scales to tens of thousands.
  (let [{:keys [root store]} (h/temp-store!)]
    (is (check
         (prop/for-all [raw (gen/such-that
                             #(not (mail/own-message? (mail/parse-rfc822 %)))
                             g-email 100)]
           (let [r1 (try (ingest root store cfg raw)
                         (catch clojure.lang.ExceptionInfo _ ::refused))]
             (or (= ::refused r1)                                ; refused -> nothing to dedup
                 (let [r2 (ingest root store cfg raw)]
                   (and (= :dup (:outcome r2))
                        (= (:msgid r1) (:msgid r2)))))))))))

(deftest loop_guards_hold_over_generated_mail
  (let [{:keys [root store]} (h/temp-store!)]
    (is (check
         (prop/for-all [raw g-email]
           (let [msg (mail/parse-rfc822 raw)]
             (cond
               (mail/own-message? msg)
               (= :own (:outcome (ingest root store cfg raw)))    ; own -> dropped
               (mail/auto-generated? msg)
               (try (zero? (:facts (ingest root store cfg raw) 0)) ; auto -> never cascades facts
                    (catch clojure.lang.ExceptionInfo _ true))
               :else true)))))))

(deftest imap_reader_is_total_over_garbage_bytes
  ;; the transport must not hang or throw on a hostile/truncated server;
  ;; a session over random bytes yields a response map and terminates.
  (is (check
       (prop/for-all [bytes gen/bytes]
         (let [in (java.io.ByteArrayInputStream. bytes)
               out (java.io.ByteArrayOutputStream.)]
           (try
             (let [s (tik.imap/session in out)]
               (map? (tik.imap/cmd s "UID SEARCH UNSEEN")))
             (catch java.io.IOException _ true)
             (catch Throwable _ false)))))))
