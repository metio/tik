;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.rederive-test
  "The evidence-bundle format and re-derivation from one: that a bundle
  declares its version, that re-deriving it reaches the same stages the
  producing store did, that tampering is caught, and that everything
  reached through an untrusted archive — the paths inside it, the
  definition it pins, the URL a service is asked to fetch — is refused
  rather than trusted."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.badge :as badge]
            [tik.bundle :as bundle]
            [tik.canonical :as canonical]
            [tik.harness :as h]
            [tik.rederive :as rederive])
  (:import (java.io File)
           (java.nio.file Files)
           (java.time Instant)
           (java.util.zip GZIPOutputStream)))

(def ^:private repo (System/getProperty "user.dir"))

(defn- signed-store!
  "A store with one signed ticket on `process`, and the facts in `sets`."
  [process & sets]
  (let [root (h/temp-dir! "tik-rederive")
        key (io/file root "id_test")
        _ (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" "k" "-f" (str key))
        env {"TIK_KEY" (str key)}
        run (fn [& args] (apply h/tik! {:root root :actor "seb" :env env} args))
        _ (io/copy (io/file repo "processes" (str (name process) ".edn"))
                   (io/file (doto (io/file root "processes") (.mkdirs))
                            (str (name process) ".edn")))
        _ (run "actor" "add" "seb" (str key ".pub"))
        id (str/trim (:out (run "new" (name process) "--title" "bundled")))]
    (when (seq sets) (apply run "set" id sets))
    {:root root :id id :run run}))

(defn- bundle! [{:keys [root id run]}]
  (let [out (io/file root "evidence.tgz")]
    (run "bundle" id "--out" (str out))
    out))

(deftest a_bundle_declares_the_format_it_follows
  (let [store (signed-store! :support-request "category=:billing" "severity=:low")
        tgz (bundle! store)
        dest (h/temp-dir! "tik-rederive-dest")]
    (bundle/untar-gz! tgz dest)
    (testing "the manifest names the format and version, and nothing derivable"
      (let [m (canonical/parse (slurp (io/file dest bundle/manifest-name)))]
        (is (= bundle/format-name (:bundle/format m)))
        (is (= bundle/format-version (:bundle/version m)))
        (is (= #{:bundle/format :bundle/version} (set (keys m)))
            "a manifest that declared the ticket, head or stage would be
             asking a reader to believe the producer over the files")))
    (testing "and a bundle without one reads as the version-1 baseline"
      (.delete (io/file dest bundle/manifest-name))
      (is (= {:format bundle/format-name :version 1 :declared? false}
             (bundle/manifest dest))))))

(deftest re_deriving_a_bundle_reaches_what_the_store_reached
  (let [store (signed-store! :support-request "category=:billing" "severity=:low")
        tgz (bundle! store)
        r (bundle/read-bundle tgz)]
    (is (:verified? r) (pr-str (remove :ok? (:checks r))))
    (is (= (str (:id store)) (str (:ticket r))))
    (is (contains? (:reached r) ":triaged")
        (str "the store derives :triaged from these facts, so a bundle of it"
             " must too — " (pr-str (:reached r))))
    (testing "the definition that judged it travels and is named"
      (is (= :support-request (:process r)))
      (is (str/starts-with? (:process-hash r) "sha256-")))
    (testing "every reached stage reports the guards that granted it"
      (let [triaged (first (filter #(= :triaged (:stage %)) (:stages r)))]
        (is (:holds-now? triaged))
        (is (every? :satisfied? (:guards triaged)))))
    (testing "and the badge names the derivation, never a grade"
      (let [svg (badge/svg r)]
        (is (str/includes? svg ":triaged"))
        (is (str/includes? svg "support-request@"))
        (is (not (re-find #"(?i)compliant|passing|approved" svg)))))))

(deftest a_derivation_is_a_function_of_the_instant_it_is_asked_for
  ;; the property that makes re-deriving worth more than recording: a
  ;; freshness window is satisfied by evidence that is fresh and
  ;; unsatisfied by the same bytes later
  (let [store (signed-store! :support-request)
        tgz (bundle! store)
        now (Instant/now)
        soon (bundle/read-bundle tgz now)
        later (bundle/read-bundle tgz (.plusSeconds now (* 400 24 3600)))]
    (is (= (:digest soon) (:digest later)) "same bytes")
    (is (not (contains? (:reached soon) ":escalated")))
    (is (contains? (:reached later) ":escalated")
        (str "an untriaged request escalates once 48 hours pass, so the same"
             " evidence derives differently a year on — "
             (pr-str (:reached later))))))

(deftest tampering_is_caught_and_nothing_derived_from_it_is_offered
  (let [store (signed-store! :support-request "category=:billing" "severity=:low")
        tgz (bundle! store)
        dest (h/temp-dir! "tik-rederive-tamper")]
    (bundle/untar-gz! tgz dest)
    (let [^File f (->> (file-seq (io/file dest "tickets"))
                       (filter #(str/ends-with? (str %) ".edn"))
                       sort first)
          bytes (Files/readAllBytes (.toPath f))]
      (aset-byte bytes 10 (unchecked-byte (bit-xor (aget ^bytes bytes 10) 1)))
      (Files/write (.toPath f) ^bytes bytes
                   ^"[Ljava.nio.file.OpenOption;"
                   (make-array java.nio.file.OpenOption 0)))
    (let [r (bundle/read-bundle dest)]
      (is (not (:verified? r)))
      (is (some #(re-find #"does not hash to its name" (:msg %))
                (remove :ok? (:checks r))))
      (is (= "unverified" (badge/headline r))
          "the badge says so rather than showing a stage")
      (is (str/includes? (badge/page r {}) "does not verify")))))

;; ------------------------------------------------- the archive is hostile

(defn- tar-entry
  "One 512-byte ustar header plus padded body — so a test can write an
  archive GNU tar would refuse to create."
  ^bytes [name typeflag ^bytes body]
  (let [h (byte-array 512)
        put (fn [^String s off]
              (dotimes [i (count s)]
                (aset-byte h (+ off i) (byte (int (.charAt s i))))))]
    (put name 0)
    (put "0000644" 100) (put "0000000" 108) (put "0000000" 116)
    (put (format "%011o" (alength body)) 124)
    (put "00000000000" 136)
    (put (str typeflag) 156)
    (put "ustar  " 257)
    ;; the checksum field is spaces while it is computed, and nothing
    ;; here reads it back — the reader under test judges paths, not sums
    (dotimes [i 8] (aset-byte h (+ 148 i) (byte 32)))
    (let [pad (byte-array (- (* 512 (quot (+ (alength body) 511) 512))
                             (alength body)))]
      (byte-array (concat (seq h) (seq body) (seq pad))))))

(defn- tgz-of ^File [& entries]
  (let [f (File/createTempFile "tik-hostile" ".tgz")]
    (with-open [o (GZIPOutputStream. (io/output-stream f))]
      (doseq [^bytes e entries] (.write o e))
      (.write o (byte-array 1024)))
    f))

(deftest an_archive_that_writes_outside_the_directory_is_refused
  (let [dest (h/temp-dir! "tik-untar")]
    (doseq [[what name] [["a traversing path" "../escaped.txt"]
                         ["a deeper traversal" "tickets/../../escaped.txt"]
                         ["an absolute path" "/tmp/escaped.txt"]]]
      (testing what
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"refusing"
             (bundle/untar-gz! (tgz-of (tar-entry name \0 (.getBytes "x")))
                               dest)))))
    (testing "a symlink is refused rather than silently skipped"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"files and directories only"
           (bundle/untar-gz! (tgz-of (tar-entry "link" \2 (byte-array 0)))
                             dest))))
    (testing "an honest path unpacks"
      (bundle/untar-gz! (tgz-of (tar-entry "./a/b.txt" \0 (.getBytes "hi")))
                        dest)
      (is (= "hi" (slurp (io/file dest "a" "b.txt")))))))

(deftest an_archive_that_unpacks_without_bound_is_refused
  (let [dest (h/temp-dir! "tik-untar-bomb")
        big (byte-array 4096)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"larger than"
         (bundle/untar-gz! (tgz-of (tar-entry "big" \0 big)) dest
                           {:max-entry-bytes 100})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"more than"
         (bundle/untar-gz! (tgz-of (tar-entry "a" \0 big)
                                   (tar-entry "b" \0 big))
                           dest {:max-bytes 5000})))))

(deftest a_definition_that_is_not_data_is_refused_before_it_judges_anything
  ;; the reason re-derivation lints: the pinned definition decides what
  ;; these facts mean, and it arrived from whoever built the bundle
  (let [dir (h/temp-dir! "tik-hostile-def")
        proc {:process/id :p :process/version 1 :lint {:runbooks :off}
              :process/facts {}
              :process/stages
              [{:stage/id :s
                :guards [[:malli [:fn "(fn [_] true)"]]]}]}
        bytes (canonical/emit proc)
        hash (str "sha256-" (canonical/sha256-hex bytes))
        by-hash (doto (io/file dir "processes" "by-hash") .mkdirs)]
    (spit (io/file by-hash (str hash ".edn")) bytes)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not lint"
         (bundle/definition {:definitions-dir by-hash} {:process-hash hash})))
    (testing "and so is one that does not hash to what the ticket pinned"
      (spit (io/file by-hash (str hash ".edn")) (canonical/emit (assoc proc :x 1)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"does not hash to"
           (bundle/definition {:definitions-dir by-hash}
             {:process-hash hash}))))))

;; -------------------------------------------------------------- the service

(deftest the_service_fetches_only_public_https
  (doseq [[what url pattern] [["a private host" "https://localhost/b.tgz"
                               #"private network"]
                              ["an address literal" "https://127.0.0.1/b.tgz"
                               #"private network"]
                              ["plain http" "http://example.com/b.tgz"
                               #"only https"]
                              ["a file URL" "file:///etc/passwd" #"only https"]]]
    (testing what
      (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern
                            (rederive/fetch! url))))))

(deftest the_cache_key_carries_the_minute_as_well_as_the_content
  ;; content alone would be wrong: a guard with a freshness window makes
  ;; a derivation a function of the instant too, so memoizing on bytes
  ;; would serve last week's answer forever
  (let [calls (atom 0)
        f (fn [] (swap! calls inc))
        t (Instant/parse "2026-08-06T12:00:00Z")]
    (rederive/cached "sha256-a" t f)
    (rederive/cached "sha256-a" (.plusSeconds t 30) f)
    (is (= 1 @calls) "the same minute reuses the answer")
    (rederive/cached "sha256-a" (.plusSeconds t 90) f)
    (is (= 2 @calls) "a later minute derives again")
    (rederive/cached "sha256-b" t f)
    (is (= 3 @calls) "different bytes derive again")
    (testing "a directory has no content address, so it is never cached"
      (rederive/cached nil t f)
      (rederive/cached nil t f)
      (is (= 5 @calls)))))

(deftest the_service_answers_hostile_requests_instead_of_dying
  (testing "an unknown path"
    (is (= 404 (:status (rederive/handler {:request-method :get :uri "/nope"})))))
  (testing "a badge request with no bundle"
    (is (= 400 (:status (rederive/handler {:request-method :get
                                           :uri "/badge.svg"})))))
  (testing "a badge request naming a private host"
    (let [r (rederive/handler {:request-method :get :uri "/badge.svg"
                               :query-string "bundle=https%3A%2F%2Flocalhost%2Fb"})]
      (is (= 400 (:status r)))
      (is (str/includes? (:body r) "private network"))))
  (testing "the index says the service is not needed"
    (let [r (rederive/handler {:request-method :get :uri "/"})]
      (is (= 200 (:status r)))
      (is (str/includes? (:body r) "You do not need this service")))))

(deftest a_posted_bundle_is_re_derived_in_full
  (let [store (signed-store! :support-request "category=:billing" "severity=:low")
        tgz (bundle! store)
        r (rederive/handler {:request-method :post :uri "/rederive"
                             :body (io/input-stream tgz)})
        parsed (canonical/parse (:body r))]
    (is (= 200 (:status r)))
    (is (:verified? parsed))
    (is (= (bundle/digest tgz) (:digest parsed)))))
