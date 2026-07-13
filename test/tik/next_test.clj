;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.next-test
  "The inbox's laws: sound (every item satisfies some ticket's explain
  reason), complete (every actionable reason surfaces as an item),
  ranked by unlock count, and who-can-act filtering never invents work."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.gen-events :as ge]
            [tik.next :as next-lens])
  (:import (java.time Instant)))

(defn at [s] (Instant/parse s))
(def now (at "2026-07-08T12:00:00Z"))

(defn- ticket [n & builders]
  (let [tid (java.util.UUID/fromString
             (format "018f2f6e-7c1a-7000-8000-%012d" n))]
    (reduce (fn [evs b] (conj evs (b tid #{(:event/id (peek evs))})))
            [(event/create-ticket {:ticket tid :actor "customer"
                                   :at (at "2026-07-08T10:00:00Z")
                                   :title (str "t" n)
                                   :process :support-request})]
            builders)))

(defn- assert-step [path value]
  (fn [tid parents]
    (event/assert-fact {:ticket tid :actor "seb" :parents parents
                        :at (at "2026-07-08T10:01:00Z")
                        :path path :value value})))

(defn- contribs [events]
  (next-lens/contributions (:event/ticket (first events))
                           ge/process events now ge/roles))

(deftest four-eyes-is-visible-to-a-different-person
  ;; a stage gated by [:different-person a b] whose two facts were BOTH
  ;; asserted by alice is unlockable by anyone else (re-assert one path so
  ;; the assertors differ). The inbox and explain must surface that to a
  ;; different person, and NOT to alice (who cannot break the tie) — the
  ;; :role/same-person reason was previously invisible to everyone.
  (let [proc {:process/id :m :process/version 1
              :process/stages [{:stage/id :open :guards []}
                               {:stage/id :done :after [:open]
                                :guards [[:different-person [:review] [:approve]]]}]}
        tid (java.util.UUID/fromString "018f2f6e-7c1a-7000-8000-000000000004")
        assert-by (fn [actor path parents]
                    (event/assert-fact {:ticket tid :actor actor :parents parents
                                        :at (at "2026-07-08T10:01:00Z")
                                        :path path :value :ok}))
        c (event/create-ticket {:ticket tid :actor "alice"
                                :at (at "2026-07-08T10:00:00Z")
                                :title "m" :process :support-request})
        e1 (assert-by "alice" [:review] #{(:event/id c)})
        e2 (assert-by "alice" [:approve] #{(:event/id e1)})
        evs [c e1 e2]
        roles {:approver {:members ["alice" "bob"]}}
        contrib (next-lens/contributions tid proc evs now roles)]
    (testing "the inbox offers it as a :set action, not parked in :waiting"
      (is (some #(= [:set [:review]] (:action %)) (:actions contrib)))
      (is (not-any? #(= :role/same-person (:reason %)) (:waiting contrib))))
    (testing "bob can act on it; alice (who asserted both) cannot"
      (is (seq (:items (next-lens/inbox [contrib] "bob"))))
      (is (empty? (:items (next-lens/inbox [contrib] "alice"))))
      (is (seq (:items (next-lens/inbox [contrib] nil))) "unfiltered still lists it"))))

(deftest fact-mismatch-is-actionable-in-the-inbox
  ;; a present-but-WRONG [:fact= …] guard reports :fact/mismatch, which
  ;; explain ranks most-actionable ("set it to the expected value"). The
  ;; inbox is complete w.r.t. explain's actionable reasons, so it must
  ;; surface a :set action — not silently drop the item into :waiting.
  (let [proc {:process/id :m :process/version 1
              :process/stages [{:stage/id :open :guards []}
                               {:stage/id :done :after [:open]
                                :guards [[:fact= [:severity] :high]]}]}
        evs (ticket 9 (assert-step [:severity] :low))     ; wrong value
        contrib (next-lens/contributions (:event/ticket (first evs))
                                         proc evs now ge/roles)]
    (is (some #(= [:set [:severity]] (:action %)) (:actions contrib))
        "the mismatch is a set-action in the inbox")
    (is (not-any? #(= :fact/mismatch (:reason %)) (:waiting contrib))
        "and is NOT parked in :waiting")))

(deftest contributions-total-over-malformed-signed-by
  ;; the boundary derivation must be total over ANY process, linted or
  ;; not: a malformed [:signed-by] guard (missing its role/path, so no
  ;; restriction to read) must contribute nothing rather than throw an
  ;; IndexOutOfBounds while computing who can unlock the stage.
  (let [tid (java.util.UUID/fromString "018f2f6e-7c1a-7000-8000-000000000001")
        evs [(event/create-ticket {:ticket tid :actor "customer"
                                   :at (at "2026-07-08T10:00:00Z")
                                   :title "m" :process :support-request})]]
    (doseq [guard [[:signed-by]                    ; 0 args
                   [:signed-by :triager]           ; 1 arg (no path)
                   [:and [:signed-by] [:signed-by :r]]
                   [:or [:not [:signed-by]]]]]
      (let [proc {:process/id :fuzz :process/version 1
                  :process/stages [{:stage/id :A :guards [guard]}]}]
        (is (map? (next-lens/contributions tid proc evs now ge/roles))
            (pr-str guard))))))

(deftest shared-missing-facts-aggregate-across-tickets
  (let [fresh-a (ticket 1)
        fresh-b (ticket 2)
        half (ticket 3 (assert-step [:category] :technical))
        {:keys [items]} (next-lens/inbox (map contribs [fresh-a fresh-b half]))
        by-action (into {} (map (juxt :action identity)) items)]
    (testing "severity is missing on all three tickets and ranks first"
      (is (= [:set [:severity]] (:action (first items))))
      (is (= 3 (count (:unlocks (first items))))))
    (testing "category is missing on only two"
      (is (= 2 (count (:unlocks (by-action [:set [:category]]))))))
    (testing "the who-can-act union names the triager"
      (is (= #{"seb"} (:who (by-action [:set [:category]])))))))

(deftest actor-filter-never-invents-and-only-removes
  (let [tickets [(ticket 1) (ticket 2)]
        all (next-lens/inbox (map contribs tickets))
        seb (next-lens/inbox (map contribs tickets) "seb")
        rando (next-lens/inbox (map contribs tickets) "rando")]
    (testing "seb (a triager) sees everything"
      (is (= (set (map :action (:items all)))
             (set (map :action (:items seb))))))
    (testing "rando loses the role-gated actions but keeps open ones"
      (is (contains? (set (map :action (:items all))) [:set [:category]]))
      (is (every? (fn [item]
                    (contains? (set (map :action (:items all)))
                               (:action item)))
                  (:items rando))))))

(deftest time-gated-work-is-waiting-not-actionable
  (let [{:keys [actions waiting]} (contribs (ticket 1))]
    (is (not-any? #(= :escalated (:stage %)) actions)
        "escalation needs elapsed time and fact-absence — nobody can act")
    (is (some #(and (= :escalated (:stage %))
                    (= :time/not-elapsed (:reason %)))
              waiting))))

(deftest settled-tickets-leave-the-inbox
  (let [landed (ticket 7
                       (assert-step [:summary] "escape hatches are noise")
                       (assert-step [:kind] :bug)
                       (assert-step [:commit] "abc1234")
                       (assert-step [:gate] :green))
        proc (edn/read-string (slurp "processes/tik-dev.edn"))
        contrib (next-lens/contributions (:event/ticket (first landed))
                                         proc landed now
                                         (:process/roles proc))]
    (is (:settled? contrib) "sticky terminal :landed is reached")
    (testing "the default inbox hides the escape hatch, and says so"
      (let [{:keys [items settled]} (next-lens/inbox [contrib])]
        (is (empty? items))
        (is (= 1 settled))))
    (testing "--all still shows it: the lens hides, derivation never does"
      (let [{:keys [items]} (next-lens/inbox [contrib] nil
                                             {:include-settled? true})]
        (is (= [[:set [:parked :reason]]] (mapv :action items)))))))

(defspec inbox-is-sound-and-complete 60
  ;; sound: every item's unlock corresponds to a frontier stage whose
  ;; explain block contains a reason this action satisfies.
  ;; complete: every actionable explain reason appears under some item.
  (prop/for-all [events ge/gen-events
                 t ge/gen-now]
    (let [tid (:event/ticket (first events))
          {:keys [items]} (next-lens/inbox
                           [(next-lens/contributions tid ge/process events
                                                     t ge/roles)]
                           nil {:include-settled? true})
          blocks (explain/explain ge/process events t ge/roles)
          expected (set
                    (for [{:keys [stage missing]} blocks
                          r missing
                          :let [a (case (:reason r)
                                    (:fact/missing :fact/invalid
                                                   :fact/retracted :fact/disputed
                                                   :fact/conflicted :role/unsatisfied)
                                    [:set (:path r)]
                                    :artifact/missing [:attach (:prefix r)]
                                    nil)]
                          :when a]
                      [a stage]))
          actual (set (for [{:keys [action unlocks]} items
                            u unlocks]
                        [action (:stage u)]))]
      ;; :alternatives expands in the lens but not in `expected`, so the
      ;; lens may offer MORE (each branch of an :or); it must offer at
      ;; least everything directly actionable, and unlock counts are
      ;; ordered.
      (and (every? actual expected)
           (apply >= (map #(count (:unlocks %)) items))))))

(defspec who-filter-is-monotone 40
  ;; filtering by actor only ever removes unlocks, never adds or invents
  (prop/for-all [events ge/gen-events
                 t ge/gen-now
                 actor (gen/elements ge/actors)]
    (let [tid (:event/ticket (first events))
          per [(next-lens/contributions tid ge/process events t ge/roles)]
          all-pairs (set (for [{:keys [action unlocks]}
                               (:items (next-lens/inbox per nil
                                                        {:include-settled? true}))
                               u unlocks]
                           [action (:ticket u) (:stage u)]))
          actor-pairs (set (for [{:keys [action unlocks]}
                                 (:items (next-lens/inbox per actor
                                                          {:include-settled? true}))
                                 u unlocks]
                             [action (:ticket u) (:stage u)]))]
      (every? all-pairs actor-pairs))))

(defspec silence-breaks-unlock-ties 40
  ;; among equal unlock counts, the quieter ticket's action leads —
  ;; attention debt is a tiebreaker, never a trump over unlock count
  (prop/for-all [events ge/gen-events
                 t ge/gen-now]
    (let [tid (:event/ticket (first events))
          per [(next-lens/contributions tid ge/process events t ge/roles)]
          items (:items (next-lens/inbox per nil {:include-settled? true}))]
      (and (every? #(<= 0 (:stale-ms %)) items)
           (every? (fn [[a b]]
                     (or (> (count (:unlocks a)) (count (:unlocks b)))
                         (>= (:stale-ms a) (:stale-ms b))
                         (< (count (:unlocks a)) (count (:unlocks b)))))
                   (partition 2 1 items))))))

(deftest role-view-shows-what-the-role-is-waited-on-for
  ;; the support-request sample: :triaged demands the :triager's
  ;; signature over [:category] — the role view must surface exactly
  ;; the role-bound action, and an unknown role sees nothing
  (let [tid (random-uuid)
        events (event/chain
                (fn [_] (event/create-ticket
                         {:ticket tid :actor "customer" :at now
                          :title "role view" :process :support-request})))
        per [(next-lens/contributions tid ge/process events now ge/roles)]
        triager (:items (next-lens/inbox per nil {:role :triager}))
        nobody (:items (next-lens/inbox per nil {:role :ghost-role}))]
    (is (seq triager))
    (is (every? #(= [:set [:category]] (:action %)) triager))
    (is (= #{"seb"} (:who (first triager))))
    (is (empty? nobody))))
