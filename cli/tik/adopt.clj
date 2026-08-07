;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.adopt
  "tik adopt: bring a library process or template into this store. A plain
  definition is copied; a template (:tik/params) is filled — interactively
  by default, its own malli spec driving typed, validated prompts, or from
  --params — then expanded, linted, and written with its runbooks. The
  expanded EDN is authoritative; nothing runs the template as code."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [tik.args :refer [read-edn-file]]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [die root]]
            [tik.lint :as lint]
            [tik.process :as process]
            [tik.render :refer [print-problems]]
            [tik.sign :as sign]
            [tik.template :as template]
            [malli.core :as m])
  (:import (java.io File)))
(defn field-hint
  "A short type hint for prompting one template parameter."
  [child]
  (case (m/type child)
    :boolean "(y/N)"
    (:vector :sequential :set) "(one or more, space-separated)"
    (:int :double) "(a number)"
    :enum (str "(one of: " (str/join ", " (m/children child)) ")")
    ""))

(defn coerce-param
  "Coerce a raw string answer to a template parameter's declared type."
  [child raw]
  (let [raw (str/trim raw)]
    (case (m/type child)
      :boolean (contains? #{"y" "yes" "true" "1"} (str/lower-case raw))
      :int (parse-long raw)
      :double (parse-double raw)
      :string raw
      :keyword (keyword raw)
      (:vector :sequential) (mapv #(coerce-param (first (m/children child)) %)
                                  (remove str/blank? (str/split raw #"[\s,]+")))
      :set (set (mapv #(coerce-param (first (m/children child)) %)
                      (remove str/blank? (str/split raw #"[\s,]+"))))
      :enum (let [vs (m/children child)]
              (or (some #(when (= raw (str %)) %) vs) (keyword raw)))
      (canonical/parse raw))))

(defn template-fields
  "Prompt specs for a template's :tik/params — one per :map entry."
  [tmpl]
  (when-let [schema (:tik/params tmpl)]
    (for [[k props child] (m/children (m/schema schema))]
      {:key k :optional? (boolean (:optional props))
       :desc (:description props) :child child :hint (field-hint child)})))

(defn prompt-params
  "Interactively ask for each parameter, typed and validated by the
  template's own :tik/params spec — no hand-writing EDN. Eager (a
  reduce, not a lazy for) so the prompt/read side effects stay ordered."
  [proc-name fields]
  (println (str "\n" proc-name " needs a few choices:\n"))
  (reduce (fn [acc {:keys [key optional? desc child hint]}]
            (print (str "  " (format "%-14s" (name key))
                        (when desc (str desc "  ")) hint "\n              > "))
            (flush)
            (let [raw (or (read-line) "")]
              (if (and optional? (str/blank? raw))
                acc
                (assoc acc key (coerce-param child raw)))))
          {}
          fields))

(defn collect-params
  "Parameters for a template: from --params <file.edn>, else interactive
  prompts driven by the template's spec."
  [tmpl proc-name opts]
  (if-let [pf (:params opts)]
    (read-edn-file (io/file pf))
    (prompt-params proc-name (template-fields tmpl))))

(defn source-root
  "Where a bundle's :hint paths resolve: the store-root above a
  processes/ or templates/ file, else the file's own directory."
  ^File [^File f]
  (let [parent (.getParentFile (.getCanonicalFile f))]
    (if (#{"processes" "templates"} (.getName parent))
      (.getParentFile parent)
      parent)))

(defn adopt-runbooks!
  "Copy the runbooks a definition's stages :hint into this store, from
  the bundle's source root. Returns how many were copied."
  [definition ^File src-root dest-root]
  (let [n (atom 0)]
    (doseq [h (keep :hint (:process/stages definition))
            :let [sf (io/file src-root h) df (io/file dest-root h)]
            :when (and (.exists sf) (not (.exists df)))]
      (io/make-parents df)
      (io/copy sf df)
      (swap! n inc))
    @n))

(defn adopt-publication!
  "Bring the library's ARCHIVED definition and its publication signature
  across, so a ticket pinning this definition carries the publisher's
  word for the rules that judged it — into evidence bundles, where a
  recipient checks it with ssh-keygen and nothing of ours (ADR 0015).

  Copying the definition itself is free of consequence: it is
  content-addressed, so bytes that do not hash to the name are refused by
  every reader.

  A SIGNATURE is different, and travels only when this store can already
  verify it. `verify` fails a definition whose signature checks against
  no registered principal, so copying one for an unregistered publisher
  would hand back a store that fails its own audit — and a bundle whose
  recipient's verify.sh fails on a sidecar we chose to include. The same
  rule the workload bridge follows: do not write what `verify` would
  refuse. Returns what happened, for the caller to report."
  [definition ^File src-root dest-root]
  (let [hash (try (process/process-hash definition) (catch Exception _ nil))
        src-dir (io/file src-root "processes" "by-hash")
        archived (when hash (io/file src-dir (str hash ".edn")))]
    (if-not (and archived (.isFile archived))
      {:archived? false}
      (let [dest-dir (io/file dest-root "processes" "by-hash")
            dest (io/file dest-dir (str hash ".edn"))
            signers (io/file dest-root "actors")
            sigs (filter (fn [^File f]
                           (str/starts-with? (.getName f) (str hash ".sig.")))
                         (or (.listFiles src-dir) []))
            grants? (fn [^File sig]
                      (boolean
                       (when (.isFile signers)
                         (when-let [who (first (sign/find-principals
                                                signers archived sig
                                                sign/namespace-process))]
                           (sign/verify signers archived sig who
                                        sign/namespace-process)))))
            [ok skipped] [(filterv grants? sigs) (remove grants? sigs)]]
        (io/make-parents dest)
        (when-not (.exists dest) (io/copy archived dest))
        (doseq [^File sig ok
                :let [df (io/file dest-dir (.getName sig))]
                :when (not (.exists df))]
          (io/copy sig df))
        {:archived? true :hash hash
         :signatures (count ok)
         :unverifiable (count skipped)
         :library (io/file src-root "actors")}))))

(defn cmd-adopt
  "adopt <process-or-template.edn> [--params <p.edn>]: bring a process
  from a library into this store. A plain definition is copied; a
  template (carries :tik/params) is filled — interactively by default,
  its own spec driving typed, validated prompts — expanded to a
  definition, linted, and written to processes/, with its runbooks
  copied alongside. The expanded EDN is authoritative; nothing runs the
  template as code (§19)."
  [{:keys [pos opts]}]
  (let [src (or (first pos)
                (die "usage: tik adopt <process-or-template.edn> [--params p.edn]"))
        srcf (io/file src)
        _ (when-not (.exists srcf) (die (str "no such file: " src)))
        raw (read-edn-file srcf)
        tmpl? (template/template? raw)
        body (if tmpl? (:tik/template raw) raw)
        nameable? #(or (keyword? %) (string? %) (symbol? %))
        ;; a label for the prompts only — the body's id may be a param
        ;; marker (a vector), resolved only by expansion, so fall back to
        ;; the file stem rather than calling `name` on a non-name
        label (let [pid (:process/id body)]
                (if (nameable? pid)
                  (name pid)
                  (str/replace (.getName srcf) #"\.(tmpl\.)?edn$" "")))
        definition (if tmpl? (template/expand raw (collect-params raw label opts)) raw)
        ;; the authoritative id comes from the EXPANDED definition, and
        ;; must be a real name — a template that expands to no usable id
        ;; is rejected here, not cast-crashed downstream
        _ (when-not (and (map? definition) (nameable? (:process/id definition)))
            (die "not a process or template (its :process/id is missing or not a name)"))
        _ (when (print-problems (lint/lint definition))
            (die "refusing to adopt a definition with lint errors"))
        pname (name (:process/id definition))
        dest (io/file (root) "processes" (str pname ".edn"))]
    (io/make-parents dest)
    (spit dest (with-out-str (pp/pprint definition)))
    (let [copied (adopt-runbooks! definition (source-root srcf) (root))
          pub (adopt-publication! definition (source-root srcf) (root))]
      (println (str "✓ " (if tmpl? "expanded" "adopted") " · lint clean → processes/"
                    pname ".edn"
                    (when (pos? copied) (str "  (+ " copied " runbook(s))"))))
      (when (:archived? pub)
        (println (str "  " (:hash pub)))
        (if (pos? (:signatures pub))
          (println (str "  publication signature travels ("
                        (:signatures pub) ") — your evidence bundles will"
                        " carry the publisher's word for these rules"))
          (when (pos? (:unverifiable pub))
            (println (str "  the library signs this definition, but no key in"
                          " your `actors` verifies it, so the signature stays"
                          " behind"))
            (println (str "    register the publisher (its key is in "
                          (:library pub) "), then adopt again")))))
      (when (some #(empty? (:members (val %) [])) (:process/roles definition))
        (println "  fill in the empty roles (tik actor add …), then:"))
      (println (str "  tik new " pname)))))
