;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.delegation-test
  "Which events a delegation covers (ADR 0037 points 1 and 2), judged on
  the events alone. Whether the delegation's own signature holds, and the
  touch requirement, are the audit's half and tested there."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.delegation :as d]
            [tik.event :as event])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-11-23T20:00:00Z"))
(defn- at [secs] (.plusSeconds ^Instant t0 secs))
(def ^:private ticket (random-uuid))
(def ^:private registry (random-uuid))
(def ^:private registry-head "sha256-3333333333333333333333333333333333333333333333333333333333333333")
(def ^:private head "sha256-1111111111111111111111111111111111111111111111111111111111111111")
(def ^:private key-line "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOrW0yE7m6Kd5h2Lq3Y6vQ0p7G5x3m1qZpP8nB9cXk2a throwaway")

(defn- delegation-event
  ([] (delegation-event {}))
  ([overrides]
   (event/add-attestation
    {:ticket registry :actor "seb" :at t0 :parents [registry-head]
     :claim (merge {:claim :delegation
                    :delegation/public-key key-line
                    :delegation/not-after (at 300)
                    :delegation/scope #{[ticket head]}
                    :delegation/facts #{[:review :verdict]}}
                   overrides)})))

(defn- verdict
  ([] (verdict {}))
  ([{:keys [actor secs parents path type tkt]
     :or {actor "seb" secs 60 parents [head] path [:review :verdict]
          type :assert tkt ticket}}]
   (case type
     :assert (event/assert-fact {:ticket tkt :actor actor :at (at secs) :parents parents
                                 :path path :value :approved})
     :retract (event/retract-fact {:ticket tkt :actor actor :at (at secs) :parents parents
                                   :path path}))))

(defn- one [ev] (first (d/delegations [ev])))

(deftest a-well-formed-delegation-is-read-with-its-parts
  (let [x (one (delegation-event))]
    (is (= "seb" (:actor x)))
    (is (= key-line (:public-key x)))
    (is (= #{[ticket head]} (:scope x)))
    (is (= #{[:review :verdict]} (:facts x)))
    (is (empty? (:problems x)))))

(deftest only-delegation-attestations-are-read
  (is (empty? (d/delegations [(verdict)])))
  (is (empty? (d/delegations [(event/add-attestation {:ticket registry :actor "seb" :at t0
                                                      :parents [registry-head] :claim {:claim :key-binding}})]))))

(deftest a-malformed-delegation-names-its-problem
  (doseq [[why overrides] {"valid for longer than five minutes" {:delegation/not-after (at 301)}
                           "expiring before it is made" {:delegation/not-after (at -1)}
                           "no expiry" {:delegation/not-after nil}
                           "not one OpenSSH key" {:delegation/public-key "ssh-ed25519 AAAA\nseb ssh-ed25519 BBBB"}
                           "an empty scope" {:delegation/scope #{}}
                           "a scope that is not ticket and head pairs" {:delegation/scope #{[ticket]}}
                           "a scope as a vector" {:delegation/scope [[ticket head]]}
                           "no fact paths" {:delegation/facts #{}}
                           "a fact path that is not keywords" {:delegation/facts #{["review" "verdict"]}}}]
    (testing why
      (let [x (one (delegation-event overrides))]
        (is (seq (:problems x)))
        (is (not (d/covers? x (verdict))))))))

(deftest a-delegation-covers-exactly-the-verdicts-it-scopes
  (let [x (one (delegation-event))]
    (is (true? (d/covers? x (verdict))))
    (testing "at its first and last instant"
      (is (true? (d/covers? x (verdict {:secs 0}))))
      (is (true? (d/covers? x (verdict {:secs 300})))))
    (doseq [[why ev] {"another actor" (verdict {:actor "eve"})
                      "before the delegation" (verdict {:secs -1})
                      "after it expired" (verdict {:secs 301})
                      "a head the reviewer was not shown" (verdict {:parents ["sha256-2222222222222222222222222222222222222222222222222222222222222222"]})
                      "another ticket" (verdict {:tkt (random-uuid)})
                      "a fact the delegation does not allow" (verdict {:path [:merge :commit]})
                      "a retraction, not an assertion" (verdict {:type :retract})}]
      (testing why (is (false? (d/covers? x ev)))))))

(deftest hostile-input-is-refused-without-throwing
  (is (= [] (d/delegations nil)))
  (is (= [] (d/delegations [nil 1 "x" {}])))
  (is (false? (d/covers? nil nil)))
  (is (false? (d/covers? {:scope "x" :facts 1} {:event/type :fact/assert})))
  (testing "a delegation map with no problems but no instants"
    (is (false? (d/covers? {:problems []}
                           {:event/type :fact/assert :event/at (at 0)})))))
