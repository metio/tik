;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.write
  "The write verbs: new (mint a ticket, inheriting store/repo context as
  signed facts), set/retract/dispute (assert, withdraw, challenge facts),
  attach/comment (artifacts by hash), and diff (evidence gained over the
  last N events). Every write lands as an ordinary signed event; the
  stage re-derives itself. Porcelain over tik.cli-core."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [actor parse-key typed-value]]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [append!* archive-process! cache-flush! context-facts
                                  die load-process load-ticket now open-ticket-rows
                                  resolve-id root stage-delta the-store ticket-ctx]]
            [tik.dag :as dag]
            [tik.dupe :as dupe]
            [tik.event :as event]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.render :refer [emit-data sid]]
            [tik.stage :as stage]
            [tik.store.protocol :as store]))

(defn cmd-new [{:keys [pos opts]}]
  (let [[proc-name] pos
        proc (load-process proc-name)
        s (the-store)
        t (now)
        similar (when-let [title (:title opts)]
                  (take 3 (dupe/radar title (open-ticket-rows s t) 0.4)))
        id (random-uuid)
        e (event/create-ticket {:ticket id :actor (actor opts) :at t
                                ;; --title with no following value parses to Boolean true —
                                ;; the log stores strings, never a parser artifact
                                :title (let [t (:title opts)]
                                         (if (string? t) t ""))
                                :process (keyword proc-name)
                                :version (:process/version proc)
                                :process-hash (archive-process! proc)})]
    (append!* s e opts)
    (doseq [[path value] (context-facts)]
      (append!* s (event/assert-fact
                   {:ticket id :actor (actor opts) :at (now)
                    :parents (dag/heads (store/events s id))
                    :path path :value value})
                opts)
      (binding [*out* *err*]
        (println (str "context: " (str/join "." (map name path)) "="
                      (pr-str value)))))
    (println (str id))
    (let [{:keys [events process roles]} (ticket-ctx s id)
          current (stage/current-stages
                   process (stage/effective-reached process events (now) roles))]
      (binding [*out* *err*]
        (println (str "stage: " (str/join ", " (map name current))
                      " — next: tik explain " (sid id)))))
    (doseq [{existing :id :keys [title score]} similar]
      (binding [*out* *err*]
        (println (str "note: looks like " (sid existing) " \"" title
                      "\" (" (int (* 100 score)) "% similar) — if this IS "
                      "that, record it: tik set " (sid id)
                      " duplicate-of=\"" (sid existing) "\""))))
    (cache-flush!)))

