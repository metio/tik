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
            [malli.error :as me]
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

;; ---------------------------------------------- schemas inside a definition

(def eval-capable-schemas
  "The malli schemas that turn definition DATA into CODE. Both compile a
  child through `m/eval`, which on a runtime carrying sci evaluates a
  string or s-expression written in the definition — so `[:fn \"(fn [_]
  …)\"]` would run whatever it says, inside derivation."
  #{:fn :multi})

(def schema-registry
  "The registry every schema in a definition compiles under: malli's data
  schemas, minus the ones that can call something.

  A definition travels. It is pinned by hash, shipped in evidence
  bundles, and re-derived by whoever holds one — so its schemas are read
  from material a stranger produced. A schema that can invoke a function
  is not data, it is a program, and evaluating one would make derivation
  neither offline nor reproducible even where nobody meant harm. The
  kernel therefore cannot compile one at all, rather than leaving every
  reader to have linted first."
  (apply dissoc (m/default-schemas) eval-capable-schemas))

(def ^:private schema-opts {:registry schema-registry})

(defn schema-compiles?
  "Does `schema` compile as data? False for a schema naming something
  outside the data-only registry — which is what `tik lint` reports and
  what derivation refuses to evaluate."
  [schema]
  (try (m/schema schema schema-opts) true
       (catch #?(:clj Exception :cljs :default) _ false)))

(defn schema-holds?
  "Does `value` satisfy `schema`, compiled as data? `::unsupported` when
  the schema does not compile — a distinct answer from `false`, because
  'this schema may not be evaluated' and 'this value does not match' are
  different things to report."
  [schema value]
  (try (m/validate schema value schema-opts)
       (catch #?(:clj Exception :cljs :default) _ ::unsupported)))

(defn schema-errors
  "Humanized errors for a schema that compiles, else nil."
  [schema value]
  (try (me/humanize (m/explain schema value schema-opts))
       (catch #?(:clj Exception :cljs :default) _ nil)))

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

#_{:splint/disable [naming/lisp-case]} ; schema names are PascalCase, as
                                        ; ProcessDef/Stage/Guard above
(def RoleBindings
  "The store's role register: role -> members, the same shape a
  definition declares."
  [:map-of :keyword [:map [:members [:vector :string]]]])

(def valid-role-bindings? (m/validator RoleBindings))

(defn resolve-roles
  "The role memberships a derivation runs against: the store's register
  overriding the pinned definition, role by role.

  A definition's `:process/roles` says which roles the process HAS and
  supplies a starting membership; it cannot be the authority on who is
  in one. Membership lives inside the hashed region, so with the
  definition as the only source a hire or a departure is a definition
  version bump — versions stop meaning the rules changed — and until
  every in-flight ticket is individually migrated a departed member
  still holds signing authority while a new one holds none. Who is in a
  role is organisation state, not a rule of the process, and the kernel
  already takes it as its own argument, so this resolves outside the
  pin.

  A role the register does not mention keeps the definition's members,
  so a store with no register derives exactly as before, and a
  definition's roles stay the default rather than a fiction to be
  restated. Overriding is whole-role: the register's entry replaces the
  definition's members for that role rather than merging with them, so
  a departure is expressible.

  Resolution takes no `now`. Time-aware validity — 'was this actor a
  member on March 1' — is ADR 0010's deferred concretion (PLAN §19) and
  wants signed bindings rather than a mutable file; until it lands, a
  re-derivation at a past instant reads today's membership. That is the
  honest consequence of membership being present-tense trust input, and
  it is the same class of input as the definition file itself (ADR
  0015) — the difference is that it is now an explicit input instead of
  one smuggled inside a content hash."
  [pinned register]
  (merge (or pinned {}) (or register {})))

(defn roles-gating
  "role -> {:members [...] :stages [stage-ids]} for one definition —
  who gates what: every role with the stages waiting on its signature.
  Roles declared but gating nothing still appear (they may satisfy
  :role/unsatisfied facts without a :signed-by spelling).

  The 2-arity takes the memberships derivation actually runs against
  (`resolve-roles`), so an admin view reports who can sign TODAY rather
  than who the definition was pinned with."
  ([process] (roles-gating process (:process/roles process)))
  ([{:process/keys [stages]} roles]
   (let [gated (reduce (fn [acc {:stage/keys [id] :keys [guards]}]
                         (reduce (fn [m role] (update m role (fnil conj []) id))
                                 acc
                                 (distinct (mapcat signing-roles guards))))
                       {}
                       stages)]
     (into {}
           (for [role (distinct (concat (keys roles) (keys gated)))]
             [role {:members (get-in roles [role :members] [])
                    :stages (get gated role [])}])))))

