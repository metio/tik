;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.email-assoc-test
  "Robust email<->ticket association: an inbound reply finds its ticket
  from the X-Tik-Ticket header or the tik-shaped Message-ID it threads on
  (In-Reply-To/References), not only the [tik <id>] subject tag — and the
  outbound sink stamps exactly what an inbound reply reads back."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.cli]
            [tik.mail :as mail]))

(def ^:private parse-rfc822 mail/parse-rfc822)
(def ^:private ticket-ref-of mail/ticket-ref-of)
(def ^:private email-message @#'tik.cli/email-message)

(def ^:private id "12af5062-fe68-44e2-9727-da6e33cbee52")

(defn- ref-of [raw] (ticket-ref-of (parse-rfc822 raw)))

(deftest headers_associate_a_reply_to_its_ticket
  (testing "X-Tik-Ticket is the most reliable — wins over a decoy subject tag"
    (is (= id (ref-of (str "From: a@b\nSubject: [tik ffffffff] decoy\n"
                           "X-Tik-Ticket: " id "\n\nbody")))))
  (testing "In-Reply-To's tik-shaped Message-ID threads with NO subject tag"
    (is (= id (ref-of (str "From: a@b\nSubject: Re: cannot log in\n"
                           "In-Reply-To: <tik." id ".received@tik.local>\n\nbody")))))
  (testing "References works too (a client sets one or the other)"
    (is (= id (ref-of (str "From: a@b\nSubject: Re: hi\n"
                           "References: <x@other> <tik." id ".triaged@tik.local>\n\nbody")))))
  (testing "the [tik <id>] subject tag remains the human-visible fallback"
    (is (= "12af5062" (ref-of "From: a@b\nSubject: Re: [tik 12af5062] x\n\nbody"))))
  (testing "a fresh, unassociated email -> nil (the bridge opens a new ticket)"
    (is (nil? (ref-of "From: a@b\nSubject: brand new problem\n\nbody"))))
  (testing "headers are case-insensitive and folding is honored"
    (is (= id (ref-of (str "From: a@b\nsubject: x\nx-tik-ticket:\n  " id "\n\nbody"))))))

(deftest outbound_stamp_round_trips_through_inbound
  ;; what the sink stamps is exactly what an inbound reply reads back:
  ;; a reply quoting the sent Message-ID in In-Reply-To finds the ticket
  ;; even when the human rewrote the subject entirely.
  (let [sent (email-message {:to "cust@x" :from "support@x"}
                            {:ticket id :title "Cannot log in" :stage :received}
                            "set fact [:category]")
        parsed (parse-rfc822 sent)]
    (is (= id (:x-tik-ticket parsed)) "the sink stamped X-Tik-Ticket")
    (let [msg-id (second (re-find #"(?i)^Message-ID:\s*(\S+)"
                                  (str "Message-ID: <tik." id ".received@tik.local>")))
          reply (str "From: cust@x\nSubject: a completely different subject\n"
                     "In-Reply-To: " msg-id "\n\nthanks\ntik> category=technical\n")]
      (is (= id (ref-of reply)) "the reply threads back to the ticket"))))
