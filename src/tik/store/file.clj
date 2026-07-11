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

(defn- pack-file ^File [^File dir] (io/file dir "events.pack"))
(defn- pack-index-file ^File [^File dir] (io/file dir "events.pack.idx"))

(defn read-pack-index
  "The pack's table of contents: {:entries [{:id :offset :length}]}.
  nil when the directory holds no pack."
  [^File dir]
  (let [idx (pack-index-file dir)]
    (when (.isFile idx)
      (try (edn/read-string (slurp idx))
           (catch Exception e
             (throw (ex-info "unreadable pack index"
                             {:reason :pack/unreadable :file (str idx)}
                             e)))))))

(defn pack-slice
  "The exact hashed byte region of one packed event."
  ^bytes [^File dir {:keys [offset length]}]
  (let [out (byte-array length)]
    (with-open [raf (java.io.RandomAccessFile. (pack-file dir) "r")]
      (.seek raf (long offset))
      (.readFully raf out))
    out))

(defn- read-packed-events [^File dir]
  (if-let [{:keys [entries]} (read-pack-index dir)]
    (mapv (fn [{:keys [id] :as entry}]
            (let [bytes (pack-slice dir entry)
                  parsed (try (edn/read-string {:readers edn-readers}
                                               (String. bytes "UTF-8"))
                              (catch Exception e
                                (throw (ex-info "unreadable packed event"
                                                {:reason :event/unreadable
                                                 :file (str (pack-file dir))
                                                 :id id}
                                                e))))]
              (assoc parsed :event/id id)))
          entries)
    []))

(defn pack!
  "Consolidate a ticket's loose event files into events.pack + index:
  the pack is the CONCATENATION of the exact per-event hashed byte
  regions, so every event's sha256 still equals its id as a slice, and
  the pack itself gets a content-addressed twin name in the index for
  cheap fingerprinting. Loose .edn files are removed after the pack is
  fully written; signature sidecars stay loose (small, and coreutils
  verification of them needs the detached files). Appends after
  packing land loose and merge on read. Idempotent: already-packed
  events are carried over, loose newcomers join the new pack."
  [root ticket-id]
  (let [dir (ticket-dir root ticket-id)
        packed (when (.isFile (pack-file dir)) (read-packed-events dir))
        loose-files (when (.isDirectory dir)
                      (filter (fn [^File f]
                                (and (.isFile f)
                                     (str/ends-with? (.getName f) ".edn")))
                              (.listFiles dir)))
        loose (mapv read-event loose-files)
        events (sort-by :event/id
                        (vals (into {}
                                    (map (juxt :event/id identity))
                                    (concat packed loose))))]
    (when (seq events)
      (let [chunks (mapv #(.getBytes ^String
                           (canonical/emit (dissoc % :event/id))
                                     "UTF-8")
                         events)
            entries (loop [es events cs chunks off 0 acc []]
                      (if (empty? es)
                        acc
                        (recur (rest es) (rest cs)
                               (+ off (alength ^bytes (first cs)))
                               (conj acc {:id (:event/id (first es))
                                          :offset off
                                          :length (alength
                                                   ^bytes (first cs))}))))
            tmp (io/file dir "events.pack.tmp")]
        (with-open [out (java.io.FileOutputStream. tmp)]
          (doseq [^bytes c chunks] (.write out c)))
        (let [pack-hash (canonical/sha256-hex-bytes
                         (java.nio.file.Files/readAllBytes (.toPath tmp)))]
          (.renameTo tmp (pack-file dir))
          (spit (pack-index-file dir)
                (pr-str {:pack (str "sha256-" pack-hash)
                         :entries entries}))
          ;; loose files leave only after the pack fully exists
          (doseq [^File f loose-files] (.delete f))
          {:packed (count events) :pack (str "sha256-" pack-hash)})))))

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
        ;; the claim, never the claim (ADR 0007). Packed and loose
        ;; events merge — loose wins ties (it cannot actually differ:
        ;; same id means same bytes)
        (let [loose (mapv read-event
                          (filter (fn [^File f]
                                    (and (.isFile f)
                                         (str/ends-with? (.getName f) ".edn")
                                         (not (str/ends-with? (.getName f)
                                                              ".pack.edn"))))
                                  (.listFiles dir)))
              loose-ids (into #{} (map :event/id) loose)]
          (into loose
                (remove #(contains? loose-ids (:event/id %))
                        (read-packed-events dir))))
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
                 (let [evdir (io/file t "events")]
                   (or (.exists (io/file evdir (str event-id ".edn")))
                       (some #(= event-id (:id %))
                             (:entries (read-pack-index evdir))))))
               (filter #(.isDirectory ^File %) (.listFiles dir))))))))

(defn file-store [root] (->FileStore root))