(defn cmd-set [{:keys [pos opts]}]
  (let [[ticket & kvs] pos
        _ (when (or (nil? ticket) (empty? kvs))
            (die "usage: tik set <id> key=value [key=value ...]   (dotted keys nest: payment.ref=abc)"))
        ;; unquoted prose splits at the shell; words without '=' belong
        ;; to the previous pair — `set <id> note=hello world` records
        ;; note="hello world" instead of erroring on "world"
        kvs (reduce (fn [acc kv]
                      (cond
                        (str/includes? kv "=") (conj acc kv)
                        (empty? acc)
                        (die (str "'" kv "' is not key=value — write it as "
                                  kv "=<value>"))
                        :else (conj (pop acc) (str (peek acc) " " kv))))
                    [] kvs)
        s (the-store)
        {:keys [id process]} (load-ticket s ticket)]
    ;; heads recomputed per event: linear chain within one command
    (doseq [kv kvs :let [[k v] (str/split kv #"=" 2)
                         path (parse-key k)]]
      (append!* s (event/assert-fact
                   {:ticket id :actor (actor opts) :at (now)
                    :parents (dag/heads (store/events s id))
                    :path path :value (typed-value process path v)})
                opts))
    (println "ok")))

(defn cmd-retract [{:keys [pos opts]}]
  (let [[ticket k] pos
        s (the-store)
        id (resolve-id s ticket)]
    (append!* s (event/retract-fact
                 {:ticket id :actor (actor opts) :at (now)
                  :parents (dag/heads (store/events s id))
                  :path (parse-key k)
                  :reason (:reason opts)})
              opts)
    (println "ok")))

(defn cmd-diff
  "Evidence gained between two points in the log: derive at event n-k and
  at the head, report stages that became derivable and facts that
  changed — never 'transitions performed', because none were."
  [{:keys [pos opts]}]
  (let [s (the-store)
        {:keys [events process roles]} (load-ticket s (first pos))
        ;; k = how many trailing events to roll back; a non-number,
        ;; negative, or huge k must be a clean usage error, not a raw
        ;; NumberFormat/IndexOutOfBounds surfaced as "a bug in tik"
        k (if-let [ks (second pos)]
            (or (parse-long ks)
                (die (str "diff <id> [k]: k must be a whole number, got " ks)))
            1)
        _ (when (neg? k) (die "diff k must be zero or positive"))
        ordered (vec (red/ordered events))
        before-events (subvec ordered 0 (max 1 (- (count ordered) (min k (count ordered)))))
        t (now)
        before-reached (stage/effective-reached process before-events t roles)
        after-reached (stage/effective-reached process ordered t roles)
        before-facts (guard/fact-map (red/ticket-state before-events))
        after-facts (guard/fact-map (red/ticket-state ordered))
        {:keys [gained lost]} (stage-delta before-reached after-reached)
        fact-changes (for [path (sort-by str (set (concat (keys before-facts)
                                                          (keys after-facts))))
                           :let [b (get before-facts path) a (get after-facts path)]
                           :when (not= b a)]
                       {:path path :before b :after a})
        data {:window (min k (dec (count ordered)))
              :gained (vec gained) :lost (vec lost) :facts (vec fact-changes)}]
    (when-not (emit-data opts data)
      (println (str "last " (:window data) " event(s):"))
      (doseq [stage-id gained] (println "  + stage" stage-id "became derivable"))
      (doseq [stage-id lost] (println "  - stage" stage-id "no longer derivable"))
      (doseq [{:keys [path before after]} fact-changes]
        (cond
          (nil? before) (println "  + fact" path "=" (pr-str after))
          (nil? after) (println "  - fact" path "(was" (pr-str before) ")")
          :else (println "  ~ fact" path "=" (pr-str after)
                         "(was" (pr-str before) ")")))
      (when (and (= before-reached after-reached) (= before-facts after-facts))
        (println "  no derivable change")))))

(defn cmd-dispute [{:keys [pos opts]}]
  (let [[ticket k] pos
        s (the-store)
        id (resolve-id s ticket)]
    (append!* s (event/dispute-fact
                 {:ticket id :actor (actor opts) :at (now)
                  :parents (dag/heads (store/events s id))
                  :path (parse-key k)
                  :reason (or (:reason opts) "disputed")})
              opts)
    (println "ok")))

(defn cmd-attach [{:keys [pos opts]}]
  (let [[ticket path] pos
        s (the-store)
        id (resolve-id s ticket)
        src (io/file path)
        _ (when-not (.exists src) (die "no such file:" path))
        bytes (java.nio.file.Files/readAllBytes (.toPath src))
        hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
        ;; blobs stored BY HASH: nothing in the store is addressed by
        ;; anything except what it is
        dest (io/file (root) "tickets" (str id) "blobs" hash)]
    (.mkdirs (.getParentFile dest))
    (io/copy src dest)
    (append!* s (event/attach-artifact
                 (cond-> {:ticket id :actor (actor opts) :at (now)
                          :parents (dag/heads (store/events s id))
                          :path (str "repro/" (.getName src)) :hash hash}
                   ;; lineage is a CLAIM by the attacher (ADR 0014):
                   ;; carried in the event body, disputable like any claim
                   (:derived-from opts)
                   (assoc :derived-from (:derived-from opts))))
              opts)
    (println "ok" hash)))

(defn cmd-comment
  "A comment IS an artifact: a text blob stored by hash, attached under
  comment/<at>. No dedicated event type (v6) — one attach covers both."
  [{:keys [pos opts]}]
  (let [[ticket & words] pos
        s (the-store)
        id (resolve-id s ticket)
        at (now)
        text (str/join " " words)
        bytes (.getBytes ^String text "UTF-8")
        hash (str "sha256-" (canonical/sha256-hex-bytes bytes))
        dest (io/file (root) "tickets" (str id) "blobs" hash)]
    (.mkdirs (.getParentFile dest))
    (spit dest text)
    (append!* s (event/attach-artifact
                 {:ticket id :actor (actor opts) :at at
                  :parents (dag/heads (store/events s id))
                  :path (str "comment/" at) :hash hash})
              opts)
    (println "ok" hash)))
