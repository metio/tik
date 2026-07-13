;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.process
  "Process definitions: schema, content hashing, and the guard vocabulary.

  Definitions are pure EDN, reviewed in merge requests, shipped GitOps-
  style. Tickets pin the definition's CONTENT HASH (ADR 0002/0006), so
  `verify` never trusts file naming — process-hash is the identity, the
  version number is a human label.

  The linter that enforces what the kernel cannot (the closed guard
  basis, facts over flags, graph sanity, stratified negation) lives in
  tik.lint, a leaf over this namespace's schema and guard vocabulary."
  (:require [malli.core :as m]
            [tik.canonical :as canonical]))

(def Guard
  [:and vector? [:cat :keyword [:* :any]]])

(def Stage
  [:map
   [:stage/id :keyword]
   [:after {:optional true} [:vector :keyword]]
   [:guards {:optional true} [:vector Guard]]
   [:stage/sticky? {:optional true} :boolean]
   [:hint {:optional true} :string]
   [:effort {:optional true} :string]        ; optional ISO-8601, never inferred
   [:sla {:optional true} [:map-of :keyword :any]]])

#_{:splint/disable [naming/lisp-case]} ; schema names are PascalCase (Guard,
                                        ; Stage); `Process` itself would clash
                                        ; with java.lang.Process
(def ProcessDef
  [:map
   [:process/id :keyword]
   [:process/version pos-int?]
   [:process/guard-vocab {:optional true} pos-int?]
   [:lint {:optional true} [:map-of :keyword :keyword]]
   [:process/roles {:optional true}
    [:map-of :keyword [:map [:members [:vector :string]]]]]
   [:process/facts {:optional true} [:map-of [:vector :keyword] :any]]
   [:process/stages [:vector Stage]]])

(def valid? (m/validator ProcessDef))
(def explain-process (m/explainer ProcessDef))

(defn process-hash
  "Content address of the definition — the identity tickets pin."
  [process]
  (canonical/content-address process))

(def guard-operators-v1
  #{:fact :fact= :artifact :signed-by :stage-reached :elapsed-since
    :and :or :not :malli})

(def guard-operators
  "Guard vocabulary v2: twelve operators, additive over v1 (old
  definitions evaluate unchanged forever — the runtime is total over
  both; the LINT enforces that a definition uses only what its declared
  :process/guard-vocab admits). :attested-within and :different-person
  arrived in v2; :fact= was briefly v6 sugar and was restored by
  dogfood evidence (see the guard namespace docstring)."
  (into guard-operators-v1 #{:attested-within :different-person}))

(defn signing-roles
  "Roles whose signature a guard tree demands, via :signed-by."
  [guard]
  (if-not (vector? guard)
    []
    (case (first guard)
      :signed-by [(second guard)]
      (:and :or) (into [] (mapcat signing-roles) (rest guard))
      :not (signing-roles (second guard))
      [])))

(defn roles-gating
  "role -> {:members [...] :stages [stage-ids]} for one definition —
  who gates what: every role with the stages waiting on its signature.
  Roles declared but gating nothing still appear (they may satisfy
  :role/unsatisfied facts without a :signed-by spelling)."
  [{:process/keys [roles stages]}]
  (let [gated (reduce (fn [acc {:stage/keys [id] :keys [guards]}]
                        (reduce (fn [m role] (update m role (fnil conj []) id))
                                acc
                                (distinct (mapcat signing-roles guards))))
                      {}
                      stages)]
    (into {}
          (for [role (distinct (concat (keys roles) (keys gated)))]
            [role {:members (get-in roles [role :members] [])
                   :stages (get gated role [])}]))))

