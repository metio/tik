;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.api-test
  "tik.api reads, verifies and derives one ticket of a store in process,
  read-only, and agrees with `tik status --edn` and `tik verify` on every
  ticket of the corpus stores, a copy of the repository's own store and
  two built fixture stores, one file-backed and one SQLite-backed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.api :as api]
            [tik.canonical :as canonical]
            [tik.cli :as cli]
            [tik.delegation-fixture :as f]
            [tik.event :as event]
            [tik.harness :as h]
            [tik.sign :as sign]
            [tik.store.protocol :as store]
            [tik.store.sqlite :as sqlite]
            [tik.verify :as verify])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.security MessageDigest)
           (java.time Instant)))

(def ^:private repo (System/getProperty "user.dir"))

(def ^:private at "2031-02-03T04:05:06Z")

(defn- copy-tree!
  "Copy `src` (a file or directory) to `dest`, skipping symbolic links."
  [^File src ^File dest]
  (cond
    (Files/isSymbolicLink (.toPath src)) nil
    (.isDirectory src) (do (.mkdirs dest)
                           (doseq [^File c (.listFiles src)]
                             (copy-tree! c (io/file dest (.getName c)))))
    (.isFile src) (do (io/make-parents dest) (io/copy src dest))))

(defn- copy-store!
  "A temp copy of the store at `root`, so neither the CLI's cache nor any
  mistake can touch the original."
  [root]
  (let [dest (h/temp-dir! "tik-api-copy")]
    (doseq [n ["tickets" "processes" "actors" "roles.edn" "oidc.edn" "jwks"
               "signatures.edn" "pq-signers"]
            :let [src (io/file root n)]
            :when (.exists src)]
      (copy-tree! src (io/file dest n)))
    dest))

(defn- run [root & args]
  (h/with-cli-root root #(cli/run-argv (mapv str args))))

(defn- tik! [root & args]
  (let [r (apply run root args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "tik " (first args) " failed: " (:err r)) r)))
    (:out r)))

(defn- new-ticket! [root & args]
  (str/trim (first (str/split-lines (apply tik! root "new" args)))))

(defn- built-store!
  "A store with signed and unsigned facts, a retraction, a dispute, a
  title correction and a link, on `backend` (:file or :sqlite)."
  [backend]
  (let [root (h/temp-dir! "tik-api-store")
        seb (f/keygen! root "seb")
        signed ["--actor" "seb" "--key" (str seb)]]
    (io/make-parents (io/file root "processes" "x"))
    (io/copy (io/file repo "processes" "support-request.edn")
             (io/file root "processes" "support-request.edn"))
    (.mkdirs (io/file root "tickets"))
    (tik! root "actor" "add" "seb" (str seb ".pub"))
    (let [a (apply new-ticket! root "support-request" "--title" "first" signed)
          b (apply new-ticket! root "track" "--title" "second" signed)]
      (apply tik! root "set" a "severity=high" signed)
      (apply tik! root "set" a "category=billing" signed)
      (apply tik! root "set" a "summary=\"two words\"" signed)
      (apply tik! root "retract" a "summary" signed)
      (tik! root "set" b "note=unsigned" "--actor" "eve")
      (apply tik! root "dispute" b "note" "not what was said" signed)
      (apply tik! root "set" b "title=\"second, renamed\"" signed)
      (apply tik! root "set" b (str "link.depends-on=" a) signed)
      (when (= :sqlite backend)
        (tik! root "store" "migrate" "--to" "sqlite"))
      {:root root :seb seb :ids [a b]})))

(defn- read-edn [s]
  (edn/read-string {:default (fn [_ v] v)} s))

(def ^:private compared [:ticket :title :rules :current :reached :facts :missing-parents])

(defn- status-of [root id]
  (select-keys (read-edn (tik! root "status" id "--edn" "--at" at)) compared))

(defn- derived-of [store id opts]
  (read-edn (pr-str (select-keys (api/derive store id (merge {:at (Instant/parse at)} opts))
                                 compared))))

