;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.delegation-verify-test
  "`tik verify` credits a verdict signed by a throwaway key to the
  reviewer who delegated to it, and only inside the delegation (ADR 0037):
  the delegation must be the reviewer's own rung-1 signature, touched
  where the store requires it, and the verdict inside its scope."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.canonical :as canonical]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.harness :as h]
            [tik.sign :as sign]
            [tik.sk-fixture :as sk]
            [tik.store.protocol :as store]
            [tik.store.file :as fstore])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- now ^Instant [] (.truncatedTo (Instant/now) ChronoUnit/MILLIS))

(defn- keygen! [root name]
  (let [k (io/file root name)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" name "-f" (str k))
    k))

(defn- bytes-of ^bytes [e] (.getBytes (canonical/emit (dissoc e :event/id)) "UTF-8"))

(defn- append-signed! [s e key]
  (store/append! s e)
  (when key
    (store/put-sidecar! s (:event/ticket e)
                        (str (:event/id e) ".sig." (sign/fingerprint (sign/pubkey key)))
                        (sign/sign-bytes key (bytes-of e)))))

(defn- fixture
  "A store with seb in `actors`, a registry ticket and a ticket to review.
  Returns the pieces a case needs to write its own delegation and verdict."
  []
  (let [root (h/temp-dir! "tik-delegation")
        seb (keygen! root "seb")
        throwaway (keygen! root "throwaway")
        env {"TIK_KEY" (str seb)}
        run (fn [& args] (:out (apply h/tik! {:root root :actor "seb" :env env} args)))]
    (doseq [p ["identity-registry" "support-request"]]
      (io/copy (io/file repo "processes" (str p ".edn"))
               (io/file (doto (io/file root "processes") (.mkdirs)) (str p ".edn"))))
    (run "actor" "add" "seb" (str seb ".pub"))
    (let [registry (java.util.UUID/fromString (str/trim (run "new" "identity-registry" "--title" "registry")))
          ticket (java.util.UUID/fromString (str/trim (run "new" "support-request" "--title" "review me")))
          s (fstore/file-store (str root))]
      {:root root :s s :seb seb :throwaway throwaway
       :registry registry :ticket ticket
       :registry-head (first (dag/heads (store/events s registry)))
       :head (first (dag/heads (store/events s ticket)))})))

(defn- delegate! [{:keys [s registry registry-head ticket head throwaway]} key
                  & {:keys [at not-after scope facts]}]
  (let [at (or at (now))
        e (event/add-attestation
           {:ticket registry :actor "seb" :at at :parents [registry-head]
            :claim {:claim :delegation
                    :delegation/public-key (sign/pubkey throwaway)
                    :delegation/not-after (or not-after (.plusSeconds ^Instant at 240))
                    :delegation/scope (or scope #{[ticket head]})
                    :delegation/facts (or facts #{[:review :verdict]})}})]
    (append-signed! s e key)
    e))

(defn- verdict! [{:keys [s ticket head throwaway]} & {:keys [at path actor]}]
  (append-signed! s (event/assert-fact {:ticket ticket :actor (or actor "seb")
                                        :at (or at (now)) :parents [head]
                                        :path (or path [:review :verdict]) :value :approved})
                  throwaway))

(defn- verify [{:keys [root ticket]}]
  (let [r (h/run-tik! {:root root :actor "seb"} "verify" (str ticket))]
    (str (:out r) (:err r))))

(deftest a-delegated-verdict-counts-as-the-reviewers
  (let [f (fixture)]
    (delegate! f (:seb f))
    (verdict! f)
    (let [out (verify f)]
      (is (str/includes? out "verify: PASS") out)
      (is (re-find #"signed by seb \(delegated by sha256-" out) out))))

(deftest a-delegated-signature-counts-for-nobody-outside-the-delegation
  (doseq [[why setup]
          {"no delegation at all"
           (fn [f] (verdict! f))
           "a delegation nobody signed"
           (fn [f] (delegate! f nil) (verdict! f))
           "a delegation signed by the throwaway key itself, a chain"
           (fn [f] (delegate! f (:throwaway f)) (verdict! f))
           "a fact the delegation does not allow"
           (fn [f] (delegate! f (:seb f)) (verdict! f :path [:summary]))
           "a verdict after the delegation expired"
           (fn [f] (let [d (delegate! f (:seb f))]
                     (verdict! f :at (.plusSeconds ^Instant (:event/at d) 241))))
           "a delegation valid for longer than five minutes"
           (fn [f] (let [t (now)]
                     (delegate! f (:seb f) :at t :not-after (.plusSeconds t 301))
                     (verdict! f :at t)))
           "a head the reviewer was not shown"
           (fn [f] (delegate! f (:seb f) :scope #{[(:ticket f) (:registry-head f)]})
             (verdict! f))}]
    (testing why
      (let [f (fixture)]
        (setup f)
        (is (str/includes? (verify f) "verify: FAIL"))))))

(deftest a-store-that-requires-a-touch-refuses-a-plain-key-delegation
  (let [f (fixture)]
    (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch true}}")
    (delegate! f (:seb f))
    (verdict! f)
    (let [out (verify f)]
      (is (str/includes? out "verify: FAIL") out)
      (is (str/includes? out "security key") out))))

(deftest a-malformed-delegation-policy-credits-nothing
  (let [f (fixture)]
    (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch")
    (delegate! f (:seb f))
    (verdict! f)
    (is (str/includes? (verify f) "verify: FAIL"))))

(defn- delegate-with-security-key! [f signer flags]
  (spit (io/file (:root f) "actors")
        (str (sign/allowed-signers-line "seb" (sk/public-line signer)) "\n")
        :append true)
  (let [e (delegate! f nil)]
    (store/put-sidecar! (:s f) (:registry f) (str (:event/id e) ".sig.sk")
                        (.getBytes ^String (sk/sign signer sign/namespace-event (bytes-of e)
                                                    :flags flags)
                                   "UTF-8"))
    e))

(deftest a-touched-security-key-delegation-satisfies-the-touch-requirement
  (let [f (fixture)]
    (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch true}}")
    (delegate-with-security-key! f (sk/signer) sk/flag-user-present)
    (verdict! f)
    (let [out (verify f)]
      (is (re-find #"signed by seb \(delegated by sha256-" out) out)
      (is (str/includes? out "verify: PASS") out)))
  (testing "the same key without a touch delegates nothing"
    (let [f (fixture)]
      (spit (io/file (:root f) "oidc.edn") "{:delegation {:require-touch true}}")
      (delegate-with-security-key! f (sk/signer) 0)
      (verdict! f)
      (is (str/includes? (verify f) "verify: FAIL")))))
