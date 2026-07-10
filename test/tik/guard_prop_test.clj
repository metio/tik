;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.guard-prop-test
  "Laws of the guard algebra: purity, boolean semantics of the
  combinators, the reasons discipline (unsatisfied iff reasons), and
  :fact= meaning present-and-equal with one reason per failure mode."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.event :as event]
            [tik.gen-events :as ge]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage]))

(defn- at [s] (java.time.Instant/parse s))

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

(defspec fact=-means-present-and-equal 100
  ;; and exactly ONE reason per failure mode — the double-report bug
  ;; (ticket 1c08e147) stays fixed
  (prop/for-all [events ge/gen-events
                 p (gen/elements ge/paths)
                 v ge/gen-value]
    (let [c (ctx events ge/base)
          {:keys [status value]} (red/fact-status (:state c) p)
          {:keys [satisfied? reasons]} (guard/eval-guard [:fact= p v] c)]
      (and (= (and (= :present status) (= v value))
              (boolean satisfied?))
           (<= (count reasons) 1)))))

(deftest removed-operators-are-not-quietly-accepted
  (let [c (ctx (ge/ops->events []) ge/base)]
    (doseq [g [[:not-stage :received]
               [:if [:fact [:category]] [:fact [:severity]]]
               [:frobnicate 1]]]
      (is (thrown? clojure.lang.ExceptionInfo (guard/eval-guard g c))
          (pr-str g)))))

(deftest v2-attested-within
  (let [tid (:event/ticket (first (ge/ops->events [])))
        create (first (ge/ops->events []))
        att (fn [t] (event/add-attestation
                     {:ticket tid :actor "ci" :at (at t)
                      :parents #{(:event/id create)}
                      :claim {:claim :ci/green :run 42}}))
        eval-at (fn [events t]
                  (guard/eval-guard [:attested-within :ci/green "PT24H"]
                                    (ctx events (at t))))]
    (testing "no attestation at all"
      (let [{:keys [satisfied? reasons]} (eval-at [create] "2026-07-08T12:00:00Z")]
        (is (not satisfied?))
        (is (= :attestation/missing (:reason (first reasons))))))
    (testing "fresh attestation satisfies"
      (is (:satisfied? (eval-at [create (att "2026-07-08T11:00:00Z")]
                                "2026-07-08T12:00:00Z"))))
    (testing "the same attestation, replayed a month later, is STALE"
      (let [{:keys [satisfied? reasons]}
            (eval-at [create (att "2026-07-08T11:00:00Z")]
                     "2026-08-08T12:00:00Z")]
        (is (not satisfied?))
        (is (= :attestation/stale (:reason (first reasons))))))))

(deftest v2-different-person
  (let [events (ge/ops->events [])
        tid (:event/ticket (first events))
        put (fn [evs actor path v t]
              (conj evs (event/assert-fact
                         {:ticket tid :actor actor :at (at t)
                          :parents #{(:event/id (peek evs))}
                          :path path :value v})))
        g [:different-person [:review] [:approve]]]
    (testing "same person on both sides fails with both names"
      (let [evs (-> events
                    (put "seb" [:review] :ok "2026-07-08T10:01:00Z")
                    (put "seb" [:approve] :ok "2026-07-08T10:02:00Z"))
            {:keys [satisfied? reasons]} (guard/eval-guard g (ctx evs ge/base))]
        (is (not satisfied?))
        (is (= :role/same-person (:reason (first reasons))))))
    (testing "four eyes satisfy"
      (let [evs (-> events
                    (put "seb" [:review] :ok "2026-07-08T10:01:00Z")
                    (put "billing" [:approve] :ok "2026-07-08T10:02:00Z"))]
        (is (:satisfied? (guard/eval-guard g (ctx evs ge/base))))))))
