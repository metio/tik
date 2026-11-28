;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.verify
  "The per-ticket verify ladder as data: L0 integrity, L1 authenticity
  (rung 1 `actors`, rung 2 identity bindings, ADR 0037 delegations,
  post-quantum sidecars and the signatures.edn policy), L3 witness
  countersignatures and L2 reproducibility. `tik verify` renders these
  items line by line and tik.api returns them, so the CLI and the library
  judge a ticket by one implementation.

  Nothing here writes, and nothing reads the process-wide store root: the
  caller passes the root, the store and the clocks, so a library caller
  can hand in a read-only store."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.dag :as dag]
            [tik.delegation-trust :as delegation-trust]
            [tik.event :as event]
            [tik.identity-trust :as identity-trust]
            [tik.pqsig :as pqsig]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.render :refer [shash]]
            [tik.sigpolicy :as sigpolicy]
            [tik.sign :as sign]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite :as sqlite])
  (:import (java.io File)
           (java.time Instant)))

(defn sidecar-names-for
  "Sidecar names of one `kind` (\"sig\", \"witness\", \"pqsig\",
  \"pqwitness\") for `target-id`."
  [s ticket-id target-id kind]
  (filterv #(str/starts-with? % (str target-id "." kind "."))
           (store/sidecar-names s ticket-id)))

(defn store-policy
  "The signatures.edn of the store at `root`, parsed (ADR 0036 point 4),
  or nil."
  [root]
  (let [f (io/file root "signatures.edn")]
    (when (.isFile f) (sigpolicy/parse (slurp f)))))

(defn- identity-registry-create? [e]
  (and (= :ticket/create (:event/type e))
       (let [p (get-in e [:event/body :ticket/process])]
         (and (or (keyword? p) (string? p))
              (= "identity-registry" (name p))))))

(defn registry-events
  "Every event of every identity-registry ticket in store `s`.

  The registry is DERIVED, not configured: a ticket pinned to the
  :identity-registry process is one, so a store cannot have a trust base
  that some config file forgot to mention, and adding a second registry
  needs no setting. A ticket that cannot be read is skipped rather than
  fatal: a corrupt unrelated ticket must not make every signature
  unverifiable, and leaving a registry out only ever grants fewer keys."
  [s]
  (into []
        (mapcat (fn [id]
                  (try (let [evs (store/events s id)]
                         ;; the create event names the process, so this
                         ;; costs a scan rather than a fold
                         (when (some identity-registry-create? evs)
                           evs))
                       (catch Exception _ nil))))
        (store/ticket-ids s)))

(defn line
  "The text `tik verify` prints for one ladder item."
  [{:keys [kind ok? text]}]
  (case kind
    :section text
    :check (str (if ok? "  ok    " "  FAIL  ") text)
    :note (str "  note  " text)
    :skip (str "  skip  " text)))

