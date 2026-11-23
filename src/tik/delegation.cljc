;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.delegation
  "Which events a delegation covers (ADR 0037).

  A delegation is an `:attestation/add` on an identity-registry ticket
  whose body is

      {:claim :delegation
       :delegation/public-key \"ssh-ed25519 …\"   the throwaway key
       :delegation/not-after #inst \"…\"          at most five minutes on
       :delegation/scope #{[ticket head] …}      the heads the reviewer saw
       :delegation/facts #{[:review :verdict]}}  what the key may assert

  The event's own `:event/actor` is the delegator. This namespace judges
  the shape of a delegation and whether it covers an event, from the
  events alone. Whether the delegation's signature is the delegator's,
  and whether it was touched, is the audit's question: a delegation that
  does not pass it covers nothing however well it is formed.

  Pure and total: a value that is not a well-formed delegation, or not an
  event, is declined, never thrown on."
  (:require [tik.identity :as identity]
            [tik.shape :as shape])
  #?(:clj (:import (java.time Duration Instant))))

(def claim-type :delegation)

(def max-window
  "The longest a delegation may stay valid: long enough to sign one batch."
  (Duration/ofMinutes 5))

(defn- instant? [x] (instance? Instant x))

(defn- fact-path? [p]
  (and (vector? p) (seq p) (every? keyword? p)))

(defn- scope-pair? [p]
  (and (vector? p) (= 2 (count p))
       (uuid? (first p))
       (string? (second p))))

(defn- problems [{:keys [actor at public-key not-after scope facts]}]
  (cond-> []
    (not (identity/principal? actor))
    (conj :delegation/bad-actor)
    (not (identity/public-key-line? public-key))
    (conj :delegation/bad-public-key)
    (not (and (instant? at) (instant? not-after)))
    (conj :delegation/no-expiry)
    (and (instant? at) (instant? not-after) (.isBefore ^Instant not-after ^Instant at))
    (conj :delegation/expires-before-it-starts)
    (and (instant? at) (instant? not-after)
         (.isAfter ^Instant not-after (.plus ^Instant at ^Duration max-window)))
    (conj :delegation/window-too-long)
    (not (and (set? scope) (seq scope) (every? scope-pair? scope)))
    (conj :delegation/bad-scope)
    (not (and (set? facts) (seq facts) (every? fact-path? facts)))
    (conj :delegation/bad-facts)))

(defn delegations
  "Every delegation claimed on `registry-events`, each with the event that
  claimed it and the `:problems` that keep it from covering anything."
  [registry-events]
  (if-not (sequential? registry-events)
    []
    (into []
          (comp (filter shape/plain-map?)
                (filter #(= :attestation/add (:event/type %)))
                (filter #(and (shape/plain-map? (:event/body %))
                              (= claim-type (get-in % [:event/body :claim]))))
                (map (fn [e]
                       (let [b (:event/body e)
                             d {:actor (:event/actor e)
                                :at (:event/at e)
                                :event (:event/id e)
                                :ticket (:event/ticket e)
                                :public-key (:delegation/public-key b)
                                :not-after (:delegation/not-after b)
                                :scope (:delegation/scope b)
                                :facts (:delegation/facts b)}]
                         (assoc d :problems (problems d))))))
          registry-events)))

(defn covers?
  "Whether the well-formed delegation `d` covers `event`: a fact assertion
  by the delegator, claimed inside the delegation's window, on a ticket
  and parent the scope names, for a path the delegation allows."
  [d event]
  (boolean
   (and (shape/plain-map? d)
        (shape/plain-map? event)
        (empty? (:problems d))
        (= :fact/assert (:event/type event))
        (= (:actor d) (:event/actor event))
        (instant? (:event/at event))
        (instant? (:at d))
        (instant? (:not-after d))
        (set? (:scope d))
        (set? (:facts d))
        (not (.isBefore ^Instant (:event/at event) ^Instant (:at d)))
        (not (.isAfter ^Instant (:event/at event) ^Instant (:not-after d)))
        (set? (:event/parents event))
        (some #(contains? (:scope d) [(:event/ticket event) %]) (:event/parents event))
        (contains? (:facts d) (get-in event [:event/body :fact/path])))))
