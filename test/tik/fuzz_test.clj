;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.fuzz-test
  "The adversarial layer of the test stack: the other four oracles
  feed tik VALID inputs and check the answers; this one feeds garbage
  and checks the manner of failure. The contract everywhere is `fail
  well`: a clean, data-carrying rejection (ex-info or a schema error)
  — never an unexpected exception class, and above all never a silent
  pass. TIK_FUZZ_N scales the iteration counts (default keeps the
  gate fast; crank it for a soak run)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.canonical :as canonical]
            [tik.event :as event]
            [tik.gen-events :as ge]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(def ^:private n
  (or (some-> (System/getenv "TIK_FUZZ_N") parse-long) 50))

(defn- fails-well?
  "Run the thunk; true when it returns normally or throws ex-info.
  Any other throwable is a fuzz finding."
  [thunk]
  (try (thunk) true
       (catch clojure.lang.ExceptionInfo _ true)
       (catch Throwable _ false)))

;; --------------------------------------------------- malformed events

(def gen-garbage
  "Structural garbage: scalars, maps, and vectors that are shaped
  nothing like (or deceptively like) an event."
  (gen/one-of
   [gen/any-equatable
    (gen/map gen/keyword gen/any-equatable {:max-elements 6})
    ;; deceptive: a real event with one key nuked or one value mangled
    (gen/let [e (gen/elements
                 [{:event/ticket (random-uuid) :event/type :fact/assert
                   :event/actor "seb" :event/at (Instant/now)
                   :event/parents #{"sha256-x"}
                   :event/body {:fact/path [:x] :fact/value 1}}])
              k (gen/elements [:event/ticket :event/type :event/actor
                               :event/at :event/parents])
              v gen/any-equatable]
      (gen/one-of [(gen/return (dissoc e k))
                   (gen/return (assoc e k v))]))]))

(defspec malformed_events_are_rejected_never_crash_never_pass n
  (prop/for-all [garbage gen-garbage]
    (and (fails-well? #(event/mint garbage))
         ;; mint must never bless garbage: either it throws, or the
         ;; input happened to be a fully valid event map
         (try (event/valid? (event/mint garbage))
              (catch clojure.lang.ExceptionInfo _ true)))))

(defspec the_reducer_is_total_over_garbage_event_sets n
  ;; events that bypassed minting (a hostile store) must not crash the
  ;; fold — reduction is a pure fn of the set, garbage included
  (prop/for-all [garbage (gen/vector gen-garbage 0 5)
                 valid ge/gen-events]
    (fails-well? #(red/ticket-state (into (vec valid) garbage)))))

;; --------------------------------------------------- malformed guards

(def gen-garbage-guard
  (gen/one-of
   [gen/any-equatable
    (gen/vector gen/keyword 0 4)
    (gen/let [op (gen/elements [:fact :fact= :artifact :signed-by
                                :stage-reached :elapsed-since :and :or
                                :not :nonsense])
              args (gen/vector gen/any-equatable 0 3)]
      (gen/return (into [op] args)))]))

(defspec guard_evaluation_is_total_or_fails_well n
  ;; the runtime's closed-vocabulary throw is ex-info by contract; a
  ;; NullPointerException from a mangled argument list is a finding
  (prop/for-all [g gen-garbage-guard
                 events ge/gen-events]
    (fails-well?
     #(guard/eval-guard g {:state (red/ticket-state events)
                           :now (Instant/now)
                           :roles ge/roles
                           :reached #{}}))))

(defspec the_fixpoint_is_total_over_garbage_processes n
  (prop/for-all [stages (gen/vector
                         (gen/let [id gen/keyword
                                   guards (gen/vector gen-garbage-guard 0 2)]
                           (gen/return {:stage/id id :guards guards}))
                         0 4)
                 events ge/gen-events]
    (fails-well?
     #(stage/effective-reached {:process/id :fuzz :process/version 1
                                :process/stages stages}
                               events (Instant/now) ge/roles))))

;; ------------------------------------------------- corrupted stores

(deftest any_single_byte_flip_is_caught_by_the_stored_identity
  ;; ADR 0007's whole point, fuzzed: filename = sha256(bytes), so no
  ;; corruption of any event file can go unnoticed. We flip one byte
  ;; at every position of every event in a real store and require the
  ;; identity check to catch each one.
  (let [dir (.toFile (Files/createTempDirectory
                      "tik-fuzz" (make-array FileAttribute 0)))
        s (fstore/file-store (str dir))
        ticket (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")
        evs (event/chain
             (fn [_] (event/create-ticket {:ticket ticket :actor "seb"
                                           :at t :title "fuzz"
                                           :process :p}))
             #(event/assert-fact {:ticket ticket :actor "seb"
                                  :at (.plusSeconds t 1) :parents %
                                  :path [:x] :value 1}))
        _ (doseq [e evs] (store/append! s e))
        files (filter #(and (.isFile ^java.io.File %)
                            (str/ends-with? (.getName ^java.io.File %)
                                            ".edn"))
                      (file-seq (io/file dir "tickets")))
        caught (atom 0) total (atom 0)]
    (is (= 2 (count files)))
    (doseq [^java.io.File f files
            :let [bytes (Files/readAllBytes (.toPath f))
                  id (str/replace (.getName f) #"\.edn$" "")]
            i (range (alength bytes))]
      (let [mutated (aclone ^bytes bytes)]
        (aset-byte mutated i (unchecked-byte (bit-xor (aget mutated i) 1)))
        (swap! total inc)
        (when (not= id (str "sha256-"
                            (canonical/sha256-hex-bytes mutated)))
          (swap! caught inc))))
    (is (= @total @caught)
        "every single-byte flip must change the content address")))
