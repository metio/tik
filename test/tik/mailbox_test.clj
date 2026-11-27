;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mailbox-test
  "The mailbox list of `bridge imap` and the rules a watched mailbox is
  held to: DKIM against a pinned verifier, a password from a secret spec
  that is no literal and no environment variable, and TLS for every host
  that is not written as a loopback address."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.mailbox :as mailbox]
            [tik.netmail :as net]))

(def ^:private one
  {:id "support-example-org"
   :imap {:host "imap.example.org" :port 993 :user "support@example.org"
          :password {:command ["pass" "show" "mail/support"]}}
   :process :support})

(def ^:private good
  {:dkim {:require true :authserv-id "mx.example.org"}
   :default-actor "mail"
   :mailboxes [one
               (-> one
                   (assoc :id "support-app" :process :app-support)
                   (assoc-in [:imap :user] "support@app.example")
                   (assoc-in [:imap :password] {:credential "support-app-pw"}))]})

(defn- problems [cfg] (mailbox/problems cfg false))

(defn- refused? [cfg re]
  (let [ps (problems cfg)]
    (and (seq ps) (some #(re-find re %) ps))))

(deftest a_list_of_mailboxes_is_accepted
  (is (empty? (problems good))))

(deftest every_mailbox_inherits_the_top_level_defaults
  (let [mbs (mailbox/mailboxes good)]
    (is (= ["support-example-org" "support-app"] (mapv :id mbs)))
    (is (every? #(= "mail" (:default-actor %)) mbs))
    (is (every? #(= "mx.example.org" (get-in % [:dkim :authserv-id])) mbs))
    (is (= [:support :app-support] (mapv :process mbs)) "each routes to its own process")
    (is (not-any? :mailboxes mbs))))

(deftest a_mailbox_may_override_a_default
  (let [cfg (assoc-in good [:mailboxes 1 :default-actor] "app-mail")]
    (is (= ["mail" "app-mail"] (mapv :default-actor (mailbox/mailboxes cfg))))))

(deftest adding_a_mailbox_is_a_config_entry
  (let [cfg (update good :mailboxes conj (-> one (assoc :id "support-klimpi-app" :process :klimpi-support)
                                             (assoc-in [:imap :user] "support@klimpi.app")))]
    (is (empty? (problems cfg)))
    (is (= 3 (count (mailbox/mailboxes cfg))))))

(deftest a_single_imap_config_is_the_default_mailbox
  (let [cfg {:imap {:host "127.0.0.1" :port 1143 :tls false :user "u" :password "p"}
             :process :track :default-actor "bridge"}]
    (is (empty? (problems cfg)) "the single form keeps its lenient rules outside a backend")
    (is (= ["default"] (mapv :id (mailbox/mailboxes cfg))))
    (testing "but a backend holds it to the strict ones"
      (let [ps (mailbox/problems cfg true)]
        (is (some #(str/includes? % ":password is a literal") ps))
        (is (some #(str/includes? % "needs :dkim") ps))))))

(deftest dkim_is_required_with_a_pinned_authserv_id
  (is (refused? (dissoc good :dkim) #"needs :dkim \{:require true"))
  (is (refused? (assoc good :dkim {:require false :authserv-id "mx"}) #"needs :dkim"))
  (is (refused? (assoc good :dkim {:require true}) #"needs :authserv-id"))
  (is (refused? (assoc good :dkim {:require true :authserv-id ""}) #"needs :authserv-id"))
  (is (refused? (assoc good :dkim {:require true :authserv-id ["mx" " "]}) #"needs :authserv-id"))
  (is (empty? (problems (assoc good :dkim {:require true :authserv-id ["mx1.example.org" "mx2.example.org"]})))
      "several verifiers may be pinned"))

(deftest the_password_comes_from_a_secret_spec
  (is (refused? (assoc-in good [:mailboxes 0 :imap :password] "hunter2") #":password is a literal"))
  (is (refused? (assoc-in good [:mailboxes 0 :imap :password] {:env "IMAP_PW"}) #"\{:env …\} is refused"))
  (is (refused? (update-in good [:mailboxes 0 :imap] dissoc :password) #"needs :password"))
  (is (refused? (assoc-in good [:mailboxes 0 :imap :password] {:vault "x"}) #":password must be"))
  (is (empty? (problems (assoc-in good [:mailboxes 0 :imap :password] {:file "/run/secrets/pw"})))))

(deftest a_refusal_never_shows_the_password
  (let [ps (problems (assoc-in good [:mailboxes 0 :imap :password] "hunter2"))]
    (is (seq ps))
    (is (not-any? #(str/includes? % "hunter2") ps))))

(deftest plaintext_is_refused_outside_loopback
  (is (refused? (assoc-in good [:mailboxes 0 :imap :tls] false) #"plaintext IMAP \(:tls false\) is refused for imap.example.org"))
  (doseq [h ["127.0.0.1" "127.1.2.3" "localhost" "::1"]]
    (is (empty? (problems (-> good (assoc-in [:mailboxes 0 :imap :tls] false)
                              (assoc-in [:mailboxes 0 :imap :host] h))))
        h))
  (testing "the single form is held to it too"
    (is (seq (problems {:imap {:host "mail.example.org" :tls false :user "u" :password "p"}
                        :process :track})))))

(deftest loopback_is_judged_on_the_text
  (is (net/loopback-host? "127.0.0.1"))
  (is (net/loopback-host? "LOCALHOST"))
  (is (net/loopback-host? "[::1]"))
  (is (not (net/loopback-host? "127.0.0.256")))
  (is (not (net/loopback-host? "127.0.0.1.example.org")))
  (is (not (net/loopback-host? "localhost.example.org")))
  (is (not (net/loopback-host? "10.0.0.1")))
  (is (not (net/loopback-host? nil))))

(deftest plaintext_sockets_to_remote_hosts_are_refused_before_connecting
  (let [e (try (net/plain-socket "imap.example.org" 143 143) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :mail/plaintext-refused (:reason (ex-data e))))))

(deftest a_mailbox_needs_an_id_a_host_a_user_and_a_process
  (is (refused? (assoc-in good [:mailboxes 0 :id] "Support Mail") #":id must be"))
  (is (refused? (update good :mailboxes conj (assoc one :process :x)) #"named twice"))
  (is (refused? (update-in good [:mailboxes 0 :imap] dissoc :host) #"needs :host"))
  (is (refused? (update-in good [:mailboxes 0 :imap] dissoc :user) #"needs :user"))
  (is (refused? (update-in good [:mailboxes 0] dissoc :process) #"needs :process"))
  (is (refused? (dissoc good :default-actor) #"needs :default-actor"))
  (is (refused? (assoc-in good [:mailboxes 0 :imap :port] 99999) #":port must be"))
  (is (refused? (assoc-in good [:mailboxes 0 :imap :tls] "yes") #":tls must be true or false")))

(deftest unknown_keys_are_refused_so_a_typo_cannot_switch_a_check_off
  (is (refused? (assoc-in good [:mailboxes 0 :dkm] {:require true}) #"unknown key\(s\) :dkm"))
  (is (refused? (assoc-in good [:mailboxes 0 :imap :pass] "x") #":imap has unknown key\(s\) :pass")))

(deftest the_shape_of_the_config_is_checked
  (is (= ["an imap config must be a map"] (problems [])))
  (is (refused? {} #"needs :imap"))
  (is (refused? {:mailboxes []} #"non-empty vector"))
  (is (refused? (assoc good :imap (:imap one)) #"not both"))
  (is (refused? {:mailboxes ["x"]} #"must be a map")))

(deftest the_actors_a_mailbox_records_mail_as
  (is (= #{"mail"} (mailbox/attributed-actors (first (mailbox/mailboxes good)))))
  (is (= #{"mail" "seb"} (mailbox/attributed-actors
                          (assoc (first (mailbox/mailboxes good)) :from->actor {"seb@example.org" "seb"})))))

(deftest a_mailbox_can_be_picked_by_id
  (let [mbs (mailbox/mailboxes good)]
    (is (= mbs (mailbox/select mbs nil)))
    (is (= ["support-app"] (mapv :id (mailbox/select mbs "support-app"))))
    (is (nil? (mailbox/select mbs "nope")))))
