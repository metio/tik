;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sync-test
  "H2 over a real replication path: the file store in git clones. Merge
  is set union because events are add-only, content-addressed files —
  two replicas appending divergently merge without a single git
  conflict, derive identical state from the merged set, and a genuine
  cross-replica disagreement surfaces as :conflicted (ADR 0003) rather
  than being resolved by the transport. If this test ever needs
  file-level conflict resolution, the store design failed (H2's kill
  criterion, verbatim)."
  (:require [tik.harness :as h]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.reduce :as red]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import
   (java.time Instant)))

(defn- tmp [name]
  (h/temp-dir! name))

(defn- git! [dir & args]
  ;; Pin init.defaultBranch so the bare origin's HEAD is `main` regardless of
  ;; the host/CI git default (unset → `master`), which would otherwise make a
  ;; clone track `master` while the replica pushes `main` — `git pull` then
  ;; fails with "no such ref". A command-line -c outranks any global/env config.
  (let [r (apply sh/sh "git" "-C" (str dir)
                 "-c" "user.email=replica@test" "-c" "user.name=replica"
                 "-c" "init.defaultBranch=main"
                 args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (first args) " failed")
                      {:args args :err (:err r) :out (:out r)})))
    r))

(defn- at [s] (Instant/parse s))

(defn- commit-all! [dir msg]
  (git! dir "add" "-A")
  (git! dir "commit" "-q" "-m" msg))

(deftest replicas-merge-by-union-and-agree
  (let [origin (tmp "tik-origin")
        a (tmp "tik-replica-a")
        b (tmp "tik-replica-b")
        tid (random-uuid)]
    (git! origin "init" "-q" "--bare")

    ;; replica A creates the ticket and publishes
    (git! a "init" "-q" "-b" "main")
    (git! a "remote" "add" "origin" (str origin))
    (let [sa (fstore/file-store (str a))
          create (event/create-ticket {:ticket tid :actor "customer"
                                       :at (at "2026-07-08T10:00:00Z")
                                       :title "replicated"
                                       :process :support-request})]
      (store/append! sa create)
      (commit-all! a "create")
      (git! a "push" "-q" "-u" "origin" "main"))

    ;; replica B clones, then BOTH replicas categorize independently:
    ;; neither observes the other — structural concurrency by construction
    (git! b "clone" "-q" (str origin) ".")
    (let [sa (fstore/file-store (str a))
          sb (fstore/file-store (str b))
          head-a (dag/heads (store/events sa tid))
          head-b (dag/heads (store/events sb tid))]
      (is (= head-a head-b) "clones start from the same head")
      (store/append! sa (event/assert-fact
                         {:ticket tid :actor "seb" :parents head-a
                          :at (at "2026-07-08T10:01:00Z")
                          :path [:category] :value :technical}))
      (store/append! sb (event/assert-fact
                         {:ticket tid :actor "billing" :parents head-b
                          :at (at "2026-07-08T10:02:00Z")
                          :path [:category] :value :billing}))
      (commit-all! a "categorize on A")
      (commit-all! b "categorize on B")
      (git! a "push" "-q")

      (testing "the merge is union: no git conflict, nothing lost"
        (let [pull (git! b "pull" "-q" "--no-rebase" "--no-edit")]
          (is (zero? (:exit pull))))
        (git! b "push" "-q")
        (git! a "pull" "-q" "--no-rebase" "--no-edit"))

      (let [evs-a (store/events sa tid)
            evs-b (store/events sb tid)]
        (testing "both replicas hold the identical event set"
          (is (= 3 (count evs-a)))
          (is (= (set (map :event/id evs-a)) (set (map :event/id evs-b)))))
        (testing "the merged store is a complete DAG"
          (is (empty? (dag/missing-parents evs-a)))
          (is (= 1 (count (dag/roots evs-a)))))
        (testing "derivation agrees byte-for-byte across replicas"
          (is (= (red/ticket-state evs-a) (red/ticket-state evs-b))))
        (testing "the cross-replica disagreement is conflicted, not resolved"
          (is (= :conflicted
                 (:status (red/fact-status (red/ticket-state evs-a)
                                           [:category]))))
          (is (= #{"seb" "billing"}
                 (set (map :by (:claims (red/fact-status
                                         (red/ticket-state evs-a)
                                         [:category])))))))

        (testing "a resolution on one replica reaches the other"
          (store/append! sa (event/assert-fact
                             {:ticket tid :actor "seb"
                              :parents (dag/heads evs-a)
                              :at (at "2026-07-08T10:05:00Z")
                              :path [:category] :value :technical}))
          (commit-all! a "resolve the disagreement")
          (git! a "push" "-q")
          (git! b "pull" "-q" "--no-rebase" "--no-edit")
          (let [state-b (red/ticket-state (store/events sb tid))]
            (is (= :present (:status (red/fact-status state-b [:category]))))
            (is (= :technical (red/fact-value state-b [:category])))))))))

(deftest identical-events-collide-harmlessly
  ;; content addressing means the SAME event appended on both replicas
  ;; is the same filename with the same bytes: git add-add of identical
  ;; content, which merges cleanly, and dedupe-by-id makes it one event
  (let [origin (tmp "tik-origin2")
        a (tmp "tik-a2")
        b (tmp "tik-b2")
        tid (random-uuid)
        create (event/create-ticket {:ticket tid :actor "customer"
                                     :at (at "2026-07-08T10:00:00Z")
                                     :title "same event twice"
                                     :process :support-request})
        follow (event/assert-fact {:ticket tid :actor "seb"
                                   :parents #{(:event/id create)}
                                   :at (at "2026-07-08T10:01:00Z")
                                   :path [:category] :value :technical})]
    (git! origin "init" "-q" "--bare")
    (git! a "init" "-q" "-b" "main")
    (git! a "remote" "add" "origin" (str origin))
    (store/append! (fstore/file-store (str a)) create)
    (commit-all! a "create")
    (git! a "push" "-q" "-u" "origin" "main")
    (git! b "clone" "-q" (str origin) ".")
    ;; both replicas mint the IDENTICAL follow-up event
    (store/append! (fstore/file-store (str a)) follow)
    (store/append! (fstore/file-store (str b)) follow)
    (commit-all! a "same event on A")
    (commit-all! b "same event on B")
    (git! a "push" "-q")
    (is (zero? (:exit (git! b "pull" "-q" "--no-rebase" "--no-edit"))))
    (let [evs (store/events (fstore/file-store (str b)) tid)]
      (is (= 2 (count evs)))
      (is (= :technical (red/fact-value (red/ticket-state evs)
                                        [:category]))))))
