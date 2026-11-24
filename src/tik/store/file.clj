;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.store.file
  "File-backed EventStore: tickets/<uuid>/events/<event-id>.edn under a root
  directory. Each file holds exactly the canonical bytes of the hashed
  region (ADR 0007): sha256sum(file) = filename = event id. Append-only and
  content-addressed, so a git merge of two replicas only ever ADDS files —
  union merge, conflict-free by construction. Blobs live under
  tickets/<uuid>/blobs/, also named by hash."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.store.protocol :as p])
  (:import (java.io File)
           (java.nio.file CopyOption Files OpenOption StandardCopyOption
                          StandardOpenOption)))

(defn- file-bytes
  "The raw bytes of a file, or nil if absent — no charset round-trip, so
  the bytes returned are exactly the bytes stored (ADR 0007), matching
  the SQLite backend's raw BLOB."
  ^bytes [^File f]
  (when (.isFile f) (Files/readAllBytes (.toPath f))))

(defn parse-event
  "The one byte->event parse every backend shares: stored text -> event
  map with `id` attached (the bytes are the hashed region, ADR 0007, so
  the id is never IN them). Fails WELL — a correct hash of garbage bytes
  is still garbage: depth precheck first (a 100k-deep vector overflows
  the recursive reader with an Error no guard catches), then EDN with
  the canonical readers, then the event-map shape guard. Any rejection
  is an ex-info with :reason :event/unreadable carrying `err-data`,
  which names where the caller read from (file, pack, or db row)."
  [text id err-data]
  (let [parsed (try (canonical/parse text)
                    (catch Exception e
                      (throw (ex-info "unreadable event"
                                      (assoc err-data :reason :event/unreadable)
                                      e))))]
    (when-not (map? parsed)
      (throw (ex-info "stored bytes do not hold an event map"
                      (assoc err-data :reason :event/unreadable
                             :read (pr-str parsed)))))
    (assoc parsed :event/id id)))

