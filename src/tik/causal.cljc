;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.causal
  "The causal view: for every reached stage, WHICH signed events its
  guards actually consume — the forensic complement to explain (which
  looks forward) and log (which is chronological). Pure derivation
  from the same inputs as every lens; nothing here is stored.

  Support reflects the CURRENT log: if a fact was superseded after the
  stage first derived, the supporting event shown is the one the
  derivation consumes NOW — ask `status --at` for the view at any
  other moment. Negations and time are supported by absence and the
  clock, not by events; they render as such rather than pretending an
  event exists."
  (:require [clojure.string :as str]
            [tik.reduce :as red]
            [tik.stage :as stage]))

(defn- fact-event [state path]
  (:event (red/fact-entry state path)))

(defn- event-ids-where
  "Ids of events matching `pred`."
  [events pred]
  (for [e events :when (pred e)] (:event/id e)))

(defn- attach-events
  "Ids of :artifact/attach events whose path sits under prefix."
  [events prefix]
  (event-ids-where events
                   (fn [e] (let [p (get-in e [:event/body :artifact/path])]
                             (and (= :artifact/attach (:event/type e))
                                  (string? p)
                                  (str/starts-with? p prefix))))))

(defn- attestation-events [events claim]
  (event-ids-where events
                   (fn [e] (and (= :attestation/add (:event/type e))
                                (= claim (get-in e [:event/body :claim]))))))

(defn support
  "[{:via <guard-ish> :events [ids] :note?}] for one guard — the
  events its satisfaction consumes. Combinators descend; :or descends
  every branch (any of them may carry the weight); :not and time
  produce a :note instead of events."
  [guard {:keys [state events] :as ctx}]
  (let [op (when (vector? guard) (first guard))]
    (case op
      (:fact :fact=) (when-let [e (fact-event state (second guard))]
                       [{:via guard :events [e]}])
      :signed-by (when-let [e (fact-event state (nth guard 2))]
                   [{:via guard :events [e]}])
      :artifact (when-let [es (seq (attach-events events (second guard)))]
                  [{:via guard :events (vec es)}])
      :attested-within (when-let [es (seq (attestation-events
                                           events (second guard)))]
                         [{:via guard :events (vec es)}])
      :different-person (into [] (mapcat #(support [:fact %] ctx))
                              [(second guard) (nth guard 2)])
      :elapsed-since [{:via guard :note "the clock, not an event"}]
      :stage-reached [{:via guard
                       :note (str "see stage " (second guard))}]
      :not [{:via guard :note "supported by absence"}]
      (:and :or) (into [] (mapcat #(support % ctx)) (rest guard))
      :malli [{:via guard :note "schema over the fact map"}]
      nil)))

(defn causal
  "[{:stage <id> :support [...]}] for every reached stage, in the
  process's stage order."
  [process events now roles]
  (let [state (red/ticket-state events)
        reached (stage/effective-reached process events now roles)
        ctx {:state state :events (red/ordered events)}]
    (vec (for [s (:process/stages process)
               :when (contains? reached (:stage/id s))]
           {:stage (:stage/id s)
            :support (vec (mapcat #(support % ctx) (:guards s [])))}))))
