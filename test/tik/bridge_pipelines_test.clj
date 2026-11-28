;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bridge-pipelines-test
  "Bridges and mailboxes as pipelines of a multi-store backend: the
  config a pipeline may name (its config file, jitter and timeout, the
  backend's :exec and failure report), the delegate keys each store must
  grant, the mailbox rules checked before anything starts, and a backend
  whose runs are child processes each seeing only its own store."
  (:require [briko.paths]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [tik.backend :as backend]
            [tik.backend-stores :as stores]
            [tik.cli :as cli]
            [tik.context :as context]
            [tik.harness :as h]))

(use-fixtures :each (fn [t] (try (t) (finally (context/strict! false)))))

;; ------------------------------------------------------------ fixtures

(defn- key! [dir n]
  (let [k (io/file dir n)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" n "-f" (str k))
    {:key (str k) :pub (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str k))))}))

(defn- store!
  "A store `<base>/<n>/.tik` whose actors file grants `grants`:
  [[actor key] …]."
  [base n grants]
  (let [root (io/file base n ".tik")]
    (.mkdirs (io/file root "tickets"))
    (spit (io/file root "actors")
          (str/join (for [[actor {:keys [pub]}] grants] (str actor " namespaces=\"tik-*\" " pub "\n"))))
    (str (.getCanonicalFile root))))

(defn- estate!
  "Two organisations' stores, as the local estate has them: each with
  its own claude key and its own bridge delegate."
  []
  (let [base (h/temp-dir! "tik-estate")
        ks (into {} (for [n ["metio-claude" "infra-claude" "github-metio" "odoo" "mail" "github-personal"]]
                      [n (key! base n)]))
        metio (store! base "metio" [["claude" (ks "metio-claude")] ["github" (ks "github-metio")]
                                    ["mail" (ks "mail")]])
        infra (store! base "infra" [["claude" (ks "infra-claude")] ["odoo" (ks "odoo")]
                                    ["github" (ks "github-metio")]])]
    {:base base :ks ks :metio metio :infra infra}))

(defn- cfg [{:keys [ks metio infra]} & pipelines]
  {:stores {:metio {:root metio :org "metio" :actor "claude" :key (:key (ks "metio-claude"))}
            :infra {:root infra :org "infra.run" :actor "claude" :key (:key (ks "infra-claude"))}}
   :pipelines (vec pipelines)})

(defn- open [c] (stores/open-stores! c {}))

(defn- problems [c] (str/join "\n" (:problems (open c))))

;; ------------------------------------------------------------ config checks

(def ^:private base-cfg
  {:stores {:metio {:root "/m" :org "metio" :actor "claude" :key "/k"}}
   :pipelines [{:id :github-metio :store :metio :every "PT10M" :run ["bridge" "github"]}]})

(defn- check-with [& kvs]
  (str/join "\n" (stores/check (apply assoc-in base-cfg kvs))))

(deftest a_bridge_pipeline_config_is_accepted
  (is (empty? (stores/check (assoc-in base-cfg [:pipelines 0]
                                      {:id :github-metio :store :metio :every "PT10M" :jitter "PT1M"
                                       :timeout "PT5M" :config "github.edn" :as "github" :key "/k/github"
                                       :run ["bridge" "github"]})))))