(defn failures
  "The failing checks among `items`."
  [items]
  (filterv #(and (= :check (:kind %)) (not (:ok? %))) items))

(defn definition-items
  "The checks of one archived definition, processes/by-hash/<stem>.edn:
  its bytes hash to its name (ADR 0007 applied to governance), and every
  publication signature (namespace tik-process, ADR 0015) verifies as a
  principal `actors` registers. An unsigned definition is a note."
  [root stem]
  (let [dir (io/file root "processes" "by-hash")
        f (io/file dir (str stem ".edn"))
        signers (io/file root "actors")
        sigs (sign/sidecars dir stem)]
    (into [{:kind :check :level :l3 :check :definition-hash :definition stem
            :ok? (= stem (canonical/address-of-text (slurp f)))
            :text (str (shash stem) "… definition bytes hash to filename")}]
          (if (empty? sigs)
            [{:kind :note :level :l3 :note :unsigned-definition :definition stem
              :text (str (shash stem) "… unsigned definition (tik process sign)")}]
            (for [sig sigs
                  :let [who (and (.exists signers)
                                 (first (sign/find-principals
                                         signers f sig sign/namespace-process)))]]
              {:kind :check :level :l3 :check :definition-publication :definition stem
               :ok? (boolean (and who (sign/verify signers f sig who
                                                   sign/namespace-process)))
               :publisher who
               :text (str (shash stem) "… published by "
                          (or who "<unregistered key>"))})))))

(defn- l0-sqlite [db id check]
  ;; the raw BLOB must be the exact hashed region — checked against
  ;; storage, not against this process's parsing (ADR 0020)
  (doseq [[eid hex] (sqlite/raw-rows db id)
          :let [raw (try (String. ^bytes (sqlite/hex->bytes hex) "UTF-8")
                         (catch Exception ex
                           (check false :unreadable eid
                                  (str (shash eid) "… row bytes unreadable: "
                                       (ex-message ex)))
                           nil))
                e (when raw
                    (try (assoc (canonical/parse raw) :event/id eid)
                         (catch Exception ex
                           (check false :unreadable eid
                                  (str (shash eid) "… row unreadable: "
                                       (ex-message ex)))
                           nil)))]
          :when e]
    (check (= eid (canonical/address-of-text raw)) :hash eid
           (str (shash eid) "… hash(stored bytes) = id"))
    (check (= raw (canonical/emit (dissoc e :event/id))) :canonical eid
           (str (shash eid) "… bytes are exactly the hashed region"))
    (check (event/valid? e) :schema eid (str (shash eid) "… schema-valid"))))

(defn- l0-files [root id check]
  (let [dir (io/file root "tickets" (str id) "events")
        files (filter (fn [^File f]
                        (and (.isFile f)
                             (str/ends-with? (.getName f) ".edn")))
                      (.listFiles dir))]
    (when-let [{:keys [entries pack] :as index} (fstore/read-pack-index dir)]
      (let [pack-bytes (java.nio.file.Files/readAllBytes
                        (.toPath (fstore/pack-file-of dir index)))]
        (check (= pack (canonical/address-of-bytes pack-bytes)) :pack nil
               (str "pack " (shash pack)
                    "… bytes hash to the index's pack address"))
        (doseq [{:keys [id] :as entry} entries
                :let [slice (try (fstore/pack-slice dir index entry)
                                 (catch clojure.lang.ExceptionInfo _ nil))]]
          (check (and slice (= id (canonical/address-of-bytes slice))) :hash id
                 (str (shash id) "… packed slice hashes to id")))))
    (doseq [^File f files
            :let [stem (str/replace (.getName f) #"\.edn$" "")
                  e (try (fstore/read-event f)
                         (catch Exception ex
                           (check false :unreadable stem
                                  (str stem " unreadable: " (ex-message ex)))
                           nil))]
            :when e
            :let [bytes-on-disk (slurp f)]]
      (check (= bytes-on-disk (canonical/emit (dissoc e :event/id))) :canonical stem
             (str stem " bytes are exactly the canonical hashed region"))
      (check (= stem (event/event-id e)) :hash stem
             (str stem " sha256(bytes) = filename = id"))
      (check (event/valid? e) :schema stem (str stem " schema-valid")))))

(defn- l1-signatures
  "Rung 1 (the curated actors registry) plus rung 2: keys a verified
  identity binding grants, each only for events claimed inside the window
  of the token that bound it. Returns the ids of the events a signature by
  their own actor verified, for the signature policy."
  [{:keys [root store id registry-events]} evs item! check]
  (let [classical-ok (atom #{})
        signers-at (identity-trust/signer-view root registry-events)
        ;; ADR 0037: a key outside the registry still counts for a
        ;; verdict a trusted delegation covers, and for nothing else
        delegations (delegation-trust/assess root store registry-events)]
    (doseq [d delegations
            :when (not (:trusted? d))]
      (item! {:kind :note :level :l1 :note :delegation-untrusted
              :text (str "delegation " (shash (:event d)) "… by " (:actor d)
                         " credits nothing: " (:why d))}))
    (if-not signers-at
      (item! {:kind :skip :level :l1 :note :no-actors
              :text "no actors registry (tik actor add <name> <key.pub>)"})
      (let [unsigned (atom 0)]
        (doseq [e (red/ordered evs)
                :let [names (sidecar-names-for store id (:event/id e) "sig")]]
          (if (empty? names)
            (swap! unsigned inc)
            (let [ev-bytes (store/event-bytes store id (:event/id e))]
              (doseq [name names
                      :let [sig (store/read-sidecar store id name)
                            verdict (when (and ev-bytes sig)
                                      (when-let [signers (signers-at (:event/at e))]
                                        (sign/verify-text signers ev-bytes sig
                                                          (:event/actor e)
                                                          sign/namespace-event)))
                            direct (true? verdict)
                            delegated (when (and (not direct) ev-bytes sig)
                                        (delegation-trust/credit delegations e ev-bytes sig))]]
                (when (or direct (some? delegated))
                  (swap! classical-ok conj (:event/id e)))
                (check (or direct (some? delegated)) :signature (:event/id e)
                       (str (shash (:event/id e)) "… signed by "
                            (:event/actor e)
                            (when delegated
                              (str " (delegated by " (shash (:event delegated)) "…)")))
                       {:actor (:event/actor e)
                        :sidecar name
                        :verdict (cond direct :verified
                                       delegated :delegated
                                       (nil? verdict) :unchecked
                                       :else :failed)
                        :delegation (:event delegated)})))))
        (when (pos? @unsigned)
          (item! {:kind :note :level :l1 :note :unsigned :count @unsigned
                  :text (str @unsigned " event(s) unsigned"
                             " (authenticity unclaimed, not failed)")}))))
    @classical-ok))

(defn- l1-post-quantum
  "ADR 0036: every post-quantum sidecar present is checked against
  pq-signers, a countersignature seals its ancestry, and the store policy
  says which events must carry which signatures."
  [{:keys [root store id]} evs classical-ok item! check]
  (let [pq-ok (atom #{})
        pq-file (io/file root "pq-signers")
        pq-sidecars (for [e (red/ordered evs)
                          name (sidecar-names-for store id (:event/id e) "pqsig")]
                      [e name])
        witnesses (for [e (red/ordered evs)
                        name (sidecar-names-for store id (:event/id e) "pqwitness")]
                    [e name])
        registry (when (and (or (seq pq-sidecars) (seq witnesses)) (.exists pq-file))
                   (try (pqsig/parse-registry (slurp pq-file))
                        (catch clojure.lang.ExceptionInfo ex
                          (check false :pq-registry nil (ex-message ex))
                          nil)))
        anchors (atom #{})
        policy (store-policy root)]
    (when (seq pq-sidecars)
      (if-not (.exists pq-file)
        (item! {:kind :skip :level :l1 :note :no-pq-signers
                :text (str (count pq-sidecars) " post-quantum signature(s),"
                           " no pq-signers registry")})
        (when registry
          (doseq [[e name] pq-sidecars
                  :let [holds? (pqsig/check-event
                                registry
                                {:actor (:event/actor e)
                                 :fpr (pqsig/sidecar-fpr name)
                                 :signature (store/read-sidecar store id name)
                                 :event-bytes (store/event-bytes store id (:event/id e))
                                 :namespace sign/namespace-event})]]
            (when holds? (swap! pq-ok conj (:event/id e)))
            (check holds? :pq-signature (:event/id e)
                   (str (shash (:event/id e)) "… post-quantum signed by "
                        (:event/actor e))
                   {:actor (:event/actor e) :sidecar name})))))
    ;; ADR 0036 point 7: an ML-DSA-65 countersignature over an event
    ;; seals its whole ancestry
    (doseq [[e name] witnesses
            :let [who (when registry
                        (pqsig/check-witness
                         registry
                         {:fpr (pqsig/sidecar-fpr name)
                          :signature (store/read-sidecar store id name)
                          :event-bytes (store/event-bytes store id (:event/id e))}))]]
      (when who (swap! anchors conj (:event/id e)))
      (check (some? who) :pq-witness (:event/id e)
             (str (shash (:event/id e)) "… post-quantum witnessed"
                  (if who (str " by " who " (whole ancestry)")
                      " by a key pq-signers does not allow to witness"))
             {:sidecar name :witness who}))
    (when policy
      (item! {:kind :section :level :l1-policy :text "L1 signature policy (signatures.edn)"})
      (let [verified (into {} (for [e evs
                                    :let [i (:event/id e)]]
                                [i (cond-> #{}
                                     (classical-ok i) (conj :ssh-ed25519)
                                     (@pq-ok i) (conj :ml-dsa-65))]))
            rows (sigpolicy/judge policy evs {:verified verified :anchors @anchors})
            failed (remove :ok? rows)]
        (doseq [{:keys [msg]} failed] (check false :policy nil msg))
        (when (empty? failed)
          (check true :policy nil (str "every event meets signatures.edn ("
                                       (count evs) " checked)")))))))

(defn- l3-witnesses [{:keys [root store id]} evs item! check]
  (let [signers (io/file root "actors")
        heads (dag/heads evs)
        witnessed (for [head heads
                        name (sidecar-names-for store id head "witness")]
                    [head name])]
    (if (empty? witnessed)
      (item! {:kind :note :level :l3 :note :no-witness
              :text "no countersigned heads (tik witness <id>)"})
      (doseq [[head name] witnessed
              :let [ev-bytes (store/event-bytes store id head)
                    sig (store/read-sidecar store id name)
                    who (and (.exists signers) ev-bytes sig
                             (first (sign/find-principals-bytes
                                     signers ev-bytes sig
                                     sign/namespace-witness)))]]
        (check (boolean (and who (sign/verify-bytes signers ev-bytes sig who
                                                    sign/namespace-witness)))
               :witness head
               (str (shash head) "… witnessed by "
                    (or who "<unregistered key>")
                    " (whole ancestry)")
               {:sidecar name :witness who})))))

(defn- l2-reproducibility [{:keys [load-process role-register now]} evs check]
  (let [state (red/ticket-state evs)
        proc (load-process state)]
    (check (or (nil? (:process-hash state))
               (= (:process-hash state) (process/process-hash proc)))
           :pinned-definition nil
           "pinned process hash resolves to its definition")
    ;; an audit reports and continues — a derivation that raises is a
    ;; FAIL line for THIS ticket, never an aborted audit
    (try
      (let [reached (stage/effective-reached proc evs (now)
                                             (process/resolve-roles
                                              (:process/roles proc {})
                                              (role-register)))]
        (check true :derivation nil
               (str "derived: " (str/join ", " (map str (sort-by str reached))))
               {:reached (vec (sort-by str reached))}))
      (catch Exception e
        (check false :derivation nil (str "derivation raises: " (ex-message e)))))))

(defn ticket-ladder
  "The verify ladder for ticket `id` as a vector of items, in the order
  `tik verify <id>` prints them. Each item has :kind (:section, :check,
  :note or :skip), :level and :text; a check also has :ok?, :check (what
  it judged) and, where it is about one event, :event.

  `ctx` keys:
    :root            the store root (actors, pq-signers, signatures.edn,
                     oidc.edn and jwks/ are read from it)
    :store           the EventStore
    :db              the SQLite database the store reads, or nil for a
                     file store
    :id              the ticket uuid
    :registry-events the store's identity-registry events
    :load-process    state -> the definition the ticket derives under
    :role-register   thunk -> the store's role register, or nil
    :now             thunk -> the verifier's Instant"
  [{:keys [root store db id now] :as ctx}]
  (let [items (transient [])
        item! (fn [m] (conj! items m))
        level (atom :l0)
        check (fn
                ([ok? what event text] (item! (cond-> {:kind :check :level @level
                                                       :ok? (boolean ok?) :check what
                                                       :text text}
                                                event (assoc :event event))))
                ([ok? what event text extra]
                 (item! (merge (cond-> {:kind :check :level @level
                                        :ok? (boolean ok?) :check what :text text}
                                 event (assoc :event event))
                               extra))))
        section! (fn [lv text] (reset! level lv) (item! {:kind :section :level lv :text text}))]
    (section! :l0 "L0 integrity")
    (if db
      (l0-sqlite db id check)
      (l0-files root id check))
    ;; the audit reports and continues even when the event SET cannot be
    ;; assembled — L0 above already named the offending file or row
    (when-let [evs (try (store/events store id)
                        (catch Exception e
                          (check false :events nil (str "events unreadable: "
                                                        (ex-message e)))
                          nil))]
      (check (empty? (dag/missing-parents evs)) :parents nil "all parents present")
      (check (= 1 (count (dag/roots evs))) :root nil "exactly one root (:ticket/create)")
      ;; A claimed time ahead of the verifier's clock is ADMISSIBLE
      ;; (ADR 0011 — the log holds every well-formed claim) and
      ;; consequential: :at is the reduction sort key AND the instant
      ;; guards evaluate at, so such an event sorts last until its time
      ;; arrives and satisfies :elapsed-since on the spot — permanently
      ;; where the stage it gates is sticky. Noted, never failed.
      (let [t (now)
            ahead (filterv #(let [a (:event/at %)]
                              (and (instance? Instant a)
                                   (.isAfter ^Instant a ^Instant t)))
                           evs)]
        (when (seq ahead)
          (item! {:kind :note :level :l0 :note :ahead :count (count ahead)
                  :text (str (count ahead) " event(s) claim a time ahead"
                             " of this clock (latest "
                             (:event/at (last (sort-by :event/at ahead)))
                             ") — these sort last and satisfy elapsed-since early")})))
      (section! :l1 "L1 authenticity")
      (let [classical-ok (l1-signatures ctx evs item! check)]
        (l1-post-quantum ctx evs classical-ok item! check))
      (section! :l3 "L3 provenance (witness countersignatures)")
      (l3-witnesses ctx evs item! check)
      (section! :l2 "L2 reproducibility")
      (l2-reproducibility ctx evs check))
    (persistent! items)))
