;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.notifier
  "`tik notify`: a local process that watches one or more stores, re-derives
  every ticket whose log changed, verifies it, and pushes a HINT of each
  stage change to the local subscribers that asked for it (ADR 0040). The
  decisions are pure functions in tik.notify; this namespace observes the
  stores, owns the socket, runs the sinks and keeps the cursor.

  Subscribers connect to a Unix socket in a directory only this user can
  enter, and every connection's peer credentials are checked again on
  accept, so nothing reaches a hint from another user or another host.
  There is no network listener. Two subscribers ship with tik:

    tik notify watch    prints each hint as one JSON line, for Claude
                        Code's Monitor tool or any script
    tik notify channel  a stdio MCP server that forwards each hint as a
                        Claude Code channel event

  and configured command sinks run a program per hint without a shell
  (notify-send for a desktop notification, for example).

  Delivery never touches the log (ADR 0019): the cursor that keeps a
  restart from repeating old hints is disposable state under
  $XDG_STATE_HOME, and losing it costs one silent baseline."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [read-edn-file]]
            [tik.audit :as audit]
            [tik.canonical :as canonical]
            [tik.cli-core :as core :refer [die]]
            [tik.notify :as n]
            [tik.query :as query]
            [tik.reduce :as red]
            [tik.select :as select]
            [tik.store.protocol :as store])
  (:import (java.io BufferedReader File InputStreamReader OutputStream)
           (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel SocketChannel)
           (java.nio.file Files LinkOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.time Duration)))

