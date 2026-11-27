;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.lean-oracle-test
  "The Clojure half of the differential oracle against the Lean model: how
  cases are encoded, which histories go to derive mode, and that the
  comparison reports every kind of disagreement. The half that needs the
  model's executable runs in `bb lean`."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.lean-oracle :as oracle]
            [tik.process :as process]
            [tik.reduce :as red])
  (:import (java.time Instant)))

(def ^:private tid #uuid "018f2f6e-7c1a-7000-8000-0000000000ad")
(def ^:private ^Instant t0 (Instant/parse "2026-11-27T10:00:00Z"))
(def ^:private now (Instant/parse "2026-11-27T12:00:00Z"))

(def ^:private proc
  {:process/id :p :process/version 1 :process/guard-vocab 2
   :process/roles {:r {:members ["seb"]}}
   :process/facts {[:f] [:enum :a :b]}
   :process/stages [{:stage/id :a :guards [[:fact [:f]]]}
                    {:stage/id :b :after [:a] :stage/sticky? true
                     :guards [[:or [:fact= [:f] :a] [:not [:stage-reached :c]]]]}
                    {:stage/id :c :guards [[:signed-by :r [:f]] [:different-person [:f] [:g]]]}]})

(defn- history [& steps]
  (apply event/chain
         (fn [_] (event/create-ticket {:ticket tid :actor "seb" :at t0 :title "t" :process :p}))
         (map-indexed
          (fn [i [op path value]]
            (let [at (.plusSeconds t0 (* 60 (inc i)))]
              (fn [parents]
                (let [spec {:ticket tid :actor "seb" :at at :parents parents :path path}]
                  (case op
                    :assert (event/assert-fact (assoc spec :value value))
                    :retract (event/retract-fact spec)
                    :dispute (event/dispute-fact (assoc spec :reason "no")))))))
          steps)))

(def ^:private roles (process/resolve-roles (:process/roles proc) {}))

(deftest guards-encode-as-canonical-strings
  (is (= ["or" ["fact=" "[:f]" ":a"] ["not" ["stage-reached" ":c"]]]
         (oracle/encode-guard [:or [:fact= [:f] :a] [:not [:stage-reached :c]]] identity)))
  (is (= ["signed-by" ":r" "[:f]"] (oracle/encode-guard [:signed-by :r [:f]] identity)))
  (is (= ["and"] (oracle/encode-guard [:and] identity)) "an empty :and stays empty"))

(deftest a-leaf-outside-the-basis-goes-to-the-replacement
  (is (= ["not" ["const" true]]
         (oracle/encode-guard [:not [:artifact "doc"]] (fn [_] ["const" true]))))
  (is (oracle/fully-modelled? [:or [:fact [:f]] [:not [:stage-reached :a]]]))
  (is (not (oracle/fully-modelled? [:and [:fact [:f]] [:elapsed-since :ticket/create "PT1H"]]))))

(deftest a-fixpoint-case-replaces-unmodelled-leaves-by-the-kernels-verdict
  (let [p (assoc-in proc [:process/stages 0 :guards] [[:artifact "doc"]])
        evs (history [:assert [:f] :a])
        st (red/ticket-state evs)
        {c :case} (oracle/fixpoint-case "x" p st now roles #{})]
    (is (= [["const" false]] (get-in c ["process" "stages" 0 "guards"])))
    (is (= [{"path" "[:f]" "value" ":a" "by" "\"seb\"" "valid" true}] (get c "facts")))))

(deftest a-value-the-schema-refuses-is-present-but-not-valid
  (let [evs (history [:assert [:f] "x"])
        {c :case} (oracle/fixpoint-case "x" proc (red/ticket-state evs) now roles #{})
        {d :case} (oracle/derive-case "y" proc evs now roles)]
    (is (false? (get-in c ["facts" 0 "valid"])))
    (is (false? (get (last (get d "events")) "valid")))))

(deftest events-encode-with-nanosecond-instants
  (let [evs (history [:assert [:f] :a] [:retract [:f]])
        encoded (mapv #(oracle/encode-event proc %) evs)]
    (is (= ["other" "assert" "retract"] (mapv #(get % "kind") encoded)))
    (is (= (+ (* 1000000000N (.getEpochSecond ^Instant t0)) 60000000000N)
           (get-in encoded [1 "at"])))
    (is (= (:event/id (second evs)) (get-in encoded [1 "id"])))))

(deftest only-dispute-free-modelled-histories-go-to-derive-mode
  (is (oracle/derive-eligible? proc (history [:assert [:f] :a] [:retract [:f]])))
  (is (not (oracle/derive-eligible? proc (history [:assert [:f] :a] [:dispute [:f]]))))
  (is (not (oracle/derive-eligible?
            (assoc-in proc [:process/stages 0 :guards] [[:artifact "doc"]])
            (history [:assert [:f] :a]))))
  (testing "two concurrent writes that disagree are a conflict"
    (let [[create] (history)
          w (fn [v at] (event/assert-fact {:ticket tid :actor "seb" :at at
                                           :parents #{(:event/id create)} :path [:f] :value v}))]
      (is (not (oracle/derive-eligible?
                proc [create (w :a (.plusSeconds t0 60)) (w :b (.plusSeconds t0 120))]))))))

(deftest a-case-survives-the-json-round-trip
  (let [evs (history [:assert [:f] :a])
        cases (oracle/cases-for "rt" proc evs now roles (reverse evs) #{:c})]
    (is (= ["rt/fixpoint" "rt/fixpoint+base" "rt/derive"] (mapv #(get-in % [:case "id"]) cases)))
    (doseq [{c :case} cases]
      (is (= c (json/parse-string (json/generate-string c)))))
    (is (empty? (oracle/echo-mismatches cases (map #(json/generate-string (:case %)) cases))))
    (is (= 1 (count (oracle/echo-mismatches
                     (take 1 cases)
                     [(json/generate-string (assoc (:case (first cases)) "base" []))])))
        "a model that drops the carry on parse is caught")))

(defn- answer [id reached]
  (json/generate-string {"id" id "reached" reached "stratified" true}))

(deftest the-comparison-reports-every-disagreement
  (let [cases [{:case {"id" "one"} :expected #{:a :b}}
               {:case {"id" "two"} :expected #{}}]]
    (is (empty? (oracle/compare-results cases [(answer "one" [":b" ":a"]) (answer "two" [])])))
    (is (= [{:id "one" :kernel [":a" ":b"] :model [":a"] :missing [":b"] :extra []}]
           (oracle/compare-results cases [(answer "one" [":a"]) (answer "two" [])])))
    (is (= [":c"] (:extra (first (oracle/compare-results
                                  cases [(answer "one" [":a" ":b"]) (answer "two" [":c"])])))))
    (is (= 1 (count (oracle/compare-results cases [(answer "one" [":a" ":b"]) (answer "zwei" [])])))
        "an answer for another case is a disagreement")
    (is (thrown? clojure.lang.ExceptionInfo
                 (oracle/compare-results cases [(answer "one" [":a" ":b"])]))
        "a missing answer is an error, never a pass")))
