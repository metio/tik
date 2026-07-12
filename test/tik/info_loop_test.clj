;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.info-loop-test
  "The customer information-request loop end to end: a stage derives,
  the email sink asks the customer (subject routes replies, body
  renders explain and teaches the reply convention), the reply's
  `tik> key=value` lines become signed facts through the bridge, and
  the stage advances. Both directions are MTA-agnostic text."
  (:require [tik.harness :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tik! [root & args]
  (let [in (when (= :in (first (take-last 2 args))) (last args))
        args (if in (drop-last 2 args) args)]
    (:out (apply h/tik! {:root root :actor "agent" :in in} args))))

(deftest the_round_trip_asks_and_the_answer_advances_the_stage
  (let [root (.toFile (Files/createTempDirectory
                       "tik-loop" (make-array FileAttribute 0)))
        outbox (io/file root "outbox.eml")
        id (str/trim (tik! root "new" "track" "--title" "need the invoice id"))]
    (spit (io/file root "effects.edn")
          (pr-str {:sinks [{:type :email :to "customer@example.test"
                            :from "tik@example.test"
                            :command ["sh" "-c" (str "cat >> " outbox)]}]
                   :stages #{:open}}))
    (spit (io/file root "bridge.edn")
          (pr-str {:from->actor {"customer@example.test" "customer"}}))

    (testing "the effect emails a routable, teaching request"
      (tik! root "effects" "run" "--config" (str (io/file root "effects.edn")))
      (let [mail (slurp outbox)]
        (is (re-find (re-pattern (str "\\[tik " id "\\]")) mail)
            "subject routes replies back to the ticket")
        (is (re-find #"set fact \[:outcome\]" mail)
            "the body IS explain — it says exactly what is needed")
        (is (re-find #"tik> key=value" mail)
            "the body teaches the reply convention")))

    (testing "the reply's tik> lines become facts and settle the stage"
      (tik! root "bridge" "email"
            :in (str "From: customer@example.test\n"
                     "Subject: Re: [tik " id "] need the invoice id — open\n"
                     "\n"
                     "Hi! Sure, here you go:\n"
                     "tik> outcome=invoice 2026-0042, paid last week\n"
                     "Best, C.\n"))
      (let [status (tik! root "status" id)]
        (is (re-find #"stage:\s+done" status))
        (is (re-find #"invoice 2026-0042, paid last week" status)
            "the multi-word value survived as one string fact")
        (is (re-find #"by customer" status)
            "the fact is the CUSTOMER's claim, not the bridge's")))))
