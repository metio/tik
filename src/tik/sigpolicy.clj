;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sigpolicy
  "The store's signature policy (ADR 0036 points 4 and 7), read from
  `signatures.edn`:

    {:require #{:ssh-ed25519 :ml-dsa-65} :from #inst \"2026-12-01T00:00:00Z\"}

  An event claimed at or after `:from` counts as signed only when every
  required signature by its actor verifies. An event claimed before it
  needs only the classical signature, and must be anchored: an ancestor
  of, or itself, an event an ML-DSA-65 witness countersigned. Once Ed25519
  can be forged, an attacker can write new events with old instants; the
  anchor is what tells those apart from the history that existed when the
  policy took effect.

  The algorithm set is closed and `:ssh-ed25519` is always required, so a
  policy can add the post-quantum half but never drop the classical one.
  A policy that does not read as exactly this shape is malformed, and a
  malformed policy fails verification rather than falling back to no
  policy: a store whose author asked for more must not quietly get less.

  Everything here is pure: the caller says which signatures verified and
  which events a witness anchored."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.time Instant)
           (java.util Date)))

(def algorithms
  "The signature algorithms a policy may require."
  #{:ssh-ed25519 :ml-dsa-65})

(def ^:private policy-keys #{:require :from})

(defn- instant [x]
  (cond
    (instance? Instant x) x
    (instance? Date x) (.toInstant ^Date x)
    (string? x) (try (Instant/parse x) (catch Exception _ nil))
    :else nil))

(defn parse
  "The policy in `text` as {:require #{…} :from Instant}, or
  {::malformed \"why\"}. nil text is no policy and reads as nil."
  [text]
  (when (some? text)
    (let [form (try (edn/read-string {:readers {'inst #(Instant/parse %)}} (str text))
                    (catch Exception e {::unreadable (ex-message e)}))
          bad (fn [why] {::malformed why})]
      (cond
        (::unreadable form) (bad (str "signatures.edn does not read as EDN: " (::unreadable form)))
        (not (map? form)) (bad "signatures.edn is a map {:require #{…} :from <instant>}")
        (seq (remove policy-keys (keys form)))
        (bad (str "signatures.edn has keys tik does not know: "
                  (str/join ", " (sort (map pr-str (remove policy-keys (keys form)))))))
        (not (set? (:require form))) (bad ":require is a set of algorithms")
        (seq (set/difference (:require form) algorithms))
        (bad (str ":require names algorithms outside " (pr-str (sort algorithms)) ": "
                  (pr-str (sort-by str (set/difference (:require form) algorithms)))))
        (not (contains? (:require form) :ssh-ed25519))
        (bad ":require keeps :ssh-ed25519 - the post-quantum signature is a second one, never the only one")
        (nil? (instant (:from form))) (bad ":from is an instant, such as #inst \"2026-12-01T00:00:00Z\"")
        :else {:require (:require form) :from (instant (:from form))}))))

(defn malformed
  "Why the parsed policy is malformed, or nil."
  [policy]
  (::malformed policy))

(defn pq?
  "Whether the policy requires the post-quantum signature at all."
  [policy]
  (and (map? policy) (not (malformed policy)) (contains? (:require policy) :ml-dsa-65)))

(defn- before-from? [policy at]
  (let [a (instant at)]
    (boolean (and a (.isBefore ^Instant a ^Instant (:from policy))))))

(defn required
  "The algorithms an event claimed at `at` must be signed with: the
  policy's set from `:from` on, the classical signature alone before it,
  and nothing without a policy. An unreadable `at` is held to the full
  set, since nothing places it before `:from`."
  [policy at]
  (cond
    (or (nil? policy) (malformed policy)) #{}
    (before-from? policy at) #{:ssh-ed25519}
    :else (:require policy)))

(defn anchored
  "The ids `anchors` commit to: each anchor and every ancestor reachable
  through `:event/parents` among `events`."
  [events anchors]
  (let [parents-of (into {} (map (juxt :event/id #(set (:event/parents %)))) events)]
    (loop [todo (filterv parents-of anchors) seen #{}]
      (if-let [[id & more] (seq todo)]
        (if (seen id)
          (recur (vec more) seen)
          (recur (into (vec more) (parents-of id)) (conj seen id)))
        seen))))

(defn judge
  "What the policy says about one ticket's events, as {:ok? :msg} rows.
  `verified` maps an event id to the set of algorithms whose signature by
  the event's actor verified; `anchors` are the ids carrying a verifying
  ML-DSA-65 witness countersignature. No rows without a policy; the
  anchor is asked for only when the policy requires the post-quantum
  half, since it seals history against a forged classical signature."
  [policy events {:keys [verified anchors]}]
  (cond
    (nil? policy) []
    (malformed policy) [{:ok? false :msg (malformed policy)}]
    :else
    (let [covered (anchored events anchors)
          short-id #(subs (str %) 0 (min 15 (count (str %))))]
      (vec
       (for [e events
             :let [id (:event/id e)
                   missing (set/difference (required policy (:event/at e)) (get verified id #{}))
                   unanchored? (and (pq? policy) (before-from? policy (:event/at e)) (not (covered id)))]]
         (cond
           (seq missing)
           {:ok? false :msg (str (short-id id) "… is claimed at " (:event/at e)
                                 " and lacks a " (str/join " and " (map name (sort missing)))
                                 " signature by " (:event/actor e) ", which signatures.edn requires")}
           unanchored?
           {:ok? false :msg (str (short-id id) "… is claimed before " (:from policy)
                                 " and no post-quantum witness anchors it - classical history"
                                 " is sealed by `tik anchor`, and an event written later with"
                                 " an earlier instant is what the anchor exists to catch")}
           :else
           {:ok? true :msg (str (short-id id) "… meets signatures.edn")}))))))
