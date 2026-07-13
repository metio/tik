;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.secret-test
  "The one secret resolver, exhaustively: a value is a literal, an
  environment variable, a password-manager command (shell string or
  argv vector), or a file — resolved fail-loud, and resolvable ANYWHERE
  in a config via the walk. The single-key spec rule keeps the :command
  effect sink from being mistaken for a secret."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.secret :as secret]))

;; --------------------------------------------------------- spec?

(deftest spec?-is-a-single-key-source-map
  (testing "the three sources, each as a lone key, are specs"
    (is (secret/spec? {:env "X"}))
    (is (secret/spec? {:command ["pass" "show" "x"]}))
    (is (secret/spec? {:command "pass show x"}))
    (is (secret/spec? {:file "/run/secrets/x"}))
    (is (secret/spec? {:credential "opsgenie_token"})))
  (testing "anything else is NOT a spec — so ordinary config passes through"
    (is (not (secret/spec? "a literal string")))
    (is (not (secret/spec? nil)))
    (is (not (secret/spec? 42)))
    (is (not (secret/spec? {})))
    (is (not (secret/spec? {:url "https://x" :token "t"})))
    (testing "the :command effect sink has :type too, so it is not a spec"
      (is (not (secret/spec? {:type :command :command ["sh" "-c" "cat"]}))))
    (testing "a two-key map even with a source key is not a spec"
      (is (not (secret/spec? {:command "x" :extra 1}))))))

;; --------------------------------------------------------- literals

(deftest literals-and-nil-pass-through
  (is (= "plain" (secret/resolve1 "x" "plain")))
  (is (nil? (secret/resolve1 "x" nil)))
  (testing "a non-string, non-spec value is a loud error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a secret"
                          (secret/resolve1 "x" 42)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a secret"
                          (secret/resolve1 "x" {:no :source})))))

;; --------------------------------------------------------- :env

(deftest env-resolves-and-fails-loud
  (testing "a set variable resolves to its value"
    (is (= (System/getenv "PATH") (secret/resolve1 "x" {:env "PATH"}))))
  (testing "an unset variable throws, naming it (never a silent blank)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"TIK_SURELY_UNSET_VAR"
                          (secret/resolve1 "x" {:env "TIK_SURELY_UNSET_VAR"})))))

;; --------------------------------------------------------- :command

(deftest command-string-runs-via-shell-first-line-trimmed
  (is (= "shellsecret"
         (secret/resolve1 "pw" {:command "printf 'shellsecret\\nmetadata'"})))
  (testing "shell features (a pipe) work in the string form"
    (is (= "SECRET"
           (secret/resolve1 "pw" {:command "printf secret | tr a-z A-Z"})))))

(deftest command-vector-runs-without-a-shell
  (is (= "vecsecret"
         (secret/resolve1 "pw" {:command ["printf" "vecsecret\nsecond line"]})))
  (testing "the vector form does NOT interpret shell metacharacters"
    ;; printf writes the literal string; no shell means no pipe/expansion
    (is (str/starts-with?
         (secret/resolve1 "pw" {:command ["printf" "a|b"]}) "a|b"))))

(deftest command-failure-is-loud
  (testing "a nonzero exit throws, naming the command"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed"
                          (secret/resolve1 "pw" {:command ["false"]}))))
  (testing "an unrunnable command fails well (ex-info, not a raw throw)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (secret/resolve1 "pw" {:command ["tik-no-such-binary-xyz"]}))))
  (testing "a malformed :command (not string/vector) is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":command must be"
                          (secret/resolve1 "pw" {:command 42})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":command must be"
                          (secret/resolve1 "pw" {:command [1 2]})))))

;; --------------------------------------------------------- :file

(deftest file-resolves-first-line-and-fails-loud
  (let [dir (h/temp-dir! "tik-secret")
        f (io/file dir "pw")]
    (spit f "filesecret\ntrailing metadata\n")
    (is (= "filesecret" (secret/resolve1 "pw" {:file (str f)})))
    (testing "a missing file throws, naming it"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not exist"
                            (secret/resolve1 "pw" {:file (str (io/file dir "nope"))}))))))

