;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mailbox
  "The mailboxes one `bridge imap` config reads, and what makes one safe
  to read. Pure: it parses and judges a config, and resolves nothing.

  A config names one mailbox under `:imap`, or several under
  `:mailboxes`. Every other top-level key is a default for each mailbox,
  so a list of support addresses shares one DKIM verifier and one
  default actor:

    {:dkim {:require true :authserv-id \"mx.example.org\"}
     :default-actor \"mail\"
     :mailboxes
     [{:id \"support-example-org\" :process :support
       :imap {:host \"imap.example.org\" :user \"support@example.org\"
              :password {:command [\"pass\" \"show\" \"mail/support\"]}}}
      {:id \"support-app\" :process :app-support
       :imap {:host \"imap.example.org\" :user \"support@app.example\"
              :password {:credential \"support-app-pw\"}}}]}

  A mailbox in a `:mailboxes` list, and every mailbox a backend watches,
  must hold to the strict rules: DKIM is required against a pinned
  authserv-id, the password is a secret spec that is neither a literal
  nor an environment variable, and plaintext IMAP is only for a loopback
  address."
  (:require [clojure.string :as str]
            [tik.netmail :refer [loopback-host?]]
            [tik.secret :as secret]))

(def mailbox-id-pattern #"[a-z0-9][a-z0-9._-]{0,62}")

(def ^:private mailbox-keys
  #{:id :imap :process :default-actor :from->actor :dkim})

(def ^:private imap-keys
  #{:host :port :tls :user :password :mailbox :search :poll-ms})

(defn mailboxes
  "The mailboxes of a parsed config, each a complete single-mailbox
  config with the top-level defaults merged under it. A config with
  `:imap` and no `:mailboxes` is the one mailbox \"default\"."
  [cfg]
  (let [defaults (dissoc cfg :mailboxes :imap)]
    (cond
      (contains? cfg :mailboxes)
      (vec (for [mb (:mailboxes cfg)]
             (if (map? mb) (merge defaults mb) mb)))

      (contains? cfg :imap)
      [(assoc cfg :id "default")]

      :else [])))

(defn- password-problem [p pw]
  (cond
    (nil? pw) (str p ": :imap needs :password, a secret spec such as {:command …}")
    (string? pw) (str p ": :password is a literal; give a secret spec, {:command …}, {:credential …} or {:file …}")
    (not (secret/spec? pw)) (str p ": :password must be {:command …}, {:credential …} or {:file …}")
    (contains? pw :env) (str p ": :password {:env …} is refused; every process of the backend inherits its environment")))

(defn- dkim-problem [p {:keys [dkim]}]
  (let [ids (let [a (:authserv-id dkim)] (if (coll? a) a [a]))]
    (cond
      (not (true? (:require dkim)))
      (str p ": needs :dkim {:require true :authserv-id …}, so a forged From is never credited to anybody")

      (not (and (seq ids) (every? #(and (string? %) (not (str/blank? %))) ids)))
      (str p ": :dkim needs :authserv-id, the id the receiving MX stamps on Authentication-Results"))))

(defn mailbox-problems
  "What is wrong with one mailbox (merged with its defaults), as strings."
  [strict? mb]
  (if-not (map? mb)
    ["a mailbox must be a map"]
    (let [p (str "mailbox " (pr-str (:id mb)))
          imap (:imap mb)
          host (:host imap)
          plaintext? (false? (:tls imap))]
      (cond-> []
        (not (and (string? (:id mb)) (re-matches mailbox-id-pattern (:id mb))))
        (conj (str p ": :id must be a string of lower-case letters, digits and ._- (it labels the mailbox's output)"))

        (seq (remove mailbox-keys (keys mb)))
        (conj (str p ": unknown key(s) " (str/join ", " (sort (map pr-str (remove mailbox-keys (keys mb)))))))

        (not (map? imap))
        (conj (str p ": needs :imap {:host … :user … :password …}"))

        (and (map? imap) (seq (remove imap-keys (keys imap))))
        (conj (str p ": :imap has unknown key(s) " (str/join ", " (sort (map pr-str (remove imap-keys (keys imap)))))))

        (and (map? imap) (not (and (string? host) (not (str/blank? host)))))
        (conj (str p ": :imap needs :host"))

        (and (map? imap) (contains? imap :tls) (not (boolean? (:tls imap))))
        (conj (str p ": :tls must be true or false"))

        (and (map? imap) plaintext? (string? host) (not (loopback-host? host)))
        (conj (str p ": plaintext IMAP (:tls false) is refused for " host "; only a loopback address may skip TLS"))

        (and (map? imap) (contains? imap :port)
             (not (and (int? (:port imap)) (< 0 (:port imap) 65536))))
        (conj (str p ": :port must be a port number"))

        (and (map? imap) (nil? (:user imap)))
        (conj (str p ": :imap needs :user"))

        (and strict? (map? imap) (password-problem p (:password imap)))
        (conj (password-problem p (:password imap)))

        (not (or (keyword? (:process mb)) (and (string? (:process mb)) (not (str/blank? (:process mb))))))
        (conj (str p ": needs :process, the process a new mail opens a ticket under"))

        (and strict? (not (and (string? (:default-actor mb)) (not (str/blank? (:default-actor mb))))))
        (conj (str p ": needs :default-actor, the actor unknown senders are recorded as"))

        (and (contains? mb :from->actor)
             (not (and (map? (:from->actor mb)) (every? string? (keys (:from->actor mb)))
                       (every? string? (vals (:from->actor mb))))))
        (conj (str p ": :from->actor must map addresses to actor names"))

        (and strict? (dkim-problem p mb))
        (conj (dkim-problem p mb))))))

(defn problems
  "Every problem of a parsed `bridge imap` config, as strings; empty when
  it may run. A `:mailboxes` list is always held to the strict rules;
  `strict?` holds a single `:imap` mailbox to them too (the backend
  does)."
  [cfg strict?]
  (if-not (map? cfg)
    ["an imap config must be a map"]
    (let [list? (contains? cfg :mailboxes)
          mbs (mailboxes cfg)
          strict? (or strict? list?)]
      (cond-> []
        (and list? (contains? cfg :imap))
        (conj "give either :imap (one mailbox) or :mailboxes (a list), not both")

        (and list? (not (and (sequential? (:mailboxes cfg)) (seq (:mailboxes cfg)))))
        (conj ":mailboxes must be a non-empty vector of mailboxes")

        (and (not list?) (not (contains? cfg :imap)))
        (conj "needs :imap {:host … :user … :password …} or :mailboxes [ … ]")

        (sequential? (:mailboxes cfg (vector)))
        (into (mapcat #(mailbox-problems strict? %) mbs))

        true
        (into (for [[id n] (frequencies (keep #(when (map? %) (:id %)) mbs)) :when (< 1 n)]
                (str "mailbox " (pr-str id) " is named twice")))))))

(defn attributed-actors
  "Every actor a mailbox can record mail as: its default actor and the
  actors its From mapping names. The key that signs its events must be
  granted to each of them, or what it ingests does not verify."
  [mb]
  (into (sorted-set) (remove nil?) (cons (:default-actor mb) (vals (:from->actor mb)))))

(defn select
  "The mailboxes `only` names (all when nil), or nil when it names one
  the config lacks."
  [mbs only]
  (if (nil? only)
    mbs
    (let [picked (filterv #(= only (:id %)) mbs)]
      (when (seq picked) picked))))
