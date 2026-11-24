;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sigpolicy-store-test
  "signatures.edn in a store (ADR 0036 points 4 and 7): writes it would
  reject are refused, `tik anchor` seals classical history, and `tik
  verify`, `tik rederive` and the bundle's verify.sh agree on what the
  policy asks."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.pq-keys :as keys]
            [tik.sigpolicy :as sigpolicy])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def ^:private repo (System/getProperty "user.dir"))

(def ^:private future-policy
  "Every event written now is claimed before :from, so it needs the
  classical signature and an anchor."
  "{:require #{:ssh-ed25519 :ml-dsa-65} :from #inst \"2099-01-01T00:00:00Z\"}")

(def ^:private past-policy
  "Every event written now is claimed after :from, so it needs both
  signatures."
  "{:require #{:ssh-ed25519 :ml-dsa-65} :from #inst \"2000-01-01T00:00:00Z\"}")

(defn- store []
  (let [root (h/temp-dir! "tik-sigpolicy")
        key (io/file root "id_test")
        pq (io/file root "pq.pem")
        pq-key (keys/jdk-key)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "k" "-f" (str key))
    (spit pq (:pem pq-key))
    (io/copy (io/file repo "processes/support-request.edn")
             (io/file (doto (io/file root "processes") (.mkdirs)) "support-request.edn"))
    (h/tik! {:root root :actor "seb" :env {"TIK_KEY" (str key)}} "actor" "add" "seb" (str key ".pub"))
    (spit (io/file root "pq-signers")
          (str "seb namespaces=\"tik-*\" ml-dsa-65 " (:public-key-b64 pq-key) "\n"))
    {:root root :env {"TIK_KEY" (str key) "TIK_PQ_KEY" (str pq)}}))

(defn- tik [{:keys [root env]} & args]
  (let [r (apply h/run-tik! {:root root :actor "seb" :env env} args)]
    (assoc r :all (str (:out r) (:err r)))))

(defn- new-ticket [st]
  (str/trim (:out (tik st "new" "support-request" "--title" "policy"))))

(defn- policy! [{:keys [root]} text] (spit (io/file root "signatures.edn") text))

(defn- verify [st id] (:all (tik st "verify" id)))

(defn- bundle! [st id]
  (let [out (io/file (:root st) (str "ev-" (System/nanoTime) ".tgz"))
        dest (h/temp-dir! "tik-sigpolicy-dest")]
    (tik st "bundle" id "--out" (str out))
    (sh/sh "tar" "xzf" (str out) "-C" (str dest))
    dest))

(defn- verify-sh [dest] (:out (sh/sh "sh" "./verify.sh" :dir (str dest))))

(defn- rederive [st dest] (:all (tik st "rederive" (str dest))))

