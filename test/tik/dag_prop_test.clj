;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.dag-prop-test
  "ADR 0004's structural claims on arbitrary (forking) DAGs: one root,
  heads commit to the entire history, and removal of a referenced event
  is detectable, never silent."
  (:require [clojure.set :as set]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.gen-events :as ge]))

(defn- build-dag
  "Random fork/join shape: each event picks one arbitrary earlier event as
  parent, plus (on odd seconds) the latest event — yielding chains, forks,
  and merges."
  [picks]
  (reduce
   (fn [evs [sec pick]]
     (let [ids (mapv :event/id evs)
           parents (cond-> #{(nth ids (mod pick (count ids)))}
                     (odd? sec) (conj (peek ids)))]
       (conj evs (event/assert-fact {:ticket ge/tid :actor "seb"
                                     :at (ge/at-sec sec) :parents parents
                                     :path [:x] :value :y}))))
   [(event/create-ticket {:ticket ge/tid :actor "seb" :at ge/base
                          :title "dag" :process :support-request})]
   picks))

(def gen-dag
  (gen/fmap build-dag
            (gen/vector (gen/tuple (gen/choose 0 600) gen/nat) 0 12)))

(defn- ancestry
  "Transitive parent closure from a set of event ids."
  [events ids]
  (let [by-id (into {} (map (juxt :event/id identity)) events)]
    (loop [frontier (set ids) seen #{}]
      (if (empty? frontier)
        seen
        (let [nxt (into #{} (mapcat #(:event/parents (by-id %))) frontier)]
          (recur (set/difference nxt seen) (into seen frontier)))))))

(defspec complete-dags-have-one-root-and-no-missing-parents 100
  (prop/for-all [events gen-dag]
    (and (= 1 (count (dag/roots events)))
         (= :ticket/create (:event/type (first (dag/roots events))))
         (empty? (dag/missing-parents events)))))

(defspec heads-commit-to-the-entire-history 100
  ;; ADR 0004's payoff: walking ancestry from the heads reaches every
  ;; event — one head set commits to all of history
  (prop/for-all [events gen-dag]
    (= (into #{} (map :event/id) events)
       (ancestry events (dag/heads events)))))

(defspec heads-are-exactly-the-unreferenced-events 100
  (prop/for-all [events gen-dag]
    (let [referenced (into #{} (mapcat :event/parents) events)
          ids (into #{} (map :event/id) events)]
      (= (dag/heads events) (set/difference ids referenced)))))

(defspec removing-a-referenced-event-is-detectable 60
  (prop/for-all [events gen-dag
                 pick gen/nat]
    (let [referenced (into #{} (mapcat :event/parents) events)
          victims (filterv #(referenced (:event/id %)) events)]
      (or (empty? victims)
          (let [victim (nth victims (mod pick (count victims)))
                remaining (remove #(= (:event/id victim) (:event/id %))
                                  events)]
            (contains? (dag/missing-parents remaining)
                       (:event/id victim)))))))
