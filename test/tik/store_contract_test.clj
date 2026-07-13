;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store-contract-test
  "ADR 0020 as a test both backends must pass: append-only and
  idempotent by id, unordered reads, exact canonical bytes preserved
  (hash(stored bytes) = id, checked against RAW storage, not this
  code's parsing), and identical derivation from either backend."
  (:require [tik.harness :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [tik.canonical :as canonical]
            [tik.gen-events :as ge]
            [tik.reduce :as red]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite :as sqlite]))

(defn- tmp [name]
  (str (h/temp-dir! name)))

(defn- backends []
  [{:name "file"
    :store (fstore/file-store (tmp "tik-file"))
    :raw (fn [s tid]
           (for [^java.io.File f (.listFiles
                                  (io/file (:root s) "tickets" (str tid)
                                           "events"))
                 :when (str/ends-with? (.getName f) ".edn")]
             [(subs (.getName f) 0 (- (count (.getName f)) 4))
              (slurp f)]))}
   {:name "sqlite"
    :store (sqlite/sqlite-store (str (tmp "tik-sqlite") "/tik.db"))
    :raw (fn [s tid]
           (for [[id hex] (sqlite/raw-rows (:db s) tid)]
             [id (String. ^bytes (sqlite/hex->bytes hex) "UTF-8")]))}])

(deftest both-backends-honor-the-contract
  (let [events (ge/ops->events [[:assert "seb" [:category] :technical 60]
                                [:assert "seb" [:severity] :high 120]
                                [:retract "seb" [:severity] nil 180]
                                [:attach "seb" [:category] nil 240]])
        tid (:event/ticket (first events))]
    (doseq [{:keys [name store raw]} (backends)]
      (testing name
        (testing "append is idempotent by id (union semantics)"
          (doseq [e (concat (shuffle events) events)]
            (store/append! store e))
          (is (= (count events) (count (store/events store tid)))))
        (testing "reads return the full set (order is the reducer's job)"
          (is (= (set (map :event/id events))
                 (set (map :event/id (store/events store tid))))))
        (testing "ticket-ids and has-event?"
          (is (= [tid] (store/ticket-ids store)))
          (is (every? #(store/has-event? store (:event/id %)) events))
          (is (not (store/has-event? store "sha256-0000"))))
        (testing "RAW stored bytes are the exact hashed region"
          (doseq [[id raw-bytes] (raw store tid)]
            (is (= id (str "sha256-" (canonical/sha256-hex raw-bytes)))
                (str name ": hash(stored bytes) = id"))))
        (testing "event-bytes returns those exact stored bytes"
          (doseq [[id raw-bytes] (raw store tid)]
            (is (= raw-bytes (String. ^bytes (store/event-bytes store tid id)
                                      "UTF-8"))
                (str name ": event-bytes = stored bytes"))))
        (testing "sidecars round-trip, and put is idempotent by name"
          (store/put-sidecar! store tid "x.sig.aa" (.getBytes "sig-a" "UTF-8"))
          (store/put-sidecar! store tid "x.witness.bb" (.getBytes "wit-b" "UTF-8"))
          (is (= #{"x.sig.aa" "x.witness.bb"}
                 (set (store/sidecar-names store tid))))
          (is (= "sig-a" (String. ^bytes (store/read-sidecar store tid "x.sig.aa")
                                  "UTF-8")))
          (is (nil? (store/read-sidecar store tid "missing")))
          (store/put-sidecar! store tid "x.sig.aa" (.getBytes "sig-a2" "UTF-8"))
          (is (= "sig-a2"
                 (String. ^bytes (store/read-sidecar store tid "x.sig.aa")
                          "UTF-8"))
              (str name ": put overwrites by name")))))))

(deftest file-store-event-bytes-survives-packing
  ;; pack! deletes the loose <id>.edn after folding it into events.pack;
  ;; event-bytes must return the pack slice, or verify/sign/witness see nil
  ;; for a still-present event (signatures endorse those exact bytes).
  (let [root (tmp "tik-pack")
        store (fstore/file-store root)
        events (ge/ops->events [[:assert "seb" [:category] :technical 60]
                                [:assert "seb" [:severity] :high 120]])
        tid (:event/ticket (first events))]
    (doseq [e events] (store/append! store e))
    (let [before (into {} (for [e events]
                            [(:event/id e)
                             (String. ^bytes (store/event-bytes store tid
                                                                (:event/id e))
                                      "UTF-8")]))]
      (fstore/pack! root tid)
      (doseq [e events]
        (is (= (before (:event/id e))
               (String. ^bytes (store/event-bytes store tid (:event/id e)) "UTF-8"))
            (str "event-bytes after pack for " (:event/id e)))
        (is (= (:event/id e)
               (str "sha256-" (canonical/sha256-hex (before (:event/id e)))))
            "the bytes still hash to the id")))))

(deftest file-store-skips-stray-non-ticket-entries
  ;; tickets/ may hold entries that are not tickets — a stray directory,
  ;; a .gitkeep. A ticket directory's name IS its uuid identity, so only a
  ;; uuid names a ticket; a non-uuid entry is skipped rather than throwing
  ;; from UUID/fromString and taking down every whole-store lens at once.
  (let [root (tmp "tik-stray")
        store (fstore/file-store root)
        e (first (ge/ops->events [[:assert "seb" [:category] :technical 60]]))
        tid (:event/ticket e)]
    (store/append! store e)
    (.mkdirs (io/file root "tickets" "not-a-uuid"))
    (.mkdirs (io/file root "tickets" ".git"))
    (spit (io/file root "tickets" ".gitkeep") "x")
    (is (= [tid] (store/ticket-ids store))
        "only the uuid-named directory names a ticket; strays are skipped")))

(deftest sqlite-db-errors-fail-well-not-as-a-bug
  ;; the embedded driver surfaces db problems as SQLException; the store
  ;; turns every one — opening the connection or running a statement —
  ;; into a data-carrying ex-info, never a raw throw reported as an
  ;; internal bug. A garbage (non-database) file is one such case.
  (let [f (str (tmp "tik-badsqlite") "/x.db")]
    (spit (io/file f) "this is not a sqlite database")
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"SQLite error"
         (sqlite/sqlite-store f)))))

(defspec backends-derive-identically 30
  (prop/for-all [events ge/gen-events]
    (let [[a b] (backends)
          tid (:event/ticket (first events))]
      (doseq [e events]
        (store/append! (:store a) e)
        (store/append! (:store b) e))
      (= (red/ticket-state (store/events (:store a) tid))
         (red/ticket-state (store/events (:store b) tid))))))
