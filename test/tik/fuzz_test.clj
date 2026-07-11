;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.fuzz-test
  "The adversarial layer of the test stack: the other four oracles
  feed tik VALID inputs and check the answers; this one feeds garbage
  and checks the manner of failure. The contract everywhere is `fail
  well`: a clean, data-carrying rejection (ex-info or a schema error)
  — never an unexpected exception class, and above all never a silent
  pass. TIK_FUZZ_N scales the iteration counts (default keeps the
  gate fast; crank it for a soak run)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.author]
            [tik.dag]
            [tik.canonical :as canonical]
            [tik.cli]
            [tik.dupe]
            [tik.event :as event]
            [tik.explain]
            [tik.work :as work]
            [tik.gen-events :as ge]
            [tik.guard :as guard]
            [tik.reduce :as red]
            [tik.stage :as stage]
            [tik.store.file :as fstore]
            [tik.store.protocol :as store]
            [tik.store.sqlite])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(def ^:private n
  (or (some-> (System/getenv "TIK_FUZZ_N") parse-long) 50))

(defn- fails-well?
  "Run the thunk; true when it returns normally or throws ex-info.
  Any other throwable is a fuzz finding."
  [thunk]
  (try (thunk) true
       (catch clojure.lang.ExceptionInfo _ true)
       (catch Throwable _ false)))

;; --------------------------------------------------- malformed events

(def gen-garbage
  "Structural garbage: scalars, maps, and vectors that are shaped
  nothing like (or deceptively like) an event."
  (gen/one-of
   [gen/any-equatable
    (gen/map gen/keyword gen/any-equatable {:max-elements 6})
    ;; deceptive: a real event with one key nuked or one value mangled
    (gen/let [e (gen/elements
                 [{:event/ticket (random-uuid) :event/type :fact/assert
                   :event/actor "seb" :event/at (Instant/now)
                   :event/parents #{"sha256-x"}
                   :event/body {:fact/path [:x] :fact/value 1}}])
              k (gen/elements [:event/ticket :event/type :event/actor
                               :event/at :event/parents])
              v gen/any-equatable]
      (gen/one-of [(gen/return (dissoc e k))
                   (gen/return (assoc e k v))]))]))

