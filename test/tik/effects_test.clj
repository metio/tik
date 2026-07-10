;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.effects-test
  "The sink armory: every adapter is a pure payload mapping (pinned
  here shape by shape), and the :command sink is the universal escape
  hatch — the webhook JSON piped to any program, tested end to end."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private repo (System/getProperty "user.dir"))
(def ^:private payload #'cli/effect-payload)

(def tr {:ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef"
         :title "printer on fire" :stage :escalated})

(deftest every_adapter_speaks_its_service's_shape
  (testing "text-in-a-field family"
    (is (= "text" (-> (payload {:type :slack} tr) keys first)))
    (is (= "content" (-> (payload {:type :discord} tr) keys first)))
    (doseq [t [:mattermost :rocketchat :googlechat]]
      (is (contains? (payload {:type t} tr) "text") (str t))))
  (testing "addressed services take their addressing from the sink"
    (is (= "alerts" (get (payload {:type :ntfy :topic "alerts"} tr) "topic")))
    (is (= "42" (get (payload {:type :telegram :chat-id "42"} tr) "chat_id")))
    (is (= ["tok" "usr"]
           ((juxt #(get % "token") #(get % "user"))
            (payload {:type :pushover :token "tok" :user "usr"} tr)))))
  (testing "alerting services carry a stable dedup identity"
    (is (str/includes? (get (payload {:type :opsgenie} tr) "alias")
                       ":escalated"))
    (is (= "tik_stage_reached"
           (get-in (first (payload {:type :alertmanager} tr))
                   ["labels" "alertname"]))))
  (testing "the fallback is the stable webhook contract"
    (is (= #{"ticket" "title" "stage" "text"}
           (set (keys (payload {:type :something-new} tr)))))))

(deftest the_command_sink_pipes_webhook_json_to_any_program
  (let [root (.toFile (Files/createTempDirectory
                       "tik-fx" (make-array FileAttribute 0)))
        outfile (io/file root "received.json")
        tik! (fn [& args]
               (let [r (apply sh/sh (concat ["bb" "tik"] (map str args)
                                            [:dir repo
                                             :env (assoc (into {} (System/getenv))
                                                         "TIK_ROOT" (str root)
                                                         "TIK_ACTOR" "seb")]))]
                 (when-not (zero? (:exit r))
                   (throw (ex-info "tik failed" {:out (:out r) :err (:err r)})))
                 (:out r)))
        id (str/trim (tik! "new" "track" "--title" "command sink"))]
    (spit (io/file root "effects.edn")
          (pr-str {:sinks [{:type :command
                            :command ["sh" "-c" (str "cat >> " outfile)]}]}))
    (tik! "effects" "run" "--config" (str (io/file root "effects.edn")))
    (let [msg (json/parse-string (slurp outfile))]
      (is (= id (get msg "ticket")))
      (is (= "command sink" (get msg "title")))
      (is (= ":open" (get msg "stage"))))
    (testing "the ledger makes redelivery a no-op"
      (tik! "effects" "run" "--config" (str (io/file root "effects.edn")))
      (is (= 1 (count (str/split-lines (slurp outfile))))))))

(deftest templates_put_the_message_in_the_sink's_words
  (testing "any sink may carry a :template with {{placeholders}}"
    (is (= "🔥 printer on fire hit :escalated — tik 018f2f6e"
           (get (payload {:type :matrix
                          :template "🔥 {{title}} hit {{stage}} — tik {{short}}"}
                         tr)
                "body"))))
  (testing "addressed adapters adopt the template too"
    (is (= "printer on fire::escalated"
           (get (payload {:type :ntfy :topic "t"
                          :template "{{title}}:{{stage}}"} tr)
                "message"))))
  (testing "the full ticket id is available when needed"
    (is (str/includes?
         (get (payload {:type :slack :template "{{ticket}}"} tr) "text")
         "018f2f6e-7c1a-7000-8000-00000000beef"))))
