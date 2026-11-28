;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.forge
  "Forge-neutral CI failure tracking (ADR 0019 inbound): failed runs
  become ci-failure tickets, one per failure STREAK, the consecutive
  failed runs of one workflow on one branch, from the first failure after
  a green run up to the next green run. tik.github and tik.gitlab turn
  their forge's runs into the run shape below; everything else is shared.

  Every event is a pure function of the forge's run history (ADR 0021).
  The ticket id hashes (host, repository id, workflow, branch, first
  failed run id); each event's :at is a timestamp the forge never
  rewrites; every event after the create names the create as its only
  parent. Two replicas polling the same repository therefore mint
  byte-identical events, and the union keeps one. The streak's first run
  is a property of the history, never of what this replica has already
  stored, so a streak whose start lies beyond the fetched history is
  skipped rather than guessed.

  A run is
    {:id :number :attempt :conclusion :workflow-id :workflow :branch
     :head-sha :url :created-at :started-at :repo-id :repo :event :actor
     :title}
  with :created-at shared by every attempt of the run and :started-at
  fixed per attempt (ISO-8601 strings).

  Also here: the JSON-over-HTTP getter every forge adapter injects, and
  the SSH port forward for an API that only listens on its own host."
  (:require [briko.http :as http]
            [briko.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.cli-core :as cli-core]
            [tik.event :as event])
  (:import (java.net InetSocketAddress ServerSocket Socket URI URLEncoder)
           (java.time Instant)
           (java.util.concurrent TimeUnit)))

;; ---------------------------------------------------------------- streaks

(defn classify
  "A completed run is :fail, :green, or nil. Cancelled, skipped and neutral
  runs say nothing about the workflow's health, so they neither extend nor
  end a streak. `cfg` holds the :failing and :green conclusion sets."
  [{:keys [failing green]} {:keys [conclusion]}]
  (condp contains? conclusion
    failing :fail
    green :green
    nil))

(defn run-order [r] [(long (or (:number r) 0)) (long (or (:id r) 0))])

(defn streaks
  "Partition one workflow's completed runs on one branch into streaks.
  `complete?` says the runs reach back to the workflow's first run on
  that branch, so a failure with nothing before it starts a streak.

  Returns maps of
    {:failed [runs…] :green run-or-nil :known-start? bool}   a streak
    {:rerun run}                                              a green retry

  A retry that turned green replaces the failed run in the forge's
  listing, so a streak may have been opened on that run id; the caller
  closes such a ticket if it exists. A retry shows as :attempt > 1, or,
  with :retry-in-place in `cfg` (a forge that keeps neither an attempt
  count nor the failed attempt), any green run may be one."
  [cfg runs complete?]
  (let [ordered (sort-by run-order runs)
        step (fn [{:keys [failed known] :as acc} r]
               (case (classify cfg r)
                 :fail (update acc :failed conj r)
                 :green (cond-> (assoc acc :failed [] :known true)
                          (seq failed)
                          (update :out conj {:failed failed :green r :known-start? known})
                          (or (:retry-in-place cfg) (> (long (or (:attempt r) 1)) 1))
                          (update :out conj {:rerun r}))
                 acc))
        {:keys [out failed known]} (reduce step {:out [] :failed [] :known complete?}
                                           ordered)]
    (cond-> out
      (seq failed) (conj {:failed failed :green nil :known-start? known}))))

(defn name-uuid
  "A UUIDv8 from SHA-256 over `s`, the shape mail tickets use (ADR 0006's
  one-algorithm policy)."
  [s]
  (let [hex (canonical/sha256-hex s)
        msb (Long/parseUnsignedLong (subs hex 0 16) 16)
        lsb (Long/parseUnsignedLong (subs hex 16 32) 16)]
    (java.util.UUID. (bit-or (bit-and msb (bit-not 0xF000)) 0x8000)
                     (bit-or (bit-and lsb 0x3FFFFFFFFFFFFFFF) Long/MIN_VALUE))))

(defn ticket-id
  "The streak's ticket id: a pure function of where it happened and the
  run that started it. The repository enters by the forge's numeric id,
  so a rename does not fork a streak, and the `tik/ci` prefix keeps the
  id space apart from issue tickets."
  [host repo-id workflow-id branch start-run-id]
  (name-uuid (str "tik/ci " host " " repo-id " " workflow-id " " branch " " start-run-id)))

(defn created-at ^Instant [run] (Instant/parse (:created-at run)))

(defn run-at
  "When this attempt of the run started."
  ^Instant [run]
  (Instant/parse (or (:started-at run) (:created-at run))))

