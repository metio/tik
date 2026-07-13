;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.template
  "Declarative process templates — parameterization as DATA, never code.

  A template is inert EDN: a `:tik/params` malli schema declaring the
  inputs, and a `:tik/template` body — a process definition with HOLES.
  `expand` is a FIXED, TOTAL, PURE substitution that is part of tik (not
  the template): it validates the params against the schema, then turns
  (template, params) into a canonical process definition. The template
  is convenience; the expanded, linted, hash-pinned EDN is authoritative
  (§19: compilation is one-way; the compiled form is the source of
  truth). Nothing here executes template-supplied code, so a template is
  as safe to share and verify as a definition — the whole point.

  The language is CLOSED — exactly two markers, so it can never grow into
  a Turing-complete evaluator (the thing §19 rejects as 'the workflow
  engine wearing our clothes'):

    [:tik/param <key>]         -> the parameter's value (substitution),
                                  valid anywhere a value appears.
    [:tik/when <key> <elem>]   -> <elem> spliced into the ENCLOSING
                                  vector iff the boolean param is truthy,
                                  else omitted (feature toggles).

  `:tik/param` / `:tik/when` are namespaced so they can never collide
  with the closed guard basis or any real definition data, and they are
  all expanded away — the produced definition contains no markers."
  (:require [malli.core :as m]
            [tik.canonical :as canonical]))

(defn- param-marker? [x]
  (and (vector? x) (= 2 (count x)) (= :tik/param (first x))))

(defn- when-marker? [x]
  (and (vector? x) (= 3 (count x)) (= :tik/when (first x))))

(defn- marker-head? [x]
  (and (vector? x) (#{:tik/param :tik/when} (first x))))

(defn- reject-malformed-marker!
  "The markers are namespaced so a `:tik/param` / `:tik/when` head never
  collides with real definition data — so a vector with that head that
  is NOT a valid marker is a typo'd marker, not data. Reject it rather
  than bake it in: a surviving `[:tik/when :j]` (element dropped) is a
  silently half-expanded definition, worse than a clean failure."
  [node]
  (when (and (marker-head? node)
             (not (param-marker? node))
             (not (when-marker? node)))
    (throw (ex-info "malformed template marker"
                    {:reason :template/malformed :node node}))))

(defn- expand-node
  [params depth node]
  ;; expand-node recurses per nesting level, so an unbounded template
  ;; body is a stack bomb (StackOverflowError — an Error no fail-well
  ;; guard catches). A template is adopted from a shared library, so a
  ;; hostile one must fail as cleanly here as a deep definition does at
  ;; canonical/emit — the whole "as safe to share as a definition" claim
  ;; rests on it. Reuse the kernel's bound; no honest template nests deep.
  (when (> depth canonical/max-depth)
    (throw (ex-info "template nesting exceeds the maximum depth"
                    {:reason :template/too-deep :max-depth canonical/max-depth})))
  (reject-malformed-marker! node)
  (let [deeper (inc depth)]
    (cond
      (param-marker? node)
      (let [k (second node)]
        (when-not (contains? params k)
          (throw (ex-info "template references an undeclared parameter"
                          {:reason :template/unknown-param :param k})))
        (get params k))

      ;; a when-marker is only meaningful as an ELEMENT of a vector (it is
      ;; intercepted there before recursion); standalone is malformed
      (when-marker? node)
      (throw (ex-info ":tik/when is only valid as an element of a vector"
                      {:reason :template/malformed :node node}))

      (vector? node)
      (into []
            (mapcat (fn [el]
                      (if (when-marker? el)
                        (let [[_ k inner] el]
                          (if (get params k) [(expand-node params deeper inner)] []))
                        [(expand-node params deeper el)])))
            node)

      (map? node)
      (into (empty node)
            (map (fn [[k v]] [(expand-node params deeper k)
                              (expand-node params deeper v)]))
            node)

      (set? node)
      (into #{} (map #(expand-node params deeper %)) node)

      :else node)))

(defn template?
  "Is `x` a template (carries a :tik/template body)?"
  [x]
  (and (map? x) (contains? x :tik/template)))

(defn expand
  "Validate `params` against the template's `:tik/params` schema, then
  expand `:tik/template` to a canonical process definition. Throws
  ex-info on a missing body, a param-spec violation, or a reference to
  an undeclared parameter — never returns a half-expanded definition.
  The result still faces `tik lint` like any hand-written definition."
  [template params]
  (when-not (template? template)
    (throw (ex-info "not a template: missing :tik/template"
                    {:reason :template/malformed})))
  (when-let [schema (:tik/params template)]
    ;; a hand-set template could carry a garbage :tik/params — malli must
    ;; not leak a raw error out of the fail-well contract
    (let [valid? (try (m/validate schema params)
                      (catch #?(:clj Exception :cljs :default) e
                        (throw (ex-info "template :tik/params is not a valid schema"
                                        {:reason :template/bad-schema} e))))]
      (when-not valid?
        (throw (ex-info "template parameters do not match :tik/params"
                        {:reason :template/bad-params
                         :errors (try (m/explain schema params) (catch #?(:clj Exception :cljs :default) _ nil))})))))
  (let [result (expand-node params 0 (:tik/template template))]
    ;; the produced definition contains NO markers (docstring invariant) —
    ;; a param VALUE that is itself a marker vector (e.g. {:k [:tik/param
    ;; :other]}) would otherwise be substituted verbatim and survive lint
    ;; as authoritative. Reject a smuggled marker rather than bake it in.
    (when-let [m (some #(when (marker-head? %) %) (tree-seq coll? seq result))]
      (throw (ex-info "a parameter value smuggled a template marker into the
                       expanded definition"
                      {:reason :template/marker-survived :node m})))
    result))
