;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.mirror
  "One record of another system mirrored as one ticket: a create event,
  then the record's history as ordered steps, each becoming one event.
  The Redmine and Odoo bridges describe a record's history as steps and
  leave the events to this namespace, so the event shape, and with it
  every event id, is the same for both."
  (:require [tik.event :as event]
            [tik.forge :as forge]))

(defn ticket-events
  "The events and blobs of ticket `id`, created at `at` with `title` under
  the pinned process in `ctx` (:actor :process :version :process-hash).
  `steps` are {:fact path :value v :at t} (a nil :value retracts) and
  {:attach path :text s :at t}, in history order.

  Each fact event names the previous event on the same path as its parent
  (the create, for the first): successive values of a path are causally
  ordered, so a change supersedes the old value where sibling assertions
  would derive as :conflicted. The chains are per path, so a step on one
  path never moves another path's events."
  [{:keys [actor process version process-hash]} id title at steps]
  (let [create (event/create-ticket
                {:ticket id :actor actor :at at :title title
                 :process process :version version :process-hash process-hash})
        cid (:event/id create)
        step->event
        (fn [{:keys [last] :as acc} {:keys [fact attach value text at]}]
          (if fact
            (let [base {:ticket id :actor actor :at at :parents #{(get last fact cid)} :path fact}
                  e (if (some? value)
                      (event/assert-fact (assoc base :value value))
                      (event/retract-fact base))]
              (-> acc (update :events conj e) (assoc-in [:last fact] (:event/id e))))
            (let [h (forge/blob-hash text)]
              (-> acc
                  (update :events conj (event/attach-artifact
                                        {:ticket id :actor actor :at at :parents #{cid}
                                         :path attach :hash h}))
                  (assoc-in [:blobs h] text)))))
        {:keys [events blobs]} (reduce step->event {:events [create] :blobs {} :last {}} steps)]
    {:id id :create-id cid :blobs blobs :events events}))
