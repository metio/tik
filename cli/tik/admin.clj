;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.admin
  "Definition, identity, and signature administration: reprocess (re-pin a
  ticket to a new definition, dry-run by default), process sign (publish a
  definition — archive + sign the canonical bytes), attest (a signed claim
  the kernel ignores), actor add (register a signer in the allowed-signers
  registry), and sign (sign this actor's own events). Porcelain over
  tik.cli-core and tik.sign."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.args :refer [actor read-edn-file slurp-existing]]
            [tik.canonical :as canonical]
            [tik.cli-core :refer [append!* archive-process! by-hash-file die load-process
                                  now put-signature! resolve-id root signing-key
                                  stage-delta the-store ticket-ctx]]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.explain :as explain]
            [tik.lint :as lint]
            [tik.process :as process]
            [tik.render :refer [print-problems shash]]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.protocol :as store])
  (:import (java.io File)))
(defn cmd-reprocess
  "reprocess <id> <new.edn> [--apply]: re-pin a ticket to a new process
  definition. Dry-run BY DEFAULT (ADR 0002): a re-pin is a consequence-
  bearing decision, so show the derived-stage diff under the pinned
  definition vs the proposed one before anyone commits. --apply appends
  the signed :process/migrate event and archives the new definition by
  hash. (Distinct from `store migrate`, which converts the storage
  backend — this changes a ticket's rules, not where events live.)"
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        new-file (or (second pos)
                     (die "usage: tik reprocess <id> <new.edn> [--apply]"))
        _ (when-not (.exists (io/file new-file)) (die "no such file:" new-file))
        new-proc (read-edn-file (io/file new-file))
        _ (when (print-problems (lint/lint new-proc))
            (die "refusing to migrate to a definition with lint errors"))
        {:keys [events process]} (ticket-ctx s id)
        t (now)
        old-roles (:process/roles process {})
        new-roles (:process/roles new-proc {})
        before (stage/effective-reached process events t old-roles)
        after (stage/effective-reached new-proc events t new-roles)
        old-hash (process/process-hash process)
        new-hash (process/process-hash new-proc)]
    (when (= old-hash new-hash)
      (die "that is the definition the ticket is already pinned to"))
    (println (str "pinned:   v" (:process/version process) " @ " (shash old-hash) "…"))
    (println (str "proposed: v" (:process/version new-proc) " @ " (shash new-hash) "…"))
    (let [{:keys [gained lost]} (stage-delta before after)]
      (doseq [stage-id lost]
        (println "  - stage" stage-id "would REGRESS (no longer derivable)"))
      (doseq [stage-id gained]
        (println "  + stage" stage-id "would become derivable"))
      (when (= before after)
        (println "  derived stages unchanged")))
    ;; what the new rules would newly demand, for stages lost or blocked
    (doseq [{:keys [stage missing]} (explain/explain new-proc events t new-roles)
            :when (contains? before stage)]
      (println (str "  new blockers for " stage ":"))
      (doseq [r missing]
        (println (str "    ✗ " (explain/reason->text r)))))
    (if-not (:apply opts)
      (println "dry run — nothing recorded. Re-run with --apply to migrate.")
      (do (archive-process! new-proc)
          (append!* s (event/migrate-process
                       {:ticket id :actor (actor opts) :at t
                        :parents (dag/heads events)
                        :version (:process/version new-proc)
                        :process-hash new-hash
                        :reason (:reason opts)})
                    opts)
          (println "migrated — ticket now pins" (shash new-hash) "…")))))

(defn cmd-process
  "process sign <name> [--key K]: publish the current definition —
  archive it content-addressed and sign the archived canonical bytes
  (namespace tik-process, ADR 0015). The hash stays the identity; the
  signature is the authority."
  [{:keys [pos opts]}]
  (let [[sub proc-name] pos]
    (when-not (and (= "sign" sub) proc-name)
      (die "usage: tik process sign <name> [--key K]"))
    (let [key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
          proc (load-process proc-name)
          hash (archive-process! proc)
          f (by-hash-file hash)
          sig (sign/sign! key f hash sign/namespace-process)]
      (println "published" proc-name "@" hash)
      (println "signature" (.getName ^File sig)))))

(defn cmd-attest
  "attest <id> <claim-edn> [--body <edn>]: record an attestation — a
  signed claim whose semantics the kernel ignores (ADR 0009), read by
  lenses and by the v2 :attested-within guard."
  [{:keys [pos opts]}]
  (let [[ticket claim-str] pos
        _ (when-not claim-str (die "usage: tik attest <id> <claim-edn>"))
        s (the-store)
        id (resolve-id s ticket)
        claim (canonical/parse claim-str)
        extra (some-> (:body opts) canonical/parse)]
    (append!* s (event/add-attestation
                 {:ticket id :actor (actor opts) :at (now)
                  :parents (dag/heads (store/events s id))
                  :claim (merge {:claim claim} extra)})
              opts)
    (println "attested" (pr-str claim) "as" (actor opts))))

(defn cmd-actor
  "actor add <name> <pubkey-file>: bind an actor to a key in the store's
  allowed-signers registry (identity ladder rung 1, PLAN §9)."
  [{:keys [pos]}]
  (let [[sub actor-name pubkey-file] pos]
    (when-not (and (= "add" sub) actor-name pubkey-file)
      (die "usage: tik actor add <name> <pubkey-file>"))
    ;; the name is written verbatim into the OpenSSH allowed-signers
    ;; registry (`<name> namespaces="tik-*" <key>`); whitespace, a quote,
    ;; or a newline would split it into a second, attacker-shaped line
    ;; (binding a victim principal to a stray key) or widen the namespace
    ;; restriction — reject it rather than corrupt the trust base
    (when (re-find #"[\s\"\\]" actor-name)
      (die (str "invalid actor name " (pr-str actor-name)
                ": no whitespace, quotes, or backslashes (the name is"
                " written verbatim into the allowed-signers registry)")))
    (let [pubkey (str/trim (slurp-existing "public key" pubkey-file))
          line (sign/allowed-signers-line actor-name pubkey)
          f (io/file (root) "actors")]
      (spit f (str line "
") :append true)
      (println "ok" (sign/fingerprint pubkey)))))

(defn cmd-sign
  "Sign this actor's OWN events that this key has not signed yet. A
  signature is an authorship claim (ADR 0010), so signing another
  actor's events would assert something false — those are skipped."
  [{:keys [pos opts]}]
  (let [s (the-store)
        id (resolve-id s (first pos))
        key (or (signing-key opts) (die "no key: pass --key or set TIK_KEY"))
        me (actor opts)
        fpr (sign/fingerprint (sign/pubkey key))
        names (set (store/sidecar-names s id))
        mine (filter #(= me (:event/actor %)) (store/events s id))
        unsigned (remove #(contains? names (str (:event/id %) ".sig." fpr))
                         mine)]
    (doseq [e unsigned]
      (put-signature! s key id (:event/id e) "sig" sign/namespace-event
                      (store/event-bytes s id (:event/id e))))
    (println "signed" (count unsigned) "event(s) as" me
             (str "(" (count mine) " authored, key " fpr ")"))))
