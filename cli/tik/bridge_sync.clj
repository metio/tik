;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bridge-sync
  "The polling bridges that mirror another system into the store:
  `bridge github` and `bridge gitlab` (CI failure streaks, tik.forge) and
  `bridge redmine` (issues, tik.redmine). Each mints events that are a
  pure function of the remote history and appends the ones the store
  lacks, so a re-poll writes nothing and replicas converge by union
  (ADR 0021). The remote enters as an injected `get-json`, which keeps the
  sync functions testable without a network."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [read-edn-file]]
            [tik.cli-core :refer [append!* archive-process! cache-flush! claimed-at
                                  die exit! load-process now repo-key root the-store]]
            [tik.forge :as forge]
            [tik.github :as gh]
            [tik.gitlab :as gl]
            [tik.redmine :as rm]
            [tik.render :refer [sid]]
            [tik.secret :as secret]
            [tik.store.protocol :as store]
            [tik.text :refer [safe-name]])
  (:import (java.time Duration Instant)))

;; ---------------------------------------------------------------- shared

(defn- create-id
  "The id of the ticket's create event, or nil when the ticket is absent."
  [s id]
  (some #(when (= :ticket/create (:event/type %)) (:event/id %))
        (store/events s id)))

(defn- append-fresh!
  "Append the events this store lacks, in order; returns how many."
  [s events opts]
  (let [fresh (remove #(store/has-event? s (:event/id %)) events)]
    (doseq [e fresh] (append!* s e opts))
    (count fresh)))

(defn bridge-ctx
  "The bridge actor and the pinned process every ticket is minted under,
  plus the clamp that keeps a forward-dated remote timestamp from sorting
  last forever."
  [{:keys [process actor]} opts]
  (let [proc-name (safe-name (name process))
        proc (load-process proc-name)]
    {:actor (or (:actor opts) actor)
     :process (keyword proc-name)
     :version (:process/version proc)
     :process-hash (archive-process! proc)
     :clamp #(claimed-at % (now))}))

(defn- read-config [opts file hint]
  (let [f (io/file (or (:config opts) (str (io/file (root) file))))]
    (when-not (.exists f)
      (die (str "no config at " f " (" hint ")")))
    (read-edn-file f)))

(defn- since [block]
  (.minus ^Instant (now) (Duration/parse (or (:lookback block) "P14D"))))

(defn- tallying []
  (let [tally (atom {})]
    {:tally tally
     :bump #(swap! tally update % (fnil inc 0))
     :report (fn [what e]
               (swap! tally update :error (fnil inc 0))
               (binding [*out* *err*]
                 (println (str "error " what ": " (ex-message e)))))}))

(defn- finish! [label tally]
  (cache-flush!)
  (println (str label " sweep complete: " (into (sorted-map) tally)))
  (exit! (if (:error tally) 1 0)))

(defn- outcome-line [{:keys [outcome id events repo branch workflow-id oldest]}]
  (case outcome
    :ticket (str "new ticket " (sid id) " (" events " event(s))")
    :update (str "+" events " event(s) -> " (sid id))
    :recovered (str "recovered " (sid id))
    :unknown-start (str "skip " repo " workflow " workflow-id "@" branch
                        ": streak starts before the fetched history (oldest "
                        oldest "); raise :max-pages")
    (str outcome)))

;; ---------------------------------------------------------------- ci streaks

(defn sync-streaks!
  "Bring one workflow's streaks on one branch into the store. `ctx` is a
  bridge-ctx plus :host :repo :branch :workflow-id; `history` is
  {:runs :complete?} in the tik.forge run shape; `cfg` holds the
  conclusion sets. Returns outcome maps {:outcome :ticket|:update|
  :recovered|:unknown-start :id … :events n}; never prints."
  [s ctx cfg {:keys [runs complete?]} since opts]
  (let [ctx (assoc ctx :repo-key (repo-key (last (str/split (:repo ctx) #"/"))))]
    (doall
     (for [st (forge/streaks cfg runs complete?)
           :when (forge/in-lookback? since st)
           :let [r (cond
                     (:rerun st)
                     (let [rr (:rerun st)
                           id (forge/streak-ticket-id ctx rr)]
                       (when-let [cid (create-id s id)]
                         (let [n (append-fresh! s [(forge/green-event ctx id cid rr)] opts)]
                           (when (pos? n) {:outcome :recovered :id id :events n}))))

                     (not (:known-start? st))
                     {:outcome :unknown-start :repo (:repo ctx) :branch (:branch ctx)
                      :workflow-id (:workflow-id ctx) :oldest (:url (first (:failed st)))}

                     :else
                     (let [{:keys [id events blobs]} (forge/streak-events ctx st)
                           created? (nil? (create-id s id))]
                       (doseq [[h t] blobs] (forge/write-blob! (root) id h t))
                       (let [n (append-fresh! s events opts)]
                         (when (pos? n)
                           {:outcome (cond created? :ticket
                                           (:green st) :recovered
                                           :else :update)
                            :id id :events n}))))]
           :when r]
       r))))

(defn- ci-defaults [block]
  {:process (or (:process block) :ci-failure)})

;; ---------------------------------------------------------------- github

(def ^:private gh-sources
  "The config blocks that enable a GitHub source."
  [:actions])

(defn- github-cfg [acfg]
  (merge gh/conclusions (select-keys acfg [:failing :green])))

(defn sync-github-workflow!
  "One GitHub workflow on one branch: fetch its history and sync it."
  [s get-json acfg ctx {:keys [repo branch workflow-id since]} opts]
  (let [cfg (github-cfg acfg)]
    (sync-streaks! s (assoc ctx :host gh/host :repo repo :branch branch :workflow-id workflow-id)
                   cfg
                   (gh/workflow-history get-json cfg repo workflow-id branch
                                        (or (:max-pages acfg) 5))
                   since opts)))

(defn github-ctx [acfg cfg opts]
  (bridge-ctx (assoc (ci-defaults acfg) :actor (or (:actor cfg) "github")) opts))

(defn- github-config [opts]
  (let [cfg (read-config opts "github.edn"
                         (str "need {:repos [\"owner/name\" …] or :orgs [\"owner\"]"
                              " and a source block such as :actions {}}"))]
    (when-not (some cfg gh-sources)
      (die "the github config enables no source: add :actions {} (the only source so far)"))
    cfg))

(defn- github-getter
  "owner -> the `get-json` carrying that owner's token: the :tokens entry
  for the owner, else :token, else $GITHUB_TOKEN. A fine-grained GitHub
  token is scoped to one resource owner, hence one per owner. Each token
  resolves once, on first use, so a command for an owner the sweep never
  touches never runs."
  [cfg]
  (let [api (or (:api cfg) "https://api.github.com")
        getter (memoize
                (fn [spec]
                  (gh/json-getter api (secret/resolve1 "token" spec))))]
    (fn [owner]
      (getter (or (get-in cfg [:tokens owner]) (:token cfg) {:env "GITHUB_TOKEN"})))))

(defn github-poll!
  "One sweep over every configured repository for each enabled source:
  each (repo, branch, workflow) syncs in isolation, so one failing API
  call skips that unit and the sweep continues. Returns the tally."
  [s get-json-for cfg ctxs opts]
  (let [{:keys [tally bump report]} (tallying)
        acfg (:actions cfg)
        t0 (since acfg)]
    (doseq [{:keys [repo branches]} (when acfg (gh/targets get-json-for cfg report))
            :let [get-json (get-json-for (gh/owner-of repo))]
            branch branches]
      (try
        (doseq [wf (gh/active-workflows get-json repo branch t0)
                r (sync-github-workflow! s get-json acfg (:actions ctxs)
                                         {:repo repo :branch branch :workflow-id wf :since t0}
                                         opts)]
          (bump (:outcome r))
          (println (outcome-line r)))
        (catch Exception e
          (report (str repo "@" branch) e))))
    @tally))

(defn watched?
  "Is a workflow_run on this repository and branch one the config covers?
  Explicit :repos entries name their branches (default branch otherwise);
  :orgs entries cover every repository of the owner on its default branch."
  [cfg repo branch default-branch]
  (let [entry (some #(let [e (if (string? %) {:repo %} %)] (when (= repo (:repo e)) e))
                    (:repos cfg))]
    (and (not (contains? (set (:exclude cfg)) repo))
         (or (some? entry) (contains? (set (:orgs cfg)) (gh/owner-of repo)))
         (contains? (set (or (seq (:branches entry)) [default-branch])) branch))))

(defn- actions-delivery
  "The sync a `workflow_run` delivery asks for, as a thunk, or nil when
  the delivery is not a completed run the config covers."
  [s get-json-for cfg ctxs opts p]
  (let [run (:workflow_run p)
        repo (get-in p [:repository :full_name])
        branch (:head_branch run)]
    (when (and (:actions cfg)
               (= "completed" (:action p))
               (watched? cfg repo branch (get-in p [:repository :default_branch])))
      (fn []
        (sync-github-workflow! s (get-json-for (gh/owner-of repo)) (:actions cfg) (:actions ctxs)
                               {:repo repo :branch branch :workflow-id (:workflow_id run)
                                :since (since (:actions cfg))}
                               opts)))))

(def ^:private deliveries
  "X-GitHub-Event kind -> the function turning a delivery into a sync
  thunk. A further source registers its event kind here."
  {"workflow_run" actions-delivery})

(defn github-webhook-handler
  "The httpkit handler for GitHub webhook deliveries. It answers at once
  and runs the sync in the background (GitHub gives a delivery ten
  seconds); syncs are serialized within this process only."
  [s get-json-for cfg ctxs opts secret]
  (let [parse (requiring-resolve 'cheshire.core/parse-string)
        lock (Object.)]
    (fn [{:keys [request-method headers body]}]
      (try
        (let [bytes (if body (.readAllBytes ^java.io.InputStream body) (byte-array 0))
              kind (get headers "x-github-event")]
          (cond
            (not= :post request-method) {:status 405 :body "POST only\n"}
            (not (gh/valid-signature? secret bytes (get headers "x-hub-signature-256")))
            {:status 401 :body "bad signature\n"}
            (= "ping" kind) {:status 200 :body "pong\n"}
            (not (contains? deliveries kind)) {:status 204}
            :else
            (if-let [job ((deliveries kind) s get-json-for cfg ctxs opts
                          (parse (String. ^bytes bytes "UTF-8") true))]
              (do (future
                    (locking lock
                      (try
                        (doseq [r (job)] (println (outcome-line r)))
                        (cache-flush!)
                        (catch Exception e
                          (binding [*out* *err*]
                            (println (str "error in " kind " delivery: " (ex-message e))))))))
                  {:status 202 :body "accepted\n"})
              {:status 204})))
        (catch Exception e
          {:status 400 :body (str "tik: " (ex-message e) "\n")})))))

(defn cmd-bridge-github
  "bridge github [--config github.edn] [--webhook [--port N]]
  Failed GitHub Actions runs become ci-failure tickets, one per failure
  streak (see tik.forge). Without --webhook it polls every configured
  repository once and exits; schedule it from a timer or `tik backend`.
  With --webhook it serves GitHub `workflow_run` deliveries, authenticated
  by the X-Hub-Signature-256 HMAC, and syncs the delivered workflow. Both
  drivers are idempotent, so running them side by side, or on several
  replicas, is safe."
  [opts]
  (let [cfg (github-config opts)
        s (the-store)
        get-json-for (github-getter cfg)
        ctxs (cond-> {}
               (:actions cfg) (assoc :actions (github-ctx (:actions cfg) cfg opts)))]
    (if (:webhook opts)
      (let [secret (secret/resolve1 "webhook secret"
                                    (or (get-in cfg [:webhook :secret])
                                        {:env "GITHUB_WEBHOOK_SECRET"}))
            _ (when (str/blank? secret)
                (die "bridge github --webhook needs a secret (:webhook {:secret …} or GITHUB_WEBHOOK_SECRET)"))
            port (or (some-> (:port opts) str parse-long)
                     (get-in cfg [:webhook :port])
                     8787)
            run-server (requiring-resolve 'org.httpkit.server/run-server)]
        (run-server (github-webhook-handler s get-json-for cfg ctxs opts secret) {:port port})
        (println (str "github webhook listening on :" port " (ctrl-c stops)"))
        @(promise))
      (finish! "github" (github-poll! s get-json-for cfg ctxs opts)))))

;; ---------------------------------------------------------------- gitlab

(defn- gitlab-cfg [pcfg]
  (merge gl/conclusions (select-keys pcfg [:failing :green])))

(defn sync-gitlab-source!
  "One GitLab pipeline source on one branch of `target`: fetch its history
  and sync it."
  [s get-json pcfg ctx target branch source since opts]
  (let [cfg (gitlab-cfg pcfg)]
    (sync-streaks! s (assoc ctx :repo (:repo target) :branch branch :workflow-id source)
                   cfg
                   (gl/pipeline-history get-json cfg target source branch
                                        (or (:max-pages pcfg) 5))
                   since opts)))

(defn gitlab-poll!
  "One sweep over every configured GitLab project; each (project, branch,
  source) syncs in isolation. Returns the tally."
  [s get-json cfg ctx opts]
  (let [{:keys [tally bump report]} (tallying)
        pcfg (:pipelines cfg)
        t0 (since pcfg)]
    (doseq [target (gl/targets get-json cfg report)
            branch (:branches target)]
      (try
        (doseq [source (gl/active-sources get-json target branch t0)
                r (sync-gitlab-source! s get-json pcfg ctx target branch source t0 opts)]
          (bump (:outcome r))
          (println (outcome-line r)))
        (catch Exception e
          (report (str (:repo target) "@" branch) e))))
    @tally))

(defn cmd-bridge-gitlab
  "bridge gitlab [--config gitlab.edn]
  Failed GitLab pipelines become ci-failure tickets, one per failure
  streak. Polls once and exits. A :tunnel {:ssh dest :target host:port}
  holds an SSH port forward open for the sweep, for an API that only
  listens on its own host."
  [opts]
  (let [cfg (read-config opts "gitlab.edn"
                         (str "need {:url \"https://gitlab.example\" :membership true"
                              " or :groups […] or :projects […], and :pipelines {}}"))
        _ (when-not (:url cfg) (die "the gitlab config needs :url"))
        _ (when-not (:pipelines cfg) (die "the gitlab config enables no source: add :pipelines {}"))
        s (the-store)
        ctx (assoc (bridge-ctx (assoc (ci-defaults (:pipelines cfg))
                                      :actor (or (:actor cfg) "gitlab"))
                               opts)
                   :host (gl/host-of (:url cfg)))
        token (secret/resolve1 "token" (or (:token cfg) {:env "GITLAB_TOKEN"}))
        api (or (:api cfg) (str (str/replace (:url cfg) #"/+$" "") "/api/v4"))
        tally (forge/with-tunnel (:tunnel cfg) api
                (fn [base] (gitlab-poll! s (gl/json-getter base token) cfg ctx opts)))]
    (finish! "gitlab" tally)))

;; ---------------------------------------------------------------- redmine

(defn- mirrored-issue-ids
  "Ids of the issues from the Redmine at `url` this store already holds,
  read from the bridge's [:redmine :url] facts, so an issue that leaves
  the query (reassigned, moved) keeps being mirrored."
  [s url]
  (let [prefix (str (str/replace (str url) #"/+$" "") "/issues/")]
    (set (for [id (store/ticket-ids s)
               e (store/events s id)
               :when (and (= :fact/assert (:event/type e))
                          (= [:redmine :url] (get-in e [:event/body :fact/path])))
               :let [v (str (get-in e [:event/body :fact/value]))]
               :when (str/starts-with? v prefix)]
           (parse-long (subs v (count prefix)))))))

(defn sync-issue!
  "Mirror one issue (with its journal) into the store; returns an outcome
  map or nil when nothing was new."
  [s ctx issue opts]
  (let [{:keys [id events blobs]} (rm/issue-events ctx issue)
        created? (nil? (create-id s id))]
    (doseq [[h t] blobs] (forge/write-blob! (root) id h t))
    (let [n (append-fresh! s events opts)]
      (when (pos? n)
        {:outcome (if created? :ticket :update) :id id :events n}))))

(defn redmine-poll!
  "One sweep: every issue the :query selects plus every issue already
  mirrored, each fetched with its journal and synced in isolation."
  [s get-json cfg ctx opts]
  (let [{:keys [tally bump report]} (tallying)
        listed (rm/list-issues get-json (or (:query cfg) {:assigned_to_id "me"}))
        known (remove (set (map :id listed)) (mirrored-issue-ids s (:url cfg)))
        summaries (into listed (when (seq known) (rm/issues-by-id get-json (sort known))))
        tables (assoc (rm/fetch-tables get-json summaries) :users (:users cfg))]
    (doseq [{issue-id :id} (sort-by :id summaries)]
      (try
        (let [issue (rm/issue-with-journal get-json issue-id)]
          (when-let [r (sync-issue! s (assoc ctx :names (rm/names tables issue)) issue opts)]
            (bump (:outcome r))
            (println (str (outcome-line r) " #" issue-id))))
        (catch Exception e
          (report (str "#" issue-id) e))))
    @tally))

(defn cmd-bridge-redmine
  "bridge redmine [--config redmine.edn]
  Redmine issues become redmine-issue tickets mirroring the issue's
  status, assignee, priority, tracker, project, subject and due date, with
  description and notes as artifacts. Polls once and exits."
  [opts]
  (let [cfg (read-config opts "redmine.edn"
                         "need {:url \"https://redmine.example\" :key <secret spec>}")
        _ (when-not (:url cfg) (die "the redmine config needs :url"))
        s (the-store)
        ctx (assoc (bridge-ctx {:process (or (:process cfg) :redmine-issue)
                                :actor (or (:actor cfg) "redmine")}
                               opts)
                   :host (rm/host-of (:url cfg))
                   :url (:url cfg))
        key (secret/resolve1 "API key" (or (:key cfg) {:env "REDMINE_API_KEY"}))]
    (finish! "redmine" (redmine-poll! s (rm/json-getter (:url cfg) key) cfg ctx opts))))
