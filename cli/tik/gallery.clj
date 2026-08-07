;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.gallery
  "tik gallery: a directory of process definitions rendered as pages you
  can browse.

  A process is meant to be READ before it is adopted — it says who signs
  what, and taking one unread is taking somebody else's org chart. So
  this renders for reading: the stage graph, every guard in a sentence,
  the roles that must be filled, and the runbooks that say how the
  evidence gets produced. It is a gallery, not a package index; there is
  nothing here to install.

  Every page is DERIVED from the definition it describes, so a catalog
  cannot drift into describing a process that no longer exists. Each one
  carries the definition's content address, which is what a ticket pins
  and what `tik rederive --expect-definition` compares against — so a
  page that has gone stale says so, by naming a hash the library no
  longer publishes.

  Pure rendering over `tik.draw` and the definition itself: no store, no
  network, no clock."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [read-edn-file]]
            [tik.cli-core :refer [die]]
            [tik.draw :as draw]
            [tik.lint :as lint]
            [tik.process :as process]
            [tik.text :refer [safe-name]])
  (:import (java.io File)))

(defn- path-str [p]
  (if (vector? p) (str/join "." (map safe-name p)) (pr-str p)))

(defn guard-prose
  "One guard as a sentence. The closed operator basis is enumerated; a
  malformed or unknown guard falls back to its EDN, so a library with a
  broken definition still renders instead of failing to build."
  ([g] (guard-prose g 0))
  ([g depth]
   (let [sub #(guard-prose % (inc depth))]
     (cond
       (< 8 depth) "…"
       (not (and (vector? g) (seq g))) (str "`" (pr-str g) "`")
       :else
       (case (first g)
         :fact  (str "the fact `" (path-str (second g)) "` stands")
         :fact= (str "the fact `" (path-str (second g)) "` equals `"
                     (pr-str (nth g 2 nil)) "`")
         :artifact (str "an artifact is attached whose path starts with `"
                        (nth g 1 nil) "`")
         :signed-by (str "`" (path-str (nth g 2 nil))
                         "` was asserted by a member of the `"
                         (safe-name (second g)) "` role")
         :stage-reached (str "stage `" (safe-name (second g)) "` is reached")
         :elapsed-since (str "`" (nth g 2 nil) "` has passed since `"
                             (safe-name (second g)) "`")
         :attested-within (str "an attestation of `" (path-str (second g))
                               "` exists, no older than `" (nth g 2 nil) "`")
         :different-person (str "the facts "
                                (str/join " and "
                                          (map #(str "`" (path-str %) "`")
                                               (rest g)))
                                " came from different people")
         :and (str/join ", and " (map sub (rest g)))
         :or (str "either " (str/join ", or " (map sub (rest g))))
         :not (str "it is NOT the case that " (sub (second g)))
         :malli (str "the facts satisfy `" (pr-str (second g)) "`")
         (str "`" (pr-str g) "`"))))))

(defn- yaml-string
  "A value safe in YAML front matter. Quoted always rather than only when
  it looks risky: a purpose carrying a colon, a quote or a newline is
  ordinary prose and must not decide whether the page parses."
  [v]
  (str \" (-> (str v)
              (str/replace "\\" "\\\\")
              (str/replace "\"" "\\\"")
              (str/replace #"\s+" " ")
              str/trim)
       \"))

(defn- front-matter [title description]
  (str "---\ntitle: " (yaml-string title) "\n"
       "description: " (yaml-string description) "\n"
       "tags: [process, library]\n---\n\n"))

(defn- facts-table [proc]
  (when-let [facts (seq (:process/facts proc))]
    (str "## Facts it records\n\n| Path | Shape |\n| --- | --- |\n"
         (str/join "\n" (for [[path schema] (sort-by (comp path-str key) facts)]
                          (str "| `" (path-str path) "` | `"
                               (pr-str schema) "` |")))
         "\n\n")))

(defn- roles-section [proc]
  (when-let [roles (seq (:process/roles proc))]
    (str "## Roles to fill\n\n"
         "Every role ships empty, so adopting a process never inherits\n"
         "somebody else's org chart. Name yours with `tik roles add"
         " <role> <actor>`.\n\n"
         (str/join "\n" (for [[role spec] (sort-by (comp str key) roles)]
                          (str "- `" (safe-name role) "`"
                               (if (seq (:members spec))
                                 (str " — declared members: "
                                      (str/join ", " (map #(str "`" % "`")
                                                          (:members spec))))
                                 " — empty"))))
         "\n\n")))

(defn- tidy
  "Collapse runs of blank lines. Sections are assembled from optional
  parts, so an absent runbook or an unguarded stage would otherwise leave
  a gap that markdown linters object to."
  [s]
  (str/replace s #"\n{3,}" "\n\n"))

(defn- stages-section [proc]
  (str "## Stages\n\n"
       (str/join "\n"
                 (for [s (:process/stages proc)]
                   (str "### `" (safe-name (:stage/id s)) "`"
                        (when (:stage/sticky? s) " · sticky") "\n\n"
                        (when (seq (:after s))
                          (str "Follows "
                               (str/join ", " (map #(str "`" (safe-name %) "`")
                                                   (:after s)))
                               ".\n\n"))
                        (when (:stage/sticky? s)
                          (str "Once reached it stays reached: the fold carries"
                               " it forward, so later\nevidence cannot take it"
                               " away.\n\n"))
                        (if (seq (:guards s))
                          (str "Reached when:\n\n"
                               (str/join "\n" (for [g (:guards s)]
                                                (str "- " (guard-prose g))))
                               "\n\n")
                          "Reached immediately — it carries no guards.\n\n")
                        (when-let [h (:hint s)]
                          (str "Runbook: `" h "`\n\n")))))))

(defn page
  "One definition as a page: what it is, what it demands, and how to take
  it. `hash` is passed in rather than recomputed so a caller can render
  the ARCHIVED address when a library publishes one."
  [proc hash file-name]
  (let [id (safe-name (:process/id proc))
        stages (count (:process/stages proc))
        drawn (draw/process proc nil)]
    (tidy
     (str (front-matter id
                       (str "A tik process with " stages " stage"
                            (when-not (= 1 stages) "s")
                            (when-let [p (:process/purpose proc)]
                              (str ": " p))))
         (when-let [p (:process/purpose proc)] (str p "\n\n"))
         "## Identity\n\n"
         "A definition is named by its content, so this address is what a\n"
         "ticket pins and what a consumer checks against:\n\n"
         "```text\n" hash "\n```\n\n"
         (when (seq drawn)
           (str "## Shape\n\n```text\n" (str/join "\n" drawn) "\n```\n\n"))
         (roles-section proc)
         (facts-table proc)
         (stages-section proc)
         "## Take it\n\n"
         "```sh\ntik adopt " file-name "\n```\n\n"
         "The definition and its runbooks are copied into your store, and"
         " the\npublisher's signature travels with them when a key in your"
         " `actors`\nverifies it. Read the stages before you adopt: they say"
         " who has to\nsign what, which is a decision about your"
         " organisation.\n"))))

(defn index-page
  "The catalog: every definition in the library, with the shape of each."
  [entries]
  (str (front-matter "Processes"
                     (str "A library of " (count entries)
                          " tik process definitions, each derived from the"
                          " definition itself."))
       "Process definitions to read, adapt and adopt. Each page is generated"
       " from the\ndefinition it describes, so what you read is what a ticket"
       " would derive under.\n\n"
       "A process is worth reading before it is taken: it says who must sign"
       " what,\nand the roles ship empty so adoption never inherits somebody"
       " else's org\nchart.\n\n"
       "| Process | Stages | Roles |\n| --- | --- | --- |\n"
       (str/join "\n"
                 (for [{:keys [proc slug]} (sort-by :slug entries)]
                   (str "| [`" (safe-name (:process/id proc)) "`](/processes/"
                        slug "/) | "
                        (str/join ", " (map #(str "`" (safe-name (:stage/id %)) "`")
                                            (:process/stages proc)))
                        " | "
                        (or (some->> (:process/roles proc) keys sort
                                     (map #(str "`" (safe-name %) "`"))
                                     seq (str/join ", "))
                            "—")
                        " |")))
       "\n"))

(defn- definition-files [^File dir]
  (->> (or (.listFiles dir) [])
       (filter (fn [^File f]
                 (and (.isFile f) (str/ends-with? (.getName f) ".edn"))))
       (sort-by (fn [^File f] (.getName f)))))

(defn- archived-hash
  "The address the library PUBLISHES for this definition when it archives
  one, else the address computed from the file. They agree — the archive
  is the canonical form of the same data — but preferring the published
  file keeps a page citing exactly what the library serves."
  [^File dir proc]
  (let [computed (process/process-hash proc)
        archived (io/file dir "by-hash" (str computed ".edn"))]
    (when-not (.isFile archived)
      (binding [*out* *err*]
        (println (str "note: " (safe-name (:process/id proc))
                      " is not archived under by-hash/ — the page cites the"
                      " address computed from the source"))))
    computed))

(defn cmd-gallery
  "gallery <processes-dir> [--out dir]: render a process library as pages
  to read.

  Every page is derived from the definition, so a catalog cannot drift
  into describing a process nobody publishes any more, and each carries
  the content address a ticket would pin. Definitions that do not lint
  are rendered with their problems named rather than skipped — a library
  hiding a broken definition helps nobody."
  [{:keys [pos opts]}]
  (let [src (or (first pos) (die "usage: tik gallery <processes-dir> [--out dir]"))
        dir (io/file src)
        _ (when-not (.isDirectory dir)
            (die (str "not a directory of process definitions: " src)))
        out (io/file (or (:out opts) "gallery"))
        files (definition-files dir)
        _ (when (empty? files)
            (die (str "no .edn definitions in " src)))
        entries (vec (for [^File f files
                           :let [proc (try (read-edn-file f)
                                           (catch Exception e
                                             (die (str "cannot read " (.getName f)
                                                       ": " (ex-message e)))))]
                           :when (map? proc)]
                       {:proc proc :file f
                        :slug (str/replace (.getName f) #"\.edn$" "")}))]
    (.mkdirs out)
    (doseq [{:keys [proc file slug]} entries
            :let [hash (archived-hash dir proc)
                  problems (filter #(= :error (:level %)) (lint/lint proc))]]
      (when (seq problems)
        (binding [*out* *err*]
          (println (str "warning: " slug " does not lint — "
                        (str/join "; " (map :msg problems))))))
      (spit (io/file out (str slug ".md"))
            (page proc hash (str "processes/" (.getName ^File file)))))
    (spit (io/file out "_index.md") (index-page entries))
    (println (str "wrote " (inc (count entries)) " page(s) to " out))
    (doseq [{:keys [slug]} entries] (println (str "  " slug)))))
