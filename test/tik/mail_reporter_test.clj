;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mail-reporter-test
  "The mail bridge records a DKIM-authenticated sender of a new ticket as
  a keyed pseudonym, keeps the address in a private table outside the
  store, refuses blocked senders before writing anything, and `tik
  reporter` resolves, forgets and counts the pseudonyms (ADR 0043)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.bridge :as bridge]
            [tik.cli]
            [tik.cli-core :as core]
            [tik.harness :as h]
            [tik.imap]
            [tik.mailbox :as mailbox]
            [tik.reduce :as reduce]
            [tik.reporter :as reporter]
            [tik.store.protocol :as store]))

;; ------------------------------------------------------------ fixtures

(def ^:private key-a (str/join (repeat 4 "0123456789abcdef")))
(def ^:private key-b (str/join (repeat 4 "fedcba9876543210")))

(def ^:private reported-process
  {:process/id :reported
   :process/version 1
   :process/guard-vocab 2
   :process/purpose "a report mailed to a desk"
   :process/facts {[:reporter :id] [:re "^hmac-sha256:[0-9a-f]{64}$"]
                   [:outcome] [:string {:min 4}]}
   :process/stages [{:stage/id :reported
                     :guards [[:fact [:reporter :id]]]}]})

(defn- fixture
  "A store with the `reported` process, a key file and a table directory
  outside the store, and a config file outside the store too."
  ([] (fixture {}))
  ([{:keys [key] :or {key key-a}}]
   (let [{:keys [root store]} (h/temp-store!)
         outside (h/temp-dir! "tik-reporter")
         key-file (io/file outside "reporter.key")
         table (str (io/file outside "state" "reporters"))]
     (io/make-parents (io/file root "processes" "reported.edn"))
     (spit (io/file root "processes" "reported.edn") (pr-str reported-process))
     (spit key-file (str key "\n"))
     {:root root :store store :outside outside :table table
      :reporter {:key {:file (str key-file)} :table table}})))

(defn- cfg [{:keys [reporter]} & {:as extra}]
  (merge {:process :reported :default-actor "inbound"
          :dkim {:require true :authserv-id "mx.support.example"}
          :reporter reporter}
         extra))

(defn- mail
  [{:keys [from subject body ar msgid]
    :or {subject "help" body "the body" ar "mx.support.example; dkim=pass header.d=customer.example"}}]
  (str (when ar (str "Authentication-Results: " ar "\n"))
       "From: " from "\n"
       (when msgid (str "Message-ID: " msgid "\n"))
       "Subject: " subject "\n\n" body))

(defn- run-email [{:keys [root outside]} config raw]
  (let [f (io/file outside "bridge.edn")]
    (spit f (pr-str config))
    (h/with-cli-root root
      (fn []
        (binding [*in* (java.io.StringReader. raw)]
          (tik.cli/run-argv ["bridge" "email" "--config" (str f)]))))))

