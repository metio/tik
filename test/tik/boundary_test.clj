;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.boundary-test
  "One named case per class of input that escaped the kernel as a raw
  host exception, each through the public function a caller actually
  uses. tik.totality-test enumerates the same classes over every public
  function; these pin them by name, so a failure here reads as the
  sentence it breaks."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.canonical :as canonical]
            [tik.causal :as causal]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.gen-events :as ge]
            [tik.link :as link]
            [tik.next :as next]
            [tik.plan :as plan]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.stage :as stage]
            [tik.work :as work])
  (:import (java.time Instant)))

(defn- fails-well?
  "True when `f` on `args` returns or throws ex-info; false on any other
  Throwable."
  [f & args]
  (let [thunk #(apply f args)]
    (try (thunk) true
         (catch clojure.lang.ExceptionInfo _ true)
         (catch Throwable _ false))))

(def ^:private now (Instant/parse "2026-07-09T10:00:00Z"))

(def ^:private log
  (event/chain
   (fn [_] (event/create-ticket {:ticket ge/tid :actor "seb" :at ge/base
                                 :title "t" :process :support-request :version 1}))
   #(event/assert-fact {:ticket ge/tid :actor "seb" :at (ge/at-sec 5)
                        :parents % :path [:category] :value :billing})))

(defn- with-at [at] (assoc-in log [1 :event/at] at))

(defn- derivations
  "Every lens that derives from (process, events, now, roles)."
  [p events n roles]
  [#(stage/effective-reached p events n roles)
   #(explain/explain p events n roles)
   #(causal/causal p events n roles)
   #(next/contributions ge/tid p events n roles)
   #(next/settled? p events n roles)
   #(stage/stage-timeline p events roles n)])

(deftest the_valid_log_derives
  (is (set? (stage/effective-reached ge/process log now ge/roles))))

(deftest a_map_parameter_handed_a_list_is_refused
  (testing "map destructuring of a seq throws on an odd count"
    (doseq [f [event/create-ticket event/assert-fact event/retract-fact
               event/dispute-fact event/attach-artifact event/add-attestation
               event/migrate-process explain/actionability explain/reason->text]
            v ['(0 0 0) (list :a 1 :b)]]
      (is (fails-well? f v) (str f " " (pr-str v)))))
  (is (nil? (link/render '(0 0 0))) "a non-reference renders as nil")
  (is (fails-well? explain/permanent? '(0 0 0) '(:a 1 :b)))
  (is (fails-well? explain/actionable-by? '(:a 1 :b) nil "seb")))

(deftest an_event_with_a_hostile_field_is_refused_by_every_derivation
  (doseq [at [1.5 "2026-07-08T10:00:00Z" 7 :k (java.util.Date. 0) nil]
          f (derivations ge/process (with-at at) now ge/roles)]
    (is (fails-well? f) (str "at " (pr-str at))))
  (doseq [body [#{1 2} (sorted-set 1 2) '(:fact/path [:x] :fact/value)]
          f (derivations ge/process (assoc-in log [1 :event/body] body) now ge/roles)]
    (is (fails-well? f) (str "body " (pr-str body)))))

(deftest a_hostile_parents_value_still_derives
  (doseq [p [7 "sha256-x" (sorted-set 1 2) '("a") {:a 1}]]
    (let [events (assoc-in log [1 :event/parents] p)]
      (is (set? (stage/effective-reached ge/process events now ge/roles)) (pr-str p))
      (is (set? (dag/heads events)) (pr-str p))
      (is (map? (red/ticket-state events)) (pr-str p)))))

(deftest a_log_that_is_not_a_collection_is_refused
  (doseq [events [true 0 "events" :k (Object.)]
          f (concat (derivations ge/process events now ge/roles)
                    [#(red/ticket-state events) #(dag/heads events)
                     #(dag/roots events) #(dag/missing-parents events)
                     #(work/sessions events) #(work/work-records events)])]
    (is (fails-well? f) (pr-str events))))

(deftest a_definition_with_a_hostile_stage_is_refused
  (doseq [p [{:process/stages [(sorted-set 1 2)]}
             {:process/stages true}
             {:process/stages [{:stage/id :a :after 5}]}
             {:process/stages [{:stage/id :a :guards :b}]}
             (sorted-set 1 2)
             '(:process/stages)]
          f (concat (derivations p log now ge/roles)
                    [#(stage/sticky-ids p) #(stage/downstream p :a)
                     #(stage/current-stages p #{:a}) #(process/roles-gating p)])]
    (is (fails-well? f) (pr-str p))))

(deftest now_must_be_an_instant
  (doseq [n [nil "2026-07-09T10:00:00Z" 0 (java.util.Date. 0)]
          f (derivations ge/process log n ge/roles)]
    (is (fails-well? f) (pr-str n))))

(deftest roles_must_map_roles_to_member_lists
  (doseq [r [{:triager {:members 5}} [:triager] (sorted-map 1 2) '(:a 1 :b)]
          f (derivations ge/process log now r)]
    (is (fails-well? f) (pr-str r)))
  (is (fails-well? process/resolve-roles '(0 0 0) nil)))

(deftest canonical_text_and_bytes_are_typed
  (doseq [v [1 [1] :k (byte-array 2) \a]]
    (is (fails-well? canonical/parse v) (pr-str v))
    (is (fails-well? canonical/check-nesting v) (pr-str v))
    (is (fails-well? canonical/sha256-hex v) (pr-str v)))
  (is (fails-well? canonical/sha256-hex nil))
  (is (fails-well? canonical/sha256-hex-bytes "abc"))
  (is (nil? (canonical/parse nil)) "nil reads as empty text, as it always did"))

(deftest a_step_must_be_a_function
  (is (fails-well? event/chain [0]))
  (is (fails-well? event/chain :k identity 7)))

(deftest a_plan_needs_a_map_of_collections
  (doseq [[edges settled] [[{:a 1} #{}] [{:a #{:b}} [:b]] ['(:a) #{}]
                           [{:a (sorted-set 1 2)} #{}] [{:a #{:b}} (sorted-set 1)]]
          f [plan/summary plan/critical-path plan/ready]]
    (is (fails-well? f edges settled) (pr-str edges settled)))
  (is (fails-well? plan/cyclic-nodes {:a :b}))
  (is (fails-well? plan/longest-paths (fn [_] 5) [:a] (constantly false))
      "a parents-of that answers a scalar"))

(deftest work_lens_inputs_are_typed
  (is (fails-well? work/session-duration nil))
  (is (fails-well? work/session-duration {:start now :end "later"}))
  (is (fails-well? work/sessions [{:event/at 1.5}]))
  (is (fails-well? work/draft [{:events [{:event/at "x"}]}] "seb" nil nil))
  (is (fails-well? work/usage-totals [(sorted-set 1 2)] nil))
  (is (fails-well? work/usage-totals [] (sorted-map 1 2))))

(deftest the_inbox_combines_only_contributions
  (doseq [per-ticket [true [7] [{:actions 5}] [{:actions [{:who '(:a)}]}]
                      [{:stale-ms "old"}]]]
    (is (fails-well? next/inbox per-ticket "seb" nil) (pr-str per-ticket)))
  (is (fails-well? next/inbox [] "seb" '(:include-settled? true :role))))