(defn- passes?
  "Whether all three verifiers pass the ticket. verify.sh is asked only
  where the host's OpenSSL can check ML-DSA-65, and must otherwise fail."
  [st id]
  (let [dest (bundle! st id)
        sh-out (verify-sh dest)
        rd (rederive st dest)
        v (verify st id)]
    {:verify (str/includes? v "verify: PASS")
     :rederive (and (str/includes? rd "the bytes are what they claim to be")
                    (not (re-find #"(?m)^\s*FAIL" rd)))
     :verify-sh (if keys/openssl-has-ml-dsa?
                  (str/includes? sh-out "bundle: PASS")
                  (str/includes? sh-out "bundle: FAIL"))
     :out (str v "\n---\n" rd "\n---\n" sh-out)}))

(defn- all-pass? [r] (and (:verify r) (:rederive r) (:verify-sh r)))

(defn- all-fail? [r]
  (and (not (:verify r)) (not (:rederive r))
       (if keys/openssl-has-ml-dsa? (not (:verify-sh r)) true)))

(deftest anchored-history-passes-and-a-later-event-claimed-before-from-does-not
  (let [st (store)
        id (new-ticket st)]
    (tik st "set" id "summary=\"before the policy\"")
    (policy! st future-policy)
    (testing "classical history nobody anchored fails everywhere"
      (let [r (passes? st id)]
        (is (all-fail? r) (:out r))
        (is (str/includes? (:out r) "no post-quantum witness anchors") (:out r))))
    (let [anchored (tik st "anchor")]
      (is (zero? (:exit anchored)) (:all anchored))
      (is (str/includes? (:all anchored) "anchored 1 head(s)") (:all anchored)))
    (testing "once anchored, every verifier passes"
      (let [r (passes? st id)]
        (is (all-pass? r) (:out r))
        (is (str/includes? (:out r) "every event meets signatures.edn") (:out r))))
    (testing "an event written after the anchor and claimed before :from is outside it"
      (tik st "set" id "summary=\"written later, claimed earlier\"")
      (let [r (passes? st id)]
        (is (all-fail? r) (:out r))))
    (testing "anchoring again seals it"
      (tik st "anchor")
      (is (all-pass? (passes? st id))))))

(deftest from-from-on-every-event-carries-both-signatures
  (let [st (store)
        _ (policy! st past-policy)
        id (new-ticket st)]
    (tik st "set" id "summary=\"hybrid\"")
    (is (all-pass? (passes? st id)))
    (testing "an event whose post-quantum signature is gone fails everywhere"
      (doseq [^java.io.File f (.listFiles (h/events-dir (:root st) id))
              :when (str/includes? (.getName f) ".pqsig.")]
        (.delete f))
      (let [r (passes? st id)]
        (is (all-fail? r) (:out r))
        (is (str/includes? (:out r) "ml-dsa-65") (:out r))))))

(deftest a-write-the-policy-would-reject-stores-nothing
  (doseq [[why env-change policy]
          [["no ML-DSA key from :from on" #(dissoc % "TIK_PQ_KEY") past-policy]
           ["no classical key" #(dissoc % "TIK_KEY") future-policy]
           ["a malformed policy" identity "{:require #{:ml-dsa-65} :from #inst \"2000-01-01T00:00:00Z\"}"]]]
    (testing why
      (let [st (store)
            _ (policy! st policy)
            r (tik (update st :env env-change) "new" "support-request" "--title" "refused")]
        (is (not (zero? (:exit r))) (:all r))
        (is (str/includes? (:err r) "signatures.edn") (:err r))
        (is (h/clean-output? (:all r)))
        (is (not (.exists (io/file (:root st) "tickets"))))))))

(deftest a-malformed-policy-fails-verification-rather-than-meaning-none
  (let [st (store)
        id (new-ticket st)]
    (policy! st "{:require #{:ssh-ed25519} :from \"whenever\"}")
    (let [r (passes? st id)]
      (is (all-fail? r) (:out r)))))

(deftest anchor-needs-a-key-allowed-to-witness
  (let [st (store)
        _ (new-ticket st)]
    (spit (io/file (:root st) "pq-signers")
          (str/replace (slurp (io/file (:root st) "pq-signers")) "tik-*" "tik-event"))
    (let [r (tik st "anchor")]
      (is (not (zero? (:exit r))))
      (is (str/includes? (:err r) "witness") (:err r))
      (is (h/clean-output? (:all r))))))

(deftest a-key-allowed-every-namespace-signs-and-witnesses-in-every-verifier
  (let [st (store)
        reg (io/file (:root st) "pq-signers")
        _ (spit reg (str/replace (slurp reg) "tik-*" "*"))
        id (new-ticket st)]
    (tik st "set" id "summary=\"signed and anchored by a * key\"")
    (policy! st future-policy)
    (is (zero? (:exit (tik st "anchor"))))
    (let [r (passes? st id)]
      (is (all-pass? r) (:out r)))))

(defn- shell-epoch-ms
  "What the bundle's verify.sh reads `text` as, in epoch milliseconds, or
  nil when it does not read it."
  [text]
  (let [sh-text @(requiring-resolve 'tik.audit/bundle-verify-sh)
        start (str/index-of sh-text "epoch_ms() {")
        end (str/index-of sh-text "\n}\n" start)
        out (str/trim (:out (sh/sh "sh" "-c" (str (subs sh-text start (+ end 2)) "\nepoch_ms \"$1\"")
                                   "sh" text)))]
    (when-not (str/blank? out) (parse-long out))))

(defn- policy-epoch-ms [text]
  (some-> (sigpolicy/parse (str "{:require #{:ssh-ed25519} :from #inst \"" text "\"}"))
          :from
          (.toEpochMilli)))

(deftest verify-sh-reads-from-as-tik-does
  (doseq [text ["2026-12-01T00:00:00Z"
                "2026-12-01T00:00:00.5Z"
                "2026-12-01T00:00:00.123Z"
                "2026-12-01T00:00:00+01:00"
                "2026-12-01T00:00:00.000-00:00"
                "2000-02-29T12:30:45.007+05:45"
                "2026-12-01T00:00:00-05:30"
                "2026-12-01T00:00:00.250+18:00"
                "2024-02-29T23:59:59Z"
                "2026-12-01T00:00:00+19:00"
                "2026-12-01T00:00:00+01:60"
                "2026-02-29T00:00:00Z"
                "2026-04-31T00:00:00Z"
                "2026-13-01T00:00:00Z"
                "2026-12-01T24:00:00Z"
                "2026-12-01T23:59:60Z"
                "2026-12-01T00:00Z"
                "2026-12-01"
                "2026-12-01t00:00:00z"
                "2026-12-01T00:00:00.1234Z"
                "2026-12-01T00:00:00+01:00:30"
                "1969-12-31T23:59:59Z"]]
    (testing text
      (is (= (policy-epoch-ms text) (shell-epoch-ms text))))))

(deftest a-from-with-an-offset-means-the-same-instant-everywhere
  (let [st (store)
        from (.minus (Instant/now) 30 ChronoUnit/MINUTES)
        ;; the same instant written as wall-clock time one hour east of UTC
        text (.format (.withOffsetSameInstant (.atOffset from ZoneOffset/UTC) (ZoneOffset/ofHours 1))
                      (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssxxx"))
        _ (policy! st (str "{:require #{:ssh-ed25519 :ml-dsa-65} :from #inst \"" text "\"}"))
        id (new-ticket st)]
    (tik st "set" id "summary=\"hybrid, after an offset :from\"")
    (let [r (passes? st id)]
      (is (all-pass? r) (:out r)))))
