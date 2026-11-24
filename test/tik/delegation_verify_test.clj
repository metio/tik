;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.delegation-verify-test
  "`tik verify` credits a verdict signed by a throwaway key to the
  reviewer who delegated to it, and only inside the delegation (ADR 0037):
  the delegation must be the reviewer's own rung-1 signature, touched
  where the store requires it, and the verdict inside its scope."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.delegation-fixture :as f]
            [tik.harness :as h]
            [tik.sk-fixture :as sk])
  (:import (java.time Instant)))

(defn- verify [{:keys [root ticket]}]
  (let [r (h/run-tik! {:root root :actor "seb"} "verify" (str ticket))]
    (str (:out r) (:err r))))

(deftest a-delegated-verdict-counts-as-the-reviewers
  (let [f (f/fixture)]
    (f/delegate! f (:seb f))
    (f/verdict! f)
    (let [out (verify f)]
      (is (str/includes? out "verify: PASS") out)
      (is (re-find #"signed by seb \(delegated by sha256-" out) out))))

(deftest a-reviewer-key-the-in-process-reader-lacks-still-delegates
  (let [f (f/fixture {:key-type "ecdsa"})]
    (f/delegate! f (:seb f))
    (f/verdict! f)
    (let [out (verify f)]
      (is (str/includes? out "verify: PASS") out)
      (is (re-find #"signed by seb \(delegated by sha256-" out) out))))

(deftest a-throwaway-key-the-in-process-reader-lacks-still-signs
  (let [f (f/fixture)
        f (assoc f :throwaway (f/keygen! (:root f) "ecdsa-throwaway" "ecdsa"))]
    (f/delegate! f (:seb f))
    (f/verdict! f)
    (let [out (verify f)]
      (is (str/includes? out "verify: PASS") out)
      (is (re-find #"signed by seb \(delegated by sha256-" out) out))))

(deftest a-delegated-signature-counts-for-nobody-outside-the-delegation
  (doseq [[why setup]
          {"no delegation at all"
           (fn [f] (f/verdict! f))
           "a delegation nobody signed"
           (fn [f] (f/delegate! f nil) (f/verdict! f))
           "a delegation signed by the throwaway key itself, a chain"
           (fn [f] (f/delegate! f (:throwaway f)) (f/verdict! f))
           "a fact the delegation does not allow"
           (fn [f] (f/delegate! f (:seb f)) (f/verdict! f :path [:summary]))
           "a verdict after the delegation expired"
           (fn [f] (let [d (f/delegate! f (:seb f))]
                     (f/verdict! f :at (.plusSeconds ^Instant (:event/at d) 241))))
           "a delegation valid for longer than five minutes"
           (fn [f] (let [t (f/now)]
                     (f/delegate! f (:seb f) :at t :not-after (.plusSeconds t 301))
                     (f/verdict! f :at t)))
           "a head the reviewer was not shown"
           (fn [f] (f/delegate! f (:seb f) :scope #{[(:ticket f) (:registry-head f)]})
             (f/verdict! f))}]
    (testing why
      (let [f (f/fixture)]
        (setup f)
        (is (str/includes? (verify f) "verify: FAIL"))))))

(deftest a-store-that-requires-a-touch-refuses-a-plain-key-delegation
  (let [f (f/fixture)]
    (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch true}}")
    (f/delegate! f (:seb f))
    (f/verdict! f)
    (let [out (verify f)]
      (is (str/includes? out "verify: FAIL") out)
      (is (str/includes? out "security key") out))))

(deftest a-malformed-delegation-policy-credits-nothing
  (let [f (f/fixture)]
    (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch")
    (f/delegate! f (:seb f))
    (f/verdict! f)
    (is (str/includes? (verify f) "verify: FAIL"))))

(deftest a-touched-security-key-delegation-satisfies-the-touch-requirement
  (let [f (f/fixture)]
    (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch true}}")
    (f/delegate-with-security-key! f (sk/signer) sk/flag-user-present)
    (f/verdict! f)
    (let [out (verify f)]
      (is (re-find #"signed by seb \(delegated by sha256-" out) out)
      (is (str/includes? out "verify: PASS") out)))
  (testing "the same key without a touch delegates nothing"
    (let [f (f/fixture)]
      (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch true}}")
      (f/delegate-with-security-key! f (sk/signer) 0)
      (f/verdict! f)
      (is (str/includes? (verify f) "verify: FAIL")))))

(deftest an-ecdsa-security-key-delegation-needs-the-touch-too
  (doseq [[flags expected] [[sk/flag-user-present "verify: PASS"] [0 "verify: FAIL"]]]
    (testing (str "flags " flags)
      (let [f (f/fixture)]
        (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch true}}")
        (f/delegate-with-security-key! f (sk/ecdsa-signer) flags)
        (f/verdict! f)
        (let [out (verify f)]
          (is (str/includes? out expected) out))))))
