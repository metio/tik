;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.draw
  "Draw a process definition as a vertical ASCII stage graph — pure
  porcelain, f(definition) -> lines. A process IS a DAG of stages
  (`:after` edges) with a guard tree on each node, so the picture is a
  derivation like every other lens; nothing here is stored. The kernel
  speaks EDN — the glyphs and the terse guard prose live here, on the
  porcelain side of the seam.

  Layout: stages flow top-to-bottom. A single child continues the same
  lane (`│`/`▼`); a stage with several children forks (`├─▶`/`└─▶`), each
  branch keeping its own lane. A JOIN — a stage reached from more than
  one parent (a diamond) — is drawn once under its deepest parent and its
  line carries `⋈ after a, b` naming every input, so a merge reads
  honestly without ASCII edges crossing. Totality is a contract: a
  hostile or malformed definition draws *something* and never throws (the
  guard gloss and every name go through str-safe fallbacks)."
  (:require [clojure.string :as str]))

(defn- nm
  "A name that never cast-crashes: keyword/symbol/string -> name, else
  str. Stage ids, roles, and fact-path elements are not kernel-
  constrained to be names, so a drawing must tolerate any value."
  [x]
  (if (or (keyword? x) (symbol? x) (string? x)) (name x) (str x)))

(defn- path-str [p]
  (if (sequential? p) (str/join "." (map nm p)) (nm p)))

(defn- gloss-val [v]
  (cond (keyword? v) (nm v)
        (string? v) (pr-str v)
        :else (str v)))

(defn- gloss-guard
  "One guard as a terse symbol string. The closed operator basis is
  enumerated; an unknown or malformed guard falls back to pr-str rather
  than throwing — the drawing stays total over any definition."
  [g]
  (if-not (and (vector? g) (seq g))
    (pr-str g)
    (case (first g)
      :fact             (path-str (second g))
      :fact=            (str (path-str (second g)) " = " (gloss-val (nth g 2 nil)))
      :signed-by        (str "✎" (nm (second g)))
      :artifact         (str "⧉" (path-str (second g)))
      :stage-reached    (str "⤳" (nm (second g)))
      :elapsed-since    (str "⏱" (nm (nth g 2 nil)))
      :attested-within  (str "⊙" (path-str (second g)))
      :different-person (str "⚖ " (str/join " ≠ " (map path-str (rest g))))
      :and              (str/join " · " (map gloss-guard (rest g)))
      :or               (str "(" (str/join " | " (map gloss-guard (rest g))) ")")
      :not              (str "¬" (gloss-guard (second g)))
      :malli            "⊨schema"
      (pr-str g))))

(defn- stage-gloss [stage join-parents]
  (let [guards (str/join " · " (map gloss-guard (:guards stage)))
        merge  (when (seq join-parents)
                 (str "⋈ after " (str/join ", " (map nm join-parents))))]
    (str/join "   " (remove str/blank? [merge guards]))))

(defn- stage-parents
  "The :after ids of `stage` that are real stages in the definition —
  a dangling reference is dropped so a malformed graph still draws."
  [stage ids]
  (filterv ids (:after stage)))

(defn- depths
  "id -> longest-path length from a root, computed cycle-safely so a
  malformed cyclic definition (which lint rejects, but draw must survive)
  terminates. A node on a cycle resolves to the depth of its already-seen
  ancestors, never recurses forever."
  [ids parents-of]
  (let [memo (atom {})]
    (letfn [(d [id seen]
              (or (@memo id)
                  (if (contains? seen id)
                    0
                    (let [ps (parents-of id)
                          v (if (empty? ps)
                              0
                              (inc (apply max (map #(d % (conj seen id)) ps))))]
                      (swap! memo assoc id v)
                      v))))]
      (into {} (map (fn [id] [id (d id #{})])) ids))))

(defn process
  "The stage graph of process definition `proc` as a seq of strings.
  Empty when there are no stages. Pure and total."
  [proc]
  (let [raw-stages (:process/stages proc)
        stages (if (sequential? raw-stages) (vec raw-stages) [])
        by-id (into {} (map (juxt :stage/id identity)) stages)
        ids (set (map :stage/id stages))
        order (into {} (map-indexed (fn [i s] [(:stage/id s) i])) stages)
        parents-of (fn [id] (stage-parents (by-id id) ids))
        depth (depths ids parents-of)
        ;; a join child is drawn under its PRIMARY parent — the deepest,
        ;; ties broken by definition order — so every other input is
        ;; already above it and the `⋈ after …` annotation reads true
        primary (fn [id]
                  (when-let [ps (seq (parents-of id))]
                    (last (sort-by (juxt depth order) ps))))
        child-map (reduce (fn [m s]
                            (let [id (:stage/id s)
                                  p (primary id)]
                              (cond-> m p (update p (fnil conj []) id))))
                          {} stages)
        ;; a join child also leaves a DASHED stub (`┈▶`) under each of its
        ;; non-primary parents, so the merge is visible from every incoming
        ;; branch — not only on the join node's `⋈ after …` line
        ref-map (reduce (fn [m s]
                          (let [id (:stage/id s)
                                ps (parents-of id)
                                prim (primary id)]
                            (if (> (count ps) 1)
                              (reduce (fn [m p]
                                        (cond-> m (not= p prim)
                                                (update p (fnil conj []) id)))
                                      m ps)
                              m)))
                        {} stages)
        roots (->> stages (map :stage/id) (filter #(empty? (parents-of %))))]
    (letfn [(walk [id own-prefix connector desc-prefix]
              (let [stage (by-id id)
                    ps (parents-of id)
                    label (str (nm id) (when (:stage/sticky? stage) " ★"))
                    gloss (stage-gloss stage (when (> (count ps) 1) ps))
                    ;; real children draw their full subtree; refs are leaf
                    ;; stubs pointing at a join drawn under its primary parent
                    entries (concat (map (fn [c] {:id c}) (child-map id))
                                    (map (fn [c] {:id c :ref? true}) (ref-map id)))
                    n (count entries)]
                (concat
                 [{:left (str own-prefix connector label) :gloss gloss}]
                 (when (pos? n)
                   (concat
                    [{:sep (str desc-prefix "│")}]
                    (if (= n 1)
                      (let [e (first entries)]
                        (if (:ref? e)
                          [{:left (str desc-prefix "┈▶ " (nm (:id e))) :gloss ""}]
                          (walk (:id e) desc-prefix "▼ " desc-prefix)))
                      (mapcat (fn [i {:keys [id ref?]}]
                                (let [last? (= i (dec n))
                                      arrow (cond
                                              (and ref? last?) "└┈▶ "
                                              ref? "├┈▶ "
                                              last? "└─▶ "
                                              :else "├─▶ ")]
                                  (if ref?
                                    [{:left (str desc-prefix arrow (nm id)) :gloss ""}]
                                    (walk id desc-prefix arrow
                                          (str desc-prefix (if last? "    " "│   "))))))
                              (range) entries)))))))]
      (let [rows (mapcat (fn [r] (cons {:sep ""} (walk r "" "● " "")))
                         roots)
            rows (rest rows)                       ; drop the leading blank
            width (transduce (keep #(some-> (:left %) count)) max 0 rows)]
        (map (fn [{:keys [left gloss sep]}]
               (cond
                 (some? sep) sep
                 (str/blank? gloss) left
                 :else (str (format (str "%-" (max 1 width) "s") left)
                            "   ⊢ " gloss)))
             rows)))))
