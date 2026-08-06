;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.bundle
  "The evidence-bundle format, and re-derivation from a bundle alone.

  A bundle is a file store cut down to one ticket and everything that
  ticket's derivation needs: the events, their detached signatures, the
  hash-pinned definition that judges them, the registry the signatures
  check against, and the key bindings that registry rests on. That is
  what makes it evidence rather than an archive — a recipient recomputes
  the answer instead of being told it.

  Nothing here reads an ambient store. Every function takes the
  directory, so one process can re-derive many bundles at once and none
  of them can reach the machine's own tickets.

  Everything the format promises is checked HERE, against the files,
  never taken from the manifest: the manifest declares the packaging
  version and nothing else, because everything else is derivable. A
  bundle arrives from a stranger, so the reader treats it as hostile —
  the archive is unpacked by this namespace rather than by `tar`, the
  shipped `verify.sh` is never executed, and the definition must lint
  before it is allowed to judge anything."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.guard :as guard]
            [tik.identity-trust :as identity-trust]
            [tik.jwks :as jwks]
            [tik.lint :as lint]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store])
  (:import (java.io File InputStream)
           (java.time Instant)
           (java.util.zip GZIPInputStream)))

(def format-name
  "The format's name, so a reader can say what it is holding."
  "tik-evidence-bundle")

(def format-version
  "The on-disk contract's version. Bumped only when a conforming reader
  written against the previous version would compute a different answer
  or fail — see docs/content/evidence/bundle-format.md."
  1)

(def manifest-name "bundle.edn")

(defn manifest-edn
  "The manifest a producer writes. It declares the packaging contract and
  NOTHING else: the ticket, the process, the head and the stage are all
  derivable from the files, and a bundle that declared them would be
  inviting a reader to believe the producer instead of the evidence."
  []
  (str "{:bundle/format " (pr-str format-name)
       " :bundle/version " format-version "}\n"))

(defn manifest
  "The manifest as data. A bundle without one is version 1 — the format
  as it shipped before it had a name, which is the compatibility
  baseline every later version is measured against."
  [dir]
  (let [f (io/file dir manifest-name)]
    (if-not (.isFile f)
      {:format format-name :version 1 :declared? false}
      (let [m (try (canonical/parse (slurp f)) (catch Exception _ nil))]
        {:format (or (:bundle/format m) format-name)
         :version (or (:bundle/version m) 1)
         :declared? true}))))

;; ------------------------------------------------------- unpacking, safely

(def default-limits
  "What an unpacked bundle may cost. A submitted archive is untrusted
  input, so the ceilings are part of the reader, not an afterthought: a
  few kilobytes of gzip expands without bound otherwise."
  {:max-entries 20000
   :max-bytes (* 64 1024 1024)
   :max-entry-bytes (* 16 1024 1024)})

(defn- refuse! [msg]
  (throw (ex-info msg {:reason :bundle/malformed})))

(def ^:private ^String nul (str (char 0)))
(def ^:private nul-or-space (re-pattern (str "[" nul " ]")))

