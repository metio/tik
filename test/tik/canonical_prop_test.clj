;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.canonical-prop-test
  "Properties of the un-migratable layer: canonical EDN is a normal form
  (round-trips, emit is a fixpoint, key order is irrelevant) and content
  addressing is injective up to value equality."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.canonical :as c])
  (:import (java.time Instant)))

(def readers {'inst (fn [s] (Instant/parse s))
              'uuid (fn [s] (java.util.UUID/fromString s))})
(defn- read-canonical [s] (edn/read-string {:readers readers} s))

(def gen-instant
  ;; millisecond precision: canonical form truncates to millis, so exact
  ;; round-trip equality is only promised there
  (gen/fmap #(Instant/ofEpochMilli %) (gen/choose 0 4102444800000)))

(def gen-safe-symbol
  ;; prefixed so a generated symbol can never collide with nil/true/false
  (gen/fmap #(symbol (str "sym-" (name %))) gen/keyword))

(def gen-scalar
  (gen/one-of [(gen/return nil)
               gen/boolean
               gen/large-integer
               gen/string
               gen/keyword
               gen/keyword-ns
               gen-safe-symbol
               gen/uuid
               gen-instant]))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/fmap set (gen/vector inner 0 4))
                  (gen/fmap #(into {} %)
                            (gen/vector (gen/tuple inner inner) 0 4))]))
   gen-scalar))

(defspec canonical-round-trips 200
  (prop/for-all [v gen-value]
    (= v (read-canonical (c/emit v)))))

(defspec emit-is-a-fixpoint 200
  ;; reading canonical bytes and re-emitting yields the same bytes: there
  ;; is exactly one canonical form per value
  (prop/for-all [v gen-value]
    (= (c/emit v) (c/emit (read-canonical (c/emit v))))))

(defspec map-key-order-is-irrelevant 200
  (prop/for-all [entries (gen/vector (gen/tuple gen-scalar gen-value) 0 8)]
    (let [m (into {} entries)
          shuffled (if (seq m)
                     (apply array-map (mapcat identity (shuffle (vec m))))
                     m)]
      (and (= (c/emit m) (c/emit shuffled))
           (= (c/content-address m) (c/content-address shuffled))))))

(defspec content-address-is-injective-up-to-equality 100
  ;; hashes collide exactly when values are equal (modulo SHA-256 itself)
  (prop/for-all [a gen-value
                 b gen-value]
    (= (= a b)
       (= (c/content-address a) (c/content-address b)))))

(defspec content-address-has-the-declared-shape 100
  (prop/for-all [v gen-value]
    (boolean (re-matches #"sha256-[0-9a-f]{64}" (c/content-address v)))))

(deftest unsupported-types-rejected-however-deeply-nested
  (doseq [v [1.5 {:a 1.5} [#{{:x [1/3]}}] {:k {:kk 1.5M}}]]
    (is (thrown? clojure.lang.ExceptionInfo (c/emit v)) (pr-str v))))
