;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.docs-index-test
  "The website's runbook and decision indexes are written by hand — the
  theme hands a section's landing page to its author, and a curated list
  grouped by process and by theme is worth more to a reader than an
  alphabetical dump. What a hand-written index cannot do is notice a new
  file, so this test does: every document in the knowledge bundle must be
  linked from the index that presents it, and every link must resolve to a
  document that exists.

  Same contract as tik.guide-test, one level up: a page that claims to
  list something is checked against what it lists."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private repo (System/getProperty "user.dir"))

(defn- slugs-in-bundle [dir]
  (into #{} (comp (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
                  (map #(str/replace (.getName ^java.io.File %) #"\.md$" "")))
        (.listFiles (io/file repo "kb" dir))))

(defn- slugs-linked-from [index-path section]
  (let [text (slurp (io/file repo index-path))]
    (into #{} (map second)
          (re-seq (re-pattern (str "\\(/" section "/([a-z0-9-]+)/\\)")) text))))

(defn- check-section [dir section index-path]
  (let [on-disk (slugs-in-bundle dir)
        linked (slugs-linked-from index-path section)]
    (testing (str section ": every bundle document is presented")
      (is (empty? (sort (remove linked on-disk)))
          (str "these kb/" dir " documents are not linked from " index-path
               " — a reader of the site cannot reach them")))
    (testing (str section ": every link resolves")
      (is (empty? (sort (remove on-disk linked)))
          (str index-path " links to kb/" dir " documents that do not exist")))
    on-disk))

(deftest every_runbook_is_linked_from_the_runbook_index
  (let [found (check-section "runbooks" "runbooks"
                             "docs/content/runbooks/_index.md")]
    (is (seq found) "the bundle should hold runbooks at all")))

(deftest every_decision_is_linked_from_the_decision_index
  (let [found (check-section "decisions" "decisions"
                             "docs/content/decisions/_index.md")]
    (is (seq found) "the bundle should hold decisions at all")))
