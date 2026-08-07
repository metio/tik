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
            [tik.canonical :as canonical]
            [tik.cli]
            [tik.gallery :as gallery]
            [tik.harness :as h]
            [tik.process :as process]
            [tik.template :as tmpl]))

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

;; --------------------------------------------------------------- templates

(def ^:private tmpl
  {:tik/params [:map
                [:window {:description "how long?"} :string]
                [:with-review {:optional true :description "second pair of eyes?"}
                 :boolean]]
   :tik/template
   {:process/id :sample
    :process/version 1
    :lint {:runbooks :off}
    :process/facts {[:note] [:string {:min 2}]}
    :process/stages
    [{:stage/id :opened :guards [[:fact [:note]]]}
     [:tik/when :with-review
      {:stage/id :reviewed :after [:opened]
       :guards [[:elapsed-since :ticket/create [:tik/param :window]]]}]]}})

(deftest a_template_page_says_what_you_choose_and_what_it_costs
  (let [proc (tmpl/expand tmpl {:window "P1D" :with-review true})
        md (gallery/page proc "sha256-ref" "templates/sample.tmpl.edn"
                         {:template {:questions (gallery/template-questions tmpl)
                                     :optional (gallery/optional-stages tmpl)
                                     :params-file "sample.params.edn"
                                     :expanded? true}})]
    (testing "the questions come from the template's own spec"
      (is (str/includes? md "What you choose"))
      (is (str/includes? md "how long?"))
      (is (str/includes? md "second pair of eyes?"))
      (is (str/includes? md "*(optional)*")))
    (testing "the identity says the answers decide it, not that this is THE hash"
      (is (str/includes? md "Your answers decide this one"))
      (is (str/includes? md "sample.params.edn")))
    (testing "and an optional stage says which answer includes it"
      (is (str/includes? md "Included when you answer yes to `with-review`")))))

(deftest a_template_without_reference_answers_still_lists_its_questions
  (let [md (gallery/page (assoc (:tik/template tmpl) :process/stages [])
                         "(your answers decide it)" "templates/sample.tmpl.edn"
                         {:template {:questions (gallery/template-questions tmpl)
                                     :optional {}
                                     :params-file "sample.params.edn"
                                     :expanded? false}})]
    (is (str/includes? md "how long?"))
    (is (str/includes? md "ships no reference"))
    (is (not (str/includes? md "with every option on"))
        "it must not promise a shape it does not show")))

(deftest optional_stages_are_read_from_the_when_markers
  (is (= {:reviewed :with-review} (gallery/optional-stages tmpl)))
  (testing "a template with no conditional stages has none"
    (is (empty? (gallery/optional-stages
                 {:tik/template {:process/stages [{:stage/id :a}]}})))))

(deftest the_command_renders_definitions_and_templates_side_by_side
  (let [lib (h/temp-dir! "tik-lib")
        procs (doto (io/file lib "processes") .mkdirs)
        tmpls (doto (io/file lib "templates") .mkdirs)
        out (h/temp-dir! "tik-lib-out")]
    (io/copy (io/file repo "processes/track.edn") (io/file procs "track.edn"))
    (spit (io/file tmpls "sample.tmpl.edn") (pr-str tmpl))
    (spit (io/file tmpls "sample.params.edn") (pr-str {:window "P1D"}))
    (let [r (tik.cli/run-argv ["gallery" (str procs) (str tmpls)
                               "--out" (str out)])]
      (is (zero? (:exit r)) (:err r))
      (testing "both are rendered, under slugs that cannot collide"
        (is (.isFile (io/file out "track.md")))
        (is (.isFile (io/file out "sample-template.md"))))
      (testing "and an answer sheet is not mistaken for a process"
        (is (not (.exists (io/file out "sample.params.md"))))
        (is (not (.exists (io/file out "sample-params.md"))))))))

;; --------------------------------------------------- publishing the assets

(deftest publishing_serves_what_the_pages_cite
  (let [lib (h/temp-dir! "tik-pub-lib")
        procs (doto (io/file lib "processes") .mkdirs)
        by-hash (doto (io/file procs "by-hash") .mkdirs)
        tmpls (doto (io/file lib "templates") .mkdirs)
        proc (read-edn-file (io/file repo "processes/track.edn"))
        hash (process/process-hash proc)
        out (h/temp-dir! "tik-pub-out")
        assets (h/temp-dir! "tik-pub-assets")]
    (io/copy (io/file repo "processes/track.edn") (io/file procs "track.edn"))
    (spit (io/file by-hash (str hash ".edn")) (canonical/emit proc))
    (spit (io/file by-hash (str hash ".sig.abcd")) "a signature")
    (spit (io/file lib "actors") "seb namespaces=\"tik-*\" ssh-ed25519 AAAA seb\n")
    (spit (io/file tmpls "sample.tmpl.edn") (pr-str tmpl))
    (spit (io/file tmpls "sample.params.edn") (pr-str {:window "P1D"}))
    (let [r (tik.cli/run-argv ["gallery" (str procs) (str tmpls)
                               "--out" (str out) "--assets" (str assets)])]
      (is (zero? (:exit r)) (:err r))
      (testing "the archived bytes and their signature are served"
        (is (.isFile (io/file assets "by-hash" (str hash ".edn"))))
        (is (.isFile (io/file assets "by-hash" (str hash ".sig.abcd")))))
      (testing "served bytes still hash to the address the page cites"
        (is (= hash (str "sha256-"
                         (canonical/sha256-hex
                          (slurp (io/file assets "by-hash" (str hash ".edn"))))))))
      (testing "with the registry those signatures check against"
        (is (str/includes? (slurp (io/file assets "actors")) "seb")))
      (testing "and a template publishes nothing — its expansion is the
                adopter's, not the library's"
        (is (= 2 (count (.listFiles (io/file assets "by-hash")))))))))

(deftest the_intro_survives_regeneration
  (let [lib (h/temp-dir! "tik-intro-lib")
        procs (doto (io/file lib "processes") .mkdirs)
        out (h/temp-dir! "tik-intro-out")
        intro (io/file lib "intro.md")]
    (io/copy (io/file repo "processes/track.edn") (io/file procs "track.edn"))
    (spit intro "Fetch one with `curl`, and check its hash.")
    (dotimes [_ 2]
      (tik.cli/run-argv ["gallery" (str procs) "--out" (str out)
                         "--intro" (str intro)]))
    (is (str/includes? (slurp (io/file out "_index.md"))
                       "Fetch one with `curl`")
        "library prose lives in a file, so regenerating cannot discard it")))