(defn- safe-path
  "`name` as a relative path with no way out of the destination, or a
  refusal. An archive naming `/etc/…` or `../…` is not a bundle with an
  odd layout, it is an attempt to write outside the directory."
  [name]
  (let [parts (remove #(or (str/blank? %) (= "." %)) (str/split name #"/"))]
    (when (str/starts-with? name "/")
      (refuse! (str "refusing an absolute path in the archive: " name)))
    (when (str/includes? name nul)
      (refuse! "refusing an archive entry whose name carries a NUL"))
    (when (some #{".."} parts)
      (refuse! (str "refusing a traversing path in the archive: " name)))
    (vec parts)))

(defn- read-fully
  "Exactly `n` bytes, or a refusal — a truncated archive must not read as
  a short entry."
  ^bytes [^InputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (= off n)
        buf
        (let [r (.read in buf off (- n off))]
          (if (neg? r)
            (refuse! "the archive ends mid-entry")
            (recur (+ off r))))))))

(defn- octal
  "A tar header's octal field. Trailing NUL/space padding is normal."
  [^bytes header off len]
  (let [s (-> (String. header (int off) (int len) "US-ASCII")
              (str/replace nul-or-space ""))]
    (if (str/blank? s) 0 (Long/parseLong s 8))))

(defn- cstr ^String [^bytes header off len]
  (let [^String s (String. header (int off) (int len) "UTF-8")
        i (.indexOf s nul)]
    (if (neg? i) s (subs s 0 i))))

(defn untar-gz!
  "Unpack a gzipped tar into `dest`, reading it ourselves rather than
  handing an untrusted archive to `tar`.

  Only regular files and directories travel: a symlink, a hardlink, a
  device node or a pax/global header is refused by name rather than
  quietly skipped, because a reader that ignores what it does not
  understand cannot say what it verified. Paths are checked before
  anything is created, and entry count and byte totals are capped."
  ([^File tgz ^File dest] (untar-gz! tgz dest default-limits))
  ([^File tgz ^File dest limits]
   (let [{:keys [max-entries max-bytes max-entry-bytes]}
         (merge default-limits limits)
         block 512]
     ;; a file that is not a gzipped tar, or one that stops halfway, is a
     ;; refusal with a sentence — never a ZipException or an EOFException
     ;; reaching a person at a terminal
     (try
     (with-open [in (GZIPInputStream. (io/input-stream tgz))]
       (loop [entries 0 total 0 long-name nil]
         (let [header (try (read-fully in block)
                           (catch clojure.lang.ExceptionInfo _ nil))]
           (cond
             ;; a tar ends with NUL blocks; a stream that simply stops is
             ;; also an end, and everything unpacked so far still stands
             (or (nil? header) (every? zero? header))
             {:entries entries :bytes total}

             (< max-entries entries)
             (refuse! (str "the archive holds more than " max-entries
                           " entries"))

             :else
             (let [raw (char (aget ^bytes header 156))
                   ;; a plain file is type "0"; historic tars write NUL
                   typeflag (if (= raw (char 0)) \0 raw)
                   size (octal header 124 12)
                   padded (* block (quot (+ size (dec block)) block))
                   name (or long-name
                            (let [prefix (cstr header 345 155)
                                  n (cstr header 0 100)]
                              (if (str/blank? prefix) n (str prefix "/" n))))]
               (when (< max-entry-bytes size)
                 (refuse! (str "archive entry " name " is larger than "
                               max-entry-bytes " bytes")))
               (when (< max-bytes (+ total size))
                 (refuse! (str "the archive unpacks to more than " max-bytes
                               " bytes")))
               (case typeflag
                 \0
                 (let [parts (safe-path name)
                       _ (when (empty? parts)
                           (refuse! "refusing an archive file with no name"))
                       ^File f (apply io/file dest parts)
                       body (read-fully in padded)]
                   (io/make-parents f)
                   (with-open [o (io/output-stream f)]
                     (.write o body 0 size))
                   (recur (inc entries) (+ total size) nil))

                 ;; "./" is the archive root and needs nothing created
                 \5 (let [parts (safe-path name)]
                      (when (seq parts)
                        (.mkdirs ^File (apply io/file dest parts)))
                      (recur (inc entries) total nil))

                 ;; GNU long name: the next header's path, as data
                 \L (let [body (read-fully in padded)]
                      (recur entries total (cstr body 0 size)))

                 (refuse! (str "refusing archive entry " name
                               " of type " typeflag
                               " — a bundle carries files and directories"
                               " only"))))))))
     (catch java.io.IOException e
       (refuse! (str "not a readable gzipped archive: " (ex-message e))))))))

(defn open!
  "A bundle as a directory: `src` may already be one, or a .tgz that is
  unpacked into a fresh temp directory. Returns {:dir :tmp} — :tmp is
  the caller's to delete when the work is done."
  ([src] (open! src default-limits))
  ([src limits]
   (let [f (io/file src)]
     (when-not (.exists f)
       (refuse! (str "no such bundle: " src)))
     (if (.isDirectory f)
       {:dir f}
       (let [tmp (.toFile (java.nio.file.Files/createTempDirectory
                           "tik-bundle-read"
                           (make-array java.nio.file.attribute.FileAttribute 0)))]
         (untar-gz! f tmp limits)
         {:dir tmp :tmp tmp})))))

(defn delete-tree!
  "Remove a directory this namespace created."
  [^File dir]
  (when (and dir (.exists dir))
    (doseq [^File f (reverse (file-seq dir))] (.delete f))))

(defn digest
  "The bundle's content address — sha256 over the archive as submitted.
  The cache key a service may keep, because it names bytes that cannot
  change under it."
  [^File f]
  (when (.isFile f)
    (str "sha256-" (canonical/sha256-hex-bytes
                    (java.nio.file.Files/readAllBytes (.toPath f))))))

;; ---------------------------------------------------------------- layout

(defn- files-under [^File dir pred]
  (if-not (.isDirectory dir)
    []
    (vec (sort-by #(.getName ^File %) (filter pred (.listFiles dir))))))

(defn- edn? [^File f] (and (.isFile f) (str/ends-with? (.getName f) ".edn")))

(defn layout
  "Where everything is, checked as we go: exactly one ticket directory,
  the definition its create event pinned, the registry, and the optional
  parts (blobs, key bindings, a role register)."
  [dir]
  (let [tickets (io/file dir "tickets")
        tdirs (filter #(.isDirectory ^File %) (or (.listFiles tickets) []))]
    (when-not (.isDirectory tickets)
      (refuse! "no tickets/ directory — an evidence bundle carries one"))
    (when-not (= 1 (count tdirs))
      (refuse! (str "a version-1 bundle carries exactly one ticket, found "
                    (count tdirs))))
    (let [^File tdir (first tdirs)]
      {:dir dir
       :ticket (.getName tdir)
       :events-dir (io/file tdir "events")
       :blobs-dir (io/file tdir "blobs")
       :actors (io/file dir "actors")
       :roles (io/file dir "roles.edn")
       :identity-dir (io/file dir "identity")
       :definitions-dir (io/file dir "processes" "by-hash")})))

;; ------------------------------------------------------------ the checks

(defn- ok [msg] {:ok? true :msg msg})
(defn- bad [msg] {:ok? false :msg msg})
(defn- note [msg] {:note? true :msg msg})

(defn- hashes-to-name?
  "sha256(bytes) = filename, the property the whole format rests on."
  [^File f strip-edn?]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath f))
        named (cond-> (.getName f) strip-edn? (str/replace #"\.edn$" ""))]
    (= named (str "sha256-" (canonical/sha256-hex-bytes bytes)))))

(defn integrity-checks
  "L0: every stored file's name is the sha256 of its bytes, its bytes are
  exactly the canonical hashed region, and it parses as an event."
  [{:keys [events-dir blobs-dir definitions-dir identity-dir]}]
  (let [event-files (concat (files-under events-dir edn?)
                            (files-under identity-dir edn?))]
    (vec
     (concat
      (for [^File f event-files]
        (if-not (hashes-to-name? f true)
          (bad (str (.getName f) " does not hash to its name"))
          (let [stem (str/replace (.getName f) #"\.edn$" "")
                e (try (fstore/read-event f) (catch Exception ex ex))]
            (cond
              (instance? Exception e)
              (bad (str stem " is not readable as an event: "
                        (ex-message ^Exception e)))

              (not= (slurp f) (canonical/emit (dissoc e :event/id)))
              (bad (str stem " is not exactly the canonical hashed region"))

              (not (event/valid? e))
              (bad (str stem " is not a schema-valid event"))

              :else (ok (str stem " hashes to its name and is schema-valid"))))))
      (for [^File f (files-under definitions-dir edn?)]
        (if (hashes-to-name? f true)
          (ok (str (.getName f) " definition bytes hash to their name"))
          (bad (str (.getName f) " definition does not hash to its name"))))
      (for [^File f (files-under blobs-dir (fn [^File f] (.isFile f)))]
        (if (hashes-to-name? f false)
          (ok (str (.getName f) " blob bytes hash to their name"))
          (bad (str (.getName f) " blob does not hash to its name"))))))))

(defn dag-checks
  "Every parent an event names is present, and the ticket has exactly one
  root. A signature binds an event's BYTES, not its PRESENCE, so without
  this a suppressed interior event leaves every remaining file hashing
  and verifying while the history it commits to is gone."
  [events]
  (let [missing (dag/missing-parents events)
        roots (dag/roots events)]
    [(if (empty? missing)
       (ok "every referenced parent is present")
       (bad (str (count missing) " referenced parent(s) missing: "
                 (str/join ", " (sort missing)))))
     (if (= 1 (count roots))
       (ok "exactly one root event")
       (bad (str "expected exactly one root event, found " (count roots))))]))

(defn- sidecar-files [^File events-dir id kind]
  (files-under events-dir
               (fn [^File f]
                 (and (.isFile f)
                      (str/starts-with? (.getName f) (str id "." kind "."))))))

(defn authorship-checks
  "L1: each detached signature verifies AS THE EVENT'S OWN `:event/actor`,
  never merely as some registered principal — otherwise a registered
  actor forges another's authorship and the audit still passes."
  [{:keys [events-dir actors]} events]
  (if-not (.isFile ^File actors)
    [(note "no actors registry travels with this bundle — authorship is unclaimed")]
    (let [unsigned (atom 0)
          checks (vec (for [e (red/ordered events)
                            :let [id (:event/id e)
                                  sigs (sidecar-files events-dir id "sig")]
                            :when (or (seq sigs) (do (swap! unsigned inc) false))
                            ^File sig sigs
                            :let [bytes (java.nio.file.Files/readAllBytes
                                         (.toPath (io/file events-dir (str id ".edn"))))
                                  sb (java.nio.file.Files/readAllBytes (.toPath sig))
                                  actor (:event/actor e)]]
                        ;; an event names its own author, and the signature
                        ;; must verify AS THAT ACTOR — an event whose actor
                        ;; is missing or is not a name has nothing to bind
                        ;; a signature to, which is a failure and not an
                        ;; error to raise on
                        (if-not (string? actor)
                          (bad (str (.getName sig) " covers an event with no"
                                    " :event/actor to bind it to"))
                          (if (try (sign/verify-bytes actors bytes sb actor)
                                   (catch Exception _ false))
                            (ok (str (.getName sig) " verifies as " actor))
                            (bad (str (.getName sig) " does not verify as its"
                                      " event's actor (" actor ")"))))))]
      (cond-> checks
        (pos? @unsigned)
        (conj (note (str @unsigned " event(s) unsigned — authenticity"
                         " unclaimed, not failed")))))))

(defn witness-checks
  "L3: countersignatures over a head. One signature timestamps the whole
  ancestry that head commits to."
  [{:keys [events-dir actors]} events]
  (let [heads (dag/heads events)
        pairs (for [h heads, ^File sc (sidecar-files events-dir h "witness")]
                [h sc])]
    (if (empty? pairs)
      [(note "no countersigned heads")]
      (vec (for [[h ^File sc] pairs
                 :let [ev (io/file events-dir (str h ".edn"))
                       bytes (java.nio.file.Files/readAllBytes (.toPath ev))
                       sb (java.nio.file.Files/readAllBytes (.toPath sc))
                       who (first (sign/find-principals-bytes
                                   actors bytes sb sign/namespace-witness))]]
             (if (and who (sign/verify-bytes actors bytes sb who
                                             sign/namespace-witness))
               (ok (str (subs h 0 15) "… witnessed by " who
                        " (whole ancestry)"))
               (bad (str (subs h 0 15) "… carries a countersignature that"
                         " does not verify"))))))))

;; ------------------------------------------------------------ rung 2

(defn- pem-files [{:keys [identity-dir]}]
  (into {}
        (map (fn [^File f] [(str/replace (.getName f) #"\.pem$" "") (slurp f)]))
        (files-under (io/file identity-dir "keys")
                     (fn [^File f] (and (.isFile f)
                                        (str/ends-with? (.getName f) ".pem"))))))

(defn bindings
  "The key bindings this bundle carries, read from the excerpted registry
  events under identity/. Their parents are deliberately absent — a
  binding travels as one event, not as its whole registry — so their
  standing rests on L0 plus the issuer's signature, never on the DAG."
  [{:keys [identity-dir]}]
  (vec (for [^File f (files-under identity-dir edn?)
             :let [e (try (fstore/read-event f) (catch Exception _ nil))
                   b (:event/body e)]
             :when (= :identity (:claim b))]
         {:event (str/replace (.getName f) #"\.edn$" "")
          :actor (:identity/actor b)
          :issuer (:identity/issuer b)
          :subject (:identity/subject b)
          :public-key (:identity/public-key b)
          :id-token (:identity/id-token b)
          :at (:event/at e)})))

(defn binding-checks
  "Rung 2, re-earned rather than taken on the producer's word (ADR 0023):
  the id-token must carry the issuer's own signature, be about the
  subject the binding names, have been live when the binding was
  written, and grant a key the registry actually lists.

  An issuer whose key the bundle does not carry is a NOTE: the binding
  grants nothing without it, so it widens no trust — and a bundle that
  failed on it could never be made verifiable again, because events are
  never deleted."
  [{:keys [actors] :as layout*}]
  (let [pems (pem-files layout*)
        registry (when (.isFile ^File actors) (slurp actors))]
    (vec
     (for [b (bindings layout*)
           :let [tok (str (:id-token b))
                 [h p sig] (str/split tok #"\.")
                 parsed (identity-trust/token-claims tok)
                 kid (:kid (try (identity-trust/token-header tok)
                                (catch Exception _ nil)))
                 pem (get pems (str kid))]]
       (cond
         (or (str/blank? (str p)) (str/blank? (str sig)))
         (bad (str "the binding for " (:actor b) " carries a malformed token"))

         (nil? pem)
         (note (str "no pinned key for issuer kid " kid
                    " — the binding for " (:actor b) " grants nothing here"))

         (not (try ((jwks/pem-verifier {(str kid) pem})
                    (str h "." p)
                    (jwks/b64url-bytes sig)
                    (identity-trust/token-header tok))
                   (catch Exception _ false)))
         (bad (str "the issuer did not sign the token in the binding for "
                   (:actor b)))

         (not= (str (:subject b)) (str (:sub parsed)))
         (bad (str "the binding for " (:actor b)
                   " names a subject the token does not"))

         (not= (str (:issuer b)) (str (:iss parsed)))
         (bad (str "the binding for " (:actor b)
                   " names an issuer the token does not"))

         (not (identity-trust/token-live-at? parsed (:at b)))
         (bad (str "the token in the binding for " (:actor b)
                   " was not live when the binding was written"))

         (not (and registry (str/includes? registry (str (:public-key b)))))
         (bad (str "the binding for " (:actor b)
                   " binds a key the registry does not list"))

         :else
         (ok (str (:actor b) " is bound to its key by " (:subject b)
                  ", per " (:issuer b))))))))

(defn signers
  "Who the signatures in this bundle can be checked against, and on whose
  say-so: a line the producer curated, or a key an issuer's own signature
  earned. Whether that separation matters is the reader's call, which is
  why it is reported rather than collapsed into a verdict."
  [{:keys [actors] :as layout*}]
  (let [earned (into {} (map (juxt :public-key identity)) (bindings layout*))]
    (vec (for [line (str/split-lines (if (.isFile ^File actors)
                                       (slurp actors) ""))
               :when (not (str/blank? line))
               :let [[who] (str/split line #"\s+")
                     b (some (fn [[k v]] (when (str/includes? line (str k)) v))
                             earned)]]
           (cond-> {:actor who}
             b (assoc :earned-by {:issuer (:issuer b) :subject (:subject b)}))))))

;; ------------------------------------------------------------ derivation

(defn definition
  "The definition this ticket pinned, read from the bundle and refused
  unless it lints. A definition is a program in the only sense that
  matters — it decides what these facts mean — and this one arrived from
  a stranger, so it passes the same gate an author's does."
  [{:keys [definitions-dir]} state]
  (let [hash (:process-hash state)
        f (io/file definitions-dir (str hash ".edn"))]
    (cond
      (nil? hash)
      (refuse! "the ticket pins no definition, so nothing can judge it")

      (not (.isFile f))
      (refuse! (str "the definition this ticket pinned (" hash
                    ") does not travel with the bundle"))

      :else
      (let [proc (canonical/parse (slurp f))]
        (when-not (= hash (process/process-hash proc))
          (refuse! (str "the definition in the bundle does not hash to "
                        hash)))
        (when-let [errors (seq (filter #(= :error (:level %)) (lint/lint proc)))]
          (refuse! (str "the pinned definition does not lint: "
                        (str/join "; " (map :msg errors)))))
        proc))))

(defn- role-register [{:keys [roles]}]
  (when (.isFile ^File roles)
    (let [r (try (canonical/parse (slurp roles)) (catch Exception _ nil))]
      (when (process/valid-role-bindings? r) r))))

(defn stage-report
  "Each reached stage with the guards that judged it, and whether they
  still hold at `now`. A sticky stage is carried by the fold, so it stays
  reached after its guards stop holding — that is the intent, and saying
  so is the difference between naming a derivation and implying one."
  [proc events reached roles now]
  (let [state (red/ticket-state events)
        ctx {:state state :process proc :now now :roles roles
             :reached reached :fact-memo (volatile! {})}
        sticky (stage/sticky-ids proc)]
    (vec (for [s (:process/stages proc)
               :when (contains? reached (:stage/id s))
               :let [evaluated (mapv (fn [g] [g (guard/eval-guard g ctx)])
                                     (:guards s []))]]
           {:stage (:stage/id s)
            :sticky? (contains? sticky (:stage/id s))
            :holds-now? (every? (comp :satisfied? second) evaluated)
            :guards (mapv (fn [[g r]] {:guard g :satisfied? (:satisfied? r)})
                          evaluated)}))))

(defn checks
  "Every check the format promises, in ladder order. `:ok? false`
  anywhere means the bundle failed."
  [layout* events]
  (vec (concat (integrity-checks layout*)
               (dag-checks events)
               (authorship-checks layout* events)
               (binding-checks layout*)
               (witness-checks layout* events))))

(defn re-derive
  "Read a bundle directory, check it, and re-derive what its facts imply
  at `now`.

  `now` is an argument because the answer depends on it: a guard with a
  freshness window (`:attested-within`, `:elapsed-since`) is satisfied by
  today's evidence and unsatisfied by the same evidence next month, which
  is the property that makes this worth re-deriving rather than
  recording. Whoever renders the result names the instant."
  [dir now]
  (let [layout* (layout dir)
        s (fstore/file-store (str dir))
        id (parse-uuid (:ticket layout*))
        _ (when-not id (refuse! (str "the ticket directory is not a uuid: "
                                     (:ticket layout*))))
        events (store/events s id)
        _ (when (empty? events) (refuse! "the bundle carries no events"))
        state (red/ticket-state events)
        proc (definition layout* state)
        roles (process/resolve-roles (:process/roles proc {})
                                     (role-register layout*))
        reached (stage/effective-reached proc events now roles)
        cs (checks layout* events)]
    {:format (manifest dir)
     :ticket id
     :title (:title state)
     :process (:process state)
     :process-hash (:process-hash state)
     :derived-at now
     :events (count events)
     :head (vec (sort (dag/heads events)))
     :reached (into (sorted-set) (map str) reached)
     :current (into (sorted-set) (map str) (stage/current-stages proc reached))
     :stages (stage-report proc events reached roles now)
     :missing (explain/explain proc events now roles reached)
     :signers (signers layout*)
     :verified? (every? #(or (:ok? %) (:note? %)) cs)
     :checks cs}))

(defn read-bundle
  "`re-derive` over a .tgz or a directory, with the archive's content
  address attached and any temporary directory cleaned up. The one entry
  point porcelain needs."
  ([src] (read-bundle src (Instant/now) default-limits))
  ([src now] (read-bundle src now default-limits))
  ([src now limits]
   (let [{:keys [dir tmp]} (open! src limits)]
     (try (assoc (re-derive dir now) :digest (digest (io/file src)))
          (finally (delete-tree! tmp))))))
