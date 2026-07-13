;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.cli-test
  "The porcelain's parsing contract — the H9 surface: values people
  actually type must round-trip without EDN knowledge."
  (:require [tik.harness :as h]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]
            [tik.event :as event]
            [tik.store.protocol :as store]))

(def parse-value #'cli/parse-value)
(def parse-args #'cli/parse-args)

(deftest flags_accept_the_equals_form
  ;; --flag=value is the near-universal CLI convention; parse-args must
  ;; bind :flag to the value, not the junk key :flag=value. Parsing it
  ;; wrong silently drops authorship (--actor), the signing key (--key),
  ;; or the derivation clock (--at) and falls back exit-0, no diagnostic.
  (testing "the value rides in the same token"
    (is (= {:actor "alice"} (:opts (parse-args ["set" "id" "n=1" "--actor=alice"]))))
    (is (= {:format "json"} (:opts (parse-args ["ls" "--format=json"]))))
    (is (= {:key "/p/k"} (:opts (parse-args ["set" "id" "--key=/p/k"])))))
  (testing "positionals carrying = (key=value facts) are untouched"
    (is (= ["set" "id" "note=hi"]
           (:pos (parse-args ["set" "id" "note=hi" "--actor=alice"])))))
  (testing "the space form still works, and a bare flag is boolean true"
    (is (= {:actor "bob"} (:opts (parse-args ["next" "--actor" "bob"]))))
    (is (= {:apply true} (:opts (parse-args ["plan" "--apply"])))))
  (testing "an empty value (--flag=) is the empty string, not a junk key"
    (is (= {:title ""} (:opts (parse-args ["new" "--title="]))))))

(deftest values_people_actually_type
  (testing "one complete EDN form stays EDN"
    (is (= 120 (parse-value "120")))
    (is (= :green (parse-value ":green")))
    (is (= "quoted" (parse-value "\"quoted\"")))
    (is (= [1 2] (parse-value "[1 2]"))))
  (testing "bare single words become keywords (facts over strings)"
    (is (= :billing (parse-value "billing")))
    (is (= :tik-author (parse-value "tik-author"))))
  (testing "everything else is the literal string, never a die"
    (is (= "tik author: guided interview" (parse-value "tik author: guided interview")))
    (is (= "31ffd53" (parse-value "31ffd53")))
    (is (= "hello world" (parse-value "hello world")))
    (is (= "a,b{" (parse-value "a,b{")))))

(defn- in
  "run-argv against a fresh store: the private root accessor is
  redirected at its var so every command resolves the temp dir, no
  TIK_ROOT env needed."
  [root & argv]
  (with-redefs-fn {#'cli/root (constantly (str root))}
    (fn [] (cli/run-argv (mapv str argv)))))

(deftest run_argv_is_the_in_process_entry_point
  ;; the second entry beside -main: same dispatch, output captured, and
  ;; every exit! trapped into a CODE instead of killing the process —
  ;; this is what lets the MCP server reuse the whole CLI without a
  ;; subprocess (cli/tik/mcp.clj)
  (let [root (h/temp-dir! "tik-runargv")]
    (System/setProperty "user.name" "tester")
    (testing "a successful command returns exit 0 and captures stdout"
      (let [_ (in root "new" "track" "--title" "in-process ticket")
            r (in root "ls" "--edn")]
        (is (zero? (:exit r)))
        (is (str/includes? (:out r) "in-process ticket"))))
    (testing "a die (unknown id) is trapped: nonzero exit, message on err"
      (let [r (in root "explain" "nomatch" "--edn")]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "no ticket starting with"))
        (is (str/blank? (:out r)))))
    (testing "a gated refusal is trapped with the frontier's exit code"
      (let [id (-> (in root "ls" "--edn") :out
                   (str/split #"[^0-9a-f-]") (->> (filter #(= 36 (count %))))
                   first)
            r (in root "agent" "set" id "x=1" "--actor" "outsider")]
        (is (= 3 (:exit r)))
        (is (re-find #"REFUSED|admissible|not admitted"
                     (str (:out r) (:err r))))))
    (testing "an unknown command does not throw out of the process"
      (let [r (in root "no-such-command")]
        (is (integer? (:exit r)))
        (is (str/includes? (str (:out r) (:err r)) "not a command"))))))

(deftest actor_add_rejects_a_name_that_would_corrupt_the_registry
  ;; the name is written verbatim into the OpenSSH allowed-signers file;
  ;; whitespace/quote/newline would split it into a second attacker-shaped
  ;; line or widen the namespace restriction. Reject before writing.
  (let [root (h/temp-dir! "tik-actorname")]
    (with-redefs-fn {#'cli/root (constantly (str root))}
      (fn []
        (doseq [bad ["evil namespaces=\"*\"" "two words" "line\nbreak" "quote\"x"]]
          (let [r (cli/run-argv ["actor" "add" bad "k.pub"])]
            (is (= 1 (:exit r)) (pr-str bad))
            (is (re-find #"invalid actor name" (str (:out r) (:err r)))
                (pr-str bad))))
        (is (not (.exists (io/file root "actors")))
            "no corrupt registry line was written")))))

(deftest oidc_password_prefers_a_secret_source_over_argv
  ;; the resource-owner password must not ride in argv (ps/proc); it
  ;; resolves — through the unified tik.secret resolver — from a password
  ;; manager command, a file, the environment, or (last, with a warning)
  ;; a literal flag. nil when none is given (the caller uses device flow).
  (let [resolve-pw #'cli/resolve-oidc-password]
    (testing "--password-command runs a secret manager (first line)"
      (is (= "pwFromPass"
             (resolve-pw {:password-command "printf 'pwFromPass\\nmeta'"}))))
    (testing "--password-file reads the file's first line"
      (let [f (io/file (h/temp-dir! "tik-oidcpw") "pw")]
        (spit f "pwFromFile\n")
        (is (= "pwFromFile" (resolve-pw {:password-file (str f)})))))
    (testing "a literal --password still works, but warns to stderr"
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (is (= "literalpw" (resolve-pw {:password "literalpw"}))))
        (is (re-find #"visible to other users" (str err)))))
    (testing "the command source wins over a literal when both are given"
      (binding [*err* (java.io.StringWriter.)]
        (is (= "cmdWins"
               (resolve-pw {:password-command "printf cmdWins"
                            :password "literalpw"})))))
    (testing "nothing given -> nil (device flow)"
      (is (nil? (resolve-pw {}))))))

(defn- at [s] (java.time.Instant/parse s))

(deftest verify_changed_catches_a_pruned_inner_ancestor
  ;; --changed fingerprints the present event-id SET, not just the DAG
  ;; heads: deleting a diamond-INTERIOR event leaves the tips unchanged
  ;; (it stays referenced by the merge that named it a parent), so a
  ;; head-only drift check would skip the ticket and report PASS on a
  ;; store whose signed history was silently pruned. The id-set
  ;; fingerprint forces a re-verify, which catches the missing parent.
  (let [{:keys [root store]} (h/temp-store!)
        _ (io/copy (io/file (System/getProperty "user.dir")
                            "processes/support-request.edn")
                   (io/file (doto (io/file root "processes") (.mkdirs))
                            "support-request.edn"))
        tid (java.util.UUID/fromString "018f2f6e-7c1a-7000-8000-0000000000d1")
        c (event/create-ticket {:ticket tid :actor "seb" :at (at "2026-07-08T10:00:00Z")
                                :title "diamond" :process :support-request})
        a (event/assert-fact {:ticket tid :actor "seb" :parents #{(:event/id c)}
                              :at (at "2026-07-08T10:01:00Z") :path [:p] :value 1})
        b (event/assert-fact {:ticket tid :actor "seb" :parents #{(:event/id c)}
                              :at (at "2026-07-08T10:02:00Z") :path [:q] :value 1})
        m (event/assert-fact {:ticket tid :actor "seb"
                              :parents #{(:event/id a) (:event/id b)}
                              :at (at "2026-07-08T10:03:00Z") :path [:r] :value 1})]
    (doseq [e [c a b m]] (store/append! store e))
    (with-redefs-fn {#'cli/root (constantly (str root))}
      (fn []
        (is (zero? (:exit (cli/run-argv ["verify"])))
            "the honest diamond verifies and writes the drift cache")
        (.delete (io/file root "tickets" (str tid) "events"
                          (str (:event/id a) ".edn")))
        (let [r (cli/run-argv ["verify" "--changed"])]
          (is (= 1 (:exit r))
              "a pruned interior ancestor forces a re-verify, not a skip"))))))
