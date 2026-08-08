;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.lint-store-test
  "`tik lint` with no argument audits the store's hygiene — the layer
  between verify (integrity) and explain (derivation): unkempt, not
  wrong. Findings must name the fixing command; settled tickets are
  left in peace."
  (:require [tik.canonical :as canonical]
            [tik.harness :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private repo (System/getProperty "user.dir"))

(defn- tik* [root & args]
  (apply h/run-tik! {:root root :actor "seb"} args))

(deftest store_lint_names_the_fix_and_spares_the_settled
  (let [root (h/temp-dir! "tik-lint")
        _ (io/copy (io/file repo "processes/support-request.edn")
                   (io/file (doto (io/file root "processes")
                              (.mkdirs)) "support-request.edn"))
        ;; the definition ships :triager empty, and settling this ticket
        ;; needs a signed category — who is in a role lives in the register
        _ (tik* root "roles" "add" "triager" "seb")
        id (str/trim (:out (tik* root "new" "support-request"
                                 "--title" "undescribed")))
        short (subs id 0 8)]
    (testing "an open ticket without a description is a finding"
      (let [r (tik* root "lint")]
        (is (= 1 (:exit r)))
        (is (re-find (re-pattern (str short " has no description")) (:out r)))
        (is (re-find #"unsigned event" (:out r))
            "unsigned events are findings too")
        (is (re-find (re-pattern (str "tik set " short " description="))
                     (:out r))
            "the finding names the fixing command")))
    (testing "description silences that finding"
      (tik* root "set" id "description=a one-liner")
      (is (not (re-find #"has no description" (:out (tik* root "lint"))))))
    (testing "a settled ticket is not nagged"
      (tik* root "set" id "category=:billing" "severity=:low"
            "resolution.ref=\"done-and-dusted\"" "customer.ack=true")
      (let [r (tik* root "lint")]
        (is (not (re-find (re-pattern short) (:out r)))
            (:out r))))))

(deftest prose_rot_heuristics_and_live_links
  (let [root (h/temp-dir! "tik-rot")
        _ (io/copy (io/file repo "processes/support-request.edn")
                   (io/file (doto (io/file root "processes")
                              (.mkdirs)) "support-request.edn"))
        a (str/trim (:out (tik* root "new" "support-request"
                                "--title" "the referenced work")))
        b (str/trim (:out (tik* root "new" "support-request"
                                "--title" "the referring ticket")))]
    (testing "a description reporting another ticket's status is flagged"
      (tik* root "set" b (str "description=blocked until " (subs a 0 8)
                              " is finished"))
      (is (re-find #"reports another ticket's status"
                   (:out (tik* root "lint")))))
    (testing "the lint's own advice silences it: a link fact instead"
      (tik* root "set" b "description=make the follow-up change"
            (str "link.blocked-by=\"" (subs a 0 8) "\""))
      (is (not (re-find #"reports another ticket" (:out (tik* root "lint"))))))
    (testing "the link renders the target's CURRENT derived stage"
      (is (re-find #"\(received\)\s+[0-9a-f]{8} the referenced work.*\[blocked-by\]"
                   (:out (tik* root "status" b)))))
    (testing "a description older than the latest landing is flagged"
      (tik* root "set" b "commit=\"abcdef0\"")
      (is (re-find #"description predates its latest landing"
                   (:out (tik* root "lint"))))
      (tik* root "set" b "description=make the follow-up change, still")
      (is (not (re-find #"predates" (:out (tik* root "lint"))))))
    (testing "an unresolvable link degrades, never crashes"
      (tik* root "set" b "link.see-also=\"ffffffff\"")
      (let [r (tik* root "status" b)]
        (is (zero? (:exit r)))
        (is (re-find #"unresolved" (:out r)))))))

(deftest declared_types_end_the_quoting_wars
  (let [root (h/temp-dir! "tik-typed")
        _ (io/copy (io/file repo "processes/tik-dev.edn")
                   (io/file (doto (io/file root "processes") (.mkdirs))
                            "tik-dev.edn"))
        id (str/trim (:out (tik* root "new" "tik-dev" "--title" "typed")))]
    (testing "a bare hex commit stays a string because the process says string"
      (tik* root "set" id "commit=a051932f")
      (is (re-find #"\[:commit\] = \"a051932f\""
                   (:out (tik* root "status" id)))))
    (testing "an all-digit hash stays a string too"
      (tik* root "set" id "commit=4118197")
      (is (re-find #"\[:commit\] = \"4118197\""
                   (:out (tik* root "status" id)))))
    (testing "an explicit :colon still means keyword"
      (tik* root "set" id "gate=:green")
      (is (re-find #"\[:gate\] = :green" (:out (tik* root "status" id)))))
    (testing "undeclared facts keep the old bare-word-is-keyword behavior"
      (tik* root "set" id "mood=curious")
      (is (re-find #"\[:mood\] = :curious" (:out (tik* root "status" id)))))
    (testing "ls --where matches facts either spelling"
      (is (re-find #"typed" (:out (tik* root "ls" "--where" "fact:gate=:green"))))
      (is (not (re-find #"typed"
                        (:out (tik* root "ls" "--where" "fact:gate=:red"))))))
    (testing "new prints a stage hint on stderr"
      (let [r (tik* root "new" "tik-dev" "--title" "hinted")]
        (is (re-find #"stage: captured — next: tik explain" (:err r)))))))

(deftest lint_and_show_report_the_identity_a_ticket_pins
  ;; The value a publisher puts beside a definition and a consumer pins
  ;; with `tik rederive --expect-definition`. It is deliberately NOT the
  ;; file's checksum — the address is taken over the parsed definition,
  ;; so two files that read the same are the same definition.
  (let [root (h/temp-dir! "tik-identity")
        src (io/file repo "processes/support-request.edn")
        _ (io/copy src (io/file (doto (io/file root "processes") (.mkdirs))
                                "support-request.edn"))
        hash-of (fn [out]
                  (second (re-find #"(sha256-[0-9a-f]{64})" out)))
        lint-out (:out (tik* root "lint" (str src)))
        show-out (:out (tik* root "show" (str src)))]
    (is (some? (hash-of lint-out)) lint-out)
    (is (= (hash-of lint-out) (hash-of show-out))
        "both lenses report the same identity")
    (testing "and it is the address of the DEFINITION, not of the file"
      (is (not= (hash-of lint-out)
                (str "sha256-" (canonical/sha256-hex (slurp src))))))
    (testing "so a reformatted file keeps its identity"
      (let [reformatted (io/file root "reformatted.edn")]
        ;; same data, different bytes: a trailing comment and no final
        ;; newline change every byte-level checksum and nothing else
        (spit reformatted (str ";; a comment the address does not see\n"
                               (str/trim (slurp src))))
        (is (= (hash-of lint-out)
               (hash-of (:out (tik* root "lint" (str reformatted))))))))))
