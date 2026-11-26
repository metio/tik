;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.notify
  "The pure half of `tik notify`: what a notifier decides, with no clock,
  store, socket or process in sight. tik.notifier does the I/O and calls
  these functions with what it observed.

  A notification is a HINT to look, never evidence (ADR 0040). It names a
  ticket, its title, the stages it left and entered, and the actor of the
  event that moved it, and nothing else: no fact values, no instructions.
  The receiver re-derives with `tik status` before acting.

  The pieces compose in one direction:

    cursor + observations --advance--> cursor' + diffs + refusals
    diffs --enqueue--> coalescer --drain--> due hints (one per ticket per window)
    due hint x registry --recipients--> subscriber ids
    subscriber id x buckets --take-token--> delivered or rate-limited

  Every function here takes its time as epoch milliseconds and returns new
  values; none of them reads a clock."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------ hints

(def hint-keys
  "The only keys a hint carries. A payload with any other key is a bug."
  #{:store :ticket :title :from :to :actor})

(def ^:private max-title 160)
(def ^:private max-actor 64)

(defn clean-text
  "`s` as one line of plain text at most `limit` characters long: control,
  format and bidirectional-override characters are dropped and whitespace
  runs fold to one space, so a title cannot forge a second line of a
  JSON-lines stream, reorder what a terminal shows, or grow without bound.
  Anything that is not a string becomes the empty string."
  [s limit]
  (if-not (string? s)
    ""
    (let [kept (str/join (keep (fn [ch]
                                 (let [c (int ch)
                                       t (Character/getType (char ch))]
                                   (cond
                                     (Character/isWhitespace (char ch)) " "
                                     (or (= t Character/CONTROL)
                                         (= t Character/FORMAT)
                                         (= t Character/SURROGATE)
                                         (= t Character/PRIVATE_USE)
                                         (= t Character/UNASSIGNED)
                                         (<= 0x202A c 0x202E)
                                         (<= 0x2066 c 0x2069)) nil
                                     :else (str ch))))
                               s))
          folded (str/trim (str/replace kept #" +" " "))]
      (if (> (count folded) limit)
        (str (subs folded 0 (dec limit)) "…")
        folded))))

(defn stage-names
  "A set of stage keywords as a sorted vector of their names."
  [stages]
  (vec (sort (map name stages))))

(defn hint
  "The hint for one stage diff of `store`: exactly hint-keys, every value a
  string or a vector of strings."
  [store {:keys [ticket title from to actor]}]
  {:store (str store)
   :ticket (str ticket)
   :title (clean-text title max-title)
   :from (stage-names from)
   :to (stage-names to)
   :actor (clean-text (or actor "") max-actor)})

;; ------------------------------------------------------------ diffs

(defn stage-diffs
  "The tickets whose current stages differ between view `old` and view
  `new`, each a map {ticket-id {:current #{stage} :title s}}, as
  [{:ticket :title :from :to}] sorted by ticket id. A ticket in `new` and
  not in `old` is new and moves from no stage. A ticket only in `old` is
  ignored: a log never deletes, so its absence is a reading that failed,
  not a ticket that left."
  [old new]
  (vec (for [[id {:keys [current title]}] (sort-by (comp str key) new)
             :let [before (get-in old [id :current] #{})]
             :when (not= (set before) (set current))]
         {:ticket id :title title :from (set before) :to (set current)})))

(defn trigger-actor
  "The actor of the newest event of `ordered` (events in reduction order,
  each {:id :actor}) whose id is not in `seen`: the event that moved the
  ticket. nil when nothing is new."
  [seen ordered]
  (:actor (last (remove #(contains? (set seen) (:id %)) ordered))))

(defn changed-tickets
  "The ticket ids whose fingerprint in `fingerprints` ({id fp}) differs from
  the one `cursor` recorded: the only tickets worth deriving again."
  [cursor fingerprints]
  (vec (sort-by str (for [[id fp] fingerprints
                          :when (not= fp (get-in cursor [id :fp]))]
                      id))))

(defn advance
  "Fold `observations` ({id obs}) into `cursor`, the last state the notifier
  acted on for one store, and return {:cursor :diffs :refused}.

  An observation is {:fp :events [{:id :actor}] :current #{stage} :title
  :verified? :why}. A verified one replaces the ticket's entry and yields
  its stage diff against the entry's last verified stages, with the actor
  of the newest event the entry had not seen. An unverified one yields no
  diff, only a refusal naming why; its entry keeps the last verified
  stages and events and records the new fingerprint, so the same unverified
  bytes are not judged again on every poll and the next verified change
  diffs against stages that were verified.

  With `baseline?` (a store the notifier has no cursor for yet), verified
  observations are recorded and nothing is reported: a first start must
  not announce every ticket in the store."
  [cursor observations baseline?]
  (reduce
   (fn [acc [id {:keys [fp events current title verified? why]}]]
     (let [prev (get cursor id)]
       (if verified?
         (let [entry {:fp fp :events (mapv :id events) :current (set current)}
               diff (when-not baseline?
                      (first (stage-diffs {id {:current (:current prev #{})}}
                                          {id {:current current :title title}})))]
           (cond-> (assoc-in acc [:cursor id] entry)
             diff (update :diffs conj
                          (assoc diff :actor (trigger-actor (:events prev) events)))))
         (-> acc
             (assoc-in [:cursor id] (assoc (or prev {:events [] :current #{}}) :fp fp))
             (update :refused conj {:ticket id :why (or why "verification failed")})))))
   {:cursor (or cursor {}) :diffs [] :refused []}
   (sort-by (comp str key) observations)))

;; ------------------------------------------------------------ coalescing

(defn enqueue
  "Add `h` (a hint) to the coalescer `co` at `now`. A ticket already pending
  keeps the stages it started from and the moment it was first seen, and
  takes the newest title, stages and actor, so a burst of events folds
  into one hint for the window."
  [co h now]
  (let [k [(:store h) (:ticket h)]]
    (if-let [{:keys [first-ms] :as pending} (get-in co [:pending k])]
      (assoc-in co [:pending k]
                {:first-ms first-ms
                 :hint (assoc h :from (get-in pending [:hint :from]))})
      (assoc-in co [:pending k] {:first-ms now :hint h}))))

(defn drain
  "[co' due] for the coalescer `co` at `now` with a window of `window-ms`:
  the hints pending at least that long leave, sorted by store and ticket.
  A ticket that returned to the stages it started from leaves silently."
  [co now window-ms]
  (let [due (filter (fn [[_ {:keys [first-ms]}]] (>= (- now first-ms) window-ms))
                    (:pending co))]
    [(update co :pending #(apply dissoc % (map key due)))
     (vec (for [[_ {:keys [hint]}] (sort-by key due)
                :when (not= (:from hint) (:to hint))]
            hint))]))

;; ------------------------------------------------------------ subscriptions

(def default-ttl-ms (* 60 60 1000))
(def max-ttl-ms (* 24 60 60 1000))

(defn subscribe
  "[registry' refusal] after subscriber `id` asks for `request`
  {:store :selector :ttl-ms :principal} at `now`. `readable` is the set of
  store names the requester's principal may read; a request for any other
  store is refused, so a subscriber never learns of a store it could not
  read itself. The time to live is bounded: a forgotten subscriber expires
  instead of listening forever. A permanent subscription (a configured
  sink) passes :permanent? and never expires."
  [registry id {:keys [store selector ttl-ms permanent? principal]} readable now]
  (cond
    (not (contains? (set readable) store))
    [registry (str "store " (pr-str store) " is not one this subscriber may read")]

    (and (some? ttl-ms) (not (and (integer? ttl-ms) (pos? ttl-ms))))
    [registry "the time to live must be a positive number of seconds"]

    :else
    [(assoc-in registry [:subs id]
               {:store store
                :selector (or selector "")
                :principal principal
                :expires-ms (when-not permanent?
                              (+ now (min max-ttl-ms (or ttl-ms default-ttl-ms))))})
     nil]))

(defn unsubscribe [registry id] (update registry :subs dissoc id))

(defn expire
  "[registry' expired-ids]: subscriptions past their time to live at `now`
  leave the registry."
  [registry now]
  (let [gone (vec (sort-by str (for [[id {:keys [expires-ms]}] (:subs registry)
                                     :when (and expires-ms (<= expires-ms now))]
                                 id)))]
    [(update registry :subs #(apply dissoc % gone)) gone]))

(defn recipients
  "The subscriber ids `h` goes to: live subscriptions to its store whose
  selector is in `matching` (the selectors the ticket satisfies, computed by
  the caller from the ticket's derivation), sorted."
  [registry h matching now]
  (vec (sort-by str (for [[id {:keys [store selector expires-ms]}] (:subs registry)
                          :when (and (= store (:store h))
                                     (or (nil? expires-ms) (< now expires-ms))
                                     (contains? (set matching) selector))]
                      id))))

;; ------------------------------------------------------------ rate limit

(defn take-token
  "[buckets' allowed?]: a token bucket per subscriber holding at most
  `burst` tokens and refilling one every `per-ms`. A subscriber that has
  spent its burst gets nothing until the bucket refills, so a runaway
  store cannot flood a session."
  [buckets id now {:keys [per-ms burst]}]
  (let [{:keys [tokens at]} (get buckets id {:tokens burst :at now})
        refilled (min burst (+ tokens (quot (max 0 (- now at)) per-ms)))
        at' (if (>= refilled burst) now (+ at (* per-ms (quot (max 0 (- now at)) per-ms))))]
    (if (pos? refilled)
      [(assoc buckets id {:tokens (dec refilled) :at at'}) true]
      [(assoc buckets id {:tokens 0 :at at'}) false])))

;; ------------------------------------------------------------ renderings

(defn line
  "A hint as one human line: `<ticket> \"<title>\": a, b -> c (by actor)`."
  [{:keys [ticket title from to actor]}]
  (str ticket " \"" title "\": "
       (if (seq from) (str/join ", " from) "(new)")
       " -> " (if (seq to) (str/join ", " to) "(none)")
       (when (seq actor) (str " (by " actor ")"))))

(defn render-argv
  "A command sink's argv with {store} {ticket} {title} {from} {to} {actor}
  replaced by the hint's values. Each element stays one argument and no
  shell ever sees it, so a title cannot inject a command."
  [argv h]
  (let [vals {"store" (:store h) "ticket" (:ticket h) "title" (:title h)
              "from" (str/join "," (:from h)) "to" (str/join "," (:to h))
              "actor" (:actor h) "line" (line h)}]
    (mapv (fn [arg] (str/replace (str arg) #"\{(store|ticket|title|from|to|actor|line)\}"
                                 (fn [[_ k]] (get vals k ""))))
          argv)))

(defn channel-notification
  "The Claude Code channel event for `h`: notifications/claude/channel with
  the hint's line as content and its identifiers as meta attributes."
  [h]
  {:jsonrpc "2.0"
   :method "notifications/claude/channel"
   :params {:content (line h)
            :meta {:store (:store h)
                   :ticket (:ticket h)
                   :from (str/join "," (:from h))
                   :to (str/join "," (:to h))
                   :actor (:actor h)}}})

;; ------------------------------------------------------------ requests

(def max-request-bytes 4096)

(defn parse-subscribe
  "A subscriber's request, already parsed from JSON into a map with string
  keys, as {:request {:store :selector :ttl-ms}} or {:refused reason}.
  `compile-selector` throws ex-info on a selector tik cannot read."
  [m compile-selector]
  (let [{:strs [op store selector ttl_seconds]} (when (map? m) m)]
    (cond
      (not= "subscribe" op) {:refused "the only request is {\"op\":\"subscribe\",…}"}
      (not (string? store)) {:refused "subscribe needs a store name"}
      (and (some? selector) (not (string? selector))) {:refused "a selector is a string"}
      (and (some? ttl_seconds) (not (and (integer? ttl_seconds) (pos? ttl_seconds))))
      {:refused "ttl_seconds must be a positive integer"}
      :else
      (if-let [bad (try (compile-selector (or selector "")) nil
                        (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        {:refused (str "bad selector: " bad)}
        {:request {:store store
                   :selector (or selector "")
                   :ttl-ms (when ttl_seconds (* 1000 (long ttl_seconds)))}}))))

;; ------------------------------------------------------------ local only

(defn socket-dir-problem
  "Why the socket directory, as observed {:exists? :owner :me :perms}, must
  not hold the notifier's socket, else nil. It must be ours and closed to
  everybody else, or another local user could connect, or replace the
  socket with one of their own. `perms` is the rwxrwxrwx string."
  [{:keys [exists? owner me perms]}]
  (cond
    (not exists?) nil
    (not= owner me) (str "it belongs to " owner ", not " me)
    (not (re-matches #"rwx------" (str perms))) (str "its permissions are " perms ", not rwx------")
    :else nil))

;; ------------------------------------------------------------ cursor

(defn cursor->edn
  "The cursor of every store as plain data for EDN: stages as names."
  [cursors]
  {:tik/notify-cursor 1
   :stores (into (sorted-map)
                 (for [[store tickets] cursors]
                   [store (into (sorted-map)
                                (for [[id {:keys [fp events current]}] tickets]
                                  [(str id) {:fp fp :events (vec events)
                                             :current (stage-names current)}]))]))})

(defn edn->cursor
  "The inverse of cursor->edn. Anything that is not a cursor reads as no
  cursor, which costs one silent baseline, never a burst of old hints."
  [x]
  (if (and (map? x) (= 1 (:tik/notify-cursor x)) (map? (:stores x)))
    (into {} (for [[store tickets] (:stores x)
                   :when (and (string? store) (map? tickets))]
               [store (into {} (for [[id {:keys [fp events current]}] tickets
                                     :when (and (string? id) (string? fp))]
                                 [id {:fp fp
                                      :events (filterv string? events)
                                      :current (set (map keyword (filter string? current)))}]))]))
    {}))
