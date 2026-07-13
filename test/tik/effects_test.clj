;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.effects-test
  "The sink armory: every adapter is a pure payload mapping (pinned
  here shape by shape), and the :command sink is the universal escape
  hatch — the webhook JSON piped to any program, tested end to end."
  (:require [tik.harness :as h]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.cli :as cli]))

(def ^:private payload #'cli/effect-payload)

(def tr {:ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef"
         :title "printer on fire" :stage :escalated})

(deftest json-str-escapes-all-control-characters
  ;; a hostile title with raw CR/TAB/other control chars must produce
  ;; VALID JSON (RFC 8259 escapes U+0000–U+001F) — else a strict endpoint
  ;; 400s and, since post! ran :throw false, the effect was ledgered
  ;; as sent and never retried.
  (let [json-str #'cli/json-str
        title (str "line1" \return "line2" \tab "x" (char 1))
        out (json-str {"title" title})]
    (is (map? (json/parse-string out)) "the emitted JSON parses")
    (is (not (str/includes? out (str \return))) "no raw CR in the output")
    (is (= title (get (json/parse-string out) "title")) "round-trips the value")))

(deftest post-failure-message-redacts-the-webhook-secret
  ;; a webhook path/query often IS the secret (Slack/Discord tokens live
  ;; in the URL path); the delivery-failure message is printed to stderr,
  ;; so it must carry only scheme+host, never the full URL.
  (let [redact #'cli/redact-url]
    (is (= "https://hooks.slack.com"
           (redact "https://hooks.slack.com/services/T000/B000/XXXXsecretXXXX")))
    (is (= "https://host:9000" (redact "https://host:9000/hook?token=sekret")))
    (is (not (str/includes? (redact "https://x/y?token=sekret") "sekret")))))

(deftest email-message-strips-header-injection
  ;; a display title carrying CR/LF must not forge a new header (Bcc:)
  ;; that `sendmail -t` would honor (RFC 5322 header injection).
  (let [em #'cli/email-message
        msg (em {:to "ops@x" :from "tik@x"}
                {:ticket "id1"
                 :title (str "Pwned" \return \newline "Bcc: evil@x") :stage :s}
                "body")
        headers (take-while (complement str/blank?) (str/split-lines msg))]
    (is (not-any? #(str/starts-with? % "Bcc:") headers)
        "the CRLF in the title cannot split off a Bcc header")))

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
  (let [root (h/temp-dir! "tik-fx")
        outfile (io/file root "received.json")
        tik! (fn [& args]
               (:out (apply h/tik! {:root root :actor "seb"} args)))
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

(deftest header_values_can_come_from_the_environment
  (let [resolve-headers #'cli/resolve-headers]
    (testing "{:env NAME} pulls from the process environment at send time"
      (is (= {"Authorization" (System/getenv "PATH")}
             (resolve-headers {"Authorization" {:env "PATH"}}))))
    (testing "plain strings pass through untouched"
      (is (= {"X-Key" "literal"} (resolve-headers {"X-Key" "literal"}))))
    (testing "an unset variable fails loudly with its name"
      (is (thrown-with-msg?
           Exception #"TIK_TEST_SURELY_UNSET"
           (resolve-headers {"X-Key" {:env "TIK_TEST_SURELY_UNSET"}}))))))

(deftest header_values_can_come_from_a_password_manager
  (let [resolve-headers #'cli/resolve-headers]
    (testing "{:command [...]} takes the first stdout line, trimmed"
      (is (= {"Authorization" "GenieKey s3cret"}
             (resolve-headers
              {"Authorization"
               {:command ["printf" "GenieKey s3cret\nsecond line"]}}))))
    (testing "a failing lookup names the header and the command"
      (is (thrown-with-msg?
           Exception #"X-Key lookup command"
           (resolve-headers {"X-Key" {:command ["false"]}}))))))
