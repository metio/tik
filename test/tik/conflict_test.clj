;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.conflict-test
  "ADR 0003 structurally: causally concurrent writes that disagree make
  a fact :conflicted; agreement is not conflict; any write that observed
  all competitors resolves; and the detection is a function of the event
  SET (commutative), even under adversarially backdated claimed times."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.reduce :as red]
            [tik.stage :as stage])
  (:import (java.time Instant)))

(def proc (edn/read-string (slurp "processes/support-request.edn")))
(def roles (:process/roles proc))
(def tid #uuid "018f2f6e-7c1a-7000-8000-00000000cafe")
(defn at [s] (Instant/parse s))
(def now (at "2026-07-08T12:00:00Z"))

(def create
  (event/create-ticket {:ticket tid :actor "customer"
                        :at (at "2026-07-08T10:00:00Z")
                        :title "forked" :process :support-request}))

(defn assert* [actor t parents path value]
  (event/assert-fact {:ticket tid :actor actor :at (at t)
                      :parents parents :path path :value value}))

(defn retract* [actor t parents path]
  (event/retract-fact {:ticket tid :actor actor :at (at t)
                       :parents parents :path path}))

(def head #{(:event/id create)})

(deftest concurrent-disagreement-conflicts
  (let [a (assert* "seb" "2026-07-08T10:01:00Z" head [:category] :technical)
        b (assert* "billing" "2026-07-08T10:02:00Z" head [:category] :billing)
        state (red/ticket-state [create a b])
        {:keys [status claims]} (red/fact-status state [:category])]
    (is (= :conflicted status))
    (is (= #{:technical :billing} (set (map :value claims))))
    (is (= #{"seb" "billing"} (set (map :by claims))))
    (testing "a conflicted fact satisfies no guard; the stage blocks"
      (let [severity (assert* "seb" "2026-07-08T10:03:00Z"
                              #{(:event/id a) (:event/id b)}
                              [:severity] :high)
            events [create a b severity]]
        (is (= #{:received}
               (stage/effective-reached proc events now roles)))
        (is (some #(= :fact/conflicted (:reason %))
                  (mapcat :missing (explain/explain proc events now roles))))))))

(deftest concurrent-agreement-is-not-conflict
  (let [a (assert* "seb" "2026-07-08T10:01:00Z" head [:category] :technical)
        b (assert* "billing" "2026-07-08T10:02:00Z" head [:category] :technical)
        state (red/ticket-state [create a b])]
    (is (= :present (:status (red/fact-status state [:category])))
        "same value from independent replicas is corroboration")))

(deftest a-write-that-observed-both-resolves
  (let [a (assert* "seb" "2026-07-08T10:01:00Z" head [:category] :technical)
        b (assert* "billing" "2026-07-08T10:02:00Z" head [:category] :billing)
        both #{(:event/id a) (:event/id b)}]
    (testing "a superseding assert resolves to its value"
      (let [c (assert* "seb" "2026-07-08T10:05:00Z" both [:category] :technical)
            state (red/ticket-state [create a b c])
            fs (red/fact-status state [:category])]
        (is (= :present (:status fs)))
        (is (= :technical (:value fs)))))
    (testing "a retract that observed both is also a resolution"
      (let [c (retract* "seb" "2026-07-08T10:05:00Z" both [:category])
            state (red/ticket-state [create a b c])]
        (is (= :retracted (:status (red/fact-status state [:category]))))))))

(deftest assert-versus-concurrent-retract-conflicts
  (let [a (assert* "seb" "2026-07-08T10:01:00Z" head [:category] :technical)
        linear #{(:event/id a)}
        r (retract* "billing" "2026-07-08T10:02:00Z" head [:category])
        b (assert* "seb" "2026-07-08T10:03:00Z" linear [:category] :technical)]
    (testing "retract concurrent with the assert it never saw"
      (is (= :conflicted
             (:status (red/fact-status (red/ticket-state [create a r])
                                       [:category])))))
    (testing "but a retract-then-reassert chain is just history"
      (let [r2 (retract* "seb" "2026-07-08T10:02:00Z" linear [:category])
            b2 (assert* "seb" "2026-07-08T10:03:00Z" #{(:event/id r2)}
                        [:category] :billing)]
        (is (= :billing
               (:value (red/fact-status (red/ticket-state [create a r2 b2])
                                        [:category]))))))
    (is b "chain events built")))

(deftest backdated-intermediates-do-not-fake-concurrency
  ;; create -> X -> M -> Y, but claimed times make M fold AFTER Y: an
  ;; incremental frontier would see X and Y as concurrent (M's ancestry
  ;; unknown when Y folds). Detection over the complete log must not.
  (let [x (assert* "seb" "2026-07-08T10:01:00Z" head [:category] :technical)
        m (event/attach-artifact {:ticket tid :actor "seb"
                                  :at (at "2026-07-08T10:09:00Z")
                                  :parents #{(:event/id x)}
                                  :path "repro/x" :hash "sha256-cafe"})
        y (assert* "seb" "2026-07-08T10:02:00Z" #{(:event/id m)}
                   [:category] :billing)
        state (red/ticket-state [create x m y])
        fs (red/fact-status state [:category])]
    (is (= :present (:status fs)) "Y observed X through M: no conflict")
    (testing "effective value stays with (at, id) order, per ADR 0004"
      (is (= :billing (:value fs))
          "the backdated M does not change whose claim reads latest"))))

(deftest conflict-detection-commutes
  (let [a (assert* "seb" "2026-07-08T10:01:00Z" head [:category] :technical)
        b (assert* "billing" "2026-07-08T10:02:00Z" head [:category] :billing)
        events [create a b]]
    (doseq [perm [[create a b] [b a create] [a create b]
                  (concat events events)]]
      (is (= :conflicted
             (:status (red/fact-status (red/ticket-state perm) [:category])))
          (pr-str (map :event/at perm))))))

(deftest a-partial-log-fakes-concurrency-and-must-announce-itself
  (testing "a strictly linear history minus one unrelated intermediate"
    (let [a1 (assert* "seb" "2026-07-08T10:01:00Z" head [:category] :technical)
          mid (assert* "seb" "2026-07-08T10:02:00Z" #{(:event/id a1)}
                       [:severity] :high)
          a2 (assert* "seb" "2026-07-08T10:03:00Z" #{(:event/id mid)}
                      [:category] :billing)
          complete [create a1 mid a2]
          partial-log [create a1 a2]]
      (is (= :present (:status (red/fact-status (red/ticket-state complete)
                                                [:category])))
          "with every ancestor present the later write simply supersedes")
      (is (= :conflicted (:status (red/fact-status (red/ticket-state partial-log)
                                                   [:category])))
          "ancestry the replica cannot see is indistinguishable from concurrency")
      (testing "so the gap must be detectable rather than silent"
        (is (empty? (dag/missing-parents complete)))
        (is (= 1 (count (dag/missing-parents partial-log)))
            "the qualifier every lens needs: this derivation is mid-sync")))))

(deftest the-linearity-shortcut-never-changes-a-verdict
  (testing "conflict detection agrees with a shortcut-free maximality check"
    ;; The fold carries :linear-writes as a one-way proof that a path's
    ;; writes form a chain. Stripping it must leave every verdict intact,
    ;; on forked and linear histories alike — otherwise the optimization
    ;; is deciding conflicts rather than merely skipping work.
    (let [a (assert* "seb" "2026-07-08T10:01:00Z" head [:category] :technical)
          b (assert* "billing" "2026-07-08T10:02:00Z" head [:category] :billing)
          c (assert* "seb" "2026-07-08T10:03:00Z"
                     #{(:event/id a) (:event/id b)} [:category] :account)
          lin1 (assert* "seb" "2026-07-08T10:04:00Z" head [:severity] :low)
          lin2 (assert* "seb" "2026-07-08T10:05:00Z" #{(:event/id lin1)}
                        [:severity] :high)
          histories [[create a b]          ; forked, disagreeing
                     [create a b c]        ; resolved by an observer
                     [create lin1 lin2]    ; linear
                     [create a b lin1 lin2]]]
      (doseq [evs histories
              path [[:category] [:severity]]
              :let [state (red/ticket-state evs)
                    stripped (assoc state :linear-writes {})]]
        (is (= (red/conflicting-claims state path)
               (red/conflicting-claims stripped path))
            (str "shortcut changed the verdict for " path
                 " over " (count evs) " events"))))))
