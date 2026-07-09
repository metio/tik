;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.gen-events
  "Generators for kernel property tests: random event histories against the
  sample support process. Deliberately biased toward collisions — few fact
  paths, few actors, a narrow timestamp window so equal :event/at values
  are common — because ties are where ordering bugs live. Histories include
  retractions, disputes, invalid-per-schema values, and unknown event
  types, since the reducer must be total over all of them."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [tik.event :as event])
  (:import (java.time Instant)))

(def process (edn/read-string (slurp "processes/support-request.edn")))
(def roles (:process/roles process))
(def tid #uuid "018f2f6e-7c1a-7000-8000-00000000beef")
(def ^Instant base (Instant/parse "2026-07-08T10:00:00Z"))
(defn at-sec ^Instant [s] (.plusSeconds base (long s)))

(def actors ["seb" "billing" "customer" "rando"])
(def known-paths [[:category] [:severity] [:resolution :ref] [:customer :ack]])
(def paths (conj known-paths [:extra]))

(def gen-value
  "Mixes schema-valid enum values with booleans, ints and strings, so facts
  land in :present-and-valid as well as :present-but-invalid."
  (gen/one-of
   [(gen/elements [:billing :technical :account :abuse
                   :low :normal :high :critical])
    gen/boolean
    gen/small-integer
    (gen/fmap str/join (gen/vector gen/char-alphanumeric 0 12))]))

(def gen-op
  (gen/tuple (gen/frequency [[6 (gen/return :assert)]
                             [2 (gen/return :retract)]
                             [2 (gen/return :dispute)]
                             [2 (gen/return :attach)]
                             [1 (gen/return :unknown)]])
             (gen/elements actors)
             (gen/elements paths)
             gen-value
             ;; narrow window on purpose: (at, id) tie-breaks get exercised
             (gen/choose 0 600)))

(defn- unknown-event
  "A type outside the closed vocabulary, minted by hand (event/mint
  rightly rejects it). The reducer must carry it in the log untouched."
  [{:keys [parents actor at]}]
  (let [e {:event/ticket tid :event/type :something/new
           :event/actor actor :event/at at :event/parents (set parents)
           :event/body {:x 1}}]
    (assoc e :event/id (event/event-id e))))

(defn ops->events
  "Linear history: :ticket/create, then one event per op. Claimed times are
  random, so an event's :event/at may precede its parent's — claimed time
  and causal order are independent axes, and both codepaths must cope."
  [ops]
  (reduce
   (fn [evs [op actor path value sec]]
     (let [parents #{(:event/id (peek evs))}
           at (at-sec sec)
           arg {:ticket tid :actor actor :at at :parents parents}]
       (conj evs
             (case op
               :assert  (event/assert-fact (assoc arg :path path :value value))
               :retract (event/retract-fact (assoc arg :path path :reason "gen"))
               :dispute (event/dispute-fact (assoc arg :path path :reason "gen"))
               :attach  (event/attach-artifact
                         (assoc arg :path (str "repro/" (name (first path)))
                                :hash "sha256-cafe"))
               :unknown (unknown-event {:parents parents :actor actor :at at})))))
   [(event/create-ticket {:ticket tid :actor "customer" :at base
                          :title "gen" :process :support-request})]
   ops))

(def gen-events
  (gen/fmap ops->events (gen/vector gen-op 0 12)))

(def gen-now
  "An evaluation instant anywhere from ticket creation to well past the
  48h escalation window."
  (gen/fmap at-sec (gen/choose 0 300000)))