(deftest a_config_file_is_named_with_config_not_in_run
  (is (re-find #"may not pass --config"
               (check-with [:pipelines 0 :run] ["bridge" "github" "--config" "github.edn"])))
  (is (re-find #":config must be a path" (check-with [:pipelines 0 :config] ""))))

(deftest jitter_and_timeout_are_checked
  (is (re-find #":jitter \"soon\" is not an ISO-8601 duration" (check-with [:pipelines 0 :jitter] "soon")))
  (is (re-find #":jitter must be shorter than :every" (check-with [:pipelines 0 :jitter] "PT10M")))
  (is (re-find #":timeout \"PT0S\" is not a positive" (check-with [:pipelines 0 :timeout] "PT0S")))
  (is (re-find #":every must be a positive duration" (check-with [:pipelines 0 :every] "PT0S")))
  (testing "a continuous pipeline has neither"
    (let [w {:id :mail :store :metio :watch true :run ["bridge" "imap" "--watch"]}]
      (is (re-find #":timeout is for a scheduled pipeline" (check-with [:pipelines 0] (assoc w :timeout "PT1M"))))
      (is (re-find #":jitter is for a scheduled pipeline" (check-with [:pipelines 0] (assoc w :jitter "PT1M")))))))

(deftest exec_and_the_failure_report_are_checked
  (is (empty? (stores/check (assoc base-cfg :exec ["/usr/bin/tik"]
                                   :on-failure {:run ["notify-send" "{store}/{pipeline}"] :repeat "PT0S"}))))
  (is (re-find #":exec must be a non-empty vector" (str/join (stores/check (assoc base-cfg :exec "/usr/bin/tik")))))
  (is (re-find #":exec must be" (str/join (stores/check (assoc base-cfg :exec [])))))
  (is (re-find #":on-failure needs :run" (str/join (stores/check (assoc base-cfg :on-failure {:run "notify-send"})))))
  (is (re-find #":on-failure has unknown key\(s\) :shell"
               (str/join (stores/check (assoc base-cfg :on-failure {:run ["x"] :shell true})))))
  (is (re-find #":repeat must be an ISO-8601 duration"
               (str/join (stores/check (assoc base-cfg :on-failure {:run ["x"] :repeat "hourly"}))))))

(deftest a_bridge_secret_in_the_environment_refuses_the_backend
  (let [ps (str/join "\n" (stores/environment-problems {"GITHUB_TOKEN" "x" "ODOO_PASSWORD" "y" "HOME" "/h"}))]
    (is (str/includes? ps "GITHUB_TOKEN is set; every store's pipelines would inherit it"))
    (is (str/includes? ps "ODOO_PASSWORD is set"))
    (is (not (str/includes? ps "HOME")))
    (is (not (str/includes? ps "\"x\"")) "a refusal names the variable, never its value")))

(deftest a_config_path_is_relative_to_its_store
  (is (= "/stores/metio/github.edn" (stores/config-path "/stores/metio" "github.edn")))
  (is (= "/stores/metio/bridges/github.edn" (stores/config-path "/stores/metio" "bridges/github.edn")))
  (is (= "/etc/tik/github.edn" (stores/config-path "/stores/metio" "/etc/tik/github.edn")))
  (is (= (str (briko.paths/home) "/github.edn") (stores/config-path "/stores/metio" "~/github.edn")))
  (is (nil? (stores/config-path "/stores/metio" nil))))

;; ------------------------------------------------------------ delegate keys per store

(deftest each_bridge_signs_as_its_delegate_in_its_own_store
  (let [{:keys [ks] :as e} (estate!)
        {:keys [problems pipelines]}
        (open (cfg e {:id :github-metio :store :metio :every "PT10M" :as "github"
                      :key (:key (ks "github-metio")) :run ["bridge" "github"]}
                   {:id :odoo-work :store :infra :every "PT10M" :as "odoo"
                    :key (:key (ks "odoo")) :run ["bridge" "odoo"]}))]
    (is (nil? problems))
    (is (= [["metio" "github" (:key (ks "github-metio"))] ["infra" "odoo" (:key (ks "odoo"))]]
           (mapv #(vector (get-in % [:tik/store :name]) (get-in % [:tik/store :actor]) (get-in % [:tik/store :key]))
                 pipelines)))
    (is (= [600000 60000 nil] ((juxt :every-ms :jitter-ms :timeout-ms) (first pipelines))))))

(deftest a_delegate_key_its_store_does_not_grant_is_refused
  (let [{:keys [ks] :as e} (estate!)]
    (is (re-find #"pipeline :odoo-work: its key is not granted to odoo in store metio's actors file"
                 (problems (cfg e {:id :odoo-work :store :metio :every "PT10M" :as "odoo"
                                   :key (:key (ks "odoo")) :run ["bridge" "odoo"]}))))))

(deftest one_delegate_key_for_two_organisations_needs_saying_so
  (let [{:keys [ks] :as e} (estate!)
        c (cfg e {:id :github-metio :store :metio :every "PT10M" :as "github"
                  :key (:key (ks "github-metio")) :run ["bridge" "github"]}
               {:id :github-infra :store :infra :every "PT10M" :as "github"
                :key (:key (ks "github-metio")) :run ["bridge" "github"]})]
    (is (re-find #"pipelines :github-infra \(infra.run\) and :github-metio \(metio\) sign with the same key"
                 (problems c)))
    (is (nil? (:problems (open (assoc c :allow-shared-keys [[:metio :infra]]))))
        "unless :allow-shared-keys names the pair")))

(deftest a_missing_config_file_is_refused_at_start
  (let [{:keys [ks] :as e} (estate!)]
    (is (re-find #"its :config .*/metio/.tik/github.edn does not exist"
                 (problems (cfg e {:id :github-metio :store :metio :every "PT10M" :as "github" :config "github.edn"
                                   :key (:key (ks "github-metio")) :run ["bridge" "github"]}))))))

;; ------------------------------------------------------------ mailbox pipelines

(defn- mail-cfg [root m]
  (spit (io/file root "imap.edn") (pr-str m)))

(def ^:private mailbox
  {:dkim {:require true :authserv-id "mx.example.org"}
   :default-actor "mail"
   :mailboxes [{:id "support-metio-wtf" :process :support
                :imap {:host "imap.example.org" :user "support@metio.wtf"
                       :password {:command ["pasejo" "secret" "show" "email/metio.wtf/support"]}}}]})

(defn- mail-pipeline [ks]
  {:id :mail-metio :store :metio :watch true :as "mail" :key (:key (ks "mail"))
   :run ["bridge" "imap" "--watch"]})

(deftest a_mailbox_pipeline_is_checked_before_the_backend_starts
  (let [{:keys [ks metio] :as e} (estate!)]
    (testing "a good mailbox list opens"
      (mail-cfg metio mailbox)
      (is (nil? (:problems (open (cfg e (mail-pipeline ks)))))))
    (testing "a mailbox without DKIM refuses the backend"
      (mail-cfg metio (dissoc mailbox :dkim))
      (is (re-find #"pipeline :mail-metio: .*imap.edn: mailbox \"support-metio-wtf\": needs :dkim"
                   (problems (cfg e (mail-pipeline ks))))))
    (testing "so does plaintext to a remote host"
      (mail-cfg metio (assoc-in mailbox [:mailboxes 0 :imap :tls] false))
      (is (re-find #"plaintext IMAP \(:tls false\) is refused for imap.example.org"
                   (problems (cfg e (mail-pipeline ks))))))
    (testing "a single-mailbox config is held to the same rules under a backend"
      (mail-cfg metio {:imap {:host "imap.example.org" :user "u" :password "literal"} :process :support
                       :default-actor "mail"})
      (let [ps (problems (cfg e (mail-pipeline ks)))]
        (is (re-find #":password is a literal" ps))
        (is (not (str/includes? ps "literal\"")) "and the refusal does not repeat the password")))
    (testing "no config at all"
      (.delete (io/file metio "imap.edn"))
      (is (re-find #"its mailbox config .*imap.edn does not exist" (problems (cfg e (mail-pipeline ks))))))))

(deftest every_actor_a_mailbox_records_mail_as_must_be_granted_the_pipeline_key
  (let [{:keys [ks metio] :as e} (estate!)]
    (mail-cfg metio (assoc mailbox :default-actor "inbound"))
    (is (re-find #"mailbox support-metio-wtf records mail as inbound, but the pipeline's key is not granted to inbound"
                 (problems (cfg e (mail-pipeline ks)))))
    (mail-cfg metio (assoc mailbox :from->actor {"seb@example.org" "seb"}))
    (is (re-find #"records mail as seb" (problems (cfg e (mail-pipeline ks))))
        "a From mapping would sign as seb with the mail key, which seb's grant does not cover")))

;; ------------------------------------------------------------ the backend runs children

(deftest a_backend_without_a_way_to_start_itself_refuses
  (let [{:keys [ks base] :as e} (estate!)
        f (io/file base "backend.edn")]
    (spit f (pr-str (cfg e {:id :github-metio :store :metio :every "PT10M" :as "github"
                            :key (:key (ks "github-metio")) :run ["bridge" "github"]})))
    (let [r (binding [backend/*backend-ticks* 1] (cli/run-argv ["backend" "--config" (str f)]))]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "cannot name its own executable")))))

(deftest each_run_is_a_child_that_sees_only_its_own_store
  (let [{:keys [ks base metio infra] :as e} (estate!)
        stub (io/file base "stub.sh")
        f (io/file base "backend.edn")]
    (spit stub (str "#!/bin/sh\n"
                    "echo \"root=$TIK_ROOT actor=$TIK_ACTOR key=$TIK_KEY args=$*\"\n"
                    "[ -n \"$GITHUB_TOKEN\" ] && echo leaked\n"
                    "exit 0\n"))
    (spit (io/file metio "github.edn") "{}")
    (spit f (pr-str (assoc (cfg e {:id :github-metio :store :metio :every "PT1H" :timeout "PT30S" :as "github"
                                   :key (:key (ks "github-metio")) :config "github.edn"
                                   :run ["bridge" "github"]}
                                {:id :odoo-work :store :infra :every "PT1H" :jitter "PT0S" :timeout "PT30S"
                                 :as "odoo" :key (:key (ks "odoo")) :run ["bridge" "odoo"]})
                           :exec ["sh" (str stub)])))
    (let [r (binding [backend/*backend-ticks* 2] (cli/run-argv ["backend" "--config" (str f)]))
          out (:out r)]
      (is (zero? (:exit r)) (:err r))
      (is (re-find (re-pattern (str "\\[metio/github-metio\\] root=" metio " actor=github key=\\S+github-metio"
                                    " args=bridge github --actor github --config " metio "/github.edn"))
                   out)
          "the metio run got metio's root, its delegate's key and its config file")
      (is (re-find (re-pattern (str "(?m)\\[infra/odoo-work\\] root=" infra " actor=odoo key=\\S+odoo args=bridge odoo --actor odoo$"))
                   (str/join "\n" (map #(str/replace % #"^<\d>" "") (str/split-lines out))))
          "the infra.run run got infra.run's")
      (is (re-find #"\[metio/github-metio\] bridge github --actor github -> ok" out))
      (is (re-find #"\[infra/odoo-work\] bridge odoo --actor odoo -> ok" out))
      (is (not (str/includes? out "leaked"))))))
