;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.reporter-cmd
  "`tik reporter`: resolve, forget and count the pseudonyms the mail
  bridge records (ADR 0043). The table it reads lies outside the store;
  the counts come from the store's tickets."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [read-edn-file]]
            [tik.cli-core :refer [all-ticket-ctx die exit! root the-store]]
            [tik.mailbox :as mailbox]
            [tik.reduce :as reduce]
            [tik.render :refer [emit-data]]
            [tik.reporter :as reporter]))

(defn- configured-reporters
  "The `:reporter` maps of the mailboxes a config names, after `--mailbox`."
  [opts]
  (let [f (io/file (or (:config opts)
                       (let [imap (io/file (root) "imap.edn")]
                         (if (.isFile imap) imap (io/file (root) "bridge.edn")))))]
    (when (.isFile f)
      (let [cfg (read-edn-file f)
            mbs (if (or (contains? cfg :mailboxes) (contains? cfg :imap))
                  (mailbox/mailboxes cfg)
                  [cfg])
            mbs (if-let [only (:mailbox opts)]
                  (or (mailbox/select mbs only)
                      (die (str "no mailbox " (pr-str only) " in " f)))
                  mbs)]
        (vec (distinct (keep #(when (map? (:reporter %)) (:reporter %)) mbs)))))))

(defn- settings
  "{:table … :fact …}: --table wins; else the one reporter the config
  (--config, else imap.edn or bridge.edn at the store root) names."
  [opts]
  (if-let [t (:table opts)]
    {:table (reporter/expand-home t) :fact reporter/default-fact}
    (let [rs (configured-reporters opts)
          tables (distinct (map reporter/table-of rs))]
      (case (count tables)
        0 (die "which table? give --table <dir>, or a --config whose mailbox has :reporter")
        1 {:table (first tables) :fact (reporter/fact-of (first rs))}
        (die (str "the config names several reporter tables (" (str/join ", " tables)
                  "); pick one with --mailbox or --table"))))))

(defn- cmd-show [opts p]
  (when-not (reporter/pseudonym? p)
    (die (str "usage: tik reporter show <hmac-sha256:…> — got " (pr-str p))))
  (let [{:keys [table]} (settings opts)]
    (if-let [{:keys [address first-seen]} (reporter/lookup table p)]
      (do (println (str p " " address " (first seen " first-seen ")"))
          (exit! 0))
      (do (println (str p " unresolvable: the table " table " holds no row for it"))
          (exit! 1)))))

(defn- cmd-forget [opts x]
  (when (str/blank? (str x))
    (die "usage: tik reporter forget <address|hmac-sha256:…>"))
  (let [{:keys [table]} (settings opts)
        n (reporter/forget! table x)]
    (println (if (pos? n)
               (str "forgot " n " row" (when (< 1 n) "s") "; the pseudonym on the tickets no longer resolves")
               "nothing to forget: the table holds no such row"))
    (exit! 0)))

(defn ticket-rows
  "One row per ticket context whose `fact` holds a pseudonym."
  [ctxs fact]
  (for [ctx ctxs
        :let [v (reduce/fact-value (:state ctx) fact)]
        :when (reporter/pseudonym? v)]
    {:ticket (:id ctx) :pseudonym v}))

(defn- cmd-stats [opts]
  (let [{:keys [table fact]} (settings opts)
        rows (reporter/stats (ticket-rows (all-ticket-ctx (the-store)) fact)
                             (reporter/read-table table))]
    (when-not (emit-data opts rows)
      (if (empty? rows)
        (println (str "no ticket carries " (str/join "." (map name fact))))
        (doseq [{:keys [pseudonym tickets address forgotten?]} rows]
          (println (format "%5d  %s  %s" tickets pseudonym (if forgotten? "(forgotten)" address))))))
    (exit! 0)))

(defn cmd-reporter
  "reporter show <pseudonym> | forget <address|pseudonym> | stats
  [--table DIR | --config F [--mailbox ID]] [--format json]"
  [{:keys [pos opts]}]
  (case (first pos)
    "show" (cmd-show opts (second pos))
    "forget" (cmd-forget opts (second pos))
    "stats" (cmd-stats opts)
    (die (str "usage: tik reporter show <hmac-sha256:…>\n"
              "       tik reporter forget <address|hmac-sha256:…>\n"
              "       tik reporter stats [--format json]\n"
              "  each takes --table <dir>, or --config <imap.edn> [--mailbox <id>]"))))
