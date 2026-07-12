;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.select
  "One selection language for the listing lenses. `ls`, `query`, and
  `search` all answer the same question — which tickets match a
  predicate — so they share one grammar instead of three bespoke flag
  sets. A selector is whitespace-separated TERMS, all ANDed, each
  optionally prefixed `not`:

    stage=:blocked                a current stage is :blocked
    fact:severity                 fact [:severity] is present
    fact:severity=:high           …present and equal (keyword/string
                                  spellings of a name match)
    actor=seb                     some event authored by seb
    disputed | conflicted         some fact is disputed / conflicted
    unsigned                      some event carries no signature
    derived-from=<hash>           some event derives from <hash>
    ~text  (or a bare word)       the ticket's haystack contains it

  e.g.  stage=:blocked and fact:severity=:high and not disputed
  (`and` is optional noise — terms are always ANDed; `or` is not in the
  grammar yet, a deliberate v1 bound).

  `compile` is pure and TOTAL: it returns a predicate over a pre-baked
  ROW, or throws an ex-info {:reason :select/bad-term} a caller renders
  as a usage error. The row carries only plain data — the I/O-bearing
  facts (unsigned?, disputed?) are computed by the caller before the
  predicate runs, so this namespace stays pure and testable:

    {:current      #{stage-keywords}
     :facts        {fact-path value}      ; effective values
     :actors       #{actor-strings}
     :derived-from #{hash-strings}
     :haystack     \"lowercased searchable text\"
     :disputed?    bool
     :conflicted?  bool
     :unsigned?    bool}"
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]))

(defn- name= [a b]
  ;; keyword/string spellings of one name match: :jaas == \"jaas\"
  (= (str/replace (str a) #"^:" "")
     (str/replace (str b) #"^:" "")))

(defn- parse-scalar
  "A term's right-hand value: a :keyword stays a keyword, everything
  else is its literal string (fact values, actor names, hashes)."
  [s]
  (if (str/starts-with? s ":") (keyword (subs s 1)) s))

(defn- fact-path [s] (mapv keyword (str/split s #"\.")))

(defn- atom-pred
  "Compile ONE term (no leading `not`) to a row predicate, or throw."
  [term]
  (let [bad! #(throw (ex-info (str "unknown selector term: " term)
                              {:reason :select/bad-term :term term}))]
    (cond
      (= term "disputed")   :disputed?
      (= term "conflicted") :conflicted?
      (= term "unsigned")   :unsigned?

      (str/starts-with? term "~")
      (let [needle (str/lower-case (subs term 1))]
        (fn [row] (str/includes? (:haystack row "") needle)))

      (str/starts-with? term "fact:")
      (let [[p v] (str/split (subs term 5) #"=" 2)
            path (fact-path p)]
        (if v
          (let [want (parse-scalar v)]
            (fn [row] (let [fv (get (:facts row) path ::absent)]
                        (and (not= fv ::absent)
                             (or (= want fv) (name= want fv))))))
          (fn [row] (contains? (:facts row) path))))

      (str/includes? term "=")
      (let [[k v] (str/split term #"=" 2)]
        (case k
          "stage" (let [want (parse-scalar v)]
                    (fn [row] (boolean (some #(name= want %) (:current row)))))
          "actor" (fn [row] (contains? (:actors row) v))
          "derived-from" (fn [row] (contains? (:derived-from row) v))
          (bad!)))

      ;; a bare word is a haystack search — the ergonomic default
      (re-matches #"[^\s:=~]+" term)
      (let [needle (str/lower-case term)]
        (fn [row] (str/includes? (:haystack row "") needle)))

      :else (bad!))))

(defn- terms
  "Split an expression into [negated? term] pairs, dropping the `and`
  noise word. A trailing `not` with no term is a bad selector."
  [expr]
  (loop [ts (if (str/blank? (str expr))
              []
              (remove #(= "and" %) (str/split (str/trim (str expr)) #"\s+")))
         out []]
    (cond
      (empty? ts) out
      (= "not" (first ts))
      (if-let [t (second ts)]
        (recur (drop 2 ts) (conj out [true t]))
        (throw (ex-info "selector ends with `not`"
                        {:reason :select/bad-term :term "not"})))
      :else (recur (rest ts) (conj out [false (first ts)])))))

(defn compile
  "A selector expression -> a predicate over a pre-baked row (see ns
  doc). Total: throws ex-info {:reason :select/bad-term} on a term it
  cannot parse; the empty selector matches everything."
  [expr]
  (let [preds (mapv (fn [[negated? term]]
                      (let [p (atom-pred term)]
                        (if negated? (complement p) p)))
                    (terms expr))]
    (fn [row] (every? #(% row) preds))))
