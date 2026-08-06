;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.identity-test
  "Rung 2 is a trust base, so the cases that matter are the ones where a
  binding must NOT count: an unverified token, a malformed claim, a
  predicate that blows up. Failing open in any of them widens who may
  sign as whom."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.identity :as identity])
  (:import (java.time Instant)))

(def ^:private tid #uuid "018f2f6e-7c1a-7000-8000-00000000010d")
(def ^:private t0 (Instant/parse "2026-07-01T10:00:00Z"))

(defn- at [n] (.plusSeconds ^Instant t0 (long n)))
(defn- add [events ctor] (conj events (ctor #{(:event/id (peek events))})))

(def ^:private base
  (event/chain
   (fn [_] (event/create-ticket {:ticket tid :actor "seb" :at t0
                                 :title "identity registry"
                                 :process :identity-registry}))))

(defn- bind! [events n {:keys [actor key subject token]}]
  (add events #(event/add-attestation
                {:ticket tid :actor (or actor "seb") :parents % :at (at n)
                 :claim (cond-> {:claim :identity
                                 :identity/issuer "https://idp.example"
                                 :identity/subject (or subject "sub-1")
                                 :identity/username "someone"
                                 :identity/actor actor
                                 :identity/public-key key}
                          token (assoc :identity/id-token token))})))

(def ^:private all (constantly true))

(deftest a-binding-is-read-off-the-registry-log
  (let [evs (bind! base 1 {:actor "ci" :key "ssh-ed25519 AAAA-ci" :token "jwt"})
        [b :as bs] (identity/bindings evs)]
    (is (= 1 (count bs)))
    (is (= "ci" (:actor b)))
    (is (= "ssh-ed25519 AAAA-ci" (:public-key b)))
    (is (= "https://idp.example" (:issuer b)))
    (is (some? (:event b)) "a trust decision must name the event it rests on")))

(deftest attestations-that-are-not-bindings-are-ignored
  (let [evs (add (bind! base 1 {:actor "ci" :key "k" :token "jwt"})
                 #(event/add-attestation
                   {:ticket tid :actor "seb" :parents % :at (at 2)
                    :claim {:claim :work :hours 3}}))]
    (is (= 1 (count (identity/bindings evs))))))

(deftest an-unverified-binding-does-not-grant-a-key
  (let [evs (-> base
                (bind! 1 {:actor "ci" :key "ssh-ed25519 AAAA-good" :token "good"})
                (bind! 2 {:actor "ci" :key "ssh-ed25519 AAAA-forged" :token "bad"}))
        trusted? #(= "good" (:id-token %))
        keys* (identity/signing-keys
               (identity/verified (identity/bindings evs) trusted?))]
    (is (= {"ci" #{"ssh-ed25519 AAAA-good"}} keys*)
        "appending a binding must not be enough to sign as that actor")))

(deftest a-binding-without-a-token-is-not-a-claim-at-all
  (testing "there is nothing for a verifier to check, so it cannot count"
    (let [evs (bind! base 1 {:actor "ci" :key "ssh-ed25519 AAAA-ci"})]
      (is (empty? (identity/verified (identity/bindings evs) all))))))

(deftest a-binding-missing-an-actor-or-key-is-refused
  (doseq [[label b] [["no actor" {:key "k" :token "jwt"}]
                     ["no key" {:actor "ci" :token "jwt"}]]]
    (testing label
      (is (empty? (identity/verified (identity/bindings (bind! base 1 b))
                                     all))))))

(deftest a-predicate-that-throws-refuses-rather-than-admits
  (let [evs (bind! base 1 {:actor "ci" :key "k" :token "jwt"})]
    (is (empty? (identity/verified (identity/bindings evs)
                                   (fn [_] (throw (ex-info "jwks unreachable" {})))))
        "a binding that cannot be checked has not been verified")))

(deftest rotation-accumulates-rather-than-replaces
  (let [evs (-> base
                (bind! 1 {:actor "ci" :key "ssh-ed25519 AAAA-old" :token "jwt"})
                (bind! 2 {:actor "ci" :key "ssh-ed25519 AAAA-new" :token "jwt"}))
        keys* (identity/signing-keys (identity/verified (identity/bindings evs) all))]
    (is (= {"ci" #{"ssh-ed25519 AAAA-old" "ssh-ed25519 AAAA-new"}} keys*)
        "an old key keeps verifying what it signed while it was current")))

(deftest distinct-actors-keep-distinct-keys
  (let [evs (-> base
                (bind! 1 {:actor "ci" :key "ssh-ed25519 AAAA-ci" :token "jwt"})
                (bind! 2 {:actor "seb" :key "ssh-ed25519 AAAA-seb" :token "jwt"}))
        keys* (identity/signing-keys (identity/verified (identity/bindings evs) all))]
    (is (= #{"ssh-ed25519 AAAA-ci"} (get keys* "ci")))
    (is (= #{"ssh-ed25519 AAAA-seb"} (get keys* "seb")))
    (is (nil? (get keys* "nobody"))
        "an actor nobody vouched for is absent, not empty")))