(defn- api-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- fixture-stores []
  (concat
   (for [^File d (.listFiles (io/file repo "corpus"))
         :when (.isDirectory (io/file d "tickets"))]
     [(str "corpus " (.getName d)) (copy-store! d)])
   [["the repository's own store" (copy-store! (io/file repo))]
    ["built file store" (:root (built-store! :file))]
    ["built sqlite store" (:root (built-store! :sqlite))]]))

;; ------------------------------------------------------------ equivalence

(deftest derive-agrees-with-tik-status-on-every-fixture-ticket
  (doseq [[label root] (fixture-stores)]
    (testing label
      (let [store (api/open-store root)
            ids (store/ticket-ids (:tik.api/store store))]
        (is (seq ids) "the population is not empty")
        (doseq [id ids
                :let [expected (status-of root id)
                      strict (api-type #(api/derive store id {:at (Instant/parse at)}))]]
          (if (= :tik.api/definition-mismatch strict)
            (is (= expected (derived-of store id {:allow-unpinned-definition true}))
                (str id " (definition does not hash to the pin)"))
            (do (is (nil? strict) (str id " derives strictly"))
                (is (= expected (derived-of store id {})) (str id)))))))))

(deftest the-equivalence-comparison-can-fail
  (let [{:keys [root ids]} (built-store! :file)
        [a b] ids
        store (api/open-store root)
        expected (status-of root a)]
    (is (= expected (derived-of store a {})) "positive control")
    (is (not= expected (derived-of store b {})) "another ticket")
    (tik! root "set" a "severity=low" "--actor" "eve")
    (is (not= expected (derived-of store a {})) "the same ticket after one more fact")))

(defn- cli-ladder-lines
  "The lines `tik verify <id>` prints before its verdict."
  [root id]
  (->> (str/split-lines (:out (run root "verify" id)))
       (take-while #(not (str/starts-with? % "verify: ")))
       vec))

(deftest verify-ladder-agrees-with-tik-verify-line-for-line
  (doseq [[label root] [["the repository's own store" (copy-store! (io/file repo))]
                        ["built file store" (:root (built-store! :file))]
                        ["built sqlite store" (:root (built-store! :sqlite))]]]
    (testing label
      (let [store (api/open-store root)
            ids (store/ticket-ids (:tik.api/store store))]
        (is (seq ids))
        (doseq [id ids
                :let [{:keys [items]} (api/verify-ticket store id)
                      ladder (take-while #(not (contains? % :definition)) items)]]
          (is (= (cli-ladder-lines root id) (mapv verify/line ladder)) (str id)))))))

(deftest a-signed-fixture-verifies-and-names-each-event
  (doseq [backend [:file :sqlite]]
    (testing (name backend)
      (let [{:keys [root ids]} (built-store! backend)
            store (api/open-store root)
            [a b] ids
            ra (api/verify-ticket store a)
            rb (api/verify-ticket store b)]
        (is (:ok? ra))
        (is (zero? (:failures ra)))
        (is (every? #(= :verified (:signature %)) (:events ra)))
        (is (every? #(= :ok (:integrity %)) (:events ra)))
        (is (:ok? rb) "an unsigned event is unclaimed, not failed")
        (is (= #{:verified :unsigned} (set (map :signature (:events rb)))))
        (is (= 1 (count (filter #(= :unsigned (:signature %)) (:events rb)))))))))

;; ------------------------------------------------------------ verification failures

(defn- unsigned-event [{:keys [ticket head]} & {:keys [actor path value]}]
  (event/assert-fact {:ticket ticket :actor (or actor "seb") :at (f/now)
                      :parents [head] :path (or path [:summary])
                      :value (or value "a summary")}))

(defn- verdict-of [fx event-id]
  (let [r (api/verify-ticket (api/open-store (:root fx)) (:ticket fx))]
    [r (first (filter #(= event-id (:event %)) (:events r)))]))

(deftest the-positive-control-of-the-failure-cases-verifies
  (let [fx (f/fixture)
        e (unsigned-event fx)]
    (f/append-signed! (:s fx) e (:seb fx))
    (let [[r v] (verdict-of fx (:event/id e))]
      (is (:ok? r) (pr-str (verify/failures (:items r))))
      (is (= :verified (:signature v))))))

(deftest a-tampered-event-fails-integrity
  (let [fx (f/fixture)
        e (unsigned-event fx)]
    (f/append-signed! (:s fx) e (:seb fx))
    (let [file (io/file (h/events-dir (:root fx) (:ticket fx)) (str (:event/id e) ".edn"))]
      (spit file (str/replace (slurp file) "a summary" "b summary")))
    (let [[r v] (verdict-of fx (:event/id e))]
      (is (not (:ok? r)))
      (is (= :failed (:integrity v)))
      (is (= :failed (:signature v)) "the signature endorses the original bytes"))))

(deftest a-tampered-sqlite-row-fails-integrity
  (let [{:keys [root ids]} (built-store! :sqlite)
        db (str (io/file root "tik.db"))
        [a] ids
        store (api/open-store root)
        victim (:event/id (last (api/ticket store a)))
        exec! (requiring-resolve 'tik.store.sqlite-jdbc/exec!)
        query-blob (requiring-resolve 'tik.store.sqlite-jdbc/query-blob)
        original (String. ^bytes (query-blob db "SELECT bytes FROM events WHERE id=?" victim) "UTF-8")]
    (exec! db "UPDATE events SET bytes=? WHERE id=?"
           (.getBytes (str/replace original "seb" "bob") "UTF-8") victim)
    (let [r (api/verify-ticket store a)
          v (first (filter #(= victim (:event %)) (:events r)))]
      (is (not (:ok? r)))
      (is (= :failed (:integrity v))))))

(deftest a-signature-by-an-unregistered-key-fails
  (let [fx (f/fixture)
        mallory (f/keygen! (:root fx) "mallory")
        e (unsigned-event fx)]
    (f/append-signed! (:s fx) e mallory)
    (let [[r v] (verdict-of fx (:event/id e))]
      (is (not (:ok? r)))
      (is (= :failed (:signature v))))))

(deftest a-signature-over-other-bytes-fails
  (let [fx (f/fixture)
        e (unsigned-event fx)
        other (unsigned-event fx :value "other bytes")]
    (store/append! (:s fx) e)
    (store/put-sidecar! (:s fx) (:ticket fx)
                        (str (:event/id e) ".sig." (sign/fingerprint (sign/pubkey (:seb fx))))
                        (sign/sign-bytes (:seb fx) (f/bytes-of other)))
    (let [[r v] (verdict-of fx (:event/id e))]
      (is (not (:ok? r)))
      (is (= :failed (:signature v))))))

(deftest an-event-claiming-an-actor-the-key-does-not-belong-to-fails
  (let [fx (f/fixture)
        e (unsigned-event fx :actor "eve")]
    (f/append-signed! (:s fx) e (:seb fx))
    (let [[r v] (verdict-of fx (:event/id e))]
      (is (not (:ok? r)))
      (is (= :failed (:signature v))))))

(deftest an-event-without-an-actor-verifies-as-nobody
  (let [fx (f/fixture)
        minted (unsigned-event fx)
        body (dissoc minted :event/id :event/actor)
        e (assoc body :event/id (canonical/content-address body))]
    (f/append-signed! (:s fx) e (:seb fx))
    (let [[r v] (verdict-of fx (:event/id e))]
      (is (not (:ok? r)))
      (is (= :failed (:signature v)))
      (is (= :failed (:integrity v)) "and L0 refuses its schema"))))

(deftest a-key-only-ssh-keygen-reads-is-unchecked-and-starts-no-process
  (let [fx (f/fixture {:key-type "ecdsa"})
        e (unsigned-event fx)]
    (f/append-signed! (:s fx) e (:seb fx))
    (testing "the CLI asks ssh-keygen and passes"
      (is (str/includes? (:out (h/run-tik! {:root (:root fx) :actor "seb"} "verify" (str (:ticket fx))))
                         "verify: PASS")))
    (let [spawned (atom [])
          real-sh sh/sh]
      (with-redefs [sh/sh (fn [& args] (swap! spawned conj args) (apply real-sh args))]
        (let [[r v] (verdict-of fx (:event/id e))]
          (is (= :unchecked (:signature v)))
          (is (not (:ok? r)) "an unchecked signature is not verified")))
      (is (empty? @spawned) "no subprocess"))))

;; ------------------------------------------------------------ ids

(deftest ticket-references-resolve-or-refuse
  (let [{:keys [root store]} (h/temp-store!)
        a #uuid "aaaaaaaa-1111-4111-8111-111111111111"
        b #uuid "aaaaaaaa-2222-4222-8222-222222222222"
        c #uuid "cccccccc-3333-4333-8333-333333333333"]
    (doseq [t [a b c]] (h/seed-ticket! store {:ticket t :at (f/now)}))
    (let [s (api/open-store root)]
      (testing "positive controls"
        (is (= c (api/resolve-id s c)))
        (is (= c (api/resolve-id s (str c))))
        (is (= c (api/resolve-id s (str/upper-case (str c)))))
        (is (= c (api/resolve-id s "cccccccc")))
        (is (= a (api/resolve-id s "aaaaaaaa-1"))))
      (is (= :tik.api/ambiguous-id (api-type #(api/resolve-id s "aaaaaaaa"))))
      (is (= [a b] (try (api/resolve-id s "aaaaaaaa")
                        (catch clojure.lang.ExceptionInfo e (:candidates (ex-data e))))))
      (is (= :tik.api/unknown-ticket (api-type #(api/resolve-id s "dddddddd"))))
      (is (= :tik.api/unknown-ticket
             (api-type #(api/derive s "dddddddd-3333-4333-8333-333333333333"))))
      (doseq [bad ["cccc" "" "cccccccc/../x" "zzzzzzzz" nil 42 :cccccccc]]
        (is (= :tik.api/invalid-id (api-type #(api/resolve-id s bad))) (pr-str bad))))))

;; ------------------------------------------------------------ read-only

(defn- snapshot
  "path -> [size sha256 mtime] of every entry below `root`, links not
  followed."
  [^File root]
  (let [base (.toPath root)]
    (into (sorted-map)
          (for [^File f (file-seq root)
                :let [p (.toPath f)]]
            [(str (.relativize base p))
             (if (Files/isRegularFile p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
               [(.length f)
                (let [d (MessageDigest/getInstance "SHA-256")]
                  (vec (.digest d (Files/readAllBytes p))))
                (.lastModified f)]
               [:dir (.lastModified f)])]))))

(defn- exercise! [root ids]
  (let [s (api/open-store root)]
    (doseq [id ids]
      (api/resolve-id s id)
      (api/ticket s id)
      (api/derive s id)
      (api/verify-ticket s id))))

(deftest no-call-writes-to-the-store
  (doseq [backend [:file :sqlite]]
    (testing (name backend)
      (let [{:keys [root ids]} (built-store! backend)
            before (snapshot root)]
        (exercise! root ids)
        (is (= before (snapshot root)))
        (testing "negative control: the snapshot sees a change"
          (spit (io/file root "probe-file") "x")
          (is (not= before (snapshot root))))))))

(deftest the-handle-refuses-writes
  (let [{:keys [root ids]} (built-store! :sqlite)
        s (api/open-store root)
        e (first (api/ticket s (first ids)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"read-only"
                          (store/append! (:tik.api/store s) e)))
    (testing "the SQLite connection itself is read-only"
      (let [exec! (requiring-resolve 'tik.store.sqlite-jdbc/exec!)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (exec! (sqlite/read-only-db (io/file root "tik.db"))
                            "DELETE FROM events")))
        (is (seq (api/ticket s (first ids))) "nothing was deleted")))))

;; ------------------------------------------------------------ bounds

(deftest reads-are-bounded
  (let [{:keys [root ids]} (built-store! :file)
        [a] ids]
    (is (seq (api/ticket (api/open-store root) a)) "positive control")
    (is (= :tik.api/too-large
           (api-type #(api/ticket (api/open-store root {:limits {:max-event-bytes 64}}) a))))
    (is (= :tik.api/too-large
           (api-type #(api/derive (api/open-store root {:limits {:max-events 1}}) a))))
    (is (= :tik.api/too-large
           (api-type #(api/verify-ticket (api/open-store root {:limits {:max-sidecar-bytes 16}}) a))))
    (is (= :tik.api/too-large
           (api-type #(api/verify-ticket (api/open-store root {:limits {:max-file-bytes 16}}) a)))
        "the actors file")
    (testing "a symbolic link in a ticket directory is refused"
      (Files/createSymbolicLink (.toPath (io/file (h/events-dir root a) "sha256-0.edn"))
                                (.toPath (io/file "/dev/zero"))
                                (make-array java.nio.file.attribute.FileAttribute 0))
      (is (= :tik.api/unreadable (api-type #(api/ticket (api/open-store root) a)))))))

;; ------------------------------------------------------------ definitions

(deftest a-definition-that-does-not-hash-to-the-pin-is-refused-by-default
  (let [{:keys [root ids]} (built-store! :file)
        [a] ids
        s (api/open-store root)]
    (is (nil? (api-type #(api/derive s a))) "positive control")
    (doseq [^File f (.listFiles (io/file root "processes" "by-hash"))] (.delete f))
    (spit (io/file root "processes" "support-request.edn")
          (str/replace (slurp (io/file root "processes" "support-request.edn"))
                       ":process/version 1" ":process/version 2"))
    (is (= :tik.api/definition-mismatch (api-type #(api/derive s a))))
    (let [d (api/derive s a {:allow-unpinned-definition true})]
      (is (= :named (get-in d [:definition :source])))
      (is (not= (get-in d [:definition :pinned]) (get-in d [:definition :derived-under]))))
    (is (not (:ok? (api/verify-ticket s a))) "L2 fails the pin")))

(deftest a-missing-definition-and-a-malformed-register-are-refused
  (let [{:keys [root ids]} (built-store! :file)
        [a] ids
        s (api/open-store root)]
    (spit (io/file root "roles.edn") "{:triager {:members [")
    (is (= :tik.api/malformed-roles (api-type #(api/derive s a))))
    (.delete (io/file root "roles.edn"))
    (doseq [^File f (.listFiles (io/file root "processes" "by-hash"))] (.delete f))
    (.delete (io/file root "processes" "support-request.edn"))
    (is (= :tik.api/definition-missing (api-type #(api/derive s a))))))

;; ------------------------------------------------------------ the handle

(deftest open-store-refuses-what-is-not-one-store
  (let [d (h/temp-dir! "tik-api-open")]
    (is (= :tik.api/not-a-store (api-type #(api/open-store (str d)))))
    (is (= :tik.api/not-a-store (api-type #(api/open-store (str (io/file d "absent"))))))
    (.mkdirs (io/file d "tickets"))
    (is (= :file (:tik.api/backend (api/open-store d))) "positive control")
    (spit (io/file d "tik.db") "")
    (is (= :tik.api/ambiguous-store (api-type #(api/open-store d))))
    (is (= :tik.api/invalid-argument (api-type #(api/open-store 42))))
    (is (= :tik.api/invalid-argument (api-type #(api/open-store (h/temp-dir!) {:limit {}}))))
    (is (= :tik.api/invalid-argument (api-type #(api/derive {} "aaaaaaaa"))))
    (is (= 1 api/version (:tik.api/version (api/open-store (doto (h/temp-dir!) (-> (io/file "tickets") .mkdirs)) {}))))))

(deftest babashka-loads-the-library-and-derives
  (let [{:keys [root ids]} (built-store! :file)
        r (apply sh/sh (concat (h/bb-command "--config" (str repo "/bb.edn") "-e"
                                             (str "(require '[tik.api :as api])"
                                                  "(prn (:current (api/derive (api/open-store \"" root "\") \""
                                                  (first ids) "\")))"
                                                  "(prn (:ok? (api/verify-ticket (api/open-store \"" root "\") \""
                                                  (first ids) "\")))"))
                               [:dir repo :env (h/subprocess-env)]))]
    (is (zero? (:exit r)) (:err r))
    (is (= [(:current (api/derive (api/open-store root) (first ids))) true]
           (mapv read-edn (str/split-lines (str/trim (:out r))))))))