;; --------------------------------------------------------- :credential (systemd)

(deftest systemd-credential-resolves-from-CREDENTIALS_DIRECTORY
  (let [dir (h/temp-dir! "tik-creds")]
    ;; systemd writes each credential as a file named after it, no trailing
    ;; newline; first-line-trimmed yields the exact value
    (spit (io/file dir "opsgenie_token") "cred-SECRET-value")
    (testing "resolves the file named NAME in $CREDENTIALS_DIRECTORY"
      (with-redefs-fn {#'secret/getenv (fn [n] (when (= n "CREDENTIALS_DIRECTORY")
                                                 (str dir)))}
        (fn []
          (is (= "cred-SECRET-value"
                 (secret/resolve1 "tok" {:credential "opsgenie_token"}))))))
    (testing "a missing credential file throws, pointing at LoadCredential="
      (with-redefs-fn {#'secret/getenv (fn [n] (when (= n "CREDENTIALS_DIRECTORY")
                                                 (str dir)))}
        (fn []
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not present"
                                (secret/resolve1 "tok" {:credential "absent"}))))))
    (testing "a name with a slash or .. is rejected (no directory escape)"
      (with-redefs-fn {#'secret/getenv (fn [n] (when (= n "CREDENTIALS_DIRECTORY")
                                                 (str dir)))}
        (fn []
          (doseq [bad ["../etc/passwd" "a/b" "..\\x"]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bare name"
                                  (secret/resolve1 "tok" {:credential bad}))
                bad)))))
    (testing "outside systemd (no $CREDENTIALS_DIRECTORY) it says so clearly"
      (with-redefs-fn {#'secret/getenv (constantly nil)}
        (fn []
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"CREDENTIALS_DIRECTORY is not set"
                                (secret/resolve1 "tok" {:credential "x"}))))))))

;; --------------------------------------------------------- resolve-secrets walk

(deftest resolve-secrets-walks-arbitrary-config
  (testing "every spec anywhere in a sink resolves; other data is untouched"
    (let [sink {:type :pushover
                :url {:command "printf https://api.pushover.net"}
                :token {:command ["printf" "tok123"]}
                :user {:env "PATH"}
                :headers {"Authorization" {:command "printf 'Bearer abc'"}
                          "X-Static" "literal"}
                :template "{{title}}"
                :retries 3}
          out (secret/resolve-secrets sink)]
      (is (= :pushover (:type out)) "keywords pass through")
      (is (= "https://api.pushover.net" (:url out)))
      (is (= "tok123" (:token out)))
      (is (= (System/getenv "PATH") (:user out)))
      (is (= "Bearer abc" (get-in out [:headers "Authorization"])))
      (is (= "literal" (get-in out [:headers "X-Static"])))
      (is (= "{{title}}" (:template out)) "a plain string is not a secret")
      (is (= 3 (:retries out)))))
  (testing "the :command effect sink is left structurally intact (not run)"
    (let [sink {:type :command :command ["sh" "-c" "should not run"]}]
      (is (= sink (secret/resolve-secrets sink)))))
  (testing "nested vectors/sets/maps are walked and preserved"
    (let [cfg {:sinks [{:type :slack :url {:command "printf u1"}}
                       {:type :discord :url "https://plain"}]
               :stages #{:landed :escalated}}
          out (secret/resolve-secrets cfg)]
      (is (= "u1" (get-in out [:sinks 0 :url])))
      (is (= "https://plain" (get-in out [:sinks 1 :url])))
      (is (= #{:landed :escalated} (:stages out)) "a set of keywords survives")
      (is (vector? (:sinks out)) "vector order/shape preserved")))
  (testing "an error names the offending field"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token"
                          (secret/resolve-secrets
                           {:token {:env "TIK_SURELY_UNSET_VAR"}})))))
