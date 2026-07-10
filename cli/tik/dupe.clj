;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.dupe
  "The duplicate radar: a similarity LENS over what tickets say about
  themselves (title, description, fact values). Ticket identity is
  content-addressed and sacred; lookalikes are a porcelain judgment —
  the radar warns, a human decides, and a decided duplicate is just a
  fact ([:duplicate-of] = <id>) like everything else. No new state,
  nothing stored, thresholds are the caller's business."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private stopwords
  #{"the" "a" "an" "and" "or" "of" "to" "in" "for" "with" "on" "is"
    "are" "be" "as" "at" "by" "it" "its" "this" "that" "from" "not"})

(defn tokens
  "Lowercased word set of a string, stopwords and single letters out."
  [s]
  (into #{}
        (comp (map str/lower-case)
              (remove stopwords)
              (filter #(> (count %) 1)))
        (re-seq #"[\p{L}\p{N}]+" (or s ""))))

(defn similarity
  "Jaccard over token sets: 0.0 (nothing shared) to 1.0 (same words)."
  [a b]
  (let [ta (tokens a) tb (tokens b)
        union (count (set/union ta tb))]
    (if (zero? union)
      0.0
      (double (/ (count (set/intersection ta tb)) union)))))

(defn haystack
  "What a ticket says about itself, as one string: title, description
  and fact values — the same self-description ls --search reads."
  [{:keys [title facts]}]
  (str/join " " (cons (or title "")
                      (map (comp str :value) (vals (or facts {}))))))

(defn lookalikes
  "Pairs of rows {:id :text} scoring at or above threshold, best first.
  O(n²) by design: the radar runs over OPEN tickets, and a store with
  enough open tickets for n² to hurt has a bigger problem than
  duplicates."
  [rows threshold]
  (->> (for [[a & more] (take-while seq (iterate rest rows))
             b more
             :let [score (similarity (:text a) (:text b))]
             :when (>= score threshold)]
         {:a (:id a) :b (:id b) :score score})
       (sort-by (comp - :score))
       vec))

(defn radar
  "Rows similar to one candidate text, best first — the create-time
  warning: 'this looks like…'."
  [candidate-text rows threshold]
  (->> (for [r rows
             :let [score (similarity candidate-text (:text r))]
             :when (>= score threshold)]
         (assoc r :score score))
       (sort-by (comp - :score))
       vec))
