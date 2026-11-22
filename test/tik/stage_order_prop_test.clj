;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.stage-order-prop-test
  "Generative tests of the order stages derive in, over random processes and
   random logs. A stage outside the sticky carry derives only when every
   stage it names in :after has derived and its guards hold; the reached set
   is the least fixpoint, so no stage left out could derive; the sticky carry
   only grows and admits a stage only together with its :after stages; and
   none of this depends on the order the definition lists its stages in. The
   properties are checked after every event of the fold, since a ticket's
   history is read at every point of it."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.event :as event]
            [tik.guard :as guard]
            [tik.lint :as lint]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.stage :as stage])
  (:import (java.time Instant)))

(def ^:private tid #uuid "018f2f6e-7c1a-7000-8000-0000000000aa")
(def ^:private ^Instant start (Instant/parse "2026-11-22T10:00:00Z"))
(def ^:private now (Instant/parse "2026-11-22T12:00:00Z"))
(def ^:private paths [[:f0] [:f1] [:f2] [:f3]])
(def ^:private roles (process/resolve-roles {:r {:members ["seb"]}} {}))

;; ------------------------------------------------------------ generators

(defn- gen-guard
  "A guard over the facts, the signer and earlier stages, negated or not.
   A negated :stage-reached is the form whose result the stratification lint
   (ADR 0005) has to make order-free, so the generated process is kept only
   when the real linter reports no error: the properties hold for exactly
   the definitions tik accepts."
  [earlier]
  (let [leaf (gen/one-of
              (cond-> [(gen/fmap (fn [p] [:fact p]) (gen/elements paths))
                       (gen/fmap (fn [[p v]] [:fact= p v])
                                 (gen/tuple (gen/elements paths) (gen/elements [:a :b])))
                       (gen/fmap (fn [p] [:not [:fact p]]) (gen/elements paths))
                       (gen/fmap (fn [[p v]] [:not [:fact= p v]])
                                 (gen/tuple (gen/elements paths) (gen/elements [:a :b])))
                       (gen/fmap (fn [p] [:signed-by :r p]) (gen/elements paths))]
                (seq earlier) (conj (gen/fmap (fn [s] [:stage-reached s]) (gen/elements earlier))
                                    (gen/fmap (fn [s] [:not [:stage-reached s]]) (gen/elements earlier)))))]
    (gen/frequency [[6 leaf]
                    [1 (gen/fmap (fn [gs] (into [:or] gs)) (gen/vector leaf 2 3))]
                    [1 (gen/fmap (fn [gs] (into [:and] gs)) (gen/vector leaf 2 3))]])))

(defn- gen-stage [i]
  (let [earlier (mapv #(keyword (str "s" %)) (range i))]
    (gen/let [after (if (seq earlier) (gen/fmap set (gen/vector (gen/elements earlier) 0 3)) (gen/return #{}))
              guards (gen/vector (gen-guard earlier) 0 3)
              sticky? (gen/frequency [[3 (gen/return false)] [1 (gen/return true)]])]
      (cond-> {:stage/id (keyword (str "s" i)) :guards guards}
        (seq after) (assoc :after (vec (sort after)))
        sticky? (assoc :stage/sticky? true)))))

(defn- lint-clean? [proc]
  (not-any? #(= :error (:level %)) (lint/lint proc)))

(def ^:private gen-process
  (gen/such-that
   lint-clean?
   (gen/let [n (gen/choose 1 7)
             stages (apply gen/tuple (map gen-stage (range n)))]
     {:process/id :generated
      :process/version 1
      :process/guard-vocab 2
      :process/purpose "a generated process"
      :process/roles {:r {:members ["seb"]}}
      :process/facts (zipmap paths (repeat [:enum :a :b]))
      :process/stages (vec stages)})
   100))

(def ^:private gen-step
  (gen/tuple (gen/elements [:assert :assert :assert :retract])
             (gen/elements paths)
             (gen/elements [:a :b])
             (gen/elements ["seb" "claude"])))

(defn- log [steps]
  (apply event/chain
         (fn [_] (event/create-ticket {:ticket tid :actor "seb" :at start
                                       :title "generated" :process :generated}))
         (map-indexed
          (fn [i [op path value actor]]
            (let [at (.plusSeconds start (* 60 (inc i)))]
              (fn [parents]
                (case op
                  :assert (event/assert-fact {:ticket tid :actor actor :at at :parents parents
                                              :path path :value value})
                  :retract (event/retract-fact {:ticket tid :actor actor :at at :parents parents
                                                :path path})))))
          steps)))

(def ^:private gen-case
  (gen/tuple gen-process (gen/fmap log (gen/vector gen-step 0 12))))

;; ------------------------------------------------------------ properties

(defn- after-of [proc id]
  (set (:after (first (filter #(= id (:stage/id %)) (:process/stages proc))))))

(defn- guards-hold? [proc stage state reached]
  (let [ctx {:state state :process proc :now now :roles roles :reached reached}]
    (every? #(:satisfied? (guard/eval-guard % ctx)) (:guards stage []))))

(defn- violations
  "What is wrong with the derivation after `events`, as data; empty when the
   ordering, fixpoint and sticky properties all hold."
  [proc events]
  (let [{:keys [state sticky-ever]} (stage/evolve proc events roles now)
        reached (stage/effective-reached proc events now roles)]
    (concat
     (for [s (:process/stages proc)
           :let [id (:stage/id s)]
           :when (and (contains? reached id) (not (contains? sticky-ever id)))
           :when (or (not (set/subset? (after-of proc id) reached))
                     (not (guards-hold? proc s state reached)))]
       {:property :reached-without-its-prerequisites :stage id :reached reached})
     (for [s (:process/stages proc)
           :let [id (:stage/id s)]
           :when (and (not (contains? reached id))
                      (set/subset? (after-of proc id) reached)
                      (guards-hold? proc s state reached))]
       {:property :derivable-but-not-reached :stage id :reached reached})
     (for [id sticky-ever
           :when (not (contains? reached id))]
       {:property :sticky-stage-dropped :stage id :reached reached}))))

(defn- prefixes [events]
  (let [ordered (vec (red/ordered events))]
    (map #(subvec ordered 0 %) (range 1 (inc (count ordered))))))

(defspec every-prefix-derives-in-order-and-to-a-fixpoint 400
  (prop/for-all [[proc events] gen-case]
    (every? (fn [pre] (empty? (violations proc pre))) (prefixes events))))

(defspec the-sticky-carry-only-grows-and-enters-with-its-prerequisites 400
  (prop/for-all [[proc events] gen-case]
    (let [runs (map #(stage/evolve proc % roles now) (prefixes events))]
      (and (every? (fn [[a b]] (set/subset? (:sticky-ever a) (:sticky-ever b)))
                   (partition 2 1 runs))
           (every? (fn [[a b]]
                     (every? #(set/subset? (after-of proc %) (:reached b))
                             (set/difference (:sticky-ever b) (:sticky-ever a))))
                   (partition 2 1 (cons {:sticky-ever #{}} runs)))))))

(defspec the-order-of-the-stage-list-does-not-matter 300
  (prop/for-all [[proc events] gen-case
                 seed gen/nat]
    (let [shuffled (update proc :process/stages
                           (fn [ss] (vec (sort-by #(hash [seed (:stage/id %)]) ss))))]
      (every? (fn [pre]
                (= (stage/effective-reached proc pre now roles)
                   (stage/effective-reached shuffled pre now roles)))
              (prefixes events)))))

(defspec the-order-events-arrive-in-does-not-matter 300
  (prop/for-all [[proc events] gen-case
                 seed gen/nat]
    (let [arrived (sort-by #(hash [seed (:event/id %)]) events)]
      (= (stage/effective-reached proc events now roles)
         (stage/effective-reached proc arrived now roles)))))

;; ------------------------------------------- examples the generator covers

(def ^:private chain-proc
  {:process/id :generated :process/version 1 :process/guard-vocab 2
   :process/roles {:r {:members ["seb"]}}
   :process/facts (zipmap paths (repeat [:enum :a :b]))
   :process/stages [{:stage/id :merged :after [:proven] :stage/sticky? true :guards [[:fact [:f2]]]}
                    {:stage/id :proven :after [:skilled] :guards [[:fact= [:f1] :a]]}
                    {:stage/id :skilled :after [:proposed] :guards [[:fact [:f3]]]}
                    {:stage/id :proposed :guards [[:fact [:f0]]]}]})

(defn- reached-after [steps]
  (stage/effective-reached chain-proc (log steps) now roles))

(deftest a-stage-whose-guards-hold-waits-for-its-prerequisite
  (is (= #{:proposed} (reached-after [[:assert [:f0] :a "claude"] [:assert [:f1] :a "claude"]])))
  (is (= #{:proposed :skilled :proven}
         (reached-after [[:assert [:f0] :a "claude"] [:assert [:f1] :a "claude"] [:assert [:f3] :a "claude"]]))))

(deftest a-guard-that-holds-before-its-prerequisite-counts-once-it-arrives
  (is (= #{:proposed :skilled :proven :merged}
         (reached-after [[:assert [:f2] :a "claude"] [:assert [:f1] :a "claude"]
                         [:assert [:f3] :a "claude"] [:assert [:f0] :a "claude"]]))))

(deftest losing-a-prerequisite-withdraws-what-follows-except-a-sticky-stage
  (let [all [[:assert [:f0] :a "claude"] [:assert [:f3] :a "claude"] [:assert [:f1] :a "claude"]]]
    (is (= #{:proposed} (reached-after (conj all [:retract [:f3] :a "claude"]))))
    (is (= #{:proposed :merged}
           (reached-after (conj all [:assert [:f2] :a "claude"] [:retract [:f3] :a "claude"]))))))

(deftest a-sticky-stage-never-enters-ahead-of-its-prerequisites
  (is (= #{:proposed}
         (reached-after [[:assert [:f2] :a "claude"] [:assert [:f0] :a "claude"]
                         [:assert [:f1] :a "claude"]]))))

(def ^:private carry-proc
  {:process/id :generated :process/version 1 :process/guard-vocab 2
   :process/roles {:r {:members ["seb"]}}
   :process/stages [{:stage/id :s0 :guards []}
                    {:stage/id :s1 :guards []}
                    {:stage/id :s2 :guards [] :stage/sticky? true}
                    {:stage/id :s3 :after [:s2] :guards [[:not [:stage-reached :s0]]]}]})

(deftest a-carried-prerequisite-does-not-let-a-negating-stage-jump-its-stratum
  (let [events (log [])
        {:keys [reached sticky-ever]} (stage/evolve carry-proc events roles now)]
    (is (= #{:s2} sticky-ever))
    (is (= #{:s0 :s1 :s2} reached))
    (is (= reached (stage/effective-reached carry-proc events now roles))
        "the read of the final state agrees with the fold")))

(def ^:private lock-proc
  {:process/id :generated :process/version 1 :process/guard-vocab 2
   :process/roles {:r {:members ["seb"]}}
   :process/facts {[:f0] [:enum :a :b]}
   :process/stages [{:stage/id :open :guards []}
                    {:stage/id :locked :after [:open] :stage/sticky? true :guards [[:fact [:f0]]]}
                    {:stage/id :later :after [:locked] :guards []}
                    {:stage/id :unlocked :after [:open] :guards [[:not [:stage-reached :locked]]]}]})

(deftest a-reached-sticky-stage-reads-as-reached-to-every-guard-at-once
  (is (= #{:open :unlocked} (stage/effective-reached lock-proc (log []) now roles)))
  (is (= #{:open :locked :later}
         (stage/effective-reached lock-proc (log [[:assert [:f0] :a "seb"]]) now roles)))
  (is (= #{:open :locked :later}
         (stage/effective-reached lock-proc (log [[:assert [:f0] :a "seb"] [:retract [:f0] :a "seb"]]) now roles))
      "the lock stays, so what follows it stays and what negates it stays out"))

(defn- permutations [xs]
  (if (empty? xs)
    [[]]
    (for [x xs, more (permutations (remove #{x} xs))] (cons x more))))

(deftest a-negating-stage-sees-its-whole-earlier-stratum-in-any-list-order
  (let [stages [{:stage/id :a :guards []}
                {:stage/id :b :after [:a] :guards []}
                {:stage/id :c :after [:a] :guards []}
                {:stage/id :d :after [:b] :guards [[:not [:stage-reached :c]]]}]
        base {:process/id :generated :process/version 1 :process/guard-vocab 2
              :process/roles {:r {:members ["seb"]}}}]
    (doseq [order (permutations stages)
            :let [proc (assoc base :process/stages (vec order))]]
      (is (= #{:a :b :c} (stage/effective-reached proc (log []) now roles))
          (str "list order " (mapv :stage/id order))))))
