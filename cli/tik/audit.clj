;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.audit
  "The audit surface: the verify ladder (L0 hash / L1 signatures / L3
  definitions) over one ticket or the whole store, witness/root
  countersignatures, the self-contained evidence bundle (events +
  signatures + a coreutils verify.sh), and export. All read-side: it
  proves what the signed log says, and never writes authoritative state."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [db-path die exit! load-pinned-process load-ticket
                                  now put-signature! resolve-id root root-dir-roots
                                  signing-key sidecar-names-for store-root-doc
                                  the-store]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.render :refer [shash sid tint]]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite :as sqlite])
  (:import (java.io File)))

(declare verify-ticket)

(defn cmd-export
  "Materialize the current store (whatever backend) as a file/git store
  at <dir> — the auditor-grade interchange format where sha256sum(file)
  = filename. Events AND their detached signature/witness sidecars travel
  (so the export verifies authorship standalone, like `store migrate`);
  blobs and the actors registry are filesystem artifacts under TIK_ROOT
  and copy with cp."
  [{:keys [pos]}]
  (let [target (or (first pos) (die "usage: tik export <dir>"))
        dir (io/file target)
        _ (when (and (not (.isDirectory dir)) (not (.mkdirs dir)))
            (die (str "cannot create export directory: " target)))
        src (the-store)
        dest (fstore/file-store target)
        [n sc]
        (try
          (let [ids (store/ticket-ids src)
                n (reduce (fn [n id]
                            (reduce (fn [n e] (store/append! dest e) (inc n))
                                    n (store/events src id)))
                          0 ids)
                sc (reduce (fn [c id]
                             (reduce (fn [c name]
                                       (store/put-sidecar!
                                        dest id name (store/read-sidecar src id name))
                                       (inc c))
                                     c (store/sidecar-names src id)))
                           0 ids)]
            [n sc])
          (catch java.io.IOException e
            (die (str "export to " target " failed: " (ex-message e)))))]
    (println "exported" n "event(s)," sc "sidecar(s) to" target)))

(defn cmd-witness
  "witness <id> [--key K]: countersign every current head — a detached
  <head>.witness.<fpr> sidecar over the head event's stored bytes. One
  signature timestamps the entire ancestry the head commits to
  (ADR 0004); observation, not authorship, hence its own namespace."
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
        fpr (sign/fingerprint (sign/pubkey key))
        heads (dag/heads (store/events s id))]
    (doseq [head heads
            :let [name (str head ".witness." fpr)]]
      (when-not (some #{name} (store/sidecar-names s id))
        (put-signature! s key id head "witness" sign/namespace-witness
                        (store/event-bytes s id head)))
      (println "witnessed" (shash head) "…"))
    (println (count heads) "head(s) countersigned — each timestamps its"
             "entire ancestry")))