(defspec malformed_events_are_rejected_never_crash_never_pass n
  (prop/for-all [garbage gen-garbage]
    (and (fails-well? #(event/mint garbage))
         ;; mint must never bless garbage: either it throws, or the
         ;; input happened to be a fully valid event map
         (try (event/valid? (event/mint garbage))
              (catch clojure.lang.ExceptionInfo _ true)))))

;; --------------------------------------------------- malformed guards

(def gen-garbage-guard
  "A guard vector with a real-or-nonsense operator and arbitrary args —
  the domain generator for guard/eval-guard and the fixpoint in the
  totality registry below."
  (gen/one-of
   [gen/any-equatable
    (gen/vector gen/keyword 0 4)
    (gen/let [op (gen/elements [:fact :fact= :artifact :signed-by
                                :stage-reached :elapsed-since :and :or
                                :not :nonsense])
              args (gen/vector gen/any-equatable 0 3)]
      (gen/return (into [op] args)))]))

;; ------------------------------------------------- corrupted stores

(deftest any_single_byte_flip_is_caught_by_the_stored_identity
  ;; ADR 0007's whole point, fuzzed: filename = sha256(bytes), so no
  ;; corruption of any event file can go unnoticed. We flip one byte
  ;; at every position of every event in a real store and require the
  ;; identity check to catch each one.
  (let [dir (.toFile (Files/createTempDirectory
                      "tik-fuzz" (make-array FileAttribute 0)))
        s (fstore/file-store (str dir))
        ticket (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")
        evs (event/chain
             (fn [_] (event/create-ticket {:ticket ticket :actor "seb"
                                           :at t :title "fuzz"
                                           :process :p}))
             #(event/assert-fact {:ticket ticket :actor "seb"
                                  :at (.plusSeconds t 1) :parents %
                                  :path [:x] :value 1}))
        _ (doseq [e evs] (store/append! s e))
        files (filter #(and (.isFile ^java.io.File %)
                            (str/ends-with? (.getName ^java.io.File %)
                                            ".edn"))
                      (file-seq (io/file dir "tickets")))
        caught (atom 0) total (atom 0)]
    (is (= 2 (count files)))
    (doseq [^java.io.File f files
            :let [bytes (Files/readAllBytes (.toPath f))
                  id (str/replace (.getName f) #"\.edn$" "")]
            i (range (alength bytes))]
      (let [mutated (aclone ^bytes bytes)]
        (aset-byte mutated i (unchecked-byte (bit-xor (aget mutated i) 1)))
        (swap! total inc)
        (when (not= id (str "sha256-"
                            (canonical/sha256-hex-bytes mutated)))
          (swap! caught inc))))
    (is (= @total @caught)
        "every single-byte flip must change the content address")))

;; ------------------------------------------------ the porcelain parsers

(def ^:private cli-parse-value @#'tik.cli/parse-value)
(def ^:private cli-parse-key @#'tik.cli/parse-key)

(defspec parse-key-is-total-over-plausible-keys n
  (prop/for-all [s (gen/such-that seq gen/string-alphanumeric)]
    (vector? (cli-parse-key s))))

(defspec similarity-is-total-and-bounded n
  (prop/for-all [a (gen/one-of [gen/string (gen/return nil)])
                 b (gen/one-of [gen/string (gen/return nil)])]
    (let [score (tik.dupe/similarity a b)]
      (and (<= 0.0 score 1.0)
           (= score (tik.dupe/similarity b a))))))

;; ------------------------------------- the totality registry

(def ^:private gen-hostile-events
  "Events whose parent references may form cycles or dangle —
  impossible to MINT (hashes), trivial for a hostile store to write."
  (gen/fmap
   (fn [edges]
     (mapv (fn [[id parents]]
             {:event/id id :event/parents (set parents)
              :event/at (Instant/parse "2026-01-01T00:00:00Z")
              :event/type :fact/assert
              :event/ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef"
              :event/actor "x" :event/body {:fact/path [:x] :fact/value 1}})
           edges))
   (gen/map (gen/fmap #(str "id" %) gen/small-integer)
            (gen/set (gen/fmap #(str "id" %) gen/small-integer)
                     {:max-elements 3})
            {:max-elements 8})))

(def ^:private one (partial gen/fmap vector))   ; a one-argument arg-vector

(def totality-registry
  "The explicit set of PURE boundary functions that promise TOTALITY:
  over their whole domain they return a value or throw ex-info — never
  another Throwable, never a silent wrong answer. One table so the
  boundary set is auditable in one place and one property (below)
  enforces the floor uniformly; a new untrusted-input surface joins
  here or it is not covered.

  Each entry is {:label :f :gen}; :gen produces the full argument
  VECTOR that :f is applied to. The generator is shaped to the
  function's real domain — feeding gen/any to a fn whose domain is
  event maps would only ever exercise the reject path — so both the
  compute and the reject sides are hit. I/O boundaries (the store
  readers, run-argv, the MCP loop) need filesystem/process context and
  are driven by their own deftests, not this pure-args table."
  [{:label "canonical/emit" :f canonical/emit
    :gen (one gen/any-equatable)}
   {:label "canonical/content-address" :f canonical/content-address
    :gen (one gen/any-equatable)}
   {:label "canonical/check-nesting" :f canonical/check-nesting
    :gen (one gen/string)}
   {:label "event/mint" :f event/mint
    :gen (one gen-garbage)}
   {:label "reduce/ticket-state" :f red/ticket-state
    :gen (gen/let [garbage (gen/vector gen-garbage 0 5)
                   valid ge/gen-events]
           [(into (vec valid) garbage)])}
   {:label "guard/eval-guard" :f guard/eval-guard
    :gen (gen/let [g gen-garbage-guard
                   events ge/gen-events]
           [g {:state (red/ticket-state events) :now (Instant/now)
               :roles ge/roles :reached #{}}])}
   {:label "stage/effective-reached" :f stage/effective-reached
    :gen (gen/let [stages (gen/vector
                           (gen/let [id gen/keyword
                                     guards (gen/vector gen-garbage-guard 0 2)]
                             {:stage/id id :guards guards})
                           0 4)
                   events ge/gen-events]
           [{:process/id :fuzz :process/version 1 :process/stages stages}
            events (Instant/now) ge/roles])}
   {:label "cli/parse-value" :f cli-parse-value
    :gen (one gen/string)}
   {:label "cli/parse-key" :f cli-parse-key
    :gen (one gen/string)}
   {:label "dupe/similarity" :f tik.dupe/similarity
    :gen (gen/tuple (gen/one-of [gen/string (gen/return nil)])
                    (gen/one-of [gen/string (gen/return nil)]))}
   {:label "author/check" :f tik.author/check
    :gen (one gen/any-equatable)}
   {:label "work/usage-totals" :f work/usage-totals
    :gen (gen/fmap (fn [records] [records {"m" {:input 3.0}}])
                   (gen/vector (gen/map gen/keyword gen/any-equatable
                                        {:max-elements 4})
                               0 4))}
   {:label "dag/heads" :f tik.dag/heads :gen (one gen-hostile-events)}
   {:label "dag/roots" :f tik.dag/roots :gen (one gen-hostile-events)}
   {:label "dag/missing-parents" :f tik.dag/missing-parents
    :gen (one gen-hostile-events)}
   {:label "explain/render" :f tik.explain/render
    :gen (gen/fmap (fn [reasons]
                     [[{:stage :fuzz :satisfied [] :missing reasons
                        :blocks #{}}]])
                   (gen/vector (gen/map gen/keyword gen/any-equatable
                                        {:max-elements 4})
                               0 4))}])

(defspec every_registered_boundary_is_total (* 3 n)
  ;; pick a boundary, generate an argument vector in ITS domain, apply,
  ;; and force any lazy result — the answer must be a value or an
  ;; ex-info, never another Throwable. The label rides along so a
  ;; failure names the function, not just an opaque fn object.
  (prop/for-all [[label f args]
                 (gen/let [{:keys [label f gen]} (gen/elements totality-registry)
                           args gen]
                   [label f args])]
    (let [result (fails-well?
                  #(let [r (apply f args)]
                     (when (seqable? r) (doall r))
                     nil))]
      (when-not result (println "NOT TOTAL:" label "on" (pr-str args)))
      result)))

;; -------------------------------- hash-valid garbage in a store file

(deftest hash_valid_garbage_is_rejected_cleanly_at_read_time
  ;; byte flips are caught by the name/content check — the sharper
  ;; attack stores GARBAGE whose name honestly hashes its bytes. The
  ;; reader must reject it with a data-carrying error, never explode.
  (let [dir (.toFile (Files/createTempDirectory
                      "tik-fuzz2" (make-array FileAttribute 0)))
        ticket (random-uuid)
        evdir (io/file dir "tickets" (str ticket) "events")]
    (.mkdirs evdir)
    (doseq [payload ["%%% not edn at all"
                     "{:not-an-event true}"
                     "[1 2 3"
                     "#=(java.lang.Runtime/getRuntime)"]]
      (let [bytes (.getBytes ^String payload "UTF-8")
            name (str "sha256-" (canonical/sha256-hex-bytes bytes) ".edn")]
        (java.nio.file.Files/write
         (.toPath (io/file evdir name)) ^bytes bytes
         ^"[Ljava.nio.file.OpenOption;"
         (make-array java.nio.file.OpenOption 0))))
    (let [s (fstore/file-store (str dir))]
      (is (fails-well? #(doall (red/ticket-state (store/events s ticket))))
          "reading a hostile store must be a clean rejection")
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (red/ticket-state (store/events s ticket))))
          "and specifically an ex-info, not a silent empty fold"))))

;; --------------------------------------------------- the argv surface

(deftest hostile_argv_never_stack_traces
  ;; every invocation a confused human or hostile script can produce
  ;; must exit 0 or 1 with WORDS on stderr — a stack trace on the
  ;; first contact is an H9 killer and a fuzz finding
  (let [root (.toFile (Files/createTempDirectory
                       "tik-argv" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        zalgo (apply str "ch" (map char [0x0341 0x0327 0x036 97 111 115]))
        run (fn [& args]
              (apply sh/sh
                     (concat ["bb" "tik"] args
                             [:dir repo
                              :env (assoc (into {} (System/getenv))
                                          "TIK_ROOT" (str root)
                                          "TIK_ACTOR" "fuzz")])))]
    (doseq [argv [["set"] ["set" "="] ["set" "x" "=y"]
                  ["status"] ["explain"] ["log"] ["causal"]
                  ["new" "\ud83c\udfab"] ["new" "track" "--title"]
                  ["ls" "--where" "="] ["ls" "--filter" "%%%"]
                  ["query"] ["query" "fact"]
                  ["whatif" "zzzz" "+NOT-A-DURATION"]
                  ["migrate" "zzzz"] ["bundle"] ["witness" "zzzz"]
                  ["attest" "zzzz"] ["work" "??"]
                  ["author" "--from" "/nonexistent.edn"]
                  ["author" "check"] ["rollout"] ["probe" "zzzz"]
                  ["lint" " "] ["--" "--" "--"]
                  [zalgo]]]
      (let [r (apply run argv)]
        (is (contains? #{0 1} (:exit r)) (pr-str argv))
        (is (not (re-find #"Exception in thread|clojure\.lang\.|StackTrace|\tat "
                          (str (:out r) (:err r))))
            (str (pr-str argv) "\n" (:err r)))))))

;; -------------------------------------------- garbage config files

(deftest garbage_configs_answer_with_words_never_traces
  ;; every EDN file an operator can typo must produce a message naming
  ;; the file and exit 1 — the top-level landing guarantees no trace
  (let [root (.toFile (Files/createTempDirectory
                       "tik-cfg" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        run (fn [& args]
              (apply sh/sh (concat ["bb" "tik"] args
                                   [:dir repo
                                    :env (assoc (into {} (System/getenv))
                                                "TIK_ROOT" (str root)
                                                "TIK_ACTOR" "fuzz")])))
        garbage "{:unclosed [vector :and (garbage"]
    (.mkdirs (io/file root "processes"))
    (doseq [[file argv]
            [["effects.edn" ["effects" "run"]]
             ["bridge.edn" ["bridge" "email"]]
             ["authoring-rules.edn" ["author" "prompt"]]
             ["answers.edn" ["author" "check" "answers.edn"]]
             ["processes/broken.edn" ["new" "broken"]]
             ["processes/broken.edn" ["lint" "processes/broken.edn"]]]]
      (spit (io/file root file) garbage)
      (let [r (if (= "email" (second argv))
                (apply run (concat argv [:in "From: a@b\nSubject: x\n\nhi"]))
                (apply run argv))]
        (is (= 1 (:exit r)) (pr-str [file argv]))
        (is (re-find #"malformed EDN in .*" (str (:err r)))
            (pr-str [file argv (:err r)]))
        (is (not (re-find #"Exception in thread|clojure\.lang\.|\tat "
                          (str (:out r) (:err r))))
            (pr-str [file argv]))))
    (testing "a garbage context marker fails new with the file named"
      ;; markers are read only when cwd sits beneath the store holder,
      ;; so this invocation runs FROM the store directory
      (spit (io/file root ".tik-facts.edn") garbage)
      (.mkdirs (io/file root "tickets"))
      (let [sub (doto (io/file root "inside") (.mkdirs))
            r (sh/sh "bb" "--config" (str repo "/bb.edn")
                     "tik" "new" "track" "--title" "x"
                     :dir (str sub)
                     :env (assoc (into {} (System/getenv))
                                 "TIK_ROOT" (str root)
                                 "TIK_ACTOR" "fuzz"))]
        (is (= 1 (:exit r)) (:err r))
        (is (re-find #"malformed EDN" (str (:err r))))))))

(deftest one_dead_sink_does_not_abandon_the_rest
  (let [root (.toFile (Files/createTempDirectory
                       "tik-sink" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        outfile (io/file root "delivered.json")
        run (fn [& args]
              (apply sh/sh (concat ["bb" "tik"] args
                                   [:dir repo
                                    :env (assoc (into {} (System/getenv))
                                                "TIK_ROOT" (str root)
                                                "TIK_ACTOR" "fuzz")])))]
    (run "new" "track" "--title" "sink isolation")
    (spit (io/file root "effects.edn")
          (pr-str {:sinks [{:type :slack
                            :url "http://127.0.0.1:1/unroutable"}
                           {:type :command
                            :command ["sh" "-c" (str "cat >> " outfile)]}]}))
    (let [r (run "effects" "run" "--config"
                 (str (io/file root "effects.edn")))]
      (is (= 1 (:exit r)) "delivery failures exit nonzero")
      (is (re-find #"failed slack .* will retry next run" (str (:err r))))
      (is (.exists outfile)
          "the healthy sink delivered despite the dead one")
      (is (not (re-find #"Exception in thread|\tat " (str (:err r))))))))

;; ------------------------------- packs, caches, hostile directory names

(defspec minted_events_always_read_back n
  ;; the corrupt-on-write guard: whatever survives mint must re-emit
  ;; to the same bytes after a plain EDN read — no store can gain a
  ;; hash-valid but forever-unreadable file through the front door
  (prop/for-all [v gen/any-equatable]
    (let [minted (try (event/assert-fact
                       {:ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef"
                        :actor "seb" :at (Instant/parse "2026-01-01T00:00:00Z")
                        :parents #{"sha256-x"} :path [:x] :value v})
                      (catch clojure.lang.ExceptionInfo _ ::refused))]
      (or (= ::refused minted)
          (let [bytes (canonical/emit (dissoc minted :event/id))]
            (= bytes (canonical/emit (edn/read-string bytes))))))))

(deftest hostile_directory_names_never_corrupt_a_store
  (let [top (.toFile (Files/createTempDirectory
                      "tik-dirs" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        run (fn [dir & args]
              (apply sh/sh (concat ["bb" "--config" (str repo "/bb.edn") "tik"]
                                   args
                                   [:dir (str dir)
                                    :env (assoc (into {} (System/getenv))
                                                "TIK_ACTOR" "fuzz")])))]
    (doseq [evil ["my repo" "wei{rd" "tick] et"]]
      (.mkdirs (io/file top evil ".git")))
    (run top "init" "--hidden")
    (testing "rollout across hostile names: strings, not broken keywords"
      (let [r (run top "rollout" "track")]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"3 ticket\(s\) created" (:out r)))))
    (testing "context facts from inside a hostile dir"
      (let [inside (io/file top "my repo")
            r (run inside "new" "track" "--title" "from within")]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"context: repo=\"my repo\"" (str (:err r))))))
    (testing "the whole store still verifies"
      (is (re-find #"verify: PASS" (:out (run top "verify")))))))

(deftest lying_pack_indexes_fail_well
  (let [dir (.toFile (Files/createTempDirectory
                      "tik-pack-fuzz" (make-array FileAttribute 0)))
        s (fstore/file-store (str dir))
        ticket (random-uuid)
        t (Instant/parse "2026-01-01T00:00:00Z")
        evs (event/chain
             (fn [_] (event/create-ticket {:ticket ticket :actor "seb"
                                           :at t :title "packed"
                                           :process :p}))
             #(event/assert-fact {:ticket ticket :actor "seb"
                                  :at (.plusSeconds t 1) :parents %
                                  :path [:x] :value 1}))
        _ (doseq [e evs] (store/append! s e))
        _ (fstore/pack! (str dir) ticket)
        evdir (io/file dir "tickets" (str ticket) "events")
        idx (io/file evdir "events.pack.idx")
        good (slurp idx)]
    (testing "the packed store reads and folds"
      (is (= 2 (count (store/events s ticket)))))
    (testing "garbage index fails well"
      (spit idx "{{{ not edn")
      (is (fails-well? #(doall (store/events s ticket))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (store/events s ticket)))))
    (testing "an index lying about offsets fails well, never EOFs raw"
      (spit idx (str/replace good #":offset 0" ":offset 999999"))
      (is (fails-well? #(doall (store/events s ticket)))))
    (testing "a truncated pack fails well"
      (spit idx good)
      (spit (io/file evdir "events.pack") "short")
      (is (fails-well? #(doall (store/events s ticket)))))))

(deftest corrupt_caches_are_misses_never_crashes
  (let [root (.toFile (Files/createTempDirectory
                       "tik-cache-fuzz" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        run (fn [& args]
              (apply sh/sh (concat ["bb" "tik"] args
                                   [:dir repo
                                    :env (assoc (into {} (System/getenv))
                                                "TIK_ROOT" (str root)
                                                "TIK_ACTOR" "fuzz")])))]
    (run "new" "track" "--title" "cache fodder")
    (run "ls")                                   ; builds the cache
    (doseq [payload ["%%% not json" "[1,2,3]" "{\"x\": {\"row\": 7}}"
                     "{\"unclosed\": "]]
      (spit (io/file root ".derived-cache.json") payload)
      (let [r (run "ls")]
        (is (zero? (:exit r)) payload)
        (is (re-find #"cache fodder" (:out r)) payload)))))

;; ------------------------------- hostile DAGs and poisoned boards

(defspec ticket_state_folds_a_hostile_dag n
  ;; the dag graph walks (heads/roots/missing-parents) are in the
  ;; totality registry; this pins the extra: the reducer FOLDS a set of
  ;; events whose parents cycle or dangle — parents are integrity, not
  ;; order, so a broken DAG must still fold, never loop
  (prop/for-all [events gen-hostile-events]
    (fails-well? #(red/ticket-state events))))

(defspec reason_rendering_is_a_string_over_garbage n
  ;; render is in the registry for totality; this asserts the STRONGER
  ;; shape contract: whatever reasons arrive — including keys from tik
  ;; versions that do not exist yet — render answers with a STRING
  (prop/for-all [reasons (gen/vector
                          (gen/map gen/keyword gen/any-equatable
                                   {:max-elements 4})
                          0 4)]
    (string? (tik.explain/render
              [{:stage :fuzz :satisfied [] :missing reasons :blocks #{}}]))))

(deftest one_poisoned_ticket_never_hides_the_healthy
  (let [root (.toFile (Files/createTempDirectory
                       "tik-poison" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        run (fn [& args]
              (apply sh/sh (concat ["bb" "tik"] args
                                   [:dir repo
                                    :env (assoc (into {} (System/getenv))
                                                "TIK_ROOT" (str root)
                                                "TIK_ACTOR" "fuzz")])))]
    (.mkdirs (io/file root "processes"))
    (spit (io/file root "processes" "poison.edn")
          (pr-str {:process/id :poison :process/version 1
                   :process/guard-vocab 1 :lint {:runbooks :off}
                   :process/stages
                   [{:stage/id :a :guards []}
                    {:stage/id :b :after [:a]
                     :guards [[:elapsed-since :ticket/create "garbage"]]}]}))
    (run "new" "track" "--title" "healthy neighbor")
    (run "new" "poison" "--title" "the poisoned one")
    (testing "lint refuses the poison at authoring time"
      (is (re-find #"not ISO-8601"
                   (:out (run "lint" "processes/poison.edn")))))
    (testing "ls shows the healthy ticket AND a visible error row"
      (let [r (run "ls")]
        (is (zero? (:exit r)))
        (is (re-find #"healthy neighbor" (:out r)))
        (is (re-find #"error\s+cannot derive" (:out r)))))
    (testing "next works around the poison and says so"
      (let [r (run "next" "--actor" "fuzz")]
        (is (zero? (:exit r)))
        (is (re-find #"skipping .*malformed guard" (str (:err r))))
        (is (re-find #"outcome" (:out r)))))
    (testing "verify reports the poison as a FAIL line and finishes"
      (let [r (run "verify")]
        (is (re-find #"FAIL  derivation raises" (:out r)))
        (is (re-find #"verify: FAIL" (:out r)))
        (is (re-find #"ok 1 event\(s\)" (:out r))
            "the audit covered the healthy ticket too")))))

;; ---------------- recursion bombs, sqlite hostility, signature sidecars

(deftest recursion_bombs_are_rejected_never_overflow
  ;; the emitter and every EDN reader recurse per nesting level, so a
  ;; 100k-deep vector is a stack bomb — and StackOverflowError is an
  ;; Error no fail-well guard catches. Write side and read side must
  ;; both answer with ex-info (or a value) long before the stack does
  (let [deep-value (reduce (fn [acc _] (vector acc)) 1 (range 100000))
        deep-text (str (str/join (repeat 100000 "["))
                       "1"
                       (str/join (repeat 100000 "]")))]
    (testing "emit rejects cleanly"
      (is (thrown? clojure.lang.ExceptionInfo (canonical/emit deep-value))))
    (testing "mint rejects cleanly"
      (is (thrown? clojure.lang.ExceptionInfo
                   (event/assert-fact
                    {:ticket (random-uuid) :actor "x" :at (Instant/now)
                     :parents #{"sha256-x"} :path [:x] :value deep-value}))))
    (testing "check-nesting rejects the raw text before any reader"
      (is (thrown? clojure.lang.ExceptionInfo
                   (canonical/check-nesting deep-text))))
    (testing "brackets inside strings and char literals don't count"
      (is (nil? (canonical/check-nesting
                 "{:a \"[[[[[[\" :b \\[ :c [1 2 3]}"))))
    (testing "parse-value answers with the literal string"
      (is (= deep-text (cli-parse-value deep-text))))))

(deftest hash_valid_recursion_bomb_in_a_store_fails_well
  ;; the sharpest form: a store file whose name honestly hashes a
  ;; 100k-deep vector. Reading must reject with words; the whole-store
  ;; audit must report THAT ticket and still finish
  (let [root (.toFile (Files/createTempDirectory
                       "tik-bomb" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        run (fn [& args]
              (apply sh/sh (concat ["bb" "tik"] args
                                   [:dir repo
                                    :env (assoc (into {} (System/getenv))
                                                "TIK_ROOT" (str root)
                                                "TIK_ACTOR" "fuzz")])))]
    (run "new" "track" "--title" "healthy neighbor")
    (run "new" "track" "--title" "the bombed one")
    (let [evdirs (->> (io/file root "tickets") .listFiles
                      (map #(io/file % "events")))
          bomb (.getBytes (str (str/join (repeat 100000 "["))
                               "1"
                               (str/join (repeat 100000 "]")))
                          "UTF-8")
          name (str "sha256-" (canonical/sha256-hex-bytes bomb) ".edn")]
      (java.nio.file.Files/write
       (.toPath (io/file (first evdirs) name)) ^bytes bomb
       ^"[Ljava.nio.file.OpenOption;"
       (make-array java.nio.file.OpenOption 0)))
    (testing "ls isolates the bombed ticket"
      (let [r (run "ls")]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"healthy neighbor" (:out r)))
        (is (re-find #"error\s+cannot derive" (:out r)))))
    (testing "verify reports and finishes the audit"
      (let [r (run "verify")]
        (is (= 1 (:exit r)))
        (is (re-find #"unreadable" (:out r)))
        (is (re-find #"verify: FAIL" (:out r)))
        (is (re-find #"ok 1 event\(s\)" (:out r))
            "the audit covered the healthy ticket too")
        (is (not (re-find #"StackOverflow|\tat " (str (:out r) (:err r)))))))))

(deftest hostile_sqlite_rows_fail_well
  ;; rows written around append! — garbage bytes under an honest id, a
  ;; ticket column that is not a uuid, a db that is not a db — must all
  ;; reject with ex-info, never a raw cast or parse exception
  (let [db (str (System/getProperty "java.io.tmpdir")
                "/tik-fuzz-" (random-uuid) ".db")
        s (tik.store.sqlite/sqlite-store db)
        t #uuid "11111111-1111-1111-1111-111111111111"]
    (sh/sh "sqlite3" db
           (str "INSERT INTO events(id,ticket,bytes) VALUES"
                "('sha256-abc','" t "', X'25252520676172626167');"
                "INSERT INTO events(id,ticket,bytes) VALUES"
                "('sha256-def','not-a-uuid', X'6e696c');"))
    (testing "garbage bytes name the row"
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (store/events s t)))))
    (testing "a non-uuid ticket column names the value"
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (store/ticket-ids s)))))
    (testing "a corrupt db file is an ex-info, not a raw shell error"
      (spit db "NOT A DATABASE")
      (is (thrown? clojure.lang.ExceptionInfo
                   (doall (store/ticket-ids s)))))
    (io/delete-file db true)))

(deftest garbage_signature_sidecars_never_trace
  ;; sidecar verification shells to ssh-keygen; whatever bytes sit in a
  ;; .sig file — prose, truncated armor, nothing — the audit answers
  ;; with verdict lines, never a trace
  (let [root (.toFile (Files/createTempDirectory
                       "tik-sig" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        run (fn [& args]
              (apply sh/sh (concat ["bb" "tik"] args
                                   [:dir repo
                                    :env (assoc (into {} (System/getenv))
                                                "TIK_ROOT" (str root)
                                                "TIK_ACTOR" "fuzz")])))]
    (run "new" "track" "--title" "sig fodder")
    (let [evdir (->> (io/file root "tickets") .listFiles first
                     (#(io/file % "events")))
          id (-> ^java.io.File (first (.listFiles ^java.io.File evdir))
                 .getName
                 (str/replace #"\.edn$" ""))]
      (spit (io/file evdir (str id ".sig.deadbeefdeadbeef")) "garbage")
      (spit (io/file evdir (str id ".sig.0123456789abcdef"))
            "-----BEGIN SSH SIGNATURE-----\ntrunc")
      (spit (io/file evdir (str id ".sig.ffffffffffffffff")) ""))
    (spit (io/file root "actors")
          "fuzz namespaces=\"tik-*\" garbage-not-a-key\n")
    (let [r (run "verify")]
      (is (contains? #{0 1} (:exit r)))
      (is (re-find #"verify: (PASS|FAIL)" (:out r)))
      (is (not (re-find #"Exception in thread|\tat "
                        (str (:out r) (:err r))))))))

;; ----------------- the HTTP serve and MCP stdio surfaces

(defn- http-status
  "GET path off the local server; the status code, or -1 on a
  connection error (the server refusing/closing, never our test)."
  [port path]
  (try
    (let [url (.toURL (java.net.URI. (str "http://127.0.0.1:" port path)))
          c (doto ^java.net.HttpURLConnection (.openConnection url)
              (.setConnectTimeout 2000)
              (.setReadTimeout 2000))]
      (try (.getResponseCode c) (finally (.disconnect c))))
    (catch Exception _ -1)))

(deftest serve_survives_every_hostile_request
  ;; the board server is read-only, but a hostile GET must not reach a
  ;; die/System-exit path — one bad request would take the board down
  ;; for everyone. Unknown ids 404, everything is request-scoped, and
  ;; the process is still answering after the whole barrage
  (let [root (.toFile (Files/createTempDirectory
                       "tik-serve" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        port 7801
        _ (sh/sh "bb" "tik" "new" "track" "--title" "served"
                 :dir repo
                 :env (assoc (into {} (System/getenv))
                             "TIK_ROOT" (str root) "TIK_ACTOR" "fuzz"))
        cmd ^"[Ljava.lang.String;" (into-array
                                    String
                                    ["bb" "tik" "serve" "--port" (str port)])
        proc (.start (doto (ProcessBuilder. cmd)
                       (.directory (io/file repo))
                       (.redirectErrorStream true)
                       (.. environment (put "TIK_ROOT" (str root)))
                       (.. environment (put "TIK_ACTOR" "fuzz"))))]
    (try
      ;; wait for the port to answer
      (loop [tries 60]
        (when (and (pos? tries) (neg? (http-status port "/")))
          (Thread/sleep 250)
          (recur (dec tries))))
      (testing "the known routes serve"
        (is (= 200 (http-status port "/")))
        (is (= 200 (http-status port "/tickets.edn"))))
      (testing "hostile ids and paths are 404, never a crash"
        (doseq [path ["/explain/ffff.edn"
                      "/explain/.edn"
                      "/explain/-------.edn"
                      "/explain/deadbeef-0000.edn"
                      "/nonsense"
                      "/explain/00000000-0000-0000-0000-000000000000.edn"]]
          (is (contains? #{404 400} (http-status port path)) path)))
      (testing "the server is still alive after the barrage"
        (is (= 200 (http-status port "/")))
        (is (.isAlive proc)))
      (finally (.destroy proc)
               (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)))))

(deftest mcp_session_survives_every_hostile_line
  ;; the MCP server reads one JSON-RPC request per line off stdio; a
  ;; line that is not JSON, not an object, names no tool, or omits a
  ;; required argument must answer with a JSON-RPC result/error and
  ;; leave the SESSION intact — a later well-formed request still works
  (let [root (.toFile (Files/createTempDirectory
                       "tik-mcp" (make-array FileAttribute 0)))
        repo (System/getProperty "user.dir")
        _ (sh/sh "bb" "tik" "new" "track" "--title" "mcp fodder"
                 :dir repo
                 :env (assoc (into {} (System/getenv))
                             "TIK_ROOT" (str root) "TIK_ACTOR" "fuzz"))
        lines (str/join
               "\n"
               ["{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"
                "not json at all {{{"
                "[1,2,3]"
                "5"
                "null"
                "\"a bare string\""
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"tik_explain\",\"arguments\":\"not-a-map\"}}"
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"tik_explain\",\"arguments\":{}}}"
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"tik_assert\",\"arguments\":{\"ticket\":null,\"key\":7,\"value\":[1]}}}"
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\"}}"
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":9}"
                ;; the canary: a well-formed request AFTER all the abuse
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/list\"}"])
        r (sh/sh "bb" "mcp"
                 :in lines :dir repo
                 :env (assoc (into {} (System/getenv))
                             "TIK_ROOT" (str root) "TIK_ACTOR" "fuzz"))]
    (is (zero? (:exit r)) (:err r))
    (is (not (re-find #"Exception in thread|\tat |----- Error"
                      (str (:out r) (:err r))))
        "no line stack-traces the session")
    (testing "every emitted line is a valid JSON-RPC object"
      (doseq [line (remove str/blank? (str/split-lines (:out r)))]
        (is (re-find #"\"jsonrpc\":\"2\.0\"" line) line)))
    (testing "the canary after the abuse still gets its answer"
      (is (re-find #"\"id\":99.*tik_board" (:out r))
          "the session survived every hostile line"))
    (testing "a garbage line answers with a parse error, not silence"
      (is (re-find #"-32700|parse error" (:out r))))))

;; ---------------------- the run-argv in-process seam

(defn- with-store
  "Run f with the CLI's private root accessor pinned to a temp dir, so
  run-argv calls resolve an isolated store with no TIK_ROOT env."
  [f]
  (let [root (.toFile (Files/createTempDirectory
                       "tik-runargv" (make-array FileAttribute 0)))]
    (with-redefs-fn {#'tik.cli/root (constantly (str root))} f)))

(defspec run_argv_is_total_over_arbitrary_argv n
  ;; the in-process entry point the MCP server rides on: whatever an
  ;; embedder passes — empty, garbage tokens, unknown commands — it
  ;; must ANSWER with {:exit :out :err}, never throw, never write to
  ;; the real stdout (that would corrupt a host's JSON-RPC stream)
  (prop/for-all [argv (gen/vector
                       (gen/one-of [gen/string
                                    (gen/elements ["ls" "explain" "set"
                                                   "agent" "status" "--edn"
                                                   "--actor" "next"])])
                       0 5)]
    (with-store
      (fn []
        (let [leaked (java.io.StringWriter.)
              r (binding [*out* leaked] (tik.cli/run-argv argv))]
          (and (map? r)
               (integer? (:exit r))
               (string? (:out r))
               (string? (:err r))
               (zero? (.length (.getBuffer leaked)))))))))

(deftest run_argv_rejects_non_string_argv_cleanly
  ;; a non-string element used to reach parse-args and raise a raw NPE
  ;; out of the runner's return contract; now it is a clean exit-1
  (with-store
    (fn []
      (doseq [argv [[nil] ["ls" nil] [42] [["ls"]] "not-a-seq" [:ls]]]
        (let [r (tik.cli/run-argv argv)]
          (is (map? r) (pr-str argv))
          (is (= 1 (:exit r)) (pr-str argv))
          (is (str/blank? (:out r)) (pr-str argv)))))))

(deftest run_argv_traps_every_exit_path
  ;; success, a die (unknown id -> 1), and a gated refusal (-> 3) all
  ;; come back as CODES; none terminates or escapes the process
  (with-store
    (fn []
      (System/setProperty "user.name" "tester")
      (is (zero? (:exit (tik.cli/run-argv ["new" "track" "--title" "t"]))))
      (is (= 1 (:exit (tik.cli/run-argv ["explain" "nomatch"]))))
      (let [id (->> (:out (tik.cli/run-argv ["ls" "--edn"]))
                    (re-seq #"[0-9a-f]{8}-[0-9a-f-]{27}")
                    first)]
        (is (= 3 (:exit (tik.cli/run-argv
                         ["agent" "set" id "x=1" "--actor" "outsider"]))))))))

(deftest run_argv_is_concurrency_safe
  ;; each call binds its OWN *out*/*err*/*exit-fn* (thread-local), so
  ;; parallel embedders never cross output or exit codes — only the
  ;; shared derived-cache atom is touched concurrently, and it is
  ;; content-addressed so it cannot serve a wrong answer
  (with-store
    (fn []
      (System/setProperty "user.name" "tester")
      (dotimes [i 8]
        (tik.cli/run-argv ["new" "track" "--title" (str "ticket-" i)]))
      (let [results (->> (range 24)
                         (mapv (fn [_] (future (tik.cli/run-argv ["ls" "--edn"]))))
                         (mapv deref))]
        (is (every? #(zero? (:exit %)) results))
        (is (every? #(str/includes? (:out %) "ticket-0") results))
        (is (every? #(str/blank? (:err %)) results)
            "no call's stderr bled into another's")))))
