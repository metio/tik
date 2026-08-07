;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.gallery-test
  "The library gallery: pages derived from definitions, readable before
  adoption, and total over whatever a library actually contains — a
  definition that does not lint still renders, because a catalog that
  silently drops the broken one is worse than one that shows it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.args :refer [read-edn-file]]
            [tik.cli]
            [tik.gallery :as gallery]
            [tik.harness :as h]
            [tik.process :as process]))

(def ^:private repo (System/getProperty "user.dir"))

(deftest a_page_says_what_the_stages_demand_in_words
  (let [proc (read-edn-file (io/file repo "processes/release.edn"))
        hash (process/process-hash proc)
        md (gallery/page proc hash "processes/release.edn")]
    (testing "it carries the identity a ticket pins"
      (is (str/includes? md hash)))
    (testing "and the guards as sentences rather than as EDN alone"
      (is (str/includes? md "was asserted by a member of the `ci` role"))
      (is (str/includes? md "an attestation of `:sbom` exists, no older than `P1D`"))
      (is (str/includes? md "came from different people")))
    (testing "with the runbook that says how the evidence is produced"
      (is (str/includes? md "kb/runbooks/release-built.md")))
    (testing "and the roles, because filling them is the adopter's decision"
      (is (str/includes? md "Roles to fill")))
    (testing "front matter survives a purpose carrying a colon"
      (let [tricky (assoc proc :process/purpose "ship it: carefully, \"always\"")
            head (first (str/split (gallery/page tricky hash "x.edn") #"\n---"))]
        (is (str/includes? head "description: \"A tik process"))
        (is (str/includes? head "\\\"always\\\""))))))

(deftest every_operator_in_the_closed_basis_reads_as_a_sentence
  (doseq [[guard expected]
          [[[:fact [:a :b]] "the fact `a.b` stands"]
           [[:fact= [:x] :done] "equals `:done`"]
           [[:artifact "repro/"] "starts with `repro/`"]
           [[:signed-by :qa [:x]] "member of the `qa` role"]
           [[:stage-reached :built] "stage `built` is reached"]
           [[:elapsed-since :ticket/create "PT48H"] "has passed since"]
           [[:attested-within :sbom "P1D"] "no older than `P1D`"]
           [[:different-person [:a] [:b]] "different people"]
           [[:and [:fact [:a]] [:fact [:b]]] ", and "]
           [[:or [:fact [:a]] [:fact [:b]]] "either "]
           [[:not [:fact [:a]]] "it is NOT the case that"]
           [[:malli [:map]] "the facts satisfy"]]]
    (is (str/includes? (gallery/guard-prose guard) expected)
        (pr-str guard))))

(deftest rendering_is_total_over_a_library_that_is_not_perfect
  (testing "a malformed guard renders as its data rather than raising"
    (is (string? (gallery/guard-prose [:no-such-operator 1 2])))
    (is (string? (gallery/guard-prose "not even a vector")))
    (is (string? (gallery/guard-prose nil))))
  (testing "a deeply nested tree stops rather than overflowing"
    (let [deep (reduce (fn [g _] [:not g]) [:fact [:a]] (range 200))]
      (is (str/includes? (gallery/guard-prose deep) "…"))))
  (testing "a definition with no stages, roles or facts still makes a page"
    (is (string? (gallery/page {:process/id :bare} "sha256-x" "bare.edn")))))

(deftest the_index_links_every_definition_it_lists
  (let [entries (for [n ["track" "release"]]
                  {:proc (read-edn-file (io/file repo (str "processes/" n ".edn")))
                   :slug n})
        md (gallery/index-page entries)]
    (is (str/includes? md "(/processes/track/)"))
    (is (str/includes? md "(/processes/release/)"))))

(deftest the_command_renders_a_directory
  (let [out (h/temp-dir! "tik-gallery-out")
        r (tik.cli/run-argv ["gallery" (str (io/file repo "processes"))
                             "--out" (str out)])]
    (is (zero? (:exit r)) (:err r))
    (is (.isFile (io/file out "_index.md")))
    (is (.isFile (io/file out "track.md")))
    (testing "and every page it wrote is real markdown with front matter"
      (doseq [^java.io.File f (.listFiles out)]
        (is (str/starts-with? (slurp f) "---\ntitle: ") (.getName f))))))