(defn cmd-root
  "root [--witness [--key K]]: one hash for the whole store. Two
  replicas agree iff their roots agree — O(1) comparison over millions
  of tickets; a witness countersignature over the root timestamps
  everything at once. Only the SIDECAR is kept (roots/): the root
  document is derived and regenerates byte-identically, so verification
  re-derives it and checks the signature against fresh bytes."
  [{:keys [opts]}]
  (let [s (the-store)
        doc (store-root-doc s)
        bytes (canonical/emit doc)
        root (str "sha256-" (canonical/sha256-hex bytes))]
    (println root (str "(" (count doc) " tickets)"))
    (when (:witness opts)
      (let [key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
            dir (io/file (root-dir-roots))
            f (io/file dir (str root ".edn"))]
        (io/make-parents f)
        (spit f bytes)
        (let [produced (sign/sign! key f (str root ".witness-tmp")
                                   sign/namespace-witness)
              target (io/file dir (str root ".witness."
                                       (sign/fingerprint (sign/pubkey key))))]
          (.renameTo ^File produced target)
          ;; the doc regenerates; only the endorsement is worth keeping
          (io/delete-file f)
          (println "witnessed" (shash root) "… ->" (.getName target)))))
    (when (:anchor opts)
      ;; DELIBERATELY light integration: anchoring shells out to the
      ;; external `ots` tool if — and only if — the operator installed
      ;; it. tik ships no timestamping dependency, mentions no chain in
      ;; its surfaces, and works identically without this flag; the
      ;; .ots sidecar is just one more detached endorsement for those
      ;; who want third-party timestamping (PLAN §10).
      (when-not (zero? (:exit (sh/sh "sh" "-c" "command -v ots")))
        (die (str "--anchor needs the external `ots` tool"
                  " (opentimestamps-client) — entirely optional;"
                  " everything else works without it")))
      (let [dir (io/file (root-dir-roots))
            f (io/file dir (str root ".edn"))
            ots-target (io/file dir (str root ".ots"))]
        (io/make-parents f)
        (spit f bytes)
        (let [r (sh/sh "ots" "stamp" (str f))]
          (if (and (zero? (:exit r))
                   (.exists (io/file (str f ".ots"))))
            (do (.renameTo (io/file (str f ".ots")) ots-target)
                (println "anchored" (shash root)
                         "… ->" (.getName ots-target)
                         "(upgrade after ~1 block: ots upgrade)"))
            (println "anchor failed (calendars unreachable?):"
                     (str/trim (str (:err r))))))
        (io/delete-file f)))))

