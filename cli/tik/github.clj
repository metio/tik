;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.github
  "The GitHub side of CI failure tracking (tik.forge): Actions runs in the
  forge-neutral run shape, repository discovery, and the webhook
  signature check. GitHub enters as an injected `get-json`, so all of it
  is testable without a network."
  (:require [clojure.string :as str]
            [tik.forge :as forge])
  (:import (java.security MessageDigest)
           (java.time Instant)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def host "github.com")

(def conclusions
  "The default :failing and :green conclusion sets."
  {:failing #{"failure" "timed_out" "startup_failure"}
   :green #{"success"}})

(defn run
  "A GitHub workflow run in the forge-neutral shape. created_at is shared
  by every attempt and run_started_at is fixed per attempt; updated_at is
  left out, because GitHub rewrites it on runs long after they completed."
  [r]
  {:id (:id r)
   :number (:run_number r)
   :attempt (or (:run_attempt r) 1)
   :conclusion (:conclusion r)
   :workflow-id (:workflow_id r)
   :workflow (:name r)
   :branch (:head_branch r)
   :head-sha (:head_sha r)
   :url (:html_url r)
   :created-at (:created_at r)
   :started-at (or (:run_started_at r) (:created_at r))
   :repo-id (get-in r [:repository :id])
   :repo (get-in r [:repository :full_name])
   :event (:event r)
   :actor (get-in r [:actor :login])
   :title (:display_title r)})

;; ---------------------------------------------------------------- webhook

(defn valid-signature?
  "Does `header` (X-Hub-Signature-256: sha256=<hex>) authenticate `body`
  under the webhook `secret`? Constant-time comparison."
  [secret ^bytes body header]
  (boolean
   (when (and (seq secret) (string? header) (str/starts-with? header "sha256="))
     (let [mac (doto (Mac/getInstance "HmacSHA256")
                 (.init (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")))
           want (.getBytes (str "sha256=" (str/join (map #(format "%02x" (bit-and % 0xff))
                                                         (.doFinal mac body))))
                           "UTF-8")]
       (MessageDigest/isEqual want (.getBytes ^String header "UTF-8"))))))

(defn json-getter
  "A `get-json` over the GitHub REST API at `api` with bearer `token`."
  [api token]
  (forge/json-getter "GitHub API" api
                     {"Accept" "application/vnd.github+json"
                      "X-GitHub-Api-Version" "2022-11-28"
                      "Authorization" (when (seq token) (str "Bearer " token))}))

;; ---------------------------------------------------------------- discovery

(defn owner-of
  "The owner segment of an \"owner/name\" repository."
  [repo]
  (first (str/split (str repo) #"/" 2)))

(defn- owner-repos
  "Every repository of `owner`. An organisation lists through /orgs; a
  user account answers that with 404 and lists through /users (public
  repositories) plus /user (the private ones, visible when the token is
  that user's own)."
  [get-json owner]
  (try
    (forge/paged get-json (str "/orgs/" owner "/repos") {:type "all"} identity 50)
    (catch clojure.lang.ExceptionInfo e
      (when-not (= 404 (:status (ex-data e))) (throw e))
      (let [own (try (filter #(= owner (get-in % [:owner :login]))
                             (forge/paged get-json "/user/repos" {:affiliation "owner"} identity 50))
                     (catch clojure.lang.ExceptionInfo e2
                       (if (#{401 403} (:status (ex-data e2))) [] (throw e2))))]
        (vals (into {} (map (juxt :full_name identity))
                    (concat (forge/paged get-json (str "/users/" owner "/repos") {:type "owner"}
                                   identity 50)
                            own)))))))

(defn targets
  "The (repo, branches) pairs to watch: the explicit :repos entries plus
  every non-archived repository of each :orgs entry, minus :exclude.
  A repo without :branches watches its default branch.

  `get-json-for` maps an owner to the `get-json` that carries that
  owner's token. A failure listing one owner, or looking up one default
  branch, goes to `on-error` (owner-or-repo, exception) and skips only
  that owner or repository."
  [get-json-for {:keys [repos orgs exclude]} on-error]
  (let [excluded (set exclude)
        org-repos (for [org orgs
                        r (try (owner-repos (get-json-for org) org)
                               (catch Exception e (on-error org e) nil))
                        :when (not (or (:archived r) (:disabled r)))]
                    {:repo (:full_name r) :default-branch (:default_branch r)})
        explicit (for [r repos]
                   (if (string? r) {:repo r} r))
        merged (vals (reduce (fn [m t] (update m (:repo t) merge t)) {}
                             (concat org-repos explicit)))]
    (vec
     (for [t (sort-by :repo merged)
           :when (not (excluded (:repo t)))
           :let [branches (or (seq (:branches t))
                              (try (some-> (or (:default-branch t)
                                               (:default_branch ((get-json-for (owner-of (:repo t)))
                                                                 (str "/repos/" (:repo t)) {})))
                                           vector)
                                   (catch Exception e (on-error (:repo t) e) nil)))]
           :when (seq branches)]
       {:repo (:repo t) :branches (vec branches)}))))

(defn active-workflows
  "Workflow ids with a completed run on `branch` since `since`, from the
  repository's most recent page of runs."
  [get-json repo branch ^Instant since]
  (->> (:workflow_runs (get-json (str "/repos/" repo "/actions/runs")
                                 {:branch branch :status "completed" :per_page forge/per-page}))
       (map run)
       (filter #(not (.isBefore (forge/run-at %) since)))
       (map :workflow-id)
       distinct
       sort))

(defn workflow-history
  "One workflow's completed runs on `branch`, newest pages first, fetched
  until the oldest classified run is green (the streak start is then
  known), the history ends, or `max-pages` is reached. `cfg` holds the
  conclusion sets. Returns {:runs [...] :complete? bool}."
  [get-json cfg repo workflow-id branch max-pages]
  (loop [page 1 acc []]
    (let [body (get-json (str "/repos/" repo "/actions/workflows/" workflow-id "/runs")
                         {:branch branch :status "completed" :per_page forge/per-page :page page})
          xs (map run (:workflow_runs body))
          acc (into acc xs)
          oldest-classified (last (filter #(forge/classify cfg %)
                                          (sort-by forge/run-order #(compare %2 %1) acc)))
          ended? (< (count xs) forge/per-page)]
      (if (or ended?
              (= :green (some->> oldest-classified (forge/classify cfg)))
              (>= page max-pages))
        {:runs acc :complete? ended?}
        (recur (inc page) acc)))))
