;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.reporter
  "Who sent a mailed report, recorded without the address (ADR 0043).

  The mail bridge records a DKIM-authenticated sender of a NEW ticket as
  a keyed pseudonym, `hmac-sha256:<64 hex>` of the normalised address,
  and keeps the pseudonym → address mapping in a table outside the
  store. The store's log is append-only and signed, so an address written
  there could never be erased; a row of the table can, and once it is
  gone the pseudonym on the ticket resolves to nothing.

    :reporter {:fact [:reporter :id]                  ; default
               :key {:command [\"pass\" \"show\" \"tik/reporter\"]}
               :table \"~/.local/state/tik/reporters\"}   ; a directory
    :block {:addresses [\"spam@example.org\"]
            :domains [\"example.net\"]                ; and its subdomains
            :pseudonyms [\"hmac-sha256:…\"]}

  Also the block list a mailbox refuses senders by, and the pure
  analytics over recorded pseudonyms. No store access here: the bridge
  and `tik reporter` bring the tickets."
  (:require [briko.paths :as paths]
            [briko.digest :as digest]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [briko.secret :as secret])
  (:import (java.io File)
           (java.nio.channels FileChannel)
           (java.nio.file CopyOption Files LinkOption OpenOption Path StandardCopyOption
                          StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermission PosixFilePermissions)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

;; ---------------------------------------------------------------- pseudonym

(def pseudonym-prefix "hmac-sha256:")

(def pseudonym-pattern #"hmac-sha256:[0-9a-f]{64}")

(def default-fact [:reporter :id])

(def min-key-bytes
  "HMAC-SHA256's own block of security: a shorter key makes the pseudonym
  easier to brute-force than the hash it is built on."
  32)

(defn pseudonym?
  [x]
  (boolean (and (string? x) (re-matches pseudonym-pattern x))))

(defn normalise-address
  "The form an address is keyed by: trimmed, lower-cased, without one
  pair of enclosing angle brackets. Nothing else is folded: plus tags,
  dots in a Gmail local part and IDN forms are provider rules, and folding
  them would merge mailboxes that are distinct elsewhere."
  [s]
  (let [t (str/lower-case (str/trim (str s)))]
    (if-let [[_ inner] (re-matches #"<(.*)>" t)]
      (str/trim inner)
      t)))

(defn pseudonym
  "The keyed pseudonym of `address` under `key` (bytes). Without the key
  it cannot be reversed or recomputed; with it, a guessed address can be
  checked, which is why the key is guarded like a signing key."
  ^String [^bytes key address]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. key "HmacSHA256")))]
    (str pseudonym-prefix
         (digest/hex (.doFinal mac (.getBytes ^String (normalise-address address) "UTF-8"))))))

;; ---------------------------------------------------------------- config

(defn expand-home ^String [s]
  (paths/expand-home (str s)))

(defn key-problem
  "Why a `:reporter :key` spec is refused, or nil. A literal key would sit
  in a config file, and an environment variable is inherited by every
  child the bridge or backend starts (ADR 0041)."
  [spec]
  (cond
    (nil? spec) ":reporter needs :key, a secret spec such as {:command …}, {:credential …} or {:file …}"
    (string? spec) ":reporter :key is a literal; give {:command …}, {:credential …} or {:file …}"
    (not (secret/spec? spec)) ":reporter :key must be {:command …}, {:credential …} or {:file …}"
    (contains? spec :env) ":reporter :key {:env …} is refused; every child process inherits the environment"))

(defn- fact-path? [x]
  (and (vector? x) (seq x) (every? keyword? x)))

(def ^:private domain-pattern #"[a-z0-9-]+(\.[a-z0-9-]+)*")

(defn- address-shaped? [x]
  (boolean (and (string? x) (re-matches #"[^@\s<>]+@[a-z0-9-]+(\.[a-z0-9-]+)*"
                                         (normalise-address x)))))

(defn- block-problems [p block reporter]
  (if-not (map? block)
    [(str p ": :block must be a map of :addresses, :domains and :pseudonyms")]
    (let [unknown (remove #{:addresses :domains :pseudonyms} (keys block))
          strings? #(and (vector? %) (every? string? %))]
      (cond-> []
        (seq unknown)
        (conj (str p ": :block has unknown key(s) " (str/join ", " (sort (map pr-str unknown)))))

        (and (contains? block :addresses)
             (not (and (strings? (:addresses block)) (every? address-shaped? (:addresses block)))))
        (conj (str p ": :block :addresses must be a vector of addresses such as \"someone@example.org\""))

        (and (contains? block :domains)
             (not (and (strings? (:domains block))
                       (every? #(re-matches domain-pattern %) (:domains block)))))
        (conj (str p ": :block :domains must be a vector of bare domain names such as \"example.org\""
                   " (lower case, no leading dot, @ or *; a domain also blocks its subdomains)"))

        (and (contains? block :pseudonyms)
             (not (and (strings? (:pseudonyms block)) (every? pseudonym? (:pseudonyms block)))))
        (conj (str p ": :block :pseudonyms must be a vector of \"hmac-sha256:<64 hex>\" values"))

        (and (seq (:pseudonyms block)) (not (map? reporter)))
        (conj (str p ": :block :pseudonyms needs :reporter, whose key computes a sender's pseudonym"))))))

(defn config-problems
  "What is wrong with a mailbox's (or bridge.edn's) `:reporter` and
  `:block`, as strings; empty when neither is set or both are sound. Pure:
  the key is judged by its spec, never resolved."
  [p {:keys [reporter block dkim] :as cfg}]
  (cond-> []
    (and (contains? cfg :reporter) (not (map? reporter)))
    (conj (str p ": :reporter must be a map {:key … :table …}"))

    (map? reporter)
    (into (let [unknown (remove #{:fact :key :table} (keys reporter))
                table (:table reporter)]
            (cond-> []
              (seq unknown)
              (conj (str p ": :reporter has unknown key(s) " (str/join ", " (sort (map pr-str unknown)))))

              (key-problem (:key reporter))
              (conj (str p ": " (key-problem (:key reporter))))

              (and (contains? reporter :fact) (not (fact-path? (:fact reporter))))
              (conj (str p ": :reporter :fact must be a vector of keywords such as [:reporter :id]"))

              (not (and (string? table) (not (str/blank? table))))
              (conj (str p ": :reporter needs :table, the local file that maps pseudonyms to addresses"))

              (and (string? table) (not (str/blank? table))
                   (not (.isAbsolute (io/file (expand-home table)))))
              (conj (str p ": :reporter :table must be an absolute path or start with ~/"))

              (not (and (true? (:require dkim)) (some? (:authserv-id dkim))))
              (conj (str p ": :reporter needs :dkim {:require true :authserv-id …};"
                         " an unauthenticated From is never recorded as a reporter")))))

    (contains? cfg :block)
    (into (block-problems p block reporter))))

(defn fact-of
  "The fact path a reporter config records its pseudonym under."
  [reporter]
  (or (:fact reporter) default-fact))

(defn table-of
  "The table path of a reporter config, with `~` expanded."
  [reporter]
  (some-> (:table reporter) expand-home))

(defn resolve-key
  "The key bytes of a sound `:key` spec, resolved now. Refuses a key
  shorter than `min-key-bytes` (counted as UTF-8 bytes of the resolved
  line); never puts the key in an error."
  ^bytes [spec]
  (when-let [p (key-problem spec)]
    (throw (ex-info p {:reason :reporter/bad-key})))
  (let [k (secret/resolve1 ":reporter :key" spec)
        b (.getBytes ^String (str k) "UTF-8")]
    (when (< (count b) min-key-bytes)
      (throw (ex-info (str ":reporter :key is " (count b) " bytes; it needs at least "
                           min-key-bytes " (for example 64 hex digits from `openssl rand -hex 32`)")
                      {:reason :reporter/short-key})))
    b))

;; ---------------------------------------------------------------- blocking

(defn domain-blocked?
  "Does the block entry `entry` cover `domain`? Equal, or `domain` ends in
  `.entry`: the match is on whole labels, so example.org covers
  mail.example.org and not badexample.org."
  [entry domain]
  (boolean (and (string? domain) (string? entry)
                (or (= domain entry) (str/ends-with? domain (str "." entry))))))

(defn blocked
  "Which rule of `block` refuses the sender `from`, or nil. `key` (bytes,
  may be nil) computes the sender's pseudonym for `:pseudonyms`. The
  answer names the rule without the address: {:rule :address :index i},
  {:rule :domain :entry d} or {:rule :pseudonym :entry p}."
  [block ^bytes key from]
  (when (and (map? block) (string? from))
    (let [addr (normalise-address from)
          domain (second (re-matches #"[^@]+@(.+)" addr))]
      (or (some (fn [[i a]] (when (= addr (normalise-address a)) {:rule :address :index i}))
                (map-indexed vector (:addresses block)))
          (some (fn [d] (when (domain-blocked? (str/lower-case d) domain) {:rule :domain :entry d}))
                (:domains block))
          (when (and key (seq (:pseudonyms block)))
            (let [p (pseudonym key addr)]
              (when (some #{p} (:pseudonyms block)) {:rule :pseudonym :entry p})))))))

(defn describe-block
  "A log line's account of a block, never naming the address itself."
  [{:keys [rule index entry]}]
  (case rule
    :address (str "address #" (inc (long index)) " of :block :addresses")
    :domain (str "domain " entry " of :block :domains")
    :pseudonym (str "pseudonym " entry " of :block :pseudonyms")))

;; ---------------------------------------------------------------- table
;; The table is a 0700 directory holding one 0600 file per pseudonym,
;; named by its hex digits and holding {:address … :first-seen …}. A row
;; is written to a temp file and renamed into place, so a reader sees a
;; whole row or none; two bridges recording the same sender write the
;; same address and the later rename wins. No lock is needed, and
;; forgetting a row is deleting its file.

(defn- path ^Path [f] (.toPath (io/file f)))

(def ^:private no-follow (make-array LinkOption 0))

(defn- perms [^Path p]
  (Files/getPosixFilePermissions p no-follow))

(def ^:private foreign-bits
  #{PosixFilePermission/GROUP_READ PosixFilePermission/GROUP_WRITE PosixFilePermission/GROUP_EXECUTE
    PosixFilePermission/OTHERS_READ PosixFilePermission/OTHERS_WRITE PosixFilePermission/OTHERS_EXECUTE})

(defn- attrs ^"[Ljava.nio.file.attribute.FileAttribute;" [mode]
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString mode))]))

(defn- check-private!
  "Refuse a table directory or row that anybody but its owner may use, or
  that is a symbolic link: the table holds the addresses the store keeps
  out of its log."
  [^Path p what]
  (when (Files/isSymbolicLink p)
    (throw (ex-info (str "the reporter table's " what " " p " is a symbolic link")
                    {:reason :reporter/table-permissions :path (str p)})))
  (when (Files/exists p no-follow)
    (let [ps (perms p)]
      (when (some foreign-bits ps)
        (throw (ex-info (str "the reporter table's " what " " p " is "
                             (PosixFilePermissions/toString ps)
                             "; it must be accessible by its owner only (chmod "
                             (if (= what "directory") "700" "600") ")")
                        {:reason :reporter/table-permissions :path (str p)}))))))

(defn- table-dir
  "The table directory, refused unless private; created 0700 when `create?`
  and missing. nil when it does not exist and is not to be created."
  ^Path [table create?]
  (let [dir (.toAbsolutePath (path table))]
    (when (and create? (not (Files/exists dir no-follow)))
      (Files/createDirectories dir (attrs "rwx------")))
    (when (Files/exists dir no-follow)
      (when-not (Files/isDirectory dir no-follow)
        (throw (ex-info (str "the reporter table " dir " is not a directory")
                        {:reason :reporter/table-unreadable})))
      (check-private! dir "directory")
      dir)))

(defn- row-name ^String [pseudonym]
  (str (subs pseudonym (count pseudonym-prefix)) ".edn"))

(defn- read-row [^Path f]
  (check-private! f "row")
  (let [m (try (canonical/parse (slurp (.toFile f))) (catch Exception _ nil))]
    (when-not (and (map? m) (string? (:address m)))
      (throw (ex-info (str "the reporter table row " f " is not a map with :address")
                      {:reason :reporter/table-unreadable})))
    m))

(defn read-table
  "The table at `table` as a map pseudonym → {:address … :first-seen …};
  {} when the directory does not exist yet."
  [table]
  (if-let [dir (table-dir table false)]
    (into (sorted-map)
          (for [^File f (.listFiles (.toFile dir))
                :let [[_ h] (re-matches #"([0-9a-f]{64})\.edn" (.getName f))]
                :when h]
            [(str pseudonym-prefix h) (read-row (.toPath f))]))
    {}))

(defn lookup
  "The row of `pseudonym`, or nil."
  [table pseudonym]
  (when (pseudonym? pseudonym)
    (when-let [dir (table-dir table false)]
      (let [f (.resolve dir (row-name pseudonym))]
        (when (Files/exists f no-follow) (read-row f))))))

(defn remember!
  "Record that `pseudonym` stands for `address`, first seen at `at` (a
  string), unless the table already holds it: a 0600 temp file in the
  0700 directory, forced to disk, then renamed into place."
  [table pseudonym address at]
  (when-not (pseudonym? pseudonym)
    (throw (ex-info "not a pseudonym" {:reason :reporter/not-a-pseudonym})))
  (let [dir (table-dir table true)
        dest (.resolve dir (row-name pseudonym))]
    (when-not (Files/exists dest no-follow)
      (let [tmp (Files/createTempFile dir ".row-" ".tmp" (attrs "rw-------"))]
        (try
          (with-open [ch (FileChannel/open tmp (into-array OpenOption [StandardOpenOption/WRITE
                                                                       StandardOpenOption/TRUNCATE_EXISTING]))]
            (.write ch (java.nio.ByteBuffer/wrap
                        (.getBytes (str (pr-str {:address (normalise-address address)
                                                 :first-seen (str at)})
                                        "\n")
                                   "UTF-8")))
            (.force ch true))
          (Files/move tmp dest (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
          (finally (Files/deleteIfExists tmp)))))
    dest))

(defn forget-rows
  "The pseudonyms of the rows `x` names in the table map `m`: a
  pseudonym's own row, or every row of an address."
  [m x]
  (if (pseudonym? x)
    (filterv #(contains? m %) [x])
    (let [a (normalise-address x)]
      (vec (for [[p row] m :when (= a (:address row))] p)))))

(defn forget!
  "Delete the rows `x` names; returns how many went."
  [table x]
  (if-let [dir (table-dir table false)]
    (count (filter #(Files/deleteIfExists (.resolve dir (row-name %)))
                   (forget-rows (read-table table) x)))
    0))

;; ---------------------------------------------------------------- analytics

(defn stats
  "Tickets per pseudonym, most first, from `rows` (maps with :pseudonym)
  and the table `m`: each {:pseudonym … :tickets n :address a-or-nil
  :forgotten? bool}. A pseudonym the table no longer holds is forgotten."
  [rows m]
  (->> (frequencies (keep :pseudonym rows))
       (map (fn [[p n]]
              (let [row (get m p)]
                {:pseudonym p :tickets n :address (:address row) :forgotten? (nil? row)})))
       (sort-by (juxt (comp - :tickets) :pseudonym))
       vec))

(defn file-mode
  "The permission string of `f`, such as \"rw-------\"."
  [f]
  (PosixFilePermissions/toString (perms (path f))))
