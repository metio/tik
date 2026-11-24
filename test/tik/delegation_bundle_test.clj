;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.delegation-bundle-test
  "A bundle carries the delegations that cover its events, and both of its
  verifiers credit a delegated verdict only inside them (ADR 0037 point
  5): `tik rederive` in process, and `verify.sh` with ssh-keygen and
  coreutils. A delegation must verify through the bundle's curated
  `actors` lines, never through a key a binding earned."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.delegation-fixture :as f]
            [tik.harness :as h]
            [tik.sk-fixture :as sk])
  (:import (java.time Instant)))

(defn- bundle! [{:keys [root ticket]}]
  (let [out (io/file root "ev.tgz")
        dest (h/temp-dir! "tik-delegation-bundle-dest")]
    (h/tik! {:root root :actor "seb"} "bundle" (str ticket) "--out" (str out))
    (sh/sh "tar" "xzf" (str out) "-C" (str dest))
    dest))

(defn- rederive-passes? [out]
  (and (str/includes? out "the bytes are what they claim to be")
       (not (re-find #"(?m)^\s*FAIL" out))))

(defn- rederive-fails? [out] (boolean (re-find #"(?m)^\s*FAIL" out)))

(defn- verify-sh [dest] (:out (sh/sh "sh" "./verify.sh" :dir (str dest))))

(defn- rederive [{:keys [root]} dest]
  (let [r (h/run-tik! {:root root :actor "seb"} "rederive" (str dest))]
    (str (:out r) (:err r))))

(deftest the-delegation-travels-and-both-verifiers-credit-the-verdict
  (let [fx (f/fixture)
        d (f/delegate! fx (:seb fx))
        _ (f/verdict! fx)
        dest (bundle! fx)]
    (is (.isFile (io/file dest "identity" (str (:event/id d) ".edn"))))
    (is (seq (filter #(str/includes? (.getName ^java.io.File %) ".sig.")
                     (.listFiles (io/file dest "identity")))))
    (testing "tik rederive"
      (let [out (rederive fx dest)]
        (is (re-find #"verifies as seb \(delegated by sha256-" out) out)
        (is (rederive-passes? out) out)))
    (testing "verify.sh"
      (let [out (verify-sh dest)]
        (is (re-find #"verifies as seb \(delegated by sha256-" out) out)
        (is (str/includes? out "bundle: PASS") out)))))

(defn- both-fail [fx dest]
  (testing "tik rederive" (is (rederive-fails? (rederive fx dest))))
  (testing "verify.sh" (is (str/includes? (verify-sh dest) "bundle: FAIL"))))

(deftest a-verdict-outside-the-delegation-fails-both-verifiers
  (doseq [[why setup]
          {"a fact the delegation does not allow"
           (fn [fx] (f/delegate! fx (:seb fx)) (f/verdict! fx :path [:summary]))
           "a verdict after the delegation expired"
           (fn [fx] (let [d (f/delegate! fx (:seb fx))]
                      (f/verdict! fx :at (.plusSeconds ^Instant (:event/at d) 241))))
           "a head the reviewer was not shown"
           (fn [fx] (f/delegate! fx (:seb fx) :scope #{[(:ticket fx) (:registry-head fx)]})
             (f/verdict! fx))
           "a delegation signed by the throwaway key itself"
           (fn [fx] (f/delegate! fx (:throwaway fx)) (f/verdict! fx))}]
    (testing why
      (let [fx (f/fixture)]
        (setup fx)
        (both-fail fx (bundle! fx))))))

(deftest a-delegation-whose-signature-was-removed-fails-both-verifiers
  (let [fx (f/fixture)
        _ (f/delegate! fx (:seb fx))
        _ (f/verdict! fx)
        dest (bundle! fx)]
    (doseq [^java.io.File s (.listFiles (io/file dest "identity"))
            :when (str/includes? (.getName s) ".sig.")]
      (.delete s))
    (both-fail fx dest)))

(deftest the-touch-requirement-travels
  (testing "a plain-key delegation from a store that requires a touch"
    (let [fx (f/fixture)]
      (spit (io/file (:root fx) "oidc.edn") "{:delegation {:require-touch true}}")
      (f/delegate! fx (:seb fx))
      (f/verdict! fx)
      (let [dest (bundle! fx)]
        (is (str/includes? (slurp (io/file dest "delegation.edn")) ":require-touch true"))
        (both-fail fx dest))))
  (testing "a touched security-key delegation passes both"
    (let [fx (f/fixture)]
      (spit (io/file (:root fx) "oidc.edn") "{:delegation {:require-touch true}}")
      (f/delegate-with-security-key! fx (sk/signer) sk/flag-user-present)
      (f/verdict! fx)
      (let [dest (bundle! fx)]
        (is (rederive-passes? (rederive fx dest)))
        (is (str/includes? (verify-sh dest) "bundle: PASS") (verify-sh dest))))))

(deftest an-ecdsa-security-key-delegation-agrees-across-verifiers
  (testing "without a touch, both refuse it"
    (let [fx (f/fixture)]
      (spit (io/file (:root fx) "oidc.edn") "{:delegation {:require-touch true}}")
      (f/delegate-with-security-key! fx (sk/ecdsa-signer) 0)
      (f/verdict! fx)
      (both-fail fx (bundle! fx))))
  (testing "with a touch, both credit it"
    (let [fx (f/fixture)]
      (spit (io/file (:root fx) "oidc.edn") "{:delegation {:require-touch true}}")
      (f/delegate-with-security-key! fx (sk/ecdsa-signer) sk/flag-user-present)
      (f/verdict! fx)
      (let [dest (bundle! fx)]
        (is (rederive-passes? (rederive fx dest)))
        (is (str/includes? (verify-sh dest) "bundle: PASS") (verify-sh dest))))))