(defn run-blob
  "The artifact text recorded for one failed run. Only fields fixed once a
  run attempt has completed go in, so every replica writes the same bytes."
  [run]
  (str/join "\n"
            [(str "run " (:id run) " attempt " (or (:attempt run) 1) " #" (:number run))
             (str "url: " (:url run))
             (str "conclusion: " (:conclusion run))
             (str "event: " (:event run))
             (str "head: " (:head-sha run))
             (str "title: " (:title run))
             (str "actor: " (:actor run))
             (str "started: " (or (:started-at run) (:created-at run)))
             ""]))

(defn blob-hash [^String text]
  (canonical/address-of-bytes (.getBytes text "UTF-8")))

(defn run-artifact-path [run]
  (str "ci/run/" (:id run) "/" (or (:attempt run) 1)))

(defn green-event
  "The bridge's word that the workflow passed again after the streak."
  [{:keys [actor clamp]} ticket create-id run]
  (event/assert-fact {:ticket ticket :actor actor :at (clamp (run-at run))
                      :parents #{create-id}
                      :path [:forge :green-run] :value (:url run)}))

(defn streak-ticket-id [{:keys [host branch workflow-id]} run]
  (ticket-id host (:repo-id run) workflow-id branch (:id run)))

(defn streak-events
  "The events and blobs one streak contributes. `ctx` carries the forge
  host, the repository coordinates (with :repo-key, the store-wide [:repo]
  value shared with tickets made inside a checkout), the bridge actor, the
  pinned process, and a `clamp` function applied to every :at
  (cli-core/claimed-at in production)."
  [{:keys [host repo repo-key branch actor process version process-hash clamp]
    :as ctx}
   {:keys [failed green]}]
  (let [start (first failed)
        id (streak-ticket-id ctx start)
        at0 (clamp (created-at start))
        create (event/create-ticket
                {:ticket id :actor actor :at at0
                 :title (str repo ": " (:workflow start) " failing on " branch)
                 :process process :version version :process-hash process-hash})
        cid (:event/id create)
        fact (fn [path value]
               (event/assert-fact {:ticket id :actor actor :at at0 :parents #{cid}
                                   :path path :value value}))
        blobs (into {} (map (fn [r] (let [t (run-blob r)] [(blob-hash t) t]))) failed)
        attach (fn [r]
                 (event/attach-artifact
                  {:ticket id :actor actor :at (clamp (run-at r)) :parents #{cid}
                   :path (run-artifact-path r) :hash (blob-hash (run-blob r))}))]
    {:id id
     :create-id cid
     :blobs blobs
     :events (cond-> [create
                      (fact [:forge :host] host)
                      (fact [:forge :repo] repo)
                      (fact [:forge :owner] (str/join "/" (butlast (str/split repo #"/"))))
                      (fact [:forge :workflow] (:workflow start))
                      (fact [:forge :branch] branch)
                      (fact [:forge :first-run] (:url start))
                      (fact [:forge :head-sha] (:head-sha start))]
               repo-key (conj (fact [:repo] repo-key))
               true (into (map attach) failed)
               green (conj (green-event ctx id cid green)))}))

(defn in-lookback?
  "Is any run of this streak newer than `since`? Older streaks are history
  the bridge does not open tickets for."
  [^Instant since {:keys [failed green rerun]}]
  (boolean (some #(not (.isBefore (run-at %) since))
                 (remove nil? (concat failed [green rerun])))))

;; ---------------------------------------------------------------- http

(def ^:private limits
  "Bounds of every forge request and of the JSON it answers. A page of 100
  workflow runs from the GitHub API measured 1.34 to 1.56 MB on
  2026-11-28 (about 15 KB a run), the organisation's repository list
  343 KB; 16 MiB holds ten such pages. The connect timeout and the
  60-second deadline are the ones the bridges always used, the deadline
  now covering the body as well."
  {:connect-timeout-ms 20000
   :timeout-ms 60000
   :max-bytes (* 16 1024 1024)})

(defn require-tls!
  "The URI of `url`, refusing plain HTTP anywhere but loopback (where the
  hop is local, or the inside of an SSH forward)."
  [url]
  (let [u (URI/create (str url))
        host (some-> (.getHost u) str/lower-case)]
    (when-not (or (= "https" (some-> (.getScheme u) str/lower-case))
                  (contains? #{"localhost" "127.0.0.1" "::1"} host))
      (throw (ex-info (str "refusing a non-HTTPS API URL: " url)
                      {:reason :forge/insecure-url :url (str url)})))
    u))

(defn- query-string [q]
  (str/join "&" (for [[k v] q :when (some? v)]
                  (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))))

(def ^:dynamic *retry-pauses*
  "Milliseconds to wait before each repeat of a request the server failed
  transiently; its length is how many repeats a request gets."
  [2000 10000])

(def ^:private transient-status #{500 502 503 504})

(defn- address [^URI u]
  (str (.getScheme u) "://" (.getHost u) (when (pos? (.getPort u)) (str ":" (.getPort u)))))

(defn- send!
  "The response {:status :headers :body} to one request. A server that
  cannot be reached - refused, timed out, failing the TLS handshake, or
  answering past the deadline - is an operational fault of that server or
  of this host's trust store, so it becomes an ex-info naming the API, the
  address and the cause instead of escaping as an exception without a
  message. A body over the limit is :forge/too-large."
  [label method url opts]
  (let [u (require-tls! url)]
    (try
      (http/request method (str u) (merge limits {:as :text} opts))
      (catch clojure.lang.ExceptionInfo e
        (if-not (http/refusal? e)
          (throw e)
          (let [{:keys [type limit]} (ex-data e)
                cause (or (ex-cause e) e)]
            (cond
              (= ::http/too-large type)
              (throw (ex-info (str label " answered with more than " limit " bytes at " (address u))
                              {:reason :forge/too-large :url (str u) :limit limit} e))

              (#{::http/connect-failed ::http/connect-timeout ::http/tls-failed ::http/timeout
                 ::http/failed ::http/interrupted} type)
              (throw (ex-info (str label " unreachable at " (address u)
                                   ": " (.getSimpleName (class cause))
                                   (when-let [m (not-empty (ex-message cause))] (str ": " m)))
                              {:reason :forge/unreachable :url (str u)} cause))

              :else (throw e))))))))

(defn- send-with-retry
  "The response to a request, repeating it after each of `*retry-pauses*`
  while the server answers with a gateway or internal error. Forges return
  these for a few requests a week and answer the same request a moment
  later; every request here reads (a GET, or an RPC call to a read-only
  method), so a repeat cannot double an effect. `label` names the API in
  errors."
  [label method url opts]
  (loop [pauses (seq *retry-pauses*)]
    (let [resp (send! label method url opts)]
      (if (and pauses (transient-status (:status resp)))
        (do (Thread/sleep (long (first pauses)))
            (recur (next pauses)))
        resp))))

(defn- parse-json
  "The parsed JSON of a forge's answer, keys as keywords, within the
  forge limits; nil for an empty body, ::invalid when it is not one JSON
  value."
  [body]
  (when-not (str/blank? body)
    (try (json/parse-string body {:keys :keyword :max-bytes (:max-bytes limits)})
         (catch Exception _ ::invalid))))

(defn json-getter
  "A `get-json` ((path query-map) -> parsed JSON) over the API at `base`,
  sending `headers` with every request. `label` names the API in errors,
  which carry {:reason :forge/http :status n}, {:reason :forge/json},
  {:reason :forge/too-large} or {:reason :forge/unreachable}.
  A transient server error is retried before it becomes one."
  [label base headers]
  (let [base (str/replace (str base) #"/+$" "")
        headers (into {} (filter (comp seq val)) (assoc headers "User-Agent" "tik-bridge"))]
    (fn [path q]
      (let [url (str base path (when (seq q) (str "?" (query-string q))))
            {:keys [status body]} (send-with-retry label :get url {:headers headers})
            body (str body)]
        (when (>= status 400)
          (throw (ex-info (str label " " status " for " path ": "
                               (subs body 0 (min 200 (count body))))
                          {:reason :forge/http :status status :path path})))
        (let [r (parse-json body)]
          (if (= ::invalid r)
            (throw (ex-info (str label " returned non-JSON for " path)
                            {:reason :forge/json :path path}))
            r))))))

;; HTTP 200 carries both results and errors in JSON-RPC, so `json-rpc-caller`
;; reads the error member itself.
(defn json-rpc-caller
  "A `call` ((service method & args) -> result) over the JSON-RPC 2.0
  endpoint at `url`, Odoo's `/jsonrpc` shape: params {:service :method
  :args}. `label` names the API in errors, which carry {:reason
  :forge/http :status n}, {:reason :forge/json}, {:reason :forge/rpc},
  {:reason :forge/too-large} or {:reason :forge/unreachable}.
  Only read-only methods may go through it, because a transient server
  error is retried."
  [label url]
  (let [url (str (require-tls! url))]
    (fn [service method & args]
      (let [{:keys [status body]}
            (send-with-retry label :post url
                             {:headers {"Content-Type" "application/json"
                                        "User-Agent" "tik-bridge"}
                              :body (json/write-string
                                     {:jsonrpc "2.0" :method "call" :id 1
                                      :params {:service service :method method :args (vec args)}})})
            body (str body)
            _ (when (>= status 400)
                (throw (ex-info (str label " " status " for " method ": "
                                     (subs body 0 (min 200 (count body))))
                                {:reason :forge/http :status status :method method})))
            r (parse-json body)
            _ (when (= ::invalid r)
                (throw (ex-info (str label " returned non-JSON for " method)
                                {:reason :forge/json :method method})))]
        (if-let [e (:error r)]
          (let [msg (str (or (get-in e [:data :message]) (:message e)))]
            (throw (ex-info (str label " refused " method ": " (subs msg 0 (min 300 (count msg))))
                            {:reason :forge/rpc :method method})))
          (:result r))))))

(def per-page 100)

(defn paged
  "Every item across pages of a page/per_page list endpoint; `items`
  extracts the page's list from the parsed body."
  [get-json path q items max-pages]
  (loop [page 1 acc []]
    (let [xs (items (get-json path (assoc q :per_page per-page :page page)))
          acc (into acc xs)]
      (if (and (= per-page (count xs)) (< page max-pages))
        (recur (inc page) acc)
        acc))))

;; ---------------------------------------------------------------- tunnel

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- listening? [port]
  (try
    (with-open [socket (Socket.)]
      (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 200)
      true)
    (catch Exception _ false)))

(defn ssh-command
  "The ssh call forwarding local `port` to the tunnel's :target, as seen
  from its :ssh destination."
  [{:keys [ssh target ssh-options]} port]
  (vec (concat ["ssh" "-N" "-o" "ExitOnForwardFailure=yes" "-o" "BatchMode=yes"]
               ssh-options
               ["-L" (str "127.0.0.1:" port ":" target) ssh])))

(defn open-tunnel
  "Start the forward and wait until its port accepts connections while ssh
  still runs `settle-ms` later. Another process may bind the free port
  before ssh does; ssh then exits on the failed forward right after
  authenticating, and that port must not count as the tunnel. Returns
  {:process :port}."
  [tunnel & {:keys [timeout-ms settle-ms] :or {timeout-ms 15000 settle-ms 1000}}]
  (when-not (and (seq (:ssh tunnel)) (seq (:target tunnel)))
    (throw (ex-info "a :tunnel needs :ssh (destination) and :target (host:port)"
                    {:reason :forge/tunnel})))
  (let [port (free-port)
        proc (-> (ProcessBuilder. ^java.util.List (ssh-command tunnel port))
                 (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                 (.start))
        deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop [ready-since nil]
      (let [now (System/currentTimeMillis)]
        (cond
          (not (.isAlive proc))
          (throw (ex-info (str "ssh " (:ssh tunnel) " ended before forwarding " (:target tunnel)
                               ": " (str/trim (slurp (.getErrorStream proc))))
                          {:reason :forge/tunnel}))

          (and ready-since (>= (- now (long ready-since)) (long settle-ms)))
          {:process proc :port port}

          (> now deadline)
          (do (.destroy proc)
              (throw (ex-info (str "ssh " (:ssh tunnel) " did not forward " (:target tunnel)
                                   " within " (quot (long timeout-ms) 1000) "s")
                              {:reason :forge/tunnel})))

          :else
          (do (Thread/sleep 100)
              (recur (or ready-since (when (listening? port) now)))))))))

(defn close-tunnel [{:keys [^Process process]}]
  (.destroy process)
  (when-not (.waitFor process 5 TimeUnit/SECONDS)
    (.destroyForcibly process)))

(defn tunneled-url
  "`url` with its scheme, host and port replaced by the local end of the
  forward. The forwarded target speaks plain HTTP; the hop that leaves
  this machine is the SSH connection."
  [url port]
  (let [u (URI/create (str url))]
    (str "http://127.0.0.1:" port (.getRawPath u))))

(defn with-tunnel
  "Call `f` with the API base to use: `url` itself without a tunnel, else
  the local end of a forward held open for the call."
  [tunnel url f]
  (if tunnel
    (let [t (open-tunnel tunnel)]
      (try (f (tunneled-url url (:port t)))
           (finally (close-tunnel t))))
    (f url)))

;; ---------------------------------------------------------------- store side

(defn write-blob!
  "Store a blob by hash under the ticket, the layout `tik attach` uses."
  [root id hash text]
  (let [dest (io/file (cli-core/blob-dir root id) hash)]
    (when-not (.exists dest)
      (io/make-parents dest)
      (spit dest text))))
