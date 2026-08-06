;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.link
  "What a link's VALUE means, including when it points somewhere else
  (ADR 0024).

  A link is an ordinary fact under a `[:link …]` path, so it disputes,
  supersedes, retracts and goes `:conflicted` like any other — nothing
  here is new machinery. What this namespace settles is the shape of the
  value, because that choice is inherited by every integration that ever
  wants to name a foreign ticket: bundles, the registry, the external
  tracker adapters.

  The value is the referent, never the path. Fact paths are vectors of
  KEYWORDS, so a uuid cannot live in one without changing what a path is
  everywhere; and `tik set <id> link.depends-on=<uuid>` already put the
  referent in the value. So a link value is either

    a bare uuid                      a ticket in this store
    {:ticket #uuid \"…\"               its identity — uuids are globally
     :head \"sha256-…\"                unique, so a store name never
     :store \"…\"}                     disambiguates, only locates

  `:head` is the point of the map. It pins WHAT WAS OBSERVED: one head
  commits to the entire ancestry (ADR 0004), so a link that carries one
  says which version of the other ticket the claim was made against, and
  a reader can tell a stale link from a current one. `:store` is
  advisory — where to fetch it — and carries no authority at all.

  Nothing here reads another ticket. A guard on a link reads the local
  fact and stops; whoever minted it is the one who looked."
  (:require [clojure.string :as str]))

(def prefix
  "The reserved path prefix. A missing fact under it means \"waiting on
  something elsewhere\", which a lens can tell apart from \"nobody here
  did the work yet\" — the same shape, two very different answers."
  :link)

(defn link-path?
  "Is this fact path a link?"
  [path]
  (and (sequential? path) (= prefix (first path))))

(defn ref-of
  "A link value -> {:ticket :head :store}, or nil when the value is not
  a reference at all.

  Total over anything a log can hold: a link's value is whatever somebody
  asserted, and a lens asking what it points at must not raise on a
  number or a map with the wrong shape."
  [v]
  (cond
    (uuid? v) {:ticket v}

    (and (string? v) (seq v))
    (when-let [t (try #?(:clj (java.util.UUID/fromString v)
                         :cljs (uuid v))
                      (catch #?(:clj Exception :cljs :default) _ nil))]
      {:ticket t})

    (map? v)
    (let [t (:ticket v)
          t (cond (uuid? t) t
                  (string? t) (try #?(:clj (java.util.UUID/fromString t)
                                      :cljs (uuid t))
                                   (catch #?(:clj Exception :cljs :default) _ nil))
                  :else nil)]
      (when t
        (cond-> {:ticket t}
          (and (string? (:head v)) (seq (:head v))) (assoc :head (:head v))
          (and (string? (:store v)) (seq (:store v))) (assoc :store (:store v)))))

    :else nil))

(defn foreign?
  "Does this reference name a store other than the one reading it?
  A reference with no `:store` is local by omission."
  [ref here]
  (boolean (and (:store ref) (not= (:store ref) here))))

(defn describe
  "One reference as a short human string — the only prose here, and it
  belongs to lenses rather than the kernel."
  [ref]
  ;; takes a reference, but a lens may hand it anything a log held, so a
  ;; non-reference is declined rather than truncated into an exception
  (when-let [t (and (map? ref) (:ticket ref))]
    (let [short (fn [n v] (let [v (str v)]
                            (if (> (count v) n) (str (subs v 0 n) "…") v)))]
      (str (short 8 t)
           (when (:store ref) (str " @" (:store ref)))
           (when (:head ref) (str " (at " (short 14 (:head ref)) ")"))))))

(defn link-name
  "The link's kind — `[:link :depends-on]` -> :depends-on — or nil when
  the path is not a link."
  [path]
  (when (and (link-path? path) (keyword? (second path)))
    (second path)))

(defn observed-head
  "The head a link pinned, if any. What tells a reader whether the claim
  still describes the ticket it points at."
  [v]
  (:head (ref-of v)))

(defn same-target?
  "Do two link values name the same ticket, whatever else differs?"
  [a b]
  (let [x (ref-of a) y (ref-of b)]
    (boolean (and x y (= (:ticket x) (:ticket y))))))

(defn render
  "A reference as the value to assert. Keeps the bare-uuid form when
  there is nothing else to say, so a local link stays as short as it
  always was."
  [{:keys [ticket head store]}]
  (if (or head store)
    (cond-> {:ticket ticket}
      head (assoc :head head)
      store (assoc :store store))
    ticket))

(defn parse-cli
  "The value a person types: a uuid, or `<uuid>@<store>`, or
  `<uuid>@<store>#<head>`. Returns a reference map or nil."
  [s]
  (when (string? s)
    (let [[before head] (str/split s #"#" 2)
          [id store] (str/split (str before) #"@" 2)]
      (when-let [ref (ref-of id)]
        (cond-> ref
          (and store (seq store)) (assoc :store store)
          (and head (seq head)) (assoc :head head))))))
