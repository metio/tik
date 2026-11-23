;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.fact-equals-status-test
  "A guard over a fact that is present but unusable reports why it is
  unusable, whatever the guard compares, so the lenses never prescribe an
  assertion that cannot discharge it."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.explain :as explain])
  (:import (java.time Instant)))

(def ^:private tid #uuid "018f2f6e-7c1a-7000-8000-0000000fe0a1")
(def ^:private t0 (Instant/parse "2026-07-08T10:00:00Z"))
(def ^:private now (Instant/parse "2026-07-08T12:00:00Z"))
(defn- at [n] (.plusSeconds ^Instant t0 (long n)))

(def ^:private proc
  {:process/id :m :process/version 1
   :process/stages [{:stage/id :open :guards []}
                    {:stage/id :ok :after [:open]
                     :guards [[:fact= [:verdict] :approved]]}
                    {:stage/id :four-eyes :after [:open]
                     :guards [[:different-person [:verdict] [:review]]]}]})

(defn- chain [& steps]
  (reduce (fn [evs step] (conj evs (step #{(:event/id (peek evs))})))
          [(event/create-ticket {:ticket tid :actor "seb" :at t0 :title "f" :process :m})]
          steps))

(defn- assert* [n actor path value]
  #(event/assert-fact {:ticket tid :actor actor :parents % :at (at n) :path path :value value}))
(defn- dispute* [n actor path]
  #(event/dispute-fact {:ticket tid :actor actor :parents % :at (at n) :path path :reason "wrong"}))
(defn- retract* [n actor path]
  #(event/retract-fact {:ticket tid :actor actor :parents % :at (at n) :path path :reason "oops"}))

(defn- reasons [events stage]
  (let [frontier (explain/explain proc events now {})]
    (->> frontier (filter #(= stage (:stage %))) first :missing
         (tree-seq (some-fn map? sequential?) #(if (map? %) (:options %) %))
         (filter #(and (map? %) (:reason %))))))

(deftest fact-equals-names-a-dispute
  (let [rs (reasons (chain (assert* 1 "seb" [:verdict] :approved)
                           (dispute* 2 "ana" [:verdict]))
                    :ok)]
    (is (= [:fact/disputed] (map :reason rs)))
    (is (= "ana" (:by (first rs))))
    (is (= "wrong" (:note (first rs))))))

(deftest fact-equals-names-a-retraction
  (let [rs (reasons (chain (assert* 1 "seb" [:verdict] :approved)
                           (retract* 2 "seb" [:verdict]))
                    :ok)]
    (is (= [:fact/retracted] (map :reason rs)))))

(deftest fact-equals-still-says-what-to-set-an-absent-fact-to
  (let [rs (reasons (chain) :ok)]
    (is (= [:fact/missing] (map :reason rs)))
    (is (= :approved (:expected (first rs))))))

(deftest different-person-names-a-dispute
  (let [rs (reasons (chain (assert* 1 "seb" [:verdict] :approved)
                           (assert* 2 "ana" [:review] :ok)
                           (dispute* 3 "bob" [:review]))
                    :four-eyes)]
    (testing "the disputed path reports its dispute"
      (is (= [:fact/disputed] (map :reason rs)))
      (is (= [:review] (:path (first rs)))))))
