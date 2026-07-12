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
                (str name ": hash(stored bytes) = id"))))))))

(defspec backends-derive-identically 30
  (prop/for-all [events ge/gen-events]
    (let [[a b] (backends)
          tid (:event/ticket (first events))]
      (doseq [e events]
        (store/append! (:store a) e)
        (store/append! (:store b) e))
      (= (red/ticket-state (store/events (:store a) tid))
         (red/ticket-state (store/events (:store b) tid))))))
