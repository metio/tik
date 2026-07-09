;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.guard-prop-test
  "Laws of the guard algebra: purity, boolean semantics of the
  combinators, the reasons discipline (unsatisfied iff reasons), sugar
  expansion staying inside the basis, and :fact= meaning exactly
  present-equal-and-schema-valid."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [tik.gen-events :as ge]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage]))

(defn ctx [events now]
  {:state (red/ticket-state events)
   :process ge/process
   :now now
   :roles ge/roles
   :reached (stage/effective-reached ge/process events now ge/roles)})

(def gen-simple-guard
  (gen/one-of
   [(gen/fmap (fn [p] [:fact p]) (gen/elements ge/paths))
    (gen/fmap (fn [[p v]] [:fact= p v])
              (gen/tuple (gen/elements ge/paths) ge/gen-value))
    (gen/elements [[:artifact "repro/"]
                   [:artifact "misc/"]
                   [:stage-reached :received]
                   [:stage-reached :triaged]
                   [:stage-reached :closed]
                   [:elapsed-since :ticket/create "PT1H"]
                   [:elapsed-since :ticket/create "PT48H"]
                   [:signed-by :triager [:category]]])]))

(def gen-guard
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/fmap #(into [:and] %) (gen/vector inner 1 3))
                  (gen/fmap #(into [:or] %) (gen/vector inner 1 3))
                  (gen/fmap (fn [g] [:not g]) inner)]))
   gen-simple-guard))

(defspec eval-guard-is-deterministic 100
  (prop/for-all [events ge/gen-events
                 now ge/gen-now
                 g gen-guard]
    (let [c (ctx events now)]
      (= (guard/eval-guard g c) (guard/eval-guard g c)))))

(defspec unsatisfied-iff-reasons 100
  ;; the reasons discipline: a satisfied guard explains nothing, an
  ;; unsatisfied guard always explains itself
  (prop/for-all [events ge/gen-events
                 now ge/gen-now
                 g gen-guard]
    (let [{:keys [satisfied? reasons]} (guard/eval-guard g (ctx events now))]
      (if satisfied? (empty? reasons) (seq reasons)))))

(defspec not-is-classical-negation 100
  (prop/for-all [events ge/gen-events
                 now ge/gen-now
                 g gen-guard]
    (let [c (ctx events now)]
      (= (:satisfied? (guard/eval-guard [:not g] c))
         (not (:satisfied? (guard/eval-guard g c)))))))

(defspec and-or-have-boolean-semantics 100
  (prop/for-all [events ge/gen-events
                 now ge/gen-now
                 gs (gen/vector gen-guard 1 4)]
    (let [c (ctx events now)
          sat? #(:satisfied? (guard/eval-guard % c))]
      (and (= (:satisfied? (guard/eval-guard (into [:and] gs) c))
              (every? sat? gs))
           (= (:satisfied? (guard/eval-guard (into [:or] gs) c))
              (boolean (some sat? gs)))))))

(def basis
  #{:fact :artifact :signed-by :stage-reached :elapsed-since
    :and :or :not :malli})

(defn- operators [guard]
  (when (vector? guard)
    (cons (first guard)
          (case (first guard)
            (:and :or) (mapcat operators (rest guard))
            :not (operators (second guard))
            nil))))

(defspec expand-emits-only-the-basis 100
  (prop/for-all [g gen-guard]
    (every? basis (operators (guard/expand g)))))

(defspec fact=-means-present-equal-and-schema-valid 100
  (prop/for-all [events ge/gen-events
                 p (gen/elements ge/paths)
                 v ge/gen-value]
    (let [c (ctx events ge/base)
          {:keys [status value]} (red/fact-status (:state c) p)
          schema (get-in ge/process [:process/facts p])
          expected (and (= :present status)
                        (= v value)
                        (or (nil? schema) (m/validate schema value)))]
      (= expected
         (boolean (:satisfied? (guard/eval-guard [:fact= p v] c)))))))

(deftest removed-operators-are-not-quietly-accepted
  (let [c (ctx (ge/ops->events []) ge/base)]
    (doseq [g [[:not-stage :received]
               [:if [:fact [:category]] [:fact [:severity]]]
               [:frobnicate 1]]]
      (is (thrown? clojure.lang.ExceptionInfo (guard/eval-guard g c))
          (pr-str g)))))
