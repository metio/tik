;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bdd-test
  "Executable Gherkin features (features/*.feature) over the kernel.

  Modern Gherkin shape: Feature > Rule > Background/Example with
  Given/When/Then/And/But steps. The runner is a deliberately small
  parser plus a closed step vocabulary mapping straight onto kernel
  calls — features are specification, the kernel is the only engine,
  and there is no glue-code framework to drift.

  Step vocabulary (Given/When):
    a ticket following the \"<process>\" process
    \"<actor>\" asserts <path> = <edn-value>
    \"<actor>\" retracts <path>
    \"<actor>\" disputes <path> because \"<reason>\"
    \"<actor>\" attaches \"<artifact-path>\"
    <n> hours pass
  Step vocabulary (Then):
    the stage :<id> is reached | is not reached
    the current stages are :<id>[, :<id>…]
    explain for :<stage> lists <path> as missing
    explain for :<stage> says only \"<role>\" may provide <path>"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.stage :as stage])
  (:import (java.io File)
           (java.time Instant)))

(def ^:private epoch (Instant/parse "2026-01-01T00:00:00Z"))

(defn- parse-path [s]
  (mapv keyword (str/split s #"\.")))

(defn- init-state [proc-name]
  (let [proc (edn/read-string (slurp (str "processes/" proc-name ".edn")))
        tid (random-uuid)]
    {:process proc
     :roles (:process/roles proc {})
     :ticket tid
     :now epoch
     :events [(event/create-ticket {:ticket tid :actor "gherkin" :at epoch
                                    :title "feature" :process (keyword proc-name)})]}))

(defn- append [st mint-fn args]
  (let [tick (.plusMillis ^Instant (:now st) 1)]
    (-> st
        (update :events conj
                (mint-fn (merge {:ticket (:ticket st) :at tick
                                 :parents #{(:event/id (peek (:events st)))}}
                                args)))
        (assoc :now tick))))

(defn- reached [st]
  (stage/effective-reached (:process st) (:events st) (:now st) (:roles st)))

(defn- explain-blocks [st]
  (explain/explain (:process st) (:events st) (:now st) (:roles st)))

(def ^:private steps
  [[#"^a ticket following the \"([^\"]+)\" process$"
    (fn [_ [p]] (init-state p))]
   [#"^\"([^\"]+)\" asserts ([\w.-]+) = (.+)$"
    (fn [st [actor path value]]
      (append st event/assert-fact {:actor actor :path (parse-path path)
                                    :value (edn/read-string value)}))]
   [#"^\"([^\"]+)\" retracts ([\w.-]+)$"
    (fn [st [actor path]]
      (append st event/retract-fact {:actor actor :path (parse-path path)}))]
   [#"^\"([^\"]+)\" disputes ([\w.-]+) because \"([^\"]+)\"$"
    (fn [st [actor path reason]]
      (append st event/dispute-fact {:actor actor :path (parse-path path)
                                     :reason reason}))]
   [#"^\"([^\"]+)\" attaches \"([^\"]+)\"$"
    (fn [st [actor path]]
      (append st event/attach-artifact {:actor actor :path path
                                        :hash "sha256-feature"}))]
   [#"^(\d+) hours pass$"
    (fn [st [h]]
      (update st :now #(.plusSeconds ^Instant % (* 3600 (parse-long h)))))]
   [#"^the stage :([\w-]+) is reached$"
    (fn [st [id]]
      (is (contains? (reached st) (keyword id))
          (str "stage :" id " should be reached; reached=" (reached st)))
      st)]
   [#"^the stage :([\w-]+) is not reached$"
    (fn [st [id]]
      (is (not (contains? (reached st) (keyword id)))
          (str "stage :" id " should NOT be reached"))
      st)]
   [#"^the current stages are (.+)$"
    (fn [st [ids]]
      (is (= (set (map #(keyword (subs % 1))
                       (str/split ids #",\s*")))
             (stage/current-stages (:process st) (reached st))))
      st)]
   [#"^explain for :([\w-]+) lists ([\w.-]+) as missing$"
    (fn [st [id path]]
      (let [block (first (filter #(= (keyword id) (:stage %))
                                 (explain-blocks st)))]
        (is (some #(= (parse-path path) (:path %)) (:missing block))
            (str "explain for :" id " should list " path
                 "; missing=" (:missing block))))
      st)]
   [#"^explain for :([\w-]+) says only \"([^\"]+)\" may provide ([\w.-]+)$"
    (fn [st [id role path]]
      (let [block (first (filter #(= (keyword id) (:stage %))
                                 (explain-blocks st)))]
        (is (some #(and (= :role/unsatisfied (:reason %))
                        (= (keyword role) (:role %))
                        (= (parse-path path) (:path %)))
                  (:missing block))
            (str "explain for :" id " should demand role " role)))
      st)]])

(defn- run-step [st text]
  (if-let [[f m] (some (fn [[re f]]
                         (when-let [m (re-matches re text)]
                           [f m]))
                       steps)]
    (f st (rest m))
    (throw (ex-info (str "no step definition matches: " text)
                    {:step text}))))

(defn- parse-feature
  "Lines -> {:name … :background [steps] :rules [{:name …
  :background [steps] :examples [{:name … :steps [steps]}]}]}. A
  Background before the first Rule is feature-level and applies to every
  rule (Gherkin semantics); features without Rule blocks get one
  implicit rule."
  [lines]
  (let [clean (->> lines
                   (map str/trim)
                   (remove #(or (str/blank? %) (str/starts-with? % "#"))))]
    (reduce
     (fn [acc line]
       (let [[_ kw rest-text] (re-matches #"^(Feature|Rule|Background|Example|Scenario|Given|When|Then|And|But):?\s*(.*)$" line)]
         (case kw
           "Feature" (assoc acc :name rest-text)
           "Rule" (-> acc
                      (assoc :saw-rule true)
                      (update :rules conj {:name rest-text :background []
                                           :examples []}))
           "Background" (assoc acc :mode (if (:saw-rule acc)
                                           :rule-background
                                           :feature-background))
           ("Example" "Scenario")
           (-> acc
               (update-in [:rules (dec (count (:rules acc)))]
                          (fnil identity {:name "" :background [] :examples []}))
               (update-in [:rules (dec (count (:rules acc))) :examples]
                          conj {:name rest-text :steps []})
               (assoc :mode :example))
           ("Given" "When" "Then" "And" "But")
           (let [ri (dec (count (:rules acc)))]
             (case (:mode acc)
               :feature-background (update acc :background conj rest-text)
               :rule-background (update-in acc [:rules ri :background]
                                           conj rest-text)
               (let [ei (dec (count (get-in acc [:rules ri :examples])))]
                 (update-in acc [:rules ri :examples ei :steps]
                            conj rest-text))))
           acc)))
     {:name "" :background [] :saw-rule false
      :rules [{:name "" :background [] :examples []}] :mode nil}
     clean)))

(deftest gherkin-features
  (let [files (->> (.listFiles (io/file "features"))
                   (filter #(str/ends-with? (.getName ^File %) ".feature"))
                   (sort-by #(.getName ^File %)))]
    (is (seq files) "feature files present")
    (doseq [^File f files
            :let [{:keys [name background rules]}
                  (parse-feature (str/split-lines (slurp f)))]]
      (testing (str (.getName f) ": " name)
        (doseq [{rule-name :name rule-background :background
                 :keys [examples]} rules
                :when (seq examples)
                {example-name :name :keys [steps]} examples]
          (testing (str rule-name " / " example-name)
            (reduce run-step nil
                    (concat background rule-background steps))))))))
