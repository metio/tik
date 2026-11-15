;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.gitlab
  "The GitLab side of CI failure tracking (tik.forge): pipelines in the
  forge-neutral run shape, and project discovery. GitLab enters as an
  injected `get-json` over its REST API (v4).

  A pipeline has no workflow id; its `source` (push, schedule, web, …)
  stands in, so a failing nightly schedule and green push pipelines on
  the same branch are separate streaks. A retried pipeline keeps its id
  and shows only its latest outcome, so every green pipeline may close a
  ticket opened on it (:retry-in-place)."
  (:require [clojure.string :as str]
            [tik.forge :as forge])
  (:import (java.net URLEncoder)
           (java.time Instant)))

(def conclusions
  "The default :failing and :green pipeline statuses, plus the retry
  semantics tik.forge/streaks needs."
  {:failing #{"failed"}
   :green #{"success"}
   :retry-in-place true})

(defn host-of
  "The host part of a GitLab URL, the forge host tickets record."
  [url]
  (.getHost (java.net.URI/create (str url))))

(defn pipeline
  "A GitLab pipeline of `project` in the forge-neutral run shape. Only
  created_at is used for time: the list endpoint carries no start time,
  and updated_at moves whenever the pipeline does."
  [{:keys [project-id repo]} p]
  {:id (:id p)
   :number (or (:iid p) (:id p))
   :attempt 1
   :conclusion (:status p)
   :workflow-id (:source p)
   :workflow (str (:source p) " pipeline")
   :branch (:ref p)
   :head-sha (:sha p)
   :url (:web_url p)
   :created-at (:created_at p)
   :started-at (:created_at p)
   :repo-id project-id
   :repo repo
   :event (:source p)
   :actor (get-in p [:user :username])
   :title nil})

(defn json-getter
  "A `get-json` over the GitLab REST API at `api` (…/api/v4)."
  [api token]
  (forge/json-getter "GitLab API" api {"PRIVATE-TOKEN" token}))

(defn- enc [s] (URLEncoder/encode (str s) "UTF-8"))

(defn targets
  "The (project, branches) pairs to watch: every project the token's
  account is a member of (:membership true), every project of each
  :groups entry and its subgroups, and the :projects entries (a path, or
  {:project path :branches […]}), minus :exclude, archived projects
  left out. A project without :branches watches its default branch. A
  failure listing one group or project goes to `on-error` and skips it."
  [get-json {:keys [membership groups projects exclude]} on-error]
  (let [excluded (set exclude)
        listed (concat
                (when membership
                  (try (forge/paged get-json "/projects" {:membership true :archived false
                                                          :simple true}
                                    identity 50)
                       (catch Exception e (on-error "membership" e) nil)))
                (for [g groups
                      p (try (forge/paged get-json (str "/groups/" (enc g) "/projects")
                                          {:include_subgroups true :archived false :simple true}
                                          identity 50)
                             (catch Exception e (on-error g e) nil))]
                  p))
        explicit (for [e projects
                       :let [e (if (string? e) {:project e} e)
                             p (try (get-json (str "/projects/" (enc (:project e))) {})
                                    (catch Exception ex (on-error (:project e) ex) nil))]
                       :when p]
                   (assoc p ::branches (:branches e)))
        merged (vals (reduce (fn [m p] (update m (:path_with_namespace p) merge p)) {}
                             (concat listed explicit)))]
    (vec
     (for [p (sort-by :path_with_namespace merged)
           :when (not (or (:archived p) (excluded (:path_with_namespace p))))
           :let [branches (or (seq (::branches p)) (some-> (:default_branch p) vector))]
           :when (seq branches)]
       {:repo (:path_with_namespace p) :project-id (:id p) :branches (vec branches)}))))

(defn active-sources
  "Pipeline sources with a finished pipeline on `branch` since `since`,
  from the project's most recent page of pipelines."
  [get-json target branch ^Instant since]
  (->> (get-json (str "/projects/" (:project-id target) "/pipelines")
                 {:ref branch :scope "finished" :order_by "id" :sort "desc"
                  :per_page forge/per-page})
       (map #(pipeline target %))
       (filter #(not (.isBefore (forge/run-at %) since)))
       (map :workflow-id)
       distinct
       sort))

(defn pipeline-history
  "One source's finished pipelines on `branch`, newest pages first, until
  the oldest classified pipeline is green, the history ends, or
  `max-pages` is reached. Returns {:runs [...] :complete? bool}."
  [get-json cfg target source branch max-pages]
  (loop [page 1 acc []]
    (let [xs (map #(pipeline target %)
                  (get-json (str "/projects/" (:project-id target) "/pipelines")
                            {:ref branch :source source :scope "finished"
                             :order_by "id" :sort "desc"
                             :per_page forge/per-page :page page}))
          acc (into acc xs)
          oldest-classified (last (filter #(forge/classify cfg %)
                                          (sort-by forge/run-order #(compare %2 %1) acc)))
          ended? (< (count xs) forge/per-page)]
      (if (or ended?
              (= :green (some->> oldest-classified (forge/classify cfg)))
              (>= page max-pages))
        {:runs acc :complete? ended?}
        (recur (inc page) acc)))))

(defn repo-name
  "The last segment of a project path, the [:repo] a checkout of it gets."
  [path]
  (last (str/split (str path) #"/")))
