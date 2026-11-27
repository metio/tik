;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.multi-store-test
  "One backend, several stores (ADR 0041): the config refusals, the
  checks made while the stores open, and the isolation each store keeps
  — its root and derivation, its actors and key, its MCP token and path —
  including under concurrent requests to several stores."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [tik.backend :as backend]
            [tik.backend-stores :as stores]
            [tik.cli :as cli]
            [tik.cli-core :as core]
            [tik.context :as context]
            [tik.args]
            [tik.harness :as h]
            [tik.notifier :as notifier]
            [tik.serve :as serve]
            [tik.sign :as sign]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.nio.file Files LinkOption)
           (java.nio.file.attribute PosixFilePermissions)))

(use-fixtures :each (fn [t] (try (t) (finally (context/strict! false)))))

;; ------------------------------------------------------------ fixtures

(defn- key! [dir name]
  (let [k (io/file dir name)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" name "-f" (str k))
    {:name name :key (str k) :pub (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str k))))
     :fpr (sign/fingerprint (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str k)))))}))

(defn- actors! [root & grants]
  (spit (io/file root "actors")
        (str/join (for [[actor {:keys [pub]}] grants] (str actor " namespaces=\"tik-*\" " pub "\n")))))

(defn- token-file! [dir name token]
  (let [f (io/file dir name)]
    (spit f token)
    (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rw-------"))
    (str f)))

(defn- in [ctx & argv]
  (context/with-store ctx (cli/run-argv (mapv str argv))))

(defn- fixture-store!
  "A store at <tmp>/<name>/.tik whose actors file grants `claude` the
  store's own key and `maintainer` a second key, with the track process
  published by the maintainer so an agent may mint over MCP."
  [base sname]
  (let [holder (io/file base sname)
        root (doto (io/file holder ".tik" "tickets") .mkdirs)
        root (.getParentFile root)
        claude (key! holder "claude")
        maint (key! holder "maintainer")]
    (actors! root ["claude" claude] ["maintainer" maint])
    (let [m {:name sname :root (str (.getCanonicalFile root)) :key (:key maint) :actor "maintainer"}]
      (in m "new" "track" "--title" (str "seed " sname) "--no-repo")
      (in m "process" "sign" "track"))
    {:name sname :root (.getCanonicalFile root) :claude claude :maint maint
     :token (apply str sname "-" (repeat 40 "t"))}))

(defn- ctx-of [{:keys [name root claude]}]
  {:name name :root (str root) :key (:key claude) :actor "claude" :org name})

(defn- two-stores! []
  (let [base (h/temp-dir! "tik-multi")]
    {:base base :a (fixture-store! base "alpha") :b (fixture-store! base "beta")}))

(defn- every-signature
  "{:type :actor :fpr :ok?} for every authorship sidecar in the store at
  `root`, verified against that store's own actors file over the exact
  stored bytes."
  [root]
  (let [s (fstore/file-store (str root))]
    (vec (for [id (store/ticket-ids s)
               e (store/events s id)
               :let [bytes (store/event-bytes s id (:event/id e))]
               n (core/sidecar-names-for s id (:event/id e) "sig")
               :let [sig (store/read-sidecar s id n)]]
           {:type (:event/type e) :actor (:event/actor e) :fpr (last (str/split n #"\."))
            :ok? (boolean (sign/verify-bytes (io/file root "actors") bytes sig (:event/actor e)))}))))

(defn- creates-by [sigs actor]
  (filter #(and (= actor (:actor %)) (= :ticket/create (:type %))) sigs))

;; ------------------------------------------------------------ config checks

(def ^:private good
  {:stores {:alpha {:root "/a" :org "a" :actor "claude" :key "/k/a" :mcp-token-file "/t/a"}
            :beta {:root "/b" :org "b" :actor "claude" :key {:command ["cat" "/k/b"]}}}
   :serve {:port 7777}
   :pipelines [{:id :sweep :store :alpha :every "PT10M" :run ["probe"]}]})

(deftest a-good-config-has-no-problems
  (is (= [] (stores/check good)))
  (is (stores/multi-store? good))
  (is (not (stores/multi-store? {:pipelines []})) "a pipelines.edn stays single-store"))

(deftest the-config-matrix
  (doseq [[why cfg expect]
          [["no stores" (assoc good :stores {}) ":stores must map"]
           ["not a map" [] "must be a map"]
           ["unknown top key" (assoc good :store {}) "unknown key(s) :store"]
           ["bad store name" (assoc-in good [:stores :Alpha!] (get-in good [:stores :alpha])) "a store name is a keyword"]
           ["string store name" (assoc-in good [:stores "gamma"] (get-in good [:stores :alpha])) "a store name is a keyword"]
           ["unknown store key" (assoc-in good [:stores :alpha :mcp-token] "/t") "unknown key(s) :mcp-token"]
           ["no root" (update-in good [:stores :alpha] dissoc :root) "needs :root"]
           ["no org" (update-in good [:stores :alpha] dissoc :org) "needs :org"]
           ["no actor" (update-in good [:stores :alpha] dissoc :actor) "needs :actor"]
           ["pattern actor" (assoc-in good [:stores :alpha :actor] "claude,*") "needs :actor"]
           ["no key" (update-in good [:stores :alpha] dissoc :key) "needs :key"]
           ["env key" (assoc-in good [:stores :alpha :key] {:env "TIK_KEY"}) "{:env …} is refused for a key"]
           ["two-key spec" (assoc-in good [:stores :alpha :key] {:file "/x" :command ["y"]}) "needs :key"]
           ["board not boolean" (assoc-in good [:stores :alpha :board] "yes") ":board must be true or false"]
           ["shared root" (assoc-in good [:stores :beta :root] "/a") "share the root /a"]
           ["shared token" (assoc-in good [:stores :beta :mcp-token-file] "/t/a") "a token opens exactly one store"]
           ["token without serve" (dissoc good :serve) "no :serve to answer MCP on"]
           ["bad port" (assoc good :serve {:port 0}) ":serve :port must be a port number"]
           ["unknown serve key" (assoc good :serve {:port 1 :tls true}) ":serve has unknown key(s) :tls"]
           ["notify names stores" (assoc good :notify {:stores {}}) "the notifier watches the backend's :stores"]
           ["pipeline without store" (assoc good :pipelines [{:id :x :every "PT1M" :run ["probe"]}]) "needs :store naming one of"]
           ["pipeline unknown store" (assoc good :pipelines [{:id :x :store :gamma :every "PT1M" :run ["probe"]}]) "needs :store naming one of"]
           ["pipeline passes --key" (assoc good :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe" "--key" "/k/b"]}]) "may not pass --key"]
           ["pipeline passes --key=" (assoc good :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe" "--key=/k/b"]}]) "may not pass --key"]
           ["pipeline passes --actor" (assoc good :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe" "--actor" "seb"]}]) "may not pass --actor"]
           ["pipeline runs serve" (assoc good :pipelines [{:id :x :store :alpha :watch true :run ["serve"]}]) "which the backend serves itself"]
           ["pipeline runs notify" (assoc good :pipelines [{:id :x :store :alpha :watch true :run ["notify"]}]) "which the backend serves itself"]
           ["pipeline bad as" (assoc good :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe"] :as "a b"}]) ":as must be a principal"]
           ["pipeline env key" (assoc good :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe"] :key {:env "K"}}]) ":key must be a file path"]
           ["pipeline unknown key" (assoc good :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe"] :root "/b"}]) "unknown key(s) :root"]
           ["pipeline bad every" (assoc good :pipelines [{:id :x :store :alpha :every "5m" :run ["probe"]}]) "is not an ISO-8601 duration"]
           ["pipeline no schedule" (assoc good :pipelines [{:id :x :store :alpha :run ["probe"]}]) "needs :every"]
           ["pipeline twice" (assoc good :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe"]}
                                                     {:id :x :store :beta :every "PT1M" :run ["probe"]}]) "is named twice"]
           ["non-string run" (assoc good :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe" 1]}]) "vector of strings"]
           ["bad shared keys" (assoc good :allow-shared-keys [[:alpha :gamma]]) ":allow-shared-keys must be"]
           ["nothing to run" (dissoc (update good :stores update-vals #(dissoc % :mcp-token-file)) :serve :pipelines) "nothing to run"]]]
    (testing why
      (let [problems (stores/check cfg)]
        (is (some #(str/includes? % expect) problems) (pr-str problems))))))

(deftest a-single-store-config-cannot-name-a-store
  (let [{:keys [root]} (h/temp-store!)]
    (spit (io/file root "pipelines.edn")
          (pr-str {:pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe"]}]}))
    (let [r (h/with-cli-root root #(cli/run-argv ["backend" "--config" (str (io/file root "pipelines.edn"))]))]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "has no :stores")))))

;; ------------------------------------------------------------ opening stores

(defn- cfg-for [{:keys [a b]} & {:as over}]
  (merge {:stores {:alpha {:root (str (:root a)) :org "alpha" :actor "claude" :key (:key (:claude a))
                           :mcp-token-file (token-file! (.getParentFile ^java.io.File (:root a)) "token" (:token a))}
                   :beta {:root (str (:root b)) :org "beta" :actor "claude" :key (:key (:claude b))
                          :mcp-token-file (token-file! (.getParentFile ^java.io.File (:root b)) "token" (:token b))}}
          :serve {:port 1}}
         over))

(defn- problems-of [cfg & [env]]
  (str/join "\n" (:problems (stores/open-stores! cfg (or env {})))))

(deftest stores-open-with-their-own-key-and-actor
  (let [fx (two-stores!)
        {opened :stores :keys [problems]} (stores/open-stores! (cfg-for fx) {})]
    (is (nil? problems))
    (is (= #{"alpha" "beta"} (set (keys opened))))
    (is (= (:key (:claude (:a fx))) (get-in opened ["alpha" :ctx :key])))
    (is (= (:key (:claude (:b fx))) (get-in opened ["beta" :ctx :key])))
    (is (= (:token (:a fx)) (get-in opened ["alpha" :token])))))

(deftest a-store-variable-in-the-environment-refuses-the-backend
  (let [fx (two-stores!)]
    (doseq [n ["TIK_KEY" "TIK_ROOT" "TIK_ACTOR" "TIK_PQ_KEY"]]
      (is (str/includes? (problems-of (cfg-for fx) {n "x"}) (str n " is set")) n))))

(deftest a-key-the-stores-own-actors-file-does-not-grant-is-refused
  (let [{:keys [a b] :as fx} (two-stores!)]
    (testing "beta's key named for alpha"
      (is (str/includes? (problems-of (assoc-in (cfg-for fx) [:stores :alpha :key] (:key (:claude b))))
                         "store alpha: its key is not granted to claude")))
    (testing "an actor alpha does not know"
      (is (str/includes? (problems-of (assoc-in (cfg-for fx) [:stores :alpha :actor] "nobody"))
                         "not granted to nobody")))
    (testing "one broken store refuses every store"
      (let [r (stores/open-stores! (assoc-in (cfg-for fx) [:stores :alpha :key] (:key (:claude b))) {})]
        (is (nil? (:stores r)))
        (is (seq (:problems r)))))
    (testing "a store without an actors file"
      (.delete (io/file (:root a) "actors"))
      (is (str/includes? (problems-of (cfg-for fx)) "has no actors file")))))

(deftest a-root-that-is-not-a-store-is-refused
  (let [fx (two-stores!)
        nowhere (h/temp-dir! "not-a-store")]
    (is (str/includes? (problems-of (assoc-in (cfg-for fx) [:stores :alpha :root] (str nowhere)))
                       "is not a tik store"))))

(deftest a-key-that-is-not-a-key-is-refused-without-showing-it
  (let [fx (two-stores!)
        junk (h/temp-file! "junk" ".key")]
    (spit junk "secret-material-that-must-not-leak")
    (let [p (problems-of (assoc-in (cfg-for fx) [:stores :alpha :key] (str junk)))]
      (is (str/includes? p "its :key is not an OpenSSH private key"))
      (is (not (str/includes? p "secret-material"))))
    (testing "a failing command names the command and its stderr, never its stdout"
      (let [p (problems-of (assoc-in (cfg-for fx) [:stores :alpha :key]
                                     {:command ["sh" "-c" "echo leaked-$((40+2)); echo nope >&2; exit 3"]}))]
        (is (str/includes? p "failed: nope"))
        (is (not (str/includes? p "leaked-42")))))))

(deftest a-command-key-lives-in-a-private-runtime-file
  (let [{:keys [a] :as fx} (two-stores!)
        cfg (assoc-in (cfg-for fx) [:stores :alpha :key] {:command ["cat" (:key (:claude a))]})
        {opened :stores :keys [problems]} (stores/open-stores! cfg {})
        f (io/file (get-in opened ["alpha" :ctx :key]))
        perms #(PosixFilePermissions/toString (Files/getPosixFilePermissions (.toPath ^java.io.File %) (make-array LinkOption 0)))]
    (is (nil? problems))
    (is (not= (:key (:claude a)) (str f)) "the command's output was written to a new file")
    (is (= "rw-------" (perms f)))
    (is (= "rwx------" (perms (.getParentFile f))))
    (is (= (slurp (:key (:claude a))) (slurp f)))))

(deftest a-shared-key-across-organisations-needs-saying-so
  (let [{:keys [a b] :as fx} (two-stores!)]
    ;; beta also grants alpha's key to claude, so only the org check can object
    (actors! (:root b) ["claude" (:claude a)])
    (let [cfg (assoc-in (cfg-for fx) [:stores :beta :key] (:key (:claude a)))]
      (is (str/includes? (problems-of cfg) "resolve to the same key"))
      (is (= "" (problems-of (assoc cfg :allow-shared-keys [[:beta :alpha]])))
          "named, the pair may share it")
      (is (= "" (problems-of (assoc-in cfg [:stores :beta :org] "alpha")))
          "one organisation may sign several of its stores with one key"))))

(deftest mcp-tokens-are-checked-when-the-stores-open
  (let [{:keys [a] :as fx} (two-stores!)
        dir (.getParentFile ^java.io.File (:root a))]
    (testing "short"
      (is (str/includes? (problems-of (assoc-in (cfg-for fx) [:stores :alpha :mcp-token-file] (token-file! dir "short" "abc")))
                         "at least 32 characters")))
    (testing "readable by others"
      (let [f (token-file! dir "open" (:token a))]
        (Files/setPosixFilePermissions (.toPath (io/file f)) (PosixFilePermissions/fromString "rw-r--r--"))
        (is (str/includes? (problems-of (assoc-in (cfg-for fx) [:stores :alpha :mcp-token-file] f))
                           "is readable by others"))))
    (testing "missing"
      (is (str/includes? (problems-of (assoc-in (cfg-for fx) [:stores :alpha :mcp-token-file] "/nonexistent/t"))
                         "does not exist")))))

(deftest a-pipeline-signs-only-as-an-actor-its-store-grants
  (let [{:keys [b] :as fx} (two-stores!)
        base (cfg-for fx)]
    (is (str/includes? (problems-of (assoc base :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe"] :as "maintainer"}]))
                       "pipeline :x: its key is not granted to maintainer")
        ":as maintainer with claude's key")
    (is (= "" (problems-of (assoc base :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe"]}]))))
    (testing "a pipeline key from another organisation's store"
      (actors! (:root (:a fx)) ["claude" (:claude (:a fx))] ["bridge" (:claude b)])
      (is (str/includes? (problems-of (assoc base :pipelines [{:id :x :store :alpha :every "PT1M" :run ["probe"]
                                                               :as "bridge" :key (:key (:claude b))}]))
                         "signs with the key of store beta, another organisation's")))))

;; ------------------------------------------------------------ isolation

(deftest a-store-context-ignores-the-environment-and-refuses-another-key
  (let [{:keys [a b]} (two-stores!)
        ctx (ctx-of a)]
    (context/with-store ctx
      (is (= (str (:root a)) (core/root)))
      (is (= (:key (:claude a)) (core/signing-key {})))
      (is (= (:key (:claude a)) (core/signing-key {:key (:key (:claude a))})) "its own key, named, is fine")
      (is (= "claude" (tik.args/actor {})))
      (is (nil? (core/pq-signing-key {})) "no post-quantum key unless the store names one"))
    (let [r (in ctx "new" "track" "--title" "x" "--no-repo" "--key" (:key (:claude b)))]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "signs only with its own key")))))

(deftest a-strict-process-refuses-to-guess-a-store
  (context/strict!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside any of them" (core/root)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside any of them" (core/signing-key {})))
  (let [{:keys [a]} (two-stores!)]
    (is (= (str (:root a)) (context/with-store (ctx-of a) (core/root))) "inside a store it answers")))

(deftest a-child-process-gets-its-stores-variables-only
  (let [env (context/with-store {:name "a" :root "/a" :key "/k/a" :actor "claude"} (context/child-env))]
    (is (= "/a" (get env "TIK_ROOT")))
    (is (= "/k/a" (get env "TIK_KEY")))
    (is (= "claude" (get env "TIK_ACTOR")))
    (is (not (contains? env "TIK_PQ_KEY")))))

(deftest derived-rows-stay-in-their-own-stores-cache
  (let [{:keys [a b]} (two-stores!)
        ca (ctx-of a) cb (ctx-of b)
        id-a (re-find #"[0-9a-f]{8}-[0-9a-f-]{27}" (:out (in ca "new" "track" "--title" "alpha-only" "--no-repo")))]
    (is (zero? (:exit (in ca "ls"))))
    (is (zero? (:exit (in cb "ls"))))
    (let [cache-b (io/file (:root b) ".derived-cache.json")]
      (is (.isFile cache-b))
      (is (not (str/includes? (slurp cache-b) id-a)) "alpha's ticket is not in beta's cache")
      (is (not (str/includes? (slurp cache-b) "alpha-only"))))
    (is (not (str/includes? (:out (in cb "ls" "--all")) "alpha-only")))))

(defn- router [{:keys [a b]}]
  (serve/store-router {:host "127.0.0.1" :port 7777
                       :stores {"alpha" {:ctx (ctx-of a) :token (:token a) :board? true}
                                "beta" {:ctx (ctx-of b) :token (:token b) :board? true}}}))

(defn- rpc [handler store token method params]
  (let [r (handler {:uri (str "/s/" store "/mcp") :request-method :post
                    :headers {"host" "127.0.0.1:7777" "authorization" (str "Bearer " token)}
                    :body (json/generate-string {:jsonrpc "2.0" :id 1 :method method :params params})})]
    (cond-> r (= 200 (:status r)) (update :body json/parse-string true))))

(deftest each-store-answers-on-its-own-path-with-its-own-token
  (let [{:keys [a b] :as fx} (two-stores!)
        handler (router fx)
        get* #(handler {:uri % :request-method :get :headers {"host" "127.0.0.1:7777"}})]
    (testing "a token opens its own store only"
      (is (= 200 (:status (rpc handler "alpha" (:token a) "tools/list" {}))))
      (is (= 401 (:status (rpc handler "beta" (:token a) "tools/list" {}))) "alpha's token on beta's path")
      (is (= 401 (:status (rpc handler "alpha" (:token b) "tools/list" {}))) "beta's token on alpha's path"))
    (testing "unknown paths"
      (is (= 404 (:status (get* "/"))))
      (is (= 404 (:status (get* "/mcp"))))
      (is (= 404 (:status (get* "/s/gamma/"))))
      (is (= 404 (:status (get* "/s/../alpha/")))))
    (testing "each board renders its own store"
      (is (str/includes? (:body (get* "/s/alpha/tickets.edn")) "seed alpha"))
      (is (not (str/includes? (:body (get* "/s/alpha/tickets.edn")) "seed beta")))
      (is (str/includes? (:body (get* "/s/beta/")) "seed beta")))
    (testing "a rebinding host is refused before routing"
      (is (= 421 (:status (handler {:uri "/s/alpha/" :request-method :get :headers {"host" "evil.example"}})))))
    (testing "the skill manifest is signed by the store's own actor and key"
      (let [skills (get-in (rpc handler "beta" (:token b) "skills/list" {}) [:body :result :skills])
            meta (some-> skills first :_meta vals first)]
        (is (seq skills))
        (is (= "claude" (:actor meta)))
        (is (true? (:signed meta)))))))

(deftest an-mcp-write-signs-with-its-stores-key
  (let [{:keys [a b] :as fx} (two-stores!)
        handler (router fx)
        r (rpc handler "beta" (:token b) "tools/call" {:name "tik_new" :arguments {:process "track" :title "via mcp"}})]
    (is (false? (get-in r [:body :result :isError])) (pr-str r))
    (let [beta (every-signature (:root b))
          alpha (every-signature (:root a))]
      (is (some #(and (= "claude" (:actor %)) (= (:fpr (:claude b)) (:fpr %)) (:ok? %)) beta))
      (is (every? :ok? beta))
      (is (not-any? #(= (:fpr (:claude b)) (:fpr %)) alpha) "nothing in alpha carries beta's key"))))

(deftest concurrent-writes-to-several-stores-each-sign-with-their-own-key
  (let [{:keys [a b] :as fx} (two-stores!)
        handler (router fx)
        n 24
        ops (vec (for [i (range n)] [(if (even? (quot i 2)) :a :b) (if (even? i) :cli :mcp) i]))
        results (doall
                 (pmap (fn [[which via i]]
                         (let [s (if (= :a which) a b)
                               title (str (:name s) "-write-" i)]
                           (case via
                             :cli (:exit (in (ctx-of s) "new" "track" "--title" title "--no-repo"))
                             :mcp (if (get-in (rpc handler (:name s) (:token s) "tools/call"
                                                   {:name "tik_new" :arguments {:process "track" :title title}})
                                              [:body :result :isError])
                                    1 0))))
                       (shuffle ops)))]
    (is (every? zero? results))
    (doseq [[s other] [[a b] [b a]]]
      (let [sigs (every-signature (:root s))
            claude-sigs (filter #(= "claude" (:actor %)) sigs)]
        (testing (:name s)
          (is (= (quot n 2) (count (creates-by sigs "claude"))) "one signed create per write, all of them in this store")
          (is (every? :ok? sigs) "every signature verifies against this store's own actors file")
          (is (every? #(= (:fpr (:claude s)) (:fpr %)) claude-sigs) "and is this store's key")
          (is (not-any? #(= (:fpr (:claude other)) (:fpr %)) sigs) "never the other store's key")
          (is (not (str/includes? (:out (in (ctx-of s) "ls" "--all")) (str (:name other) "-write-")))
              "and no write landed in the other store"))))))

;; ------------------------------------------------------------ the backend

(deftest one-backend-runs-each-stores-pipelines-in-that-store
  (let [{:keys [a b] :as fx} (two-stores!)
        cfg-file (io/file (:base fx) "backend.edn")]
    (spit cfg-file (pr-str (-> (cfg-for fx)
                               (update :stores update-vals #(dissoc % :mcp-token-file))
                               (dissoc :serve)
                               (assoc :pipelines [{:id :weekly-a :store :alpha :every "PT0.05S" :run ["recur" "track"] :period :iso-week}
                                                  {:id :weekly-b :store :beta :every "PT0.05S" :run ["recur" "track"] :period :iso-week}]))))
    (let [r (binding [backend/*backend-ticks* 2] (cli/run-argv ["backend" "--config" (str cfg-file)]))]
      (is (zero? (:exit r)) (:err r))
      (is (str/includes? (:out r) "2 store(s) (alpha, beta)"))
      (is (str/includes? (:out r) "[alpha/weekly-a] recur track"))
      (is (str/includes? (:out r) "[beta/weekly-b] recur track")))
    (doseq [s [a b]]
      (let [sigs (filter #(= "claude" (:actor %)) (every-signature (:root s)))]
        (is (= 1 (count (creates-by sigs "claude"))) (str (:name s) " got exactly its own recur ticket"))
        (is (every? #(and (:ok? %) (= (:fpr (:claude s)) (:fpr %))) sigs))))))

(deftest a-backend-with-one-broken-store-starts-none
  (let [{:keys [a b] :as fx} (two-stores!)
        cfg-file (io/file (:base fx) "backend.edn")]
    (spit cfg-file (pr-str (-> (cfg-for fx)
                               (assoc-in [:stores :beta :key] (:key (:claude a)))
                               (assoc :pipelines [{:id :weekly-a :store :alpha :every "PT0.05S" :run ["recur" "track"] :period :iso-week}]))))
    (let [r (binding [backend/*backend-ticks* 1] (cli/run-argv ["backend" "--config" (str cfg-file)]))]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "no store was started"))
      (is (str/includes? (:err r) "store beta: its key is not granted to claude")))
    (is (empty? (filter #(= "claude" (:actor %)) (every-signature (:root a))))
        "alpha's pipeline did not run either")
    (is (not (context/strict?)) "a refused backend never entered multi-store mode")
    (is (some? b))))

(deftest the-notifier-watches-the-backends-stores
  (let [cfg (notifier/config-from {:stores {"alpha" "/a" "beta" "~/b"} :poll "PT1S"} {})]
    (is (= {"alpha" "/a" "beta" (str (System/getProperty "user.home") "/b")} (:stores cfg)))
    (is (= 1000 (:poll-ms cfg)))))

(deftest a-bound-root-keeps-its-derived-rows-to-itself
  ;; the notifier derives several stores in one process by binding *root*
  (let [{:keys [a b]} (two-stores!)
        ls (fn [root] (binding [core/*root* (str root)] (cli/run-argv ["ls" "--all"])))]
    (binding [core/*root* (str (:root a))]
      (cli/run-argv ["new" "track" "--title" "only-in-alpha" "--no-repo" "--actor" "maintainer"]))
    (is (zero? (:exit (ls (:root a)))))
    (is (zero? (:exit (ls (:root b)))))
    (is (not (str/includes? (slurp (io/file (:root b) ".derived-cache.json")) "only-in-alpha")))))
