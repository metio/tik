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

(defspec the_reducer_is_total_over_garbage_event_sets n
  ;; events that bypassed minting (a hostile store) must not crash the
  ;; fold — reduction is a pure fn of the set, garbage included
  (prop/for-all [garbage (gen/vector gen-garbage 0 5)
                 valid ge/gen-events]
    (fails-well? #(red/ticket-state (into (vec valid) garbage)))))

;; --------------------------------------------------- malformed guards

(def gen-garbage-guard
  (gen/one-of
   [gen/any-equatable
    (gen/vector gen/keyword 0 4)
    (gen/let [op (gen/elements [:fact :fact= :artifact :signed-by
                                :stage-reached :elapsed-since :and :or
                                :not :nonsense])
              args (gen/vector gen/any-equatable 0 3)]
      (gen/return (into [op] args)))]))

(defspec guard_evaluation_is_total_or_fails_well n
  ;; the runtime's closed-vocabulary throw is ex-info by contract; a
  ;; NullPointerException from a mangled argument list is a finding
  (prop/for-all [g gen-garbage-guard
                 events ge/gen-events]
    (fails-well?
     #(guard/eval-guard g {:state (red/ticket-state events)
                           :now (Instant/now)
                           :roles ge/roles
                           :reached #{}}))))

(defspec the_fixpoint_is_total_over_garbage_processes n
  (prop/for-all [stages (gen/vector
                         (gen/let [id gen/keyword
                                   guards (gen/vector gen-garbage-guard 0 2)]
                           (gen/return {:stage/id id :guards guards}))
                         0 4)
                 events ge/gen-events]
    (fails-well?
     #(stage/effective-reached {:process/id :fuzz :process/version 1
                                :process/stages stages}
                               events (Instant/now) ge/roles))))

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

;; ------------------------------------------------ round 2: the porcelain

(def ^:private cli-parse-value @#'tik.cli/parse-value)
(def ^:private cli-parse-key @#'tik.cli/parse-key)

(defspec parse-value-is-total-over-arbitrary-text n
  ;; whatever a human or a hostile probe prints, the parser answers
  ;; with a VALUE — never a throw
  (prop/for-all [s gen/string]
    (fails-well? #(cli-parse-value s))))

(defspec parse-key-is-total-over-plausible-keys n
  (prop/for-all [s (gen/such-that seq gen/string-alphanumeric)]
    (vector? (cli-parse-key s))))

(defspec similarity-is-total-and-bounded n
  (prop/for-all [a (gen/one-of [gen/string (gen/return nil)])
                 b (gen/one-of [gen/string (gen/return nil)])]
    (let [score (tik.dupe/similarity a b)]
      (and (<= 0.0 score 1.0)
           (= score (tik.dupe/similarity b a))))))

(defspec author-check-is-total-over-garbage n
  ;; whatever an LLM or a corrupted answers.edn hands it, check
  ;; answers with findings — the error finding IS the clean rejection
  (prop/for-all [garbage gen/any-equatable]
    (fails-well? #(doall (tik.author/check garbage)))))

(defspec usage-totals-is-total-over-hostile-telemetry n
  ;; agent telemetry is attestation BODY — unvalidated by schema; a
  ;; hostile or buggy agent's usage map must not crash the money lens
  (prop/for-all [records (gen/vector
                          (gen/map gen/keyword gen/any-equatable
                                   {:max-elements 4})
                          0 4)]
    (fails-well? #(work/usage-totals records {"m" {:input 3.0}}))))

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

;; -------------------------------------------- round 3: garbage configs

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

;; ------------------------------------- round 4: packs, cache, dir names

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

;; ------------------------------- round 5: hostile DAGs, poisoned boards

(defspec graph_walks_terminate_on_hostile_dags n
  ;; a crafted store can contain parent references that form cycles or
  ;; point nowhere — impossible to MINT (hashes), trivial to WRITE.
  ;; Every graph function must terminate and answer
  (prop/for-all [edges (gen/map (gen/fmap #(str "id" %) gen/small-integer)
                                (gen/set (gen/fmap #(str "id" %)
                                                   gen/small-integer)
                                         {:max-elements 3})
                                {:max-elements 8})]
    (let [events (mapv (fn [[id parents]]
                         {:event/id id :event/parents (set parents)
                          :event/at (Instant/parse "2026-01-01T00:00:00Z")
                          :event/type :fact/assert
                          :event/ticket #uuid "018f2f6e-7c1a-7000-8000-00000000beef"
                          :event/actor "x"
                          :event/body {:fact/path [:x] :fact/value 1}})
                       edges)]
      (and (fails-well? #(doall (tik.dag/heads events)))
           (fails-well? #(doall (tik.dag/missing-parents events)))
           (fails-well? #(doall (tik.dag/roots events)))
           (fails-well? #(red/ticket-state events))))))

(defspec reason_rendering_is_total_over_garbage n
  ;; lenses render whatever reasons arrive — including from stores
  ;; written by future or hostile versions with reason keys we never
  ;; defined. render answers with a string, always
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

;; ---------------- round 6: recursion bombs, sqlite hostility, sidecars

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
