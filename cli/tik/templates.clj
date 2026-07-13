;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.templates
  "Starter process-definition DATA: the built-in `templates` library the
  `author --template` / `adopt` flow fills in, and the `track-process`
  fallback a fresh store materializes on first use. Pure EDN, no logic —
  the authoring interview (tik.author) and the store loader (tik.cli-core)
  both read from here.")

;; ----------------------------------------------------------- templates

(def templates
  "Known-good starting shapes (H9): each is a complete answers map —
  `tik author --template bug` writes it out like a finished interview,
  and the author edits EDN-free artifacts from there (runbooks, role
  members, the generated tests). Role members ship as change-me so the
  first `tik lint`-clean run still forces the one decision templates
  cannot make: who signs."
  {"bug"
   {:name "bug"
    :purpose "track a defect from report to verified fix"
    :stages [{:name "reported"
              :purpose "the defect is described well enough to act on"
              :after []
              :needs [{:kind :choice :path [:severity]
                       :values [:low :normal :high :critical]}
                      {:kind :fact :path [:repro :steps]}]}
             {:name "confirmed"
              :purpose "someone reproduced it — this is real"
              :after ["reported"]
              :needs [{:kind :signature :role :triager}]}
             {:name "fixed"
              :purpose "a change exists and evidence is attached"
              :after ["confirmed"]
              :needs [{:kind :fact :path [:fix :ref]}
                      {:kind :file :prefix "evidence/"}]}
             {:name "verified"
              :purpose "someone other than hope confirmed the fix"
              :after ["fixed"]
              :needs [{:kind :signature :role :verifier}]}]
    :roles {"triager" ["change-me"] "verifier" ["change-me"]}}

   "change-request"
   {:name "change-request"
    :purpose "propose, approve, and apply a change with a trail"
    :stages [{:name "submitted"
              :purpose "the change and its reason are on record"
              :after []
              :needs [{:kind :fact :path [:change :description]}
                      {:kind :choice :path [:risk]
                       :values [:low :medium :high]}]}
             {:name "approved"
              :purpose "someone accountable stands behind it"
              :after ["submitted"]
              :needs [{:kind :signature :role :approver}]}
             {:name "applied"
              :purpose "the change is live, reference recorded"
              :after ["approved"]
              :needs [{:kind :fact :path [:applied :ref]}]}]
    :roles {"approver" ["change-me"]}}

   "purchase-approval"
   {:name "purchase-approval"
    :purpose "spend money with evidence at every step"
    :stages [{:name "requested"
              :purpose "what, why, and how much are on record"
              :after []
              :needs [{:kind :fact :path [:amount] :type :number}
                      {:kind :choice :path [:category]
                       :values [:hardware :software :services :travel]}
                      {:kind :file :prefix "quotes/"}]}
             {:name "approved"
              :purpose "the budget owner stands behind the spend"
              :after ["requested"]
              :needs [{:kind :signature :role :manager}]}
             {:name "ordered"
              :purpose "the order is placed, reference recorded"
              :after ["approved"]
              :needs [{:kind :fact :path [:order :ref]}]}
             {:name "received"
              :purpose "the goods arrived with proof"
              :after ["ordered"]
              :needs [{:kind :file :prefix "delivery/"}]}]
    :roles {"manager" ["change-me"]}}})

(def track-process
  "The meta-process for 'no process yet', built in so `tik new track`
  works in an empty store (the file in processes/ is the same
  definition; a test pins them equal). Two stages: it exists, and it
  ended with an outcome on record."
  {:process/id :track
   :process/version 1
   :process/guard-vocab 1
   :process/facts {[:outcome] [:string {:min 4}]}
   :process/stages
   [{:stage/id :open
     :hint "kb/runbooks/track-open.md"
     :guards []}
    {:stage/id :done
     :after [:open]
     :stage/sticky? true
     :hint "kb/runbooks/track-done.md"
     :guards [[:fact [:outcome]]]}]})

;; ----------------------------------------------------------- interview
