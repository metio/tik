;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store.file
  "File-backed EventStore: tickets/<uuid>/events/<event-id>.edn under a root
  directory. Each file holds exactly the canonical bytes of the hashed
  region (ADR 0007): sha256sum(file) = filename = event id. Append-only and
  content-addressed, so a git merge of two replicas only ever ADDS files —
  union merge, conflict-free by construction. Blobs live under
  tickets/<uuid>/blobs/, also named by hash."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.store.protocol :as p])
  (:import (java.io File)
           (java.time Instant)))

(def edn-readers
  ;; Read #inst as java.time.Instant so values round-trip through the
  ;; canonical printer unchanged.
  {'inst (fn [s] (Instant/parse s))
   'uuid (fn [s] (java.util.UUID/fromString s))})

(defn read-event
  "The file holds exactly the hashed region (ADR 0007); the id IS the
  filename and is attached on read. `verify` recomputes it from the
  bytes. A file that is not one well-formed EDN map fails WELL here —
  a correct hash of garbage bytes is still garbage, and the rejection
  must name the file, not explode in the reader."
  [^File f]
  (let [stem (str/replace (.getName f) #"\.edn$" "")
        parsed (try (edn/read-string {:readers edn-readers} (slurp f))
                    (catch Exception e
                      (throw (ex-info "unreadable event file"
                                      {:reason :event/unreadable
                                       :file (str f)}
                                      e))))]
    (when-not (map? parsed)
      (throw (ex-info "event file does not hold an event map"
                      {:reason :event/unreadable :file (str f)
                       :read (pr-str parsed)})))
    (assoc parsed :event/id stem)))

(defn- ticket-dir ^File [root ticket-id]
  (io/file root "tickets" (str ticket-id) "events"))

(defrecord FileStore [root]
  p/EventStore
  (append! [_ event]
    (let [dir (ticket-dir root (:event/ticket event))
          f (io/file dir (str (:event/id event) ".edn"))]
      (.mkdirs dir)
      (when-not (.exists f)
        ;; EXACT canonical bytes of the hashed region — the event WITHOUT
        ;; :event/id (ADR 0007). The file's sha256 IS its filename IS the
        ;; event id: verify L0 is `sha256sum`-checkable with coreutils.
        (spit f (canonical/emit (dissoc event :event/id))))
      event))
  (events [_ ticket-id]
    (let [dir (ticket-dir root ticket-id)]
      (if (.isDirectory dir)
        ;; only *.edn holds events; .sig.* sidecars are endorsements of
        ;; the claim, never the claim (ADR 0007)
        (mapv read-event (filter (fn [^File f]
                                   (and (.isFile f)
                                        (str/ends-with? (.getName f) ".edn")))
                                 (.listFiles dir)))
        [])))
  (ticket-ids [_]
    (let [dir (io/file root "tickets")]
      (if (.isDirectory dir)
        (mapv #(java.util.UUID/fromString (.getName ^File %))
              (filter #(.isDirectory ^File %) (.listFiles dir)))
        [])))
  (has-event? [_ event-id]
    (let [dir (io/file root "tickets")]
      (boolean
       (when (.isDirectory dir)
         (some (fn [^File t]
                 (.exists (io/file t "events" (str event-id ".edn"))))
               (filter #(.isDirectory ^File %) (.listFiles dir))))))))

(defn file-store [root] (->FileStore root))