(defn verify-roots
  "Every kept root countersignature must verify against the FRESHLY
  re-derived root document — if the store changed since witnessing,
  the root it attests simply is not this store's current root (which
  is fine; roots are moments), but the signature must still verify
  against its own root's regenerated bytes when the root matches."
  [s check]
  (let [dir (root-dir-roots)
        signers (io/file (root) "actors")]
    (when (.isDirectory dir)
      (println "roots")
      (let [current-doc (store-root-doc s)
            current-bytes (canonical/emit current-doc)
            current-root (str "sha256-" (canonical/sha256-hex current-bytes))]
        (doseq [^File sc (.listFiles dir)
                :when (str/includes? (.getName sc) ".witness.")
                :let [attested-root (first (str/split (.getName sc)
                                                      #"\.witness\."))]]
          (if (= attested-root current-root)
            (let [tmp (io/file dir (str attested-root ".edn"))]
              (spit tmp current-bytes)
              (let [who (and (.exists signers)
                             (first (sign/find-principals
                                     signers tmp sc
                                     sign/namespace-witness)))]
                (check (boolean (and who (sign/verify signers tmp sc who
                                                      sign/namespace-witness)))
                       (str (shash attested-root)
                            "… CURRENT root witnessed by " (or who "<unregistered>"))))
              (io/delete-file tmp))
            (println (str "  note  " (shash attested-root)
                          "… witnessed root is historical (store has"
                          " grown since)"))))))))

(def bundle-verify-sh
  "POSIX shell + coreutils + ssh-keygen — no tik required. The bundle
  is evidence precisely because the recipient needs nothing of ours to
  check it."
  "#!/bin/sh
# Verifies this tik evidence bundle. Requires only coreutils and
# ssh-keygen (OpenSSH 8.2+). Run from anywhere: ./verify.sh
set -u
cd \"$(dirname \"$0\")\"
fail=0

# L0 integrity: every stored file's name IS the sha256 of its bytes.
for f in tickets/*/events/*.edn processes/by-hash/*.edn; do
  [ -e \"$f\" ] || continue
  base=$(basename \"$f\" .edn)
  sum=$(sha256sum \"$f\" | cut -d' ' -f1)
  if [ \"sha256-$sum\" = \"$base\" ]; then
    echo \"ok    $base bytes match their name\"
  else
    echo \"FAIL  $f bytes do not match their name\"; fail=1
  fi
done
for f in tickets/*/blobs/*; do
  [ -e \"$f\" ] || continue
  base=$(basename \"$f\")
  sum=$(sha256sum \"$f\" | cut -d' ' -f1)
  if [ \"sha256-$sum\" = \"$base\" ]; then
    echo \"ok    $base blob bytes match their name\"
  else
    echo \"FAIL  $f blob bytes do not match their name\"; fail=1
  fi
done

# L1 authenticity: detached signatures against the actors registry.
check_sig() { # $1 sidecar, $2 target file, $3 namespace
  p=$(ssh-keygen -Y find-principals -f actors -n \"$3\" -s \"$1\" < \"$2\" 2>/dev/null)
  if [ -z \"$p\" ]; then
    echo \"FAIL  $1 signed by a key absent from the actors registry\"; fail=1; return
  fi
  if ssh-keygen -Y verify -f actors -I \"$p\" -n \"$3\" -s \"$1\" < \"$2\" >/dev/null 2>&1; then
    echo \"ok    $(basename \"$1\") verifies as $p\"
  else
    echo \"FAIL  $1 does not verify\"; fail=1
  fi
}
# An event names its own author in :event/actor; the signature must
# verify AS THAT ACTOR, not merely as some registered principal —
# else a registered actor forges another's authorship and the audit
# still passes. This binds -I to the claimed author, matching the
# in-process L1 check (sign/verify-bytes uses -I actor). Canonical
# bytes sort :event/actor first, so it always leads the file.
check_event_sig() { # $1 sidecar, $2 event .edn
  actor=$(sed -n 's/^{:event\\/actor \"\\([^\"]*\\)\".*/\\1/p' \"$2\")
  if [ -z \"$actor\" ]; then
    echo \"FAIL  $2 has no :event/actor to bind its signature to\"; fail=1; return
  fi
  # find-principals only sharpens the diagnostic (registered? vs bad sig);
  # the verification DECISION binds to the claimed actor below, never to
  # whoever happened to sign — that is the whole point.
  if ! ssh-keygen -Y find-principals -f actors -n tik-event -s \"$1\" < \"$2\" >/dev/null 2>&1; then
    echo \"FAIL  $1 signed by a key absent from the actors registry\"; fail=1; return
  fi
  if ssh-keygen -Y verify -f actors -I \"$actor\" -n tik-event -s \"$1\" < \"$2\" >/dev/null 2>&1; then
    echo \"ok    $(basename \"$1\") verifies as $actor\"
  else
    echo \"FAIL  $1 does not verify as its event's actor ($actor)\"; fail=1
  fi
}
for sig in tickets/*/events/*.sig.*; do
  [ -e \"$sig\" ] || continue
  check_event_sig \"$sig\" \"${sig%%.sig.*}.edn\"
done
for sig in tickets/*/events/*.witness.*; do
  [ -e \"$sig\" ] || continue
  check_sig \"$sig\" \"${sig%%.witness.*}.edn\" tik-witness
done
for sig in processes/by-hash/*.sig.*; do
  [ -e \"$sig\" ] || continue
  check_sig \"$sig\" \"${sig%%.sig.*}.edn\" tik-process
done

# DAG completeness: every referenced parent must be present, and there
# must be exactly one root (empty :event/parents). A signature binds an
# event's BYTES, not its PRESENCE — without this, deleting a referenced
# interior event leaves every remaining file hashing and verifying, yet
# suppresses history the Merkle DAG is meant to commit to. This mirrors
# what `tik verify` enforces (all parents present, exactly one root).
present=$(mktemp)
for f in tickets/*/events/*.edn; do
  [ -e \"$f\" ] || continue
  basename \"$f\" .edn >> \"$present\"
done
roots=0
for f in tickets/*/events/*.edn; do
  [ -e \"$f\" ] || continue
  pids=$(grep -o ':event/parents #{[^}]*}' \"$f\" | grep -o 'sha256-[0-9a-f]\\{64\\}')
  if [ -z \"$pids\" ]; then
    roots=$((roots + 1))
  else
    for pid in $pids; do
      if ! grep -qxF \"$pid\" \"$present\"; then
        echo \"FAIL  $(basename \"$f\") references missing parent $pid\"; fail=1
      fi
    done
  fi
done
rm -f \"$present\"
if [ \"$roots\" != 1 ]; then
  echo \"FAIL  expected exactly one root event (empty parents), found $roots\"; fail=1
fi

if [ \"$fail\" = 0 ]; then echo 'bundle: PASS'; else echo 'bundle: FAIL'; exit 1; fi
")


(defn bundle-readme [id title process-hash]
  (str "<!--\nSPDX-FileCopyrightText: The tik Authors\n"
       "SPDX-License-Identifier: 0BSD\n-->\n\n"
       "# Evidence bundle: " title "\n\n"
       "Ticket `" id "` as a self-contained, independently verifiable\n"
       "artifact. Nothing here requires tik or trusts its producer:\n\n"
       "- `tickets/…/events/*.edn` — the append-only log. Each file's\n"
       "  NAME is the sha256 of its BYTES, and parents inside each event\n"
       "  chain them into a Merkle DAG: one head commits to all history.\n"
       "- `*.sig.*` — detached authorship signatures (`ssh-keygen -Y`).\n"
       "- `*.witness.*` — third-party countersignatures over a head:\n"
       "  one signature timestamps the entire ancestry.\n"
       "- `processes/by-hash/" process-hash ".edn` — the exact ruleset\n"
       "  the ticket pinned at creation, plus publication signatures.\n"
       "- `actors` — the allowed-signers registry the signatures check\n"
       "  against. Verify its keys out of band; it names who, the\n"
       "  signatures prove that they, and the hashes prove what.\n\n"
       "## Verify\n\n"
       "```sh\n./verify.sh\n```\n\n"
       "coreutils + ssh-keygen only. To additionally REPLAY the\n"
       "derivation (what stage these facts imply), install tik and run\n"
       "`tik export`/`tik verify` over this directory — derivation is a\n"
       "pure function of these files, so any tik, anywhere, forever,\n"
       "derives the same answer.\n"))

(defn cmd-bundle
  "The evidence bundle (PLAN §10): one ticket as a portable artifact a
  third party verifies with coreutils + ssh-keygen — no tik, no trust
  in us. This is H5's deliverable and the thing H8 sells."
  [{:keys [pos opts]}]
  (let [ticket (or (first pos) (die "usage: tik bundle <id> [--out file.tgz]"))
        s (the-store)
        {:keys [id state]} (load-ticket s ticket)
        phash (:process-hash state)
        out (io/file (or (:out opts) (str "tik-bundle-" (sid id) ".tgz")))
        work (.toFile (java.nio.file.Files/createTempDirectory
                       "tik-bundle" (make-array java.nio.file.attribute.FileAttribute 0)))
        bdir (io/file work "bundle")
        copy! (fn [^File src ^File dst]
                (when (.exists src)
                  (if (.isDirectory src)
                    (doseq [^File f (.listFiles src)
                            :when (.isFile f)]
                      (io/make-parents (io/file dst (.getName f)))
                      (io/copy f (io/file dst (.getName f))))
                    (do (io/make-parents dst) (io/copy src dst)))))]
    ;; materialize LOOSE <id>.edn events (event-bytes is pack-aware) plus
    ;; their sidecars, rather than copying the on-disk dir — a packed store
    ;; has no loose .edn for the bundle's coreutils verify.sh to sha256sum,
    ;; so a bundle of a packed ticket would otherwise fail its own verify.
    (let [evdir (io/file bdir "tickets" (str id) "events")]
      (.mkdirs evdir)
      (doseq [e (store/events s id)
              :let [eid (:event/id e)]]
        (io/copy (store/event-bytes s id eid) (io/file evdir (str eid ".edn"))))
      (doseq [name (store/sidecar-names s id)]
        (io/copy (store/read-sidecar s id name) (io/file evdir name))))
    (copy! (io/file (root) "tickets" (str id) "blobs")
           (io/file bdir "tickets" (str id) "blobs"))
    (copy! (io/file (root) "actors") (io/file bdir "actors"))
    (when phash
      (doseq [^File f (.listFiles (io/file (root) "processes" "by-hash"))
              :when (str/starts-with? (.getName f) phash)]
        (copy! f (io/file bdir "processes" "by-hash" (.getName f)))))
    (spit (io/file bdir "verify.sh") bundle-verify-sh)
    (.setExecutable (io/file bdir "verify.sh") true)
    (spit (io/file bdir "README.md")
          (bundle-readme id (:title state) (or phash "unpinned")))
    (let [r (sh/sh "tar" "czf" (str (.getAbsoluteFile out))
                   "-C" (str bdir) ".")]
      (when-not (zero? (:exit r)) (die (str "tar failed: " (:err r)))))
    (println (str "wrote " out))
    (println "verify anywhere with: tar xzf, then ./verify.sh (coreutils + ssh-keygen only)")))

(defn verify-definitions
  "Audit processes/by-hash/: every archived definition's bytes hash to
  its filename (the ADR 0007 property applied to governance), and every
  publication signature (namespace tik-process, ADR 0015) verifies
  against a registered principal."
  [check]
  (let [dir (io/file (root) "processes" "by-hash")
        signers (io/file (root) "actors")]
    (when (.isDirectory dir)
      (println "definitions")
      (doseq [^File f (.listFiles dir)
              :when (str/ends-with? (.getName f) ".edn")
              :let [stem (str/replace (.getName f) #"\.edn$" "")]]
        (check (= stem (str "sha256-" (canonical/sha256-hex (slurp f))))
               (str (shash stem) "… definition bytes hash to filename"))
        (let [sigs (sign/sidecars dir stem)]
          (if (empty? sigs)
            (println (str "  note  " (shash stem)
                          "… unsigned definition (tik process sign)"))
            (doseq [sig sigs
                    :let [who (and (.exists signers)
                                   (first (sign/find-principals
                                           signers f sig
                                           sign/namespace-process)))]]
              (check (boolean
                      (and who (sign/verify signers f sig who
                                            sign/namespace-process)))
                     (str (shash stem) "… published by "
                          (or who "<unregistered key>"))))))))))

(defn cmd-verify
  "The verify ladder. With a ticket id: that ticket. With no arguments:
  the WHOLE STORE — every ticket plus every archived definition and its
  publication signatures. One command, complete audit."
  [{:keys [pos] :as parsed}]
  (if (empty? pos)
    (let [s (the-store)
          ids (store/ticket-ids s)
          failures (atom 0)
          check (fn [ok? msg]
                  (when-not ok?
                    (println (tint "31" (str "  FAIL  " msg)))
                    (swap! failures inc)))
          ;; --changed: skip tickets whose present event-id set matches
          ;; the last full audit. A disposable accelerator (ADR 0013): it
          ;; detects DRIFT — any added OR removed event, including a
          ;; pruned inner ancestor that leaves the heads unchanged — but
          ;; NOT in-place tampering of already-audited bytes (same ids,
          ;; different content). The full run remains the audit, and
          ;; always outranks. (A head-only fingerprint would miss a
          ;; deleted diamond-interior event, whose id still appears as a
          ;; parent so the tips do not move.)
          cache-file (io/file (root) ".verify-cache")
          cache (if (and (:changed (:opts parsed)) (.exists cache-file))
                  (try (canonical/parse (slurp cache-file))
                       (catch Exception _ {}))
                  {})
          skipped (atom 0)
          verified (atom {})]
      (doseq [id ids
              ;; even LISTING a ticket's events can raise on a hostile
              ;; store; the audit reports that ticket and continues
              :let [fp (try (into (sorted-set)
                                  (map :event/id) (store/events s id))
                            (catch Exception _ ::unreadable))]]
        (if (= fp (get cache id))
          (do (swap! skipped inc)
              (swap! verified assoc id fp))
          (let [r (try (with-out-str (verify-ticket parsed id))
                       (catch Exception e
                         (str "  FAIL  " (sid id)
                              " unverifiable: " (ex-message e) "\n")))]
            (if (str/includes? r "FAIL")
              (do (print r) (swap! failures inc))
              (do (swap! verified assoc id fp)
                  (println (tint "2" (sid id))
                           (tint "32" "ok")
                           (str (count (store/events s id)) " event(s)")))))))
      (verify-definitions check)
      (verify-roots s check)
      (when (pos? @skipped)
        (println (tint "2" (str "skipped " @skipped " ticket(s) with"
                                " unchanged heads — drift check only;"
                                " run without --changed for the audit"))))
      (if (zero? @failures)
        (do (spit cache-file (pr-str @verified))
            (println (tint "32" (str "verify: PASS (" (count ids)
                                     " tickets)"))))
        (do (println (tint "31" (str "verify: FAIL (" @failures ")")))
            (exit! 1))))
    (let [f (verify-ticket parsed (resolve-id (the-store) (first pos)))]
      (verify-definitions
       (fn [ok? msg]
         (println (str (if ok? "  ok    " "  FAIL  ") msg))
         (when-not ok? (exit! 1))))
      (when (pos? f) (exit! 1)))))

(defn verify-ticket
  "The per-ticket ladder; prints, exits nonzero on failure when run for
  a single ticket."
  [{:keys []} id]
  (let [s (the-store)
        dir (io/file (root) "tickets" (str id) "events")
        files (filter (fn [^File f]
                        (and (.isFile f)
                             (str/ends-with? (.getName f) ".edn")))
                      (.listFiles dir))
        failures (atom 0)
        check (fn [ok? msg]
                (println (str (if ok? "  ok    " "  FAIL  ") msg))
                (when-not ok? (swap! failures inc)))]
    (println "L0 integrity")
    (if-let [db (db-path)]
      ;; SQLite: the raw BLOB must be the exact hashed region — checked
      ;; against storage, not against this process's parsing (ADR 0020)
      (doseq [[eid hex] (sqlite/raw-rows db id)
              :let [raw (try (String. ^bytes (sqlite/hex->bytes hex) "UTF-8")
                             (catch Exception ex
                               (check false (str (shash eid)
                                                 "… row bytes unreadable: "
                                                 (ex-message ex)))
                               nil))
                    e (when raw
                        (try (assoc (canonical/parse raw) :event/id eid)
                             (catch Exception ex
                               (check false (str (shash eid)
                                                 "… row unreadable: "
                                                 (ex-message ex)))
                               nil)))]
              :when e]
        (check (= eid (str "sha256-" (canonical/sha256-hex raw)))
               (str (shash eid) "… hash(stored bytes) = id"))
        (check (= raw (canonical/emit (dissoc e :event/id)))
               (str (shash eid) "… bytes are exactly the hashed region"))
        (check (event/valid? e) (str (shash eid) "… schema-valid")))
      (do
        (when-let [{:keys [entries pack]} (fstore/read-pack-index dir)]
          (let [pack-bytes (java.nio.file.Files/readAllBytes
                            (.toPath (io/file dir "events.pack")))]
            (check (= pack (str "sha256-"
                                (canonical/sha256-hex-bytes pack-bytes)))
                   (str "pack " (shash pack)
                        "… bytes hash to the index's pack address"))
            (doseq [{:keys [id] :as entry} entries
                    :let [slice (fstore/pack-slice dir entry)]]
              (check (= id (str "sha256-"
                                (canonical/sha256-hex-bytes slice)))
                     (str (shash id) "… packed slice hashes to id")))))
        (doseq [^File f files
                :let [stem (str/replace (.getName f) #"\.edn$" "")
                      e (try (fstore/read-event f)
                             (catch Exception ex
                               (check false (str stem " unreadable: "
                                                 (ex-message ex)))
                               nil))]
                :when e
                :let [bytes-on-disk (slurp f)]]
          (check (= bytes-on-disk (canonical/emit (dissoc e :event/id)))
                 (str stem " bytes are exactly the canonical hashed region"))
          (check (= stem (event/event-id e))
                 (str stem " sha256(bytes) = filename = id"))
          (check (event/valid? e) (str stem " schema-valid")))))
    ;; the audit reports and continues even when the event SET cannot be
    ;; assembled — L0 above already named the offending file/row
    (when-let [evs (try (store/events s id)
                        (catch Exception e
                          (check false (str "events unreadable: "
                                            (ex-message e)))
                          nil))]
      (check (empty? (dag/missing-parents evs)) "all parents present")
      (check (= 1 (count (dag/roots evs))) "exactly one root (:ticket/create)")
      (println "L1 authenticity")
      (let [signers (io/file (root) "actors")]
        (if-not (.exists signers)
          (println "  skip  no actors registry (tik actor add <name> <key.pub>)")
          (let [unsigned (atom 0)]
            (doseq [e (red/ordered evs)
                    :let [names (sidecar-names-for s id (:event/id e) "sig")]]
              (if (empty? names)
                (swap! unsigned inc)
                (let [ev-bytes (store/event-bytes s id (:event/id e))]
                  (doseq [name names
                          :let [sig (store/read-sidecar s id name)]]
                    (check (boolean
                            (and ev-bytes sig
                                 (sign/verify-bytes signers ev-bytes sig
                                                    (:event/actor e))))
                           (str (shash (:event/id e)) "… signed by "
                                (:event/actor e)))))))
            (when (pos? @unsigned)
              (println (str "  note  " @unsigned " event(s) unsigned"
                            " (authenticity unclaimed, not failed)"))))))
      (println "L3 provenance (witness countersignatures)")
      (let [signers (io/file (root) "actors")
            heads (dag/heads evs)
            witnessed (for [head heads
                            name (sidecar-names-for s id head "witness")]
                        [head name])]
        (if (empty? witnessed)
          (println "  note  no countersigned heads (tik witness <id>)")
          (doseq [[head name] witnessed
                  :let [ev-bytes (store/event-bytes s id head)
                        sig (store/read-sidecar s id name)
                        who (and (.exists signers) ev-bytes sig
                                 (first (sign/find-principals-bytes
                                         signers ev-bytes sig
                                         sign/namespace-witness)))]]
            (check (boolean (and who (sign/verify-bytes signers ev-bytes sig who
                                                        sign/namespace-witness)))
                   (str (shash head) "… witnessed by "
                        (or who "<unregistered key>")
                        " (whole ancestry)")))))
      (println "L2 reproducibility")
      (let [state (red/ticket-state evs)
            proc (load-pinned-process state)]
        (check (or (nil? (:process-hash state))
                   (= (:process-hash state) (process/process-hash proc)))
               "pinned process hash resolves to its definition")
        ;; an audit reports and continues — a derivation that raises is
        ;; a FAIL line for THIS ticket, never an aborted audit
        (try
          (let [reached (stage/effective-reached proc evs (now)
                                                 (:process/roles proc {}))]
            (println (str "  ok    derived: "
                          (str/join ", " (map str (sort-by str reached))))))
          (catch Exception e
            (check false (str "derivation raises: " (ex-message e)))))))
    (if (zero? @failures)
      (println "verify: PASS")
      (println (str "verify: FAIL (" @failures ")")))
    @failures))


