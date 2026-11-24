;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sigpolicy-test
  "signatures.edn (ADR 0036 points 4 and 7): which signatures an event
  needs, and which classical history a post-quantum witness anchors."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.sigpolicy :as policy])
  (:import (java.time Instant)))

(def ^:private hybrid "{:require #{:ssh-ed25519 :ml-dsa-65} :from #inst \"2026-12-01T00:00:00Z\"}")

(defn- at [s] (Instant/parse s))

(deftest a-well-formed-policy-reads
  (is (= {:require #{:ssh-ed25519 :ml-dsa-65} :from (at "2026-12-01T00:00:00Z")} (policy/parse hybrid)))
  (is (= (at "2026-12-01T00:00:00Z")
         (:from (policy/parse "{:require #{:ssh-ed25519} :from \"2026-12-01T00:00:00Z\"}"))))
  (is (nil? (policy/parse nil))))

(deftest anything-else-is-malformed-and-never-no-policy
  (doseq [[why text] {"not EDN" "{:require"
                      "not a map" "[:ssh-ed25519]"
                      "an unknown key" "{:require #{:ssh-ed25519} :from \"2026-12-01T00:00:00Z\" :also 1}"
                      "require not a set" "{:require [:ssh-ed25519] :from \"2026-12-01T00:00:00Z\"}"
                      "an unknown algorithm" "{:require #{:ssh-ed25519 :rsa} :from \"2026-12-01T00:00:00Z\"}"
                      "post-quantum alone" "{:require #{:ml-dsa-65} :from \"2026-12-01T00:00:00Z\"}"
                      "no from" "{:require #{:ssh-ed25519 :ml-dsa-65}}"
                      "an unreadable from" "{:require #{:ssh-ed25519} :from \"next week\"}"}]
    (testing why
      (let [p (policy/parse text)]
        (is (string? (policy/malformed p)))
        (is (= [false] (map :ok? (policy/judge p [] {}))))
        (is (= #{} (policy/required p (at "2027-01-01T00:00:00Z"))))))))

(deftest the-policy-takes-effect-at-from
  (let [p (policy/parse hybrid)]
    (is (= #{:ssh-ed25519} (policy/required p (at "2026-11-30T23:59:59.999Z"))))
    (is (= #{:ssh-ed25519 :ml-dsa-65} (policy/required p (at "2026-12-01T00:00:00Z"))))
    (is (= #{:ssh-ed25519 :ml-dsa-65} (policy/required p nil)) "an unplaceable instant meets the full set")
    (is (= #{} (policy/required nil (at "2027-01-01T00:00:00Z"))))))

(def ^:private events
  ;; old-1 <- old-2 <- new-1, and a backdated old-3 hanging off new-1
  [{:event/id "old-1" :event/actor "seb" :event/at (at "2026-11-01T00:00:00Z") :event/parents #{}}
   {:event/id "old-2" :event/actor "seb" :event/at (at "2026-11-02T00:00:00Z") :event/parents #{"old-1"}}
   {:event/id "new-1" :event/actor "seb" :event/at (at "2026-12-02T00:00:00Z") :event/parents #{"old-2"}}])

(def ^:private both #{:ssh-ed25519 :ml-dsa-65})

(deftest an-anchor-commits-to-its-whole-ancestry
  (is (= #{"old-1" "old-2"} (policy/anchored events ["old-2"])))
  (is (= #{"old-1" "old-2" "new-1"} (policy/anchored events ["new-1"])))
  (is (= #{} (policy/anchored events ["not-here"]))))

(deftest a-store-under-the-policy-passes-only-with-every-signature-and-the-anchor
  (let [p (policy/parse hybrid)
        verified {"old-1" #{:ssh-ed25519} "old-2" #{:ssh-ed25519} "new-1" both}
        ok? #(every? :ok? (policy/judge p %1 %2))]
    (is (ok? events {:verified verified :anchors ["old-2"]}))
    (testing "an event from :from on without its post-quantum signature"
      (is (not (ok? events {:verified (assoc verified "new-1" #{:ssh-ed25519}) :anchors ["old-2"]}))))
    (testing "an event from :from on without its classical signature"
      (is (not (ok? events {:verified (assoc verified "new-1" #{:ml-dsa-65}) :anchors ["old-2"]}))))
    (testing "an event before :from without its classical signature"
      (is (not (ok? events {:verified (dissoc verified "old-1") :anchors ["old-2"]}))))
    (testing "classical history nobody anchored"
      (is (not (ok? events {:verified verified :anchors []}))))
    (testing "an event written later with an earlier instant is outside every anchor"
      (let [backdated {:event/id "old-3" :event/actor "seb" :event/at (at "2026-11-03T00:00:00Z")
                       :event/parents #{"new-1"}}]
        (is (not (ok? (conj events backdated)
                      {:verified (assoc verified "old-3" #{:ssh-ed25519}) :anchors ["old-2"]})))))))

(deftest a-classical-only-policy-requires-the-classical-signature-and-no-anchor
  (let [p (policy/parse "{:require #{:ssh-ed25519} :from #inst \"2026-12-01T00:00:00Z\"}")]
    (is (every? :ok? (policy/judge p events {:verified {"old-1" #{:ssh-ed25519} "old-2" #{:ssh-ed25519} "new-1" #{:ssh-ed25519}}})))
    (is (not (every? :ok? (policy/judge p events {:verified {"new-1" #{}}}))))))

(deftest no-policy-judges-nothing
  (is (= [] (policy/judge nil events {}))))