(defn- json-parse [s] ((requiring-resolve 'cheshire.core/parse-string) s))
(defn- json-emit [x] ((requiring-resolve 'cheshire.core/generate-string) x))

(defn- log [& parts]
  (binding [*out* *err*]
    (println (str/join " " (cons "tik notify:" parts)))))

(defn- path-of ^Path [f] (.toPath (io/file (str f))))
(def ^:private no-follow (make-array LinkOption 0))

(def ^:private process-owner
  (delay (let [f (Files/createTempFile "tik-notify-owner" ".tmp" (make-array FileAttribute 0))]
           (try (.getName (Files/getOwner f (make-array LinkOption 0)))
                (finally (Files/deleteIfExists f))))))

(defn- me
  "The operating-system user this process runs as: the owner of a file it
  has just created. The user.name property would do on most days, but any
  code in the process may set it, and the socket's access check must not
  rest on something that cheap to change."
  []
  @process-owner)

;; ------------------------------------------------------------ observing

(defn- file-digest [^File f]
  (if (.isFile f)
    (canonical/sha256-hex-bytes (Files/readAllBytes (.toPath f)))
    "absent"))

(defn- store-salt
  "What every ticket's verification and derivation reads besides its own
  log: the role register and the trust files. A change to one of them
  makes every ticket worth judging again."
  []
  (canonical/sha256-hex
   (str/join "\n" (map #(str % " " (file-digest (io/file (core/root) %)))
                       ["roles.edn" "actors" "signatures.edn" "pq-signers"]))))

(defn fingerprints
  "{ticket-id fp} for the bound store: a digest of every name in a ticket's
  event directory (events, packs and signature sidecars) plus the store
  salt, so a new event, a new signature and a new register all count as
  change. A store without event directories (SQLite) falls back to the
  event ids and sidecar names, read through the store."
  [s]
  (let [salt (store-salt)
        r (core/root)]
    (into {} (for [raw (store/ticket-ids s)
                   :let [id (str raw)
                         d (io/file r "tickets" id "events")
                         names (if (.isDirectory d)
                                 (sort (map #(.getName ^File %) (.listFiles d)))
                                 (concat (sort (map (comp str :event/id) (store/events s id)))
                                         (sort (store/sidecar-names s id))))]]
               [id (canonical/sha256-hex (str salt "\n" (str/join "\n" names)))]))))

(defn verify
  "{:ok? true} when ticket `id` of store `s` may yield a hint, else
  {:ok? false :why …}. The store must have an actors registry, every event
  the cursor had not seen (`seen`) must carry a signature, and the whole
  verify ladder over the ticket (L0 integrity, the DAG, every signature
  present, the signature policy) must pass. A hint therefore never rests on
  an event nobody vouched for."
  [s id events seen]
  (let [seen (set seen)]
    (cond
      (not (.isFile (io/file (core/root) "actors")))
      {:ok? false :why "the store has no actors registry, so nothing can be verified"}

      :else
      (let [signed (core/signed-event-ids s id)
            unsigned (remove #(or (contains? seen (str (:event/id %)))
                                  (contains? signed (:event/id %)))
                             events)]
        (if (seq unsigned)
          {:ok? false :why (str (count unsigned) " new event(s) carry no signature")}
          (let [out (try (with-out-str (audit/verify-ticket {} id))
                         (catch Exception e (str "  FAIL  " (ex-message e))))]
            (if-let [fail (first (filter #(str/includes? % "FAIL") (str/split-lines out)))]
              {:ok? false :why (str "verify: " (str/trim fail))}
              {:ok? true})))))))

(defn observe
  "One ticket's observation for tik.notify/advance, plus its selector row
  under :row for routing. Derivation reads the signed log afresh; nothing
  is carried over from an earlier reading."
  [s id fp seen t]
  (try
    (let [ctx (assoc (core/ticket-ctx s id) :id id :store s)
          row (query/selector-row t ctx)
          events (mapv (fn [e] {:id (str (:event/id e)) :actor (str (:event/actor e))})
                       (red/ordered (:events ctx)))
          {:keys [ok? why]} (verify s id (:events ctx) seen)]
      {:fp fp :events events :current (set (:current row)) :title (:title row)
       :verified? ok? :why why :row row})
    (catch Exception e
      {:fp fp :verified? false :why (str "cannot derive: " (ex-message e))})))

;; ------------------------------------------------------------ cursor

(defn- read-cursor [^File f]
  (if (.isFile f)
    (try (n/edn->cursor (read-edn-file f))
         (catch Exception e
           (log "cursor" (str f) "unreadable, starting from a baseline:" (ex-message e))
           {}))
    {}))

(defn- write-cursor!
  "Write the cursor beside itself and move it into place, so a crash
  leaves the old cursor or the new one, never half of either."
  [^File f cursors]
  (.mkdirs (.getParentFile f))
  (let [tmp (io/file (.getParentFile f) (str (.getName f) ".tmp"))]
    (spit tmp (pr-str (n/cursor->edn cursors)))
    (Files/move (.toPath tmp) (.toPath f)
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                StandardCopyOption/REPLACE_EXISTING]))))

;; ------------------------------------------------------------ sinks

(defprotocol Sink
  (deliver! [sink hint] "Hand one hint over; true when it left, false when the sink is gone."))

(defrecord CommandSink [argv]
  Sink
  (deliver! [_ hint]
    (let [cmd (n/render-argv argv hint)
          p (-> (ProcessBuilder. ^java.util.List cmd)
                (.redirectErrorStream true)
                (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                (.start))]
      (.close (.getOutputStream p))
      (if (.waitFor p 10 java.util.concurrent.TimeUnit/SECONDS)
        (do (when-not (zero? (.exitValue p))
              (log "command sink" (first cmd) "exited" (.exitValue p)))
            true)
        (do (.destroyForcibly p)
            (log "command sink" (first cmd) "timed out after 10 s")
            true)))))

(defn- write-line! [^OutputStream out m]
  (locking out
    (.write out (.getBytes (str (json-emit m) "\n") "UTF-8"))
    (.flush out)))

(defrecord SocketSink [^SocketChannel ch ^OutputStream out]
  Sink
  (deliver! [_ hint]
    (try (write-line! out (assoc hint :op "hint")) true
         (catch java.io.IOException _
           (try (.close ch) (catch Exception _ nil))
           false))))

;; ------------------------------------------------------------ the loop

(defn new-state
  "The notifier's mutable state, in one atom: the cursor of every store,
  the coalescer, the subscription registry, the sinks of the registry's
  subscribers, the rate-limit buckets and the newest selector row of every
  ticket with a pending hint."
  [cursors]
  (atom {:cursors cursors :co {} :registry {} :sinks {} :buckets {} :rows {}
         :compiled {}}))

(defn- compiled [st selector]
  (or (get-in @st [:compiled selector])
      (let [p (select/compile selector)]
        (swap! st assoc-in [:compiled selector] p)
        p)))

(defn poll-store!
  "Observe the bound store once and fold what changed into the state.
  Returns true when the cursor moved."
  [st store-name t]
  (let [s (core/the-store)
        cursor (get-in @st [:cursors store-name])
        baseline? (nil? cursor)
        fps (fingerprints s)
        changed (n/changed-tickets cursor fps)]
    (when (or baseline? (seq changed))
      (core/with-registry-events s
        (fn []
          (let [obs (into {} (for [id changed]
                               [id (observe s id (get fps id) (get-in cursor [id :events]) t)]))
                {:keys [cursor diffs refused]} (n/advance cursor obs baseline?)
                now-ms (.toEpochMilli ^java.time.Instant t)]
            (doseq [{:keys [ticket why]} refused]
              (log store-name ticket "sends nothing:" why))
            (swap! st (fn [m]
                        (as-> m m
                          (assoc-in m [:cursors store-name] cursor)
                          (reduce (fn [m {:keys [ticket] :as d}]
                                    (-> m
                                        (update :co n/enqueue (n/hint store-name d) now-ms)
                                        (assoc-in [:rows [store-name ticket]]
                                                  (get-in obs [ticket :row]))))
                                  m diffs))))
            true))))))

(defn route!
  "Drain the coalescer at `now-ms` and hand every due hint to the
  subscribers whose selector its ticket satisfies, within their rate."
  [st {:keys [window-ms rate verbose?]} now-ms]
  (let [[co due] (n/drain (:co @st) now-ms window-ms)
        [registry expired] (n/expire (:registry @st) now-ms)]
    (swap! st assoc :co co :registry registry)
    (doseq [id expired]
      (when-let [sink (get-in @st [:sinks id])]
        (when (instance? SocketSink sink)
          (try (write-line! (:out sink) {:op "expired"}) (catch Exception _ nil))
          (try (.close ^SocketChannel (:ch sink)) (catch Exception _ nil))))
      (swap! st update :sinks dissoc id))
    (doseq [h due
            :let [row (get-in @st [:rows [(:store h) (:ticket h)]])
                  selectors (distinct (map :selector (vals (:subs (:registry @st)))))
                  matching (set (filter (fn [sel]
                                          (try (boolean (and row ((compiled st sel) row)))
                                               (catch Exception _ false)))
                                        selectors))]
            id (n/recipients (:registry @st) h matching now-ms)]
      (let [[buckets ok?] (n/take-token (:buckets @st) id now-ms rate)]
        (swap! st assoc :buckets buckets)
        (if-not ok?
          (log "rate limit: dropped a hint for" (:ticket h) "to subscriber" id)
          (when-let [sink (get-in @st [:sinks id])]
            (let [sent? (try (deliver! sink h)
                             (catch Exception e
                               (log "sink" id "failed:" (ex-message e))
                               true))]
              (when verbose? (log "sent" (n/line h) "to" id))
              (when-not sent?
                (swap! st (fn [m] (-> m
                                      (update :registry n/unsubscribe id)
                                      (update :sinks dissoc id))))))))))
    (swap! st update :rows #(select-keys % (keys (:pending (:co @st)))))))

(defn tick!
  "One round: poll every store, persist the cursor when it moved, route."
  [st {:keys [stores cursor-file] :as cfg} ^java.time.Instant t]
  (let [moved (doall (for [[store-name root] (sort-by key stores)]
                       (try (binding [core/*root* (str root)]
                              (poll-store! st store-name t))
                            (catch Exception e
                              (log store-name "poll failed:" (ex-message e))
                              false))))]
    (when (and cursor-file (some true? moved))
      (write-cursor! (io/file cursor-file) (:cursors @st)))
    (route! st cfg (.toEpochMilli t))))

;; ------------------------------------------------------------ the socket

(defn- perms-string [^Path p]
  (PosixFilePermissions/toString (Files/getPosixFilePermissions p no-follow)))

(defn- owner-name [^Path p] (.getName (Files/getOwner p no-follow)))

(defn ensure-socket-dir!
  "Make the socket's directory rwx------ when it is missing, and refuse one
  that exists but is not ours or is open to anybody else."
  [^File dir]
  (let [p (.toPath dir)]
    (when-not (Files/exists p no-follow)
      (Files/createDirectories p (into-array FileAttribute
                                             [(PosixFilePermissions/asFileAttribute
                                               (PosixFilePermissions/fromString "rwx------"))])))
    (when-let [why (n/socket-dir-problem {:exists? true
                                          :owner (owner-name p)
                                          :me (me)
                                          :perms (perms-string p)})]
      (die (str "refusing socket directory " dir ": " why)))))

(defn- connect ^SocketChannel [path]
  (SocketChannel/open (UnixDomainSocketAddress/of ^String (str path))))

(defn open-socket!
  "Bind the notifier's socket at `path`, rw------- in a private directory.
  A socket file nobody answers on is a leftover and is replaced; one that
  answers belongs to a running notifier, which this one will not displace."
  ^ServerSocketChannel [path]
  (let [f (io/file (str path))]
    ;; sun_path holds 108 bytes on Linux and 104 on macOS
    (when (< 100 (count (.getBytes (str (.getAbsolutePath f)) "UTF-8")))
      (die (str "socket path " path " is longer than 100 bytes; pick a shorter --socket")))
    (ensure-socket-dir! (.getParentFile (.getAbsoluteFile f)))
    (when (Files/exists (.toPath f) no-follow)
      (if (try (.close (connect path)) true (catch Exception _ false))
        (die (str "another notifier already answers on " path))
        (Files/delete (.toPath f))))
    (let [ss (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind ss (UnixDomainSocketAddress/of ^String (str path)))
      (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rw-------"))
      ss)))

(defn peer-user
  "The user name of the process at the other end of `ch`, from the
  kernel's peer credentials, or nil when the platform cannot say: a
  connection whose peer is unknown is refused. The name is read from the
  principal's record rendering, `UnixDomainPrincipal[user=…, group=…]`,
  because babashka does not allow the record's accessor; a rendering that
  does not have that shape names nobody, so the check fails closed."
  [^SocketChannel ch]
  (try (let [principal (.getOption ch jdk.net.ExtendedSocketOptions/SO_PEERCRED)]
         (second (re-matches #"UnixDomainPrincipal\[user=([^,\]]+), group=[^\]]*\]"
                             (str principal))))
       (catch Throwable _ nil)))

(defn readable-stores
  "The configured store names `user` may read: those whose root directory
  `user` owns. A subscriber names one; any other is refused."
  [stores user]
  (set (for [[store-name root] stores
             :let [p (path-of root)]
             :when (and (Files/isDirectory p no-follow)
                        (= user (owner-name p)))]
         store-name)))

(defn- read-line-capped
  "One line from `r`, at most `cap` characters, or nil at end of stream."
  [^BufferedReader r cap]
  (let [sb (StringBuilder.)]
    (loop []
      (let [c (.read r)]
        (cond
          (neg? c) (when (pos? (.length sb)) (str sb))
          (= c (int \newline)) (str sb)
          (>= (.length sb) cap) (throw (ex-info "request line too long" {}))
          :else (do (.append sb (char c)) (recur)))))))

(defn serve-connection!
  "Admit one connection: check its peer, read its subscription, register
  it, and hold it until it closes or expires."
  [st {:keys [stores]} ^SocketChannel ch]
  (let [out (Channels/newOutputStream ch)
        user (peer-user ch)
        id (str (random-uuid))
        refuse! (fn [why]
                  (try (write-line! out {:op "refused" :reason why}) (catch Exception _ nil))
                  (.close ch))]
    (if (not= user (me))
      (do (log "refused a connection from" (or user "an unknown peer")) (refuse! "not this user"))
      (let [r (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))
            watchdog (future (Thread/sleep 30000)
                             (when-not (get-in @st [:sinks id]) (.close ch)))]
        (try
          (let [line (read-line-capped r n/max-request-bytes)
                parsed (try (json-parse (str line)) (catch Exception _ nil))
                {:keys [request refused]} (n/parse-subscribe parsed select/compile)]
            (if refused
              (refuse! refused)
              (let [now (System/currentTimeMillis)
                    [registry why] (n/subscribe (:registry @st) id
                                                (assoc request :principal user)
                                                (readable-stores stores user) now)]
                (if why
                  (refuse! why)
                  (do (swap! st (fn [m] (-> m (assoc :registry registry)
                                            (assoc-in [:sinks id] (->SocketSink ch out)))))
                      (write-line! out {:op "subscribed" :store (:store request)
                                        :expires_ms (get-in registry [:subs id :expires-ms])})
                      ;; the subscriber says nothing more; its end of stream ends it
                      (while (some? (read-line-capped r n/max-request-bytes)))
                      (swap! st (fn [m] (-> m (update :registry n/unsubscribe id)
                                            (update :sinks dissoc id)))))))))
          (catch Exception _
            (swap! st (fn [m] (-> m (update :registry n/unsubscribe id)
                                  (update :sinks dissoc id))))
            (try (.close ch) (catch Exception _ nil)))
          (finally (future-cancel watchdog)))))))

(defn accept-loop!
  "Accept connections on `ss` until it closes, each on its own thread."
  [st cfg ^ServerSocketChannel ss]
  (doto (Thread. ^Runnable
         (fn []
           (while (.isOpen ss)
             (try (let [ch (.accept ss)]
                    (doto (Thread. ^Runnable #(serve-connection! st cfg ch) "tik-notify-conn")
                      (.setDaemon true) (.start)))
                  (catch Exception e
                    (when (.isOpen ss) (log "accept failed:" (ex-message e)))))))
         "tik-notify-accept")
    (.setDaemon true)
    (.start)))

;; ------------------------------------------------------------ config

(defn- env [k] (not-empty (System/getenv k)))

(defn default-socket []
  (str (io/file (or (env "XDG_RUNTIME_DIR")
                    (str (System/getProperty "java.io.tmpdir") "/tik-" (me)))
                "tik" "notify.sock")))

(defn- default-state-dir []
  (str (io/file (or (env "XDG_STATE_HOME")
                    (str (System/getProperty "user.home") "/.local/state"))
                "tik" "notify")))

(defn- millis [x default]
  (cond (nil? x) default
        (string? x) (try (.toMillis (Duration/parse x))
                         (catch Exception _ (die (str "not an ISO-8601 duration: " x))))
        :else (die (str "not an ISO-8601 duration: " (pr-str x)))))

(defn- store-name-of [root]
  (let [f (.getAbsoluteFile (io/file (str root)))]
    (if (= ".tik" (.getName f)) (.getName (.getParentFile f)) (.getName f))))

(declare config-from)

(defn config
  "The notifier's settings from notify.edn (`--config`) and the flags.
  Without a config it serves the one store tik would act on here."
  [opts]
  (let [file (:config opts)]
    (config-from (if file (read-edn-file (io/file (str file))) {}) opts)))

(defn config-from
  "The notifier's settings from a parsed notify.edn `c` and the flags. A
  multi-store backend passes its own stores as `c`'s :stores."
  [c opts]
  (let [stores (or (:stores c) {(or (:store opts) (store-name-of (core/root))) (str (core/root))})]
    (when-not (and (map? stores) (seq stores) (every? string? (keys stores)))
      (die "notify.edn :stores must map store names to store roots"))
    {:stores (into {} (for [[k v] stores]
                        [k (str/replace-first (str v) #"^~(?=/|$)" (System/getProperty "user.home"))]))
     :socket (or (:socket opts) (:socket c) (default-socket))
     :cursor-file (str (io/file (or (:state-dir c) (default-state-dir)) "cursor.edn"))
     :poll-ms (millis (:poll c) 2000)
     :window-ms (millis (:window c) 5000)
     :rate {:per-ms (millis (get-in c [:rate :per]) 6000)
            :burst (or (get-in c [:rate :burst]) 10)}
     :sinks (vec (:sinks c))
     :verbose? (boolean (:verbose opts))}))

(defn add-configured-sinks!
  "Register every configured command sink as a permanent subscriber."
  [st {:keys [stores sinks]}]
  (doseq [[i {:keys [store where command]}] (map-indexed vector sinks)
          :let [id (str "sink-" i)]]
    (when-not (and (vector? command) (seq command) (every? string? command))
      (die (str "sink " i ": :command must be a vector of strings")))
    (try (select/compile (or where ""))
         (catch clojure.lang.ExceptionInfo e (die (str "sink " i ": " (ex-message e)))))
    (let [[registry why] (n/subscribe (:registry @st) id
                                      {:store store :selector (or where "") :permanent? true}
                                      (set (keys stores)) 0)]
      (when why (die (str "sink " i ": " why)))
      (swap! st (fn [m] (-> m (assoc :registry registry)
                            (assoc-in [:sinks id] (->CommandSink command))))))))

;; ------------------------------------------------------------ subscribers

(defn- subscribe-over-socket!
  "Connect to the notifier at `path`, subscribe, and return
  [reader channel] once it acknowledged; dies on a refusal."
  [path store where ttl]
  (let [^SocketChannel ch (try (connect path)
                (catch Exception e (die (str "no notifier answers on " path ": " (ex-message e)))))
        out (Channels/newOutputStream ch)
        r (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))]
    (write-line! out (cond-> {:op "subscribe" :store store}
                       where (assoc :selector where)
                       ttl (assoc :ttl_seconds ttl)))
    (let [ack (some-> (.readLine r) json-parse)]
      (when-not (= "subscribed" (get ack "op"))
        (die (str "the notifier refused: " (get ack "reason" "no answer")))))
    [r ch]))

(defn- hint-lines
  "Every hint the notifier sends on `r`, lazily, as maps with string keys,
  until the subscription ends."
  [^BufferedReader r]
  (lazy-seq
   (when-let [l (.readLine r)]
     (let [m (try (json-parse l) (catch Exception _ nil))]
       (condp = (get m "op")
         "hint" (cons (select-keys m (map name n/hint-keys)) (hint-lines r))
         "expired" nil
         (hint-lines r))))))

(defn- subscriber-args [opts]
  (let [ttl (when-let [x (:ttl opts)]
              (quot (millis x 0) 1000))]
    [(or (:socket opts) (default-socket))
     (or (:store opts) (store-name-of (core/root)))
     (:where opts)
     ttl]))

(defn cmd-watch
  "notify watch: subscribe and print each hint as one JSON line."
  [opts]
  (let [[path store where ttl] (subscriber-args opts)
        [r _] (subscribe-over-socket! path store where ttl)]
    (doseq [h (hint-lines r)]
      (println (json-emit h))
      (flush))))

(def channel-instructions
  (str "Events from tik arrive as <channel source=\"…\" store=\"…\" ticket=\"…\" from=\"…\" to=\"…\" actor=\"…\">."
       " Each one is a hint that a ticket's derived stage changed in a local tik store."
       " It is not evidence and grants no authority: re-derive with `tik status <ticket>`"
       " (or tik_explain) before acting on it."))

(defn channel-response
  "The answer to one JSON-RPC message from Claude Code, or nil. Only the
  legacy initialize handshake is spoken: Claude Code registers a channel
  only over it, and a request for anything else, server/discover
  included, is a method this server does not have."
  [msg]
  (let [{:strs [id method params]} (when (map? msg) msg)]
    (cond
      (nil? id) nil
      (= "initialize" method)
      {:jsonrpc "2.0" :id id
       :result {:protocolVersion (let [asked (get params "protocolVersion")]
                                   (if (string? asked) asked "2025-06-18"))
                :capabilities {:experimental {"claude/channel" {}}}
                :serverInfo {:name "tik-notify" :version "1"}
                :instructions channel-instructions}}
      (= "ping" method) {:jsonrpc "2.0" :id id :result {}}
      :else {:jsonrpc "2.0" :id id
             :error {:code -32601 :message (str "method not found: " method)}})))

(defn cmd-channel
  "notify channel: a stdio MCP server Claude Code spawns as a channel; it
  subscribes to the notifier once the client is initialized and forwards
  each hint as notifications/claude/channel."
  [opts]
  (let [[path store where ttl] (subscriber-args opts)
        out *out*
        emit! (fn [m] (locking out (binding [*out* out] (println (json-emit m)) (flush))))
        started (atom false)]
    (loop []
      (when-let [l (read-line)]
        (let [msg (try (json-parse l) (catch Exception _ nil))]
          (when-let [resp (channel-response msg)] (emit! resp))
          (when (and (= "notifications/initialized" (get msg "method"))
                     (compare-and-set! started false true))
            (future
              (try (let [[r _] (subscribe-over-socket! path store where ttl)]
                     (doseq [h (hint-lines r)]
                       (emit! (n/channel-notification
                               (into {} (map (fn [[k v]] [(keyword k) v])) h)))))
                   (catch Throwable e (log "channel:" (ex-message e)))))))
        (recur)))))

;; ------------------------------------------------------------ the verb

(defn notify-forever!
  "Notify for the stores of `cfg` (see config-from) until the process ends."
  [cfg]
  (let [st (new-state (read-cursor (io/file (:cursor-file cfg))))
        _ (add-configured-sinks! st cfg)
        ss (open-socket! (:socket cfg))]
    (accept-loop! st cfg ss)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable #(do (.close ss)
                                              (Files/deleteIfExists (path-of (:socket cfg))))))
    (println (str "tik notifies on " (:socket cfg) " for "
                  (str/join ", " (sort (keys (:stores cfg)))) "; ctrl-c stops"))
    (flush)
    (loop []
      (tick! st cfg (java.time.Instant/now))
      (Thread/sleep (long (:poll-ms cfg)))
      (recur))))

(defn cmd-run [opts] (notify-forever! (config opts)))

(defn cmd-notify
  "notify [run] | notify watch | notify channel"
  [{:keys [pos opts]}]
  (case (first pos)
    (nil "run") (cmd-run opts)
    "watch" (cmd-watch opts)
    "channel" (cmd-channel opts)
    (die "usage: tik notify [run|watch|channel] [--config notify.edn] [--socket P] [--store NAME] [--where SELECTOR] [--ttl PT1H]")))