(defn read-event
  "One loose event file: the id IS the filename and is attached on read;
  `verify` recomputes it from the bytes."
  [^File f]
  (parse-event (String. (file-bytes f) "UTF-8")
               (str/replace (.getName f) #"\.edn$" "")
               {:file (str f)}))

(defn- write-synced!
  "Write `chunks` to `f` and force them to disk before returning. `pack!`
  deletes the loose event files on the strength of the pack and index
  existing; without the fsync, a crash in that window loses events the
  store already reported as packed — the bytes were only ever in the page
  cache.
  Written through java.nio.file with StandardOpenOption/SYNC rather than
  FileOutputStream + FileDescriptor.sync: the CLI runs on babashka, whose
  class allowlist has no FileDescriptor.sync, and the store must behave
  identically on bb, the JVM, and the native binary."
  [^File f chunks]
  (let [^bytes joined (byte-array (mapcat seq chunks))]
    (Files/write (.toPath f) joined
                 ^"[Ljava.nio.file.OpenOption;"
                 (into-array OpenOption [StandardOpenOption/CREATE
                                         StandardOpenOption/WRITE
                                         StandardOpenOption/TRUNCATE_EXISTING
                                         StandardOpenOption/SYNC]))))

(defn pack-installed!
  "Move the finished pack into place. Files/move THROWS when the install
  fails; File.renameTo returns a boolean that is easy to drop, and
  dropping it deletes the loose events against a pack that was never
  installed — an index describing a pack that is not there, with no
  originals left to rebuild from. Named so a test can make the install
  fail and assert that packing is all-or-nothing."
  [^File tmp ^File dest]
  (Files/move (.toPath tmp) (.toPath dest)
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn installed!
  "Atomically move the finished temp file `tmp` onto `dest`, replacing
  it. A reader sees the old file or the new one, never a partial write.
  Named so a test can fail the install and assert nothing torn remains."
  [^File tmp ^File dest]
  (Files/move (.toPath tmp) (.toPath dest)
              (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                      StandardCopyOption/REPLACE_EXISTING])))

(defn- write-atomically!
  "Write `bytes` to `dest` through a synced temp file in the same
  directory, then install it atomically, removing the temp file when
  the install fails."
  [^File dest ^bytes bytes]
  ;; the temp name carries neither .edn nor .sig./.witness., so a temp
  ;; file a killed process leaves behind is never read as an event or a
  ;; sidecar
  (let [tmp (File/createTempFile ".tik-write-" ".tmp" (.getParentFile dest))]
    (try
      (write-synced! tmp [bytes])
      (installed! tmp dest)
      (finally (.delete tmp)))))

(defn- holds-event?
  "Whether the file `f` holds the event named `id`: its bytes hash to the
  id. A torn or foreign file under the name does not."
  [^File f id]
  (and (.isFile f)
       (= id (canonical/address-of-bytes (file-bytes f)))))

(defn- ticket-dir ^File [root ticket-id]
  (io/file root "tickets" (str ticket-id) "events"))

(defn- pack-index-file ^File [^File dir] (io/file dir "events.pack.idx"))

(defn- named-pack-file
  "Where a pack whose bytes hash to `pack-hash` (\"sha256-<hex>\") lives:
  events.<hex>.pack. The name binds the index to the one pack it
  describes, so installing a new pack never changes the bytes under an
  index already in place."
  ^File [^File dir pack-hash]
  (io/file dir (str "events." (str/replace (str pack-hash) #"^sha256-" "") ".pack")))

(defn pack-file-of
  "The pack an index describes: the content-addressed file it names, or
  events.pack, the name a pack carried before packs were content
  addressed."
  ^File [^File dir index]
  (let [named (named-pack-file dir (:pack index))]
    (if (.isFile named) named (io/file dir "events.pack"))))

(defn- pack-files
  "Every pack file in `dir`, current or stale."
  [^File dir]
  (filter (fn [^File f]
            (and (.isFile f) (re-matches #"events(\.[0-9a-f]{64})?\.pack" (.getName f))))
          (or (.listFiles dir) [])))

(defn read-pack-index
  "The pack's table of contents: {:entries [{:id :offset :length}]}.
  nil when the directory holds no pack."
  [^File dir]
  (let [idx (pack-index-file dir)]
    (when (.isFile idx)
      ;; through the guarded parse, so a hostile index with pathological
      ;; nesting is an ex-info here, not a reader stack overflow
      (try (canonical/parse (slurp idx))
           (catch Exception e
             (throw (ex-info "unreadable pack index"
                             {:reason :pack/unreadable :file (str idx)}
                             e)))))))

(defn pack-slice
  "The exact hashed byte region of one packed event, checked against its
  id. An index lying about offsets, a truncated pack, or a slice whose
  bytes are some other event's fails WELL — the rejection names the
  pack, never a raw EOF or a silently swapped event."
  (^bytes [^File dir entry] (pack-slice dir (read-pack-index dir) entry))
  (^bytes [^File dir index {:keys [id offset length]}]
   (let [pf (pack-file-of dir index)
         out (try
               (let [out (byte-array (max 0 (long length)))]
                 (with-open [raf (java.io.RandomAccessFile. pf "r")]
                   (.seek raf (max 0 (long offset)))
                   (.readFully raf out))
                 out)
               (catch Exception e
                 (throw (ex-info "pack slice unreadable — index and pack disagree"
                                 {:reason :pack/unreadable :file (str pf)
                                  :id id :offset offset :length length}
                                 e))))]
     (when-not (= id (canonical/address-of-bytes out))
       (throw (ex-info "pack slice does not hash to its id — index and pack disagree"
                       {:reason :pack/unreadable :file (str pf)
                        :id id :offset offset :length length})))
     out)))

(defn- read-packed-events [^File dir]
  (if-let [{:keys [entries] :as index} (read-pack-index dir)]
    (mapv (fn [{:keys [id] :as entry}]
            (parse-event (String. ^bytes (pack-slice dir index entry) "UTF-8")
                         id
                         {:file (str (pack-file-of dir index)) :id id}))
          entries)
    []))

(defn index-installed!
  "Replace the pack index of `dir` with `bytes` atomically. Named so a
  test can fail it and assert an interrupted re-pack loses nothing."
  [^File dir ^bytes bytes]
  (write-atomically! (pack-index-file dir) bytes))

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
        packed (read-packed-events dir)
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
        (write-synced! tmp chunks)
        ;; packing is all-or-nothing. The new pack lands under its own
        ;; content address beside the old one, so the index in place
        ;; keeps describing intact bytes until the new index replaces it
        ;; atomically; an install that does not happen throws before
        ;; anything is deleted
        (let [address (canonical/address-of-bytes (java.nio.file.Files/readAllBytes (.toPath tmp)))
              current (named-pack-file dir address)]
          (pack-installed! tmp current)
          (index-installed! dir (.getBytes (pr-str {:pack address :entries entries})
                                           "UTF-8"))
          ;; loose files and superseded packs leave only once the pack
          ;; AND its index are durable
          (doseq [^File f loose-files] (.delete f))
          (doseq [^File f (pack-files dir) :when (not= f current)] (.delete f))
          {:packed (count events) :pack address})))))

(defrecord FileStore [root]
  p/EventStore
  (append! [_ event]
    (let [dir (ticket-dir root (:event/ticket event))
          f (io/file dir (str (:event/id event) ".edn"))]
      (.mkdirs dir)
      ;; present means the bytes hash to the name: a torn file left by a
      ;; killed write is replaced by the next append of the same event
      (when-not (holds-event? f (:event/id event))
        ;; EXACT canonical bytes of the hashed region — the event WITHOUT
        ;; :event/id (ADR 0007). Written as UTF-8, the charset the id is
        ;; hashed over (canonical/sha256-hex), so sha256sum(file) = filename
        ;; = event id holds on any locale — verify L0 stays coreutils-checkable.
        (write-atomically! f (.getBytes (canonical/emit (dissoc event :event/id)) "UTF-8")))
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
                                         (str/ends-with? (.getName f) ".edn")))
                                  (.listFiles dir)))
              loose-ids (into #{} (map :event/id) loose)]
          (into loose
                (remove #(contains? loose-ids (:event/id %))
                        (read-packed-events dir))))
        [])))
  (ticket-ids [_]
    (let [dir (io/file root "tickets")]
      (if (.isDirectory dir)
        ;; a ticket directory's name IS its uuid identity, so only a uuid
        ;; names a ticket; a stray non-uuid entry (.gitkeep, an editor tmp
        ;; dir) is simply not a ticket and is skipped, never a crash.
        (into []
              (keep (fn [^File f]
                      (when (.isDirectory f)
                        (try (java.util.UUID/fromString (.getName f))
                             (catch IllegalArgumentException _ nil)))))
              (.listFiles dir))
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
               (filter #(.isDirectory ^File %) (.listFiles dir)))))))
  (event-bytes [_ ticket-id event-id]
    ;; pack-aware, like `events`/`has-event?`: `pack!` deletes the loose
    ;; <id>.edn after folding it into events.pack, so a packed event's
    ;; bytes must come from its pack slice — else verify/sign/witness see
    ;; nil for a still-present event.
    (let [dir (ticket-dir root ticket-id)
          loose (io/file dir (str event-id ".edn"))]
      (if (.isFile loose)
        (file-bytes loose)
        (some->> (:entries (read-pack-index dir))
                 (some #(when (= event-id (:id %)) %))
                 (pack-slice dir)))))
  (put-sidecar! [_ ticket-id name bytes]
    ;; a sidecar lives beside the event it endorses, exactly as before —
    ;; sha256sum(<id>.edn) still auditable, the .sig./.pqsig./.witness./.pqwitness. sidecar
    ;; alongside; the store seam simply names the operation.
    (let [dir (ticket-dir root ticket-id)]
      (.mkdirs dir)
      (write-atomically! (io/file dir name) bytes)))
  (sidecar-names [_ ticket-id]
    (let [dir (ticket-dir root ticket-id)]
      (if (.isDirectory dir)
        (into [] (comp (filter #(.isFile ^File %))
                       (map #(.getName ^File %))
                       (filter #(or (str/includes? % ".sig.")
                                    (str/includes? % ".pqsig.")
                                    (str/includes? % ".witness.")
                                    (str/includes? % ".pqwitness."))))
              (.listFiles dir))
        [])))
  (read-sidecar [_ ticket-id name]
    (file-bytes (io/file (ticket-dir root ticket-id) name))))

(defn file-store [root] (->FileStore root))
