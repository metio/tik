;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.draw-test
  "The process picture, verified: a linear spine forks and rejoins into
  the right glyphs, guards gloss to their terse symbols, and — since a
  drawing is porcelain over an UNLINTED definition (`tik show` runs
  before any lint) — the renderer is total over hostile input: garbage
  guards, cycles, non-keyword ids, all draw something and never throw."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.draw :as draw]))

(def ^:private hypothesis
  {:process/id :hypothesis
   :process/stages
   [{:stage/id :captured :guards []}
    {:stage/id :stated :after [:captured]
     :guards [[:fact [:statement]] [:signed-by :maintainer]]}
    {:stage/id :running :after [:stated] :guards [[:fact [:experiment]]]}
    {:stage/id :validated :after [:running] :stage/sticky? true
     :guards [[:fact= [:verdict] :validated]]}
    {:stage/id :killed :after [:running] :stage/sticky? true
     :guards [[:fact= [:verdict] :killed]]}]})

(def ^:private diamond
  {:process/id :onboard
   :process/stages
   [{:stage/id :hired :guards []}
    {:stage/id :equipped :after [:hired] :guards [[:signed-by :it]]}
    {:stage/id :accounts :after [:hired] :guards [[:signed-by :it]]}
    {:stage/id :ready :after [:equipped :accounts]
     :guards [[:fact [:buddy]]]}]})

(deftest a_linear_spine_then_a_fork
  (let [lines (draw/process hypothesis)
        text (str/join "\n" lines)]
    (testing "roots carry ●, the spine ▼, sticky leaves ★"
      (is (str/starts-with? (first lines) "● captured"))
      (is (some #(str/starts-with? % "▼ stated") lines))
      (is (some #(re-find #"validated ★" %) lines)))
    (testing "the terminal fork branches with ├─▶ and └─▶"
      (is (some #(str/starts-with? % "├─▶ validated") lines))
      (is (some #(str/starts-with? % "└─▶ killed") lines)))
    (testing "guards gloss tersely"
      (is (re-find #"statement · ✎maintainer" text))
      (is (re-find #"verdict = validated" text)))))

(deftest a_join_is_drawn_once_and_stubbed_from_every_branch
  (let [lines (draw/process diamond)
        text (str/join "\n" lines)]
    (testing "ready's full node is drawn exactly once, under its deepest parent"
      (is (= 1 (count (filter #(re-find #"▼ ready" %) lines)))))
    (testing "and that node names every input as a join"
      (is (re-find #"ready.*⋈ after equipped, accounts" text)))
    (testing "the non-primary branch carries a dashed stub to the join"
      (is (some #(re-find #"┈▶ ready" %) lines)))
    (testing "its two inputs both appear, forked under hired"
      (is (some #(str/starts-with? % "├─▶ equipped") lines))
      (is (some #(str/starts-with? % "└─▶ accounts") lines)))))

(deftest empty_and_stageless_definitions_draw_nothing
  (is (= [] (draw/process {})))
  (is (= [] (draw/process {:process/stages []}))))

(deftest drawing_survives_pathological_size
  ;; `tik show` draws an UNLINTED definition, and depths/walk recurse per
  ;; chain length while the guard gloss recurses per :and/:or/:not nesting
  ;; — both stack bombs without a bound. A flat 50k-stage vector even
  ;; clears check-nesting (it is not deeply nested), so the cap must live
  ;; in the renderer.
  (testing "a deeply nested guard tree glosses to an ellipsis, never overflows"
    (let [deep (reduce (fn [g _] [:not g]) [:fact [:x]] (range 100000))
          lines (draw/process {:process/stages [{:stage/id :a :guards [deep]}]})]
      (is (every? string? lines))
      (is (some #(str/includes? % "…") lines))))
  (testing "a very long stage chain draws to the cap with a note, never overflows"
    (let [chain (mapv (fn [i]
                        (cond-> {:stage/id (keyword (str "s" i))}
                          (pos? i) (assoc :after [(keyword (str "s" (dec i)))])))
                      (range 50000))
          lines (draw/process {:process/stages chain})]
      (is (every? string? lines))
      (is (re-find #"50000 stages" (first lines))))))

;; ------------------------------------------- totality over hostile input

(def ^:private gen-hostile-def
  (gen/let [stages (gen/vector
                    (gen/let [id (gen/one-of [gen/keyword gen/small-integer])
                              after (gen/vector (gen/one-of [gen/keyword gen/small-integer]) 0 3)
                              guards (gen/vector gen/any-equatable 0 3)]
                      {:stage/id id :after after :guards guards})
                    0 6)]
    {:process/id :fuzz :process/stages stages}))

(defspec drawing_is_total_over_any_definition 200
  ;; `tik show` draws a definition that has NOT been linted, so a garbage
  ;; guard, a cycle, a dangling :after, or a non-keyword id must all
  ;; produce a seq of strings and never throw — a picture, or nothing.
  (prop/for-all [proc (gen/one-of [gen-hostile-def gen/any-equatable])]
    (let [lines (draw/process (if (map? proc) proc {:process/stages proc}))]
      (and (sequential? lines) (every? string? lines)))))
