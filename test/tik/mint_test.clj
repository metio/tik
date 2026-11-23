;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mint-test
  "Minting from somewhere other than the ticket's repository: tik new
  --repo and --no-repo name the context, --published-only refuses a
  definition nobody but the minter signed, and tik_new speaks both over
  MCP for a shared server whose working directory means nothing."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.args]
            [tik.cli :as cli]
            [tik.harness :as h]
            [tik.mcp :as mcp]))

(defn- holder!
  "A store at <tmp>/holder/.tik with a git repository alpha (carrying a
  .tik-facts.edn) and a plain directory beta below the holder."
  []
  (let [holder (io/file (h/temp-dir! "tik-mint") "holder")
        root (io/file holder ".tik")]
    (.mkdirs (io/file root "tickets"))
    (.mkdirs (io/file holder "alpha" ".git"))
    (spit (io/file holder "alpha" ".tik-facts.edn") "{:team :a}")
    (.mkdirs (io/file holder "beta"))
    {:holder holder :root root}))

(defn- run [root & argv]
  (h/with-cli-root root #(cli/run-argv (mapv str argv))))

(defn- facts-of [root id]
  (:out (run root "status" id)))

(defn- key! [dir name]
  (let [k (io/file dir name)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" name "-f" (str k))
    {:name name :key k :pub (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str k))))}))

(defn- actors! [root & ks]
  (spit (io/file root "actors")
        (str/join (for [{:keys [name pub]} ks] (str name " namespaces=\"tik-*\" " pub "\n")))))

(defn- sole-id [out] (re-find #"[0-9a-f]{8}-[0-9a-f-]{27}" out))

(deftest repo-names-the-context-instead-of-the-working-directory
  (let [{:keys [root]} (holder!)
        r (run root "new" "track" "--title" "in alpha" "--repo" "alpha" "--format" "json")
        data (json/parse-string (:out r) true)]
    (is (zero? (:exit r)) (:err r))
    (is (= {:repo "alpha" :team "a"} (:facts data)) "the repository and its markers, as if run inside it")
    (is (str/includes? (facts-of root (:id data)) "[:repo] = :alpha"))))

(deftest repo-refuses-anything-but-a-repository-directly-below-the-holder
  (let [{:keys [root]} (holder!)]
    (doseq [bad ["beta" "gamma" ".." "../holder" "alpha/.git" "/etc" ".tik"]]
      (let [r (run root "new" "track" "--title" "x" "--repo" bad)]
        (is (= 1 (:exit r)) bad)
        (is (str/includes? (:err r) "no git repository") bad)))
    (is (str/includes? (:out (run root "ls")) "no tickets yet") "a refused mint writes nothing")))

(deftest no-repo-mints-without-a-repository
  (let [{:keys [root]} (holder!)
        r (run root "new" "track" "--title" "nowhere" "--no-repo" "--format" "json")]
    (is (zero? (:exit r)) (:err r))
    (is (= {} (:facts (json/parse-string (:out r) true))))
    (is (= 1 (:exit (run root "new" "track" "--title" "x" "--no-repo" "--repo" "alpha"))))))

(deftest published-only-wants-a-signature-by-somebody-else
  (let [{:keys [holder root]} (holder!)
        maintainer (key! holder "maintainer")
        agent (key! holder "agent")]
    (actors! root maintainer agent)
    (is (zero? (:exit (run root "new" "track" "--title" "materialise" "--no-repo"))))
    (testing "an unsigned definition is refused"
      (let [r (run root "new" "track" "--title" "x" "--no-repo" "--published-only" "--actor" "agent")]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "is not published by anyone but agent"))))
    (testing "the minter's own signature does not count"
      (is (zero? (:exit (run root "process" "sign" "track" "--key" (:key agent)))))
      (is (= 1 (:exit (run root "new" "track" "--title" "x" "--no-repo" "--published-only" "--actor" "agent")))))
    (testing "another registered actor's signature publishes it"
      (is (zero? (:exit (run root "process" "sign" "track" "--key" (:key maintainer)))))
      (is (zero? (:exit (run root "new" "track" "--title" "ok" "--no-repo" "--published-only" "--actor" "agent")))))
    (testing "a signature by a key nobody registered counts for nothing"
      (actors! root agent)
      (is (= 1 (:exit (run root "new" "track" "--title" "x" "--no-repo" "--published-only" "--actor" "agent")))))))

(defn- call [root tool args]
  (let [line (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/call"
                                    :params {:name tool :arguments args}})
        {:keys [result]} (h/with-cli-root root #(mcp/handle line))]
    {:error? (:isError result) :text (-> result :content first :text)}))

(deftest tik-new-over-mcp
  (let [{:keys [holder root]} (holder!)
        maintainer (key! holder "maintainer")]
    (actors! root maintainer)
    (run root "new" "track" "--title" "materialise" "--no-repo")
    (testing "tools/list offers it"
      (let [tools (-> (h/with-cli-root root #(mcp/handle (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/list"})))
                      :result :tools)]
        (is (some #(= "tik_new" (:name %)) tools))))
    (testing "an unpublished definition is refused"
      (let [{:keys [error? text]} (call root "tik_new" {:process "track" :title "x" :repo "alpha"})]
        (is error?)
        (is (str/includes? text "not published"))))
    (run root "process" "sign" "track" "--key" (:key maintainer))
    (testing "a published one mints in the named repository"
      (let [{:keys [error? text]} (call root "tik_new" {:process "track" :title "fix alpha" :repo "alpha"})
            data (json/parse-string text true)]
        (is (not error?) text)
        (is (= {:repo "alpha" :team "a"} (:facts data)))
        (is (sole-id (:id data)))))
    (testing "without a repo the ticket has none, whatever the server's working directory"
      (let [{:keys [text]} (call root "tik_new" {:process "track" :title "no repo"})]
        (is (= {} (:facts (json/parse-string text true))))))
    (testing "missing arguments and a bad repo are answered with words"
      (is (str/includes? (:text (call root "tik_new" {:process "track"})) "missing required argument(s): title"))
      (is (str/includes? (:text (call root "tik_new" {:process "track" :title "x" :repo "../x"})) "no git repository"))
      (is (str/includes? (:text (call root "tik_new" {:process "track" :title "x" :repo 7})) "repo must be a string")))))

(deftest an-agents-text-is-never-read-as-a-flag
  (let [{:keys [holder root]} (holder!)
        maintainer (key! holder "maintainer")]
    (actors! root maintainer)
    (run root "new" "track" "--title" "materialise" "--no-repo")
    (run root "process" "sign" "track" "--key" (:key maintainer))
    (testing "a title that looks like a flag is the title"
      (let [{:keys [error? text]} (call root "tik_new" {:process "track" :title "--edn"})
            data (when-not error? (json/parse-string text true))]
        (is (not error?) text)
        (is (str/includes? (:out (run root "status" (:id data))) "--edn"))))
    (testing "a ticket argument that looks like a flag is a ticket argument"
      (let [{:keys [error? text]} (call root "tik_explain" {:ticket "--all"})]
        (is error?)
        (is (str/includes? text "no ticket starting with '--all'") text)))))

(deftest a-double-dash-ends-the-flags
  (is (= {:pos ["new" "--title" "x"] :opts {:actor "a"}}
         (tik.args/parse-args ["--actor" "a" "--" "new" "--title" "x"]))))