(defn- run-tik [{:keys [root]} & argv]
  (h/with-cli-root root #(tik.cli/run-argv (vec argv))))

(defn- reporter-fact [root store]
  (h/with-cli-root root
    (fn []
      (vec (for [id (store/ticket-ids store)]
             (reduce/fact-value (:state (core/ticket-ctx store id)) [:reporter :id]))))))

(defn- key-bytes [^String k] (.getBytes k "UTF-8"))

(defn- all-store-text
  "Every byte under the store root, read as text: events, blobs, sidecars."
  [root]
  (str/join "\n" (for [^java.io.File f (file-seq (io/file root)) :when (.isFile f)]
                   (slurp f))))

;; ------------------------------------------------------------ pseudonym

(deftest the_pseudonym_is_a_keyed_hash_of_the_normalised_address
  (let [p (reporter/pseudonym (key-bytes key-a) "alice@customer.example")]
    (testing "deterministic and well formed"
      (is (re-matches reporter/pseudonym-pattern p))
      (is (= p (reporter/pseudonym (key-bytes key-a) "alice@customer.example"))))
    (testing "case, surrounding blanks and angle brackets do not change it"
      (is (= p (reporter/pseudonym (key-bytes key-a) "  Alice@Customer.EXAMPLE ")))
      (is (= p (reporter/pseudonym (key-bytes key-a) "<alice@customer.example>"))))
    (testing "another key gives another pseudonym"
      (is (not= p (reporter/pseudonym (key-bytes key-b) "alice@customer.example"))))
    (testing "another address gives another pseudonym; plus tags are not folded"
      (is (not= p (reporter/pseudonym (key-bytes key-a) "bob@customer.example")))
      (is (not= p (reporter/pseudonym (key-bytes key-a) "alice+x@customer.example"))))
    (testing "it is HMAC-SHA256 (RFC 4231 test case 2)"
      (is (= "hmac-sha256:5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
             (reporter/pseudonym (key-bytes "Jefe") "what do ya want for nothing?"))))))

;; ------------------------------------------------------------ config

(defn- are-refused [problems ok]
  (doseq [[why bad re] [["no key" (update ok :reporter dissoc :key) #"needs :key"]
                        ["a literal key" (assoc-in ok [:reporter :key] "secret-literal") #"literal"]
                        ["an env key" (assoc-in ok [:reporter :key] {:env "K"}) #"\{:env …\} is refused"]
                        ["no table" (update ok :reporter dissoc :table) #"needs :table"]
                        ["a relative table" (assoc-in ok [:reporter :table] "state/r") #"absolute path"]
                        ["no dkim" (dissoc ok :dkim) #"needs :dkim \{:require true"]
                        ["dkim not required" (assoc-in ok [:dkim :require] false) #"needs :dkim"]
                        ["a bad fact" (assoc-in ok [:reporter :fact] "reporter.id") #":fact must be a vector"]
                        ["an unknown key" (assoc-in ok [:reporter :salt] "x") #"unknown key"]
                        ["a non-map" (assoc ok :reporter "yes") #"must be a map"]
                        ["a domain with a leading dot" (assoc ok :block {:domains [".example.org"]}) #"bare domain"]
                        ["a wildcard domain" (assoc ok :block {:domains ["*.example.org"]}) #"bare domain"]
                        ["a bad address" (assoc ok :block {:addresses ["nobody"]}) #":addresses must be"]
                        ["a bad pseudonym" (assoc ok :block {:pseudonyms ["hmac-sha256:xyz"]}) #":pseudonyms must be"]
                        ["pseudonyms without a reporter" (assoc (dissoc ok :reporter) :block {:pseudonyms [(str "hmac-sha256:" (str/join (repeat 64 "a")))]})
                         #"needs :reporter"]
                        ["an unknown block key" (assoc ok :block {:ips ["1.2.3.4"]}) #"unknown key"]]]
    (testing why
      (is (some #(re-find re %) (problems bad)) (pr-str (problems bad))))))

(deftest reporter_and_block_configs_are_refused_when_unsound
  (let [ok {:process :p :default-actor "a"
            :dkim {:require true :authserv-id "mx"}
            :reporter {:key {:file "/k"} :table "/var/lib/tik/reporters"}}
        problems #(reporter/config-problems "mailbox \"m\"" %)]
    (is (= [] (problems ok)))
    (is (= [] (problems (assoc-in ok [:reporter :table] "~/state/reporters"))) "~/ is accepted")
    (is (= [] (problems (dissoc ok :reporter))) "neither set is fine")
    (are-refused problems ok)))

(deftest a_mailbox_list_carries_the_reporter_checks
  (let [mb {:id "security" :process :vulnerability
            :imap {:host "imap.example.org" :user "security@example.org"
                   :password {:command ["pass" "show" "mail"]}}}
        base {:dkim {:require true :authserv-id "mx.example.org"} :default-actor "mail"}]
    (is (= [] (mailbox/problems (assoc base :mailboxes [(assoc mb :reporter {:key {:credential "rk"} :table "/srv/r"}
                                                               :block {:domains ["example.net"]})])
                                true)))
    (is (some #(re-find #"mailbox \"security\": :reporter :key \{:env" %)
              (mailbox/problems (assoc base :mailboxes [(assoc mb :reporter {:key {:env "K"} :table "/srv/r"})]) true)))))

(deftest the_bridge_refuses_to_start_on_a_bad_key_or_process
  (testing "a key shorter than 32 bytes"
    (let [fx (fixture {:key "short-key"})
          r (run-email fx (cfg fx) (mail {:from "alice@customer.example"}))]
      (is (= 1 (:exit r)))
      (is (re-find #"needs at least 32" (:err r)))
      (is (not (str/includes? (:err r) "short-key")) "the key never appears in an error")
      (is (empty? (store/ticket-ids (:store fx))))))
  (testing "a key file that does not exist"
    (let [fx (fixture)
          r (run-email fx (cfg fx :reporter (assoc (:reporter fx) :key {:file "/nonexistent/key"}))
                       (mail {:from "alice@customer.example"}))]
      (is (= 1 (:exit r)))
      (is (empty? (store/ticket-ids (:store fx))))))
  (testing "a process that does not declare the fact"
    (let [fx (fixture)
          r (run-email fx (cfg fx :process :track) (mail {:from "alice@customer.example"}))]
      (is (= 1 (:exit r)))
      (is (re-find #"does not declare the fact \[:reporter :id\]" (:err r)))))
  (testing "a process whose schema a pseudonym does not satisfy"
    (let [fx (fixture)]
      (spit (io/file (:root fx) "processes" "reported.edn")
            (pr-str (assoc-in reported-process [:process/facts [:reporter :id]] [:enum :a :b])))
      (let [r (run-email fx (cfg fx) (mail {:from "alice@customer.example"}))]
        (is (= 1 (:exit r)))
        (is (re-find #"does not satisfy" (:err r))))))
  (testing "a table inside the store"
    (let [fx (fixture)
          r (run-email fx (cfg fx :reporter (assoc (:reporter fx) :table (str (io/file (:root fx) "reporters"))))
                       (mail {:from "alice@customer.example"}))]
      (is (= 1 (:exit r)))
      (is (re-find #"lies inside the store" (:err r)))
      (is (not (.exists (io/file (:root fx) "reporters")))))))

;; ------------------------------------------------------------ recording

(deftest a_new_ticket_from_an_authenticated_sender_carries_the_pseudonym
  (let [{:keys [root store table] :as fx} (fixture)
        r (run-email fx (cfg fx) (mail {:from "Alice <Alice@Customer.example>"}))
        expected (reporter/pseudonym (key-bytes key-a) "alice@customer.example")]
    (is (zero? (:exit r)) (:err r))
    (is (= [expected] (reporter-fact root store)))
    (testing "the ticket derives the stage the fact drives"
      (let [id (first (store/ticket-ids store))]
        (is (re-find #":reported" (:out (run-tik fx "status" (str id) "--edn"))))))
    (testing "the table holds the address, private"
      (is (= "alice@customer.example" (:address (reporter/lookup table expected))))
      (is (= "rwx------" (reporter/file-mode table)))
      (is (every? #(= "rw-------" (reporter/file-mode %))
                  (.listFiles (io/file table)))))
    (testing "the address is nowhere in the store, though the scan reads the mail blob"
      (is (str/includes? (all-store-text root) "the body"))
      (is (not (str/includes? (all-store-text root) "alice@customer.example"))))))

(deftest no_pseudonym_without_a_passed_dkim_gate
  (testing "a failing DKIM verdict refuses the mail; nothing is recorded"
    (let [{:keys [store table] :as fx} (fixture)
          r (run-email fx (cfg fx) (mail {:from "alice@customer.example"
                                          :ar "evil.example; dkim=pass header.d=customer.example"}))]
      (is (= 1 (:exit r)))
      (is (empty? (store/ticket-ids store)))
      (is (= {} (reporter/read-table table)))))
  (testing "a config that slips past the checks without DKIM records no fact"
    (let [{:keys [root store table] :as fx} (fixture)
          c (assoc (dissoc (cfg fx) :dkim) ::bridge/reporter-key (key-bytes key-a))
          out (h/with-cli-root root
                #(bridge/ingest-message! store c (mail {:from "alice@customer.example" :ar nil}) {}))]
      (is (= :ticket (:outcome out)))
      (is (nil? (:reporter out)))
      (is (= [nil] (reporter-fact root store)))
      (is (= {} (reporter/read-table table)))))
  (testing "an unprepared :reporter fails closed"
    (let [{:keys [root store] :as fx} (fixture)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not resolved"
                            (h/with-cli-root root
                              #(bridge/ingest-message! store (cfg fx) (mail {:from "alice@customer.example"}) {}))))
      (is (empty? (store/ticket-ids store))))))

(deftest a_comment_records_no_reporter_and_tik_lines_cannot_forge_one
  (let [{:keys [root store table] :as fx} (fixture)
        _ (run-email fx (cfg fx) (mail {:from "alice@customer.example" :msgid "<first@x>"}))
        id (first (store/ticket-ids store))
        alice (reporter/pseudonym (key-bytes key-a) "alice@customer.example")
        forged (str "hmac-sha256:" (str/join (repeat 64 "b")))
        r (run-email fx (cfg fx) (mail {:from "bob@customer.example" :msgid "<second@x>"
                                        :subject (str "re [tik " id "]")
                                        :body (str "tik> reporter.id=" forged "\ntik> outcome=fixed now")}))]
    (is (zero? (:exit r)) (:err r))
    (is (= [alice] (reporter-fact root store)) "the reporter is still the sender who opened it")
    (is (= "fixed now" (reduce/fact-value (h/with-cli-root root #(:state (core/ticket-ctx store id))) [:outcome]))
        "other tik> lines still apply")
    (is (= #{alice} (set (keys (reporter/read-table table)))) "a commenter gets no row")))

(def ^:private gen-word
  (gen/fmap #(str "zq" (str/join %)) (gen/vector (gen/elements "abcdefghijklmnopqrstuvwxyz0123456789") 6 12)))

(defspec no_plaintext_address_reaches_the_store 20
  (prop/for-all [local gen-word
                 domain gen-word
                 subject (gen/fmap #(str/join " " %) (gen/vector gen-word 0 4))
                 body (gen/fmap #(str/join " " %) (gen/vector gen-word 0 8))
                 display (gen/elements ["" "Some One " "\"Quoted, Name\" "])]
    (let [{:keys [root store table] :as fx} (fixture)
          addr (str local "@" domain ".example")
          from (if (= "" display) addr (str display "<" (str/upper-case addr) ">"))
          r (run-email fx (cfg fx) (mail {:from from :subject subject :body body
                                          :ar (str "mx.support.example; dkim=pass header.d=" domain ".example")}))
          text (all-store-text root)]
      (and (zero? (:exit r))
           (or (str/blank? body) (str/includes? text body))
           (= 1 (count (store/ticket-ids store)))
           (not (str/includes? text addr))
           (not (str/includes? (str/lower-case text) addr))
           (not (str/includes? text local))
           (= addr (:address (reporter/lookup table (first (reporter-fact root store)))))))))

;; ------------------------------------------------------------ table

(deftest the_table_is_private_and_written_whole
  (let [dir (str (io/file (h/temp-dir! "tik-table") "a" "reporters"))
        p (reporter/pseudonym (key-bytes key-a) "alice@customer.example")]
    (reporter/remember! dir p "Alice@Customer.example" "2026-11-27T00:00:00Z")
    (testing "directory 0700 and row 0600, no temp file left behind"
      (is (= "rwx------" (reporter/file-mode dir)))
      (is (= [(str (subs p 12) ".edn")] (map #(.getName ^java.io.File %) (.listFiles (io/file dir)))))
      (is (= "rw-------" (reporter/file-mode (first (.listFiles (io/file dir)))))))
    (testing "a second remember keeps the first-seen time"
      (reporter/remember! dir p "alice@customer.example" "2030-01-01T00:00:00Z")
      (is (= {:address "alice@customer.example" :first-seen "2026-11-27T00:00:00Z"}
             (reporter/lookup dir p))))
    (testing "a table readable by others is refused"
      (java.nio.file.Files/setPosixFilePermissions
       (.toPath (io/file dir)) (java.nio.file.attribute.PosixFilePermissions/fromString "rwxr-x---"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owner only" (reporter/read-table dir)))
      (java.nio.file.Files/setPosixFilePermissions
       (.toPath (io/file dir)) (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------")))
    (testing "a row readable by others is refused"
      (let [^java.io.File f (first (.listFiles (io/file dir)))]
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath f) (java.nio.file.attribute.PosixFilePermissions/fromString "rw-r--r--"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"owner only" (reporter/lookup dir p)))))))

(deftest forget_then_show_is_unresolvable
  (let [{:keys [table] :as fx} (fixture)
        _ (run-email fx (cfg fx) (mail {:from "alice@customer.example" :msgid "<a@x>"}))
        _ (run-email fx (cfg fx) (mail {:from "bob@customer.example" :msgid "<b@x>"}))
        alice (reporter/pseudonym (key-bytes key-a) "alice@customer.example")
        bob (reporter/pseudonym (key-bytes key-a) "bob@customer.example")]
    (testing "show resolves"
      (let [r (run-tik fx "reporter" "show" alice "--table" table)]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "alice@customer.example"))))
    (testing "forget by address"
      (let [r (run-tik fx "reporter" "forget" "Alice@Customer.example" "--table" table)]
        (is (zero? (:exit r)) (:err r))
        (is (str/includes? (:out r) "forgot 1 row")))
      (let [r (run-tik fx "reporter" "show" alice "--table" table)]
        (is (= 1 (:exit r)))
        (is (str/includes? (:out r) "unresolvable"))))
    (testing "forget by pseudonym"
      (is (zero? (:exit (run-tik fx "reporter" "forget" bob "--table" table))))
      (is (str/includes? (:out (run-tik fx "reporter" "show" bob "--table" table)) "unresolvable")))
    (testing "forgetting again is not an error"
      (let [r (run-tik fx "reporter" "forget" bob "--table" table)]
        (is (zero? (:exit r)))
        (is (str/includes? (:out r) "nothing to forget"))))
    (testing "the table comes from the config when --table is absent"
      (let [f (io/file (:outside fx) "bridge.edn")]
        (spit f (pr-str (cfg fx)))
        (is (str/includes? (:out (run-tik fx "reporter" "show" alice "--config" (str f))) "unresolvable"))))))

;; ------------------------------------------------------------ blocking

(deftest blocked_senders_are_refused_and_nothing_is_written
  (let [k (key-bytes key-a)
        bad (reporter/pseudonym k "pest@customer.example")]
    (doseq [[why block from] [["by address" {:addresses ["Pest@Customer.example"]} "pest@customer.example"]
                              ["by domain" {:domains ["customer.example"]} "pest@customer.example"]
                              ["by subdomain" {:domains ["customer.example"]} "pest@mail.customer.example"]
                              ["by pseudonym" {:pseudonyms [bad]} "pest@customer.example"]]]
      (testing why
        (let [{:keys [store table root] :as fx} (fixture)
              r (run-email fx (cfg fx :block block)
                           (mail {:from from :ar "mx.support.example; dkim=pass header.d=customer.example"}))]
          (is (= 1 (:exit r)))
          (is (re-find #"refusing a blocked sender" (:err r)))
          (is (not (str/includes? (:err r) "the body")) "the body is never logged")
          (is (not (str/includes? (str (:out r) (:err r)) "pest@")) "the address is never logged")
          (is (empty? (store/ticket-ids store)) "no event")
          (is (= {} (reporter/read-table table)) "no row")
          (is (empty? (remove #{"processes"} (map #(.getName ^java.io.File %) (.listFiles (io/file root)))))
              "nothing else under the store"))))
    (testing "a lookalike domain and another address pass"
      (is (nil? (reporter/blocked {:domains ["customer.example"]} k "a@badcustomer.example")))
      (is (nil? (reporter/blocked {:addresses ["pest@customer.example"]} k "pest2@customer.example")))
      (is (nil? (reporter/blocked {:pseudonyms [bad]} k "other@customer.example"))))
    (testing "the rule is named without the address"
      (is (= {:rule :address :index 0} (reporter/blocked {:addresses ["pest@customer.example"]} k "pest@customer.example")))
      (is (= {:rule :domain :entry "customer.example"} (reporter/blocked {:domains ["customer.example"]} k "x@a.customer.example")))
      (is (= "address #1 of :block :addresses" (reporter/describe-block {:rule :address :index 0}))))))

(deftest a_blocked_mail_is_skipped_and_the_poll_continues
  (let [{:keys [root store] :as fx} (fixture)
        ar "mx.support.example; dkim=pass header.d=customer.example"
        good (mail {:from "alice@customer.example" :msgid "<g@x>" :ar ar})
        pest (mail {:from "pest@customer.example" :msgid "<p@x>" :ar ar})
        good2 (mail {:from "carol@customer.example" :msgid "<g2@x>" :ar ar})
        f (io/file (:outside fx) "imap.edn")]
    (spit f (pr-str (assoc (cfg fx :block {:addresses ["pest@customer.example"]})
                           :imap {:host "127.0.0.1" :tls false :user "desk" :password "unused"})))
    (with-redefs [tik.imap/fetch-messages (fn [_] [{:uid 1 :raw good} {:uid 2 :raw pest} {:uid 3 :raw good2}])]
      (let [r (h/with-cli-root root #(tik.cli/run-argv ["bridge" "imap" "--config" (str f)]))]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"skip uid 2: refusing a blocked sender: address #1" (:err r)))
        (is (re-find #"uid 1: new ticket \S+ from hmac-sha256:[0-9a-f]{8}…" (:out r)))
        (is (= 2 (count (store/ticket-ids store))))))))

;; ------------------------------------------------------------ stats

(deftest stats_counts_tickets_per_pseudonym
  (testing "the pure count"
    (let [a "hmac-sha256:aa" b "hmac-sha256:bb" c "hmac-sha256:cc"]
      (is (= [{:pseudonym a :tickets 3 :address "a@x" :forgotten? false}
              {:pseudonym b :tickets 1 :address nil :forgotten? true}
              {:pseudonym c :tickets 1 :address "c@x" :forgotten? false}]
             (reporter/stats [{:pseudonym a} {:pseudonym c} {:pseudonym a} {:pseudonym b}
                              {:pseudonym a} {:pseudonym nil} {}]
                             {a {:address "a@x"} c {:address "c@x"}})))
      (is (= [] (reporter/stats [] {})))))
  (testing "over a store, with a forgotten row"
    (let [{:keys [table] :as fx} (fixture)]
      (doseq [[from id] [["alice@customer.example" "<1@x>"] ["alice@customer.example" "<2@x>"]
                         ["bob@customer.example" "<3@x>"]]]
        (is (zero? (:exit (run-email fx (cfg fx) (mail {:from from :msgid id}))))))
      (run-tik fx "reporter" "forget" "bob@customer.example" "--table" table)
      (let [r (run-tik fx "reporter" "stats" "--table" table)
            lines (str/split-lines (:out r))]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"^\s+2  hmac-sha256:[0-9a-f]{64}  alice@customer.example$" (first lines)))
        (is (re-find #"^\s+1  hmac-sha256:[0-9a-f]{64}  \(forgotten\)$" (second lines))))
      (let [r (run-tik fx "reporter" "stats" "--table" table "--edn")]
        (is (= [2 1] (map :tickets (read-string (:out r)))))))))
