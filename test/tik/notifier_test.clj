;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.notifier-test
  "The notifier against real stores: hints re-derived from signed logs,
  nothing sent on a failed verification, no fact values in a payload, a
  cursor that survives a restart, store-aware routing, sinks behind one
  interface, and a socket only this user can reach."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [tik.cli]
            [tik.cli-core :as core]
            [tik.harness :as h]
            [tik.notifier :as notifier]
            [tik.notify :as n])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net UnixDomainSocketAddress)
           (java.nio.channels Channels SocketChannel)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute PosixFilePermissions)
           (java.time Instant)))

(def ^:private ^Instant t0 (Instant/parse "2026-11-27T10:00:00Z"))
(defn- at [ms] (.plusMillis t0 (long ms)))
(def ^:private cfg {:window-ms 1000 :rate {:per-ms 1000 :burst 10}})

(defn- signed-store!
  "A file store whose actors registry admits `seb` with a fresh key."
  []
  (let [{:keys [root]} (h/temp-store!)
        keyf (io/file root "id_ed25519")]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" (str keyf))
    (spit (io/file root "actors")
          (str "seb namespaces=\"tik-*\" "
               (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str keyf)))) "\n"))
    {:root root :key (str keyf)}))

(defn- tik! [{:keys [root key]} & args]
  (binding [core/*root* (str root)]
    (let [r (tik.cli/run-argv (cond-> (into (vec args) ["--actor" "seb"]) key (into ["--key" key])))]
      (assert (zero? (:exit r)) (str args " failed: " (:err r)))
      (:out r))))

(defn- new-ticket! [store title]
  (str/trim (first (str/split-lines (tik! store "new" "track" "--title" title)))))

(defn- fake-sink []
  (let [got (atom [])]
    [got (reify notifier/Sink (deliver! [_ hint] (swap! got conj hint) true))]))

(defn- with-sink
  "State over `stores` with one permanent fake subscriber per store and
  selector given."
  [st subs]
  (doseq [[id store selector sink] subs]
    (let [[reg why] (n/subscribe (:registry @st) id {:store store :selector selector :permanent? true}
                                 #{store} 0)]
      (assert (nil? why) why)
      (swap! st #(-> % (assoc :registry reg) (assoc-in [:sinks id] sink)))))
  st)

(defn- quiet-tick! [st c t]
  (let [err (java.io.StringWriter.)]
    (binding [*err* err] (notifier/tick! st c t))
    (str err)))

(deftest a_stage_change_reaches_a_subscriber_once_per_window
  (let [s (signed-store!)
        [got sink] (fake-sink)
        st (with-sink (notifier/new-state {}) [["a" "s" "" sink]])
        c (assoc cfg :stores {"s" (str (:root s))})
        before (new-ticket! s "existed before the notifier")]
    (quiet-tick! st c (at 0))
    (is (empty? @got) "the first poll is a silent baseline")
    (let [id (new-ticket! s "the thing")]
      (quiet-tick! st c (at 100))
      (tik! s "set" id "outcome=\"shipped it\"")
      (quiet-tick! st c (at 200))
      (is (empty? @got) "the window is still open")
      (quiet-tick! st c (at 1200))
      (is (= [{:store "s" :ticket id :title "the thing" :from [] :to ["done"] :actor "seb"}] @got)
          "creation and the outcome fold into one hint from no stage to done")
      (tik! s "set" before "outcome=\"also shipped\"")
      (quiet-tick! st c (at 1300))
      (quiet-tick! st c (at 2400))
      (is (= ["open"] (:from (last @got))))
      (is (= 2 (count @got))))))

(deftest a_failed_verification_sends_nothing_and_says_why
  (let [s (signed-store!)
        [got sink] (fake-sink)
        st (with-sink (notifier/new-state {}) [["a" "s" "" sink]])
        c (assoc cfg :stores {"s" (str (:root s))})]
    (quiet-tick! st c (at 0))
    (testing "an unsigned event"
      (let [id (new-ticket! (dissoc s :key) "nobody vouches")
            err (quiet-tick! st c (at 100))]
        (quiet-tick! st c (at 5000))
        (is (empty? @got))
        (is (str/includes? err id))
        (is (str/includes? err "carry no signature"))))
    (testing "a signature that does not verify"
      (let [id (new-ticket! s "forged")
            dir (h/events-dir (:root s) id)
            sig (first (filter #(str/includes? (.getName ^java.io.File %) ".sig.") (.listFiles dir)))]
        (spit sig "-----BEGIN SSH SIGNATURE-----\nAAAA\n-----END SSH SIGNATURE-----\n")
        (let [err (quiet-tick! st c (at 6000))]
          (quiet-tick! st c (at 9000))
          (is (empty? @got))
          (is (str/includes? err "verify: FAIL")))))
    (testing "a store with no actors registry"
      (let [s2 (signed-store!)
            _ (io/delete-file (io/file (:root s2) "actors"))
            st2 (with-sink (notifier/new-state {}) [["b" "s2" "" sink]])
            c2 (assoc cfg :stores {"s2" (str (:root s2))})]
        (quiet-tick! st2 c2 (at 0))
        (new-ticket! s2 "unverifiable")
        (is (str/includes? (quiet-tick! st2 c2 (at 100)) "no actors registry"))
        (quiet-tick! st2 c2 (at 5000))
        (is (empty? @got))))))

(deftest a_payload_holds_no_fact_values
  (let [s (signed-store!)
        [got sink] (fake-sink)
        st (with-sink (notifier/new-state {}) [["a" "s" "" sink]])
        c (assoc cfg :stores {"s" (str (:root s))})]
    (quiet-tick! st c (at 0))
    (let [id (new-ticket! s "plain title")]
      (tik! s "set" id "outcome=\"SECRET-VALUE-4711\"")
      (tik! s "set" id "customer=\"ACME-CONFIDENTIAL\"")
      (quiet-tick! st c (at 100))
      (quiet-tick! st c (at 2000))
      (is (= 1 (count @got)))
      (is (= n/hint-keys (set (keys (first @got)))))
      (is (not (re-find #"SECRET-VALUE|ACME-CONFIDENTIAL" (pr-str @got))))
      (is (not (re-find #"SECRET-VALUE|ACME-CONFIDENTIAL" (json/generate-string
                                                           (n/channel-notification (first @got)))))))))

(deftest a_restart_resumes_from_the_cursor_without_resending
  (let [s (signed-store!)
        cursor-file (io/file (h/temp-dir! "notify-state") "cursor.edn")
        c (assoc cfg :stores {"s" (str (:root s))} :cursor-file (str cursor-file))
        [got sink] (fake-sink)
        st (with-sink (notifier/new-state {}) [["a" "s" "" sink]])]
    (quiet-tick! st c (at 0))
    (let [id (new-ticket! s "before the restart")]
      (quiet-tick! st c (at 100))
      (quiet-tick! st c (at 2000))
      (is (= 1 (count @got)))
      (is (.isFile cursor-file))
      (let [[got2 sink2] (fake-sink)
            st2 (with-sink (notifier/new-state (#'notifier/read-cursor cursor-file))
                  [["a" "s" "" sink2]])]
        (quiet-tick! st2 c (at 3000))
        (quiet-tick! st2 c (at 9000))
        (is (empty? @got2) "nothing already sent is sent again")
        (tik! s "set" id "outcome=\"after the restart\"")
        (quiet-tick! st2 c (at 10000))
        (quiet-tick! st2 c (at 12000))
        (is (= [["open"] ["done"]] [(:from (first @got2)) (:to (first @got2))])
            "a change after the restart diffs against the stages before it")))))

(deftest a_subscriber_sees_only_its_store_and_its_selector
  (let [a (signed-store!) b (signed-store!)
        [got-a sink-a] (fake-sink)
        [got-done sink-done] (fake-sink)
        st (with-sink (notifier/new-state {}) [["a" "alpha" "" sink-a]
                                               ["d" "beta" "stage=:done" sink-done]])
        c (assoc cfg :stores {"alpha" (str (:root a)) "beta" (str (:root b))})]
    (quiet-tick! st c (at 0))
    (let [ida (new-ticket! a "in alpha")
          idb (new-ticket! b "in beta")
          idb2 (new-ticket! b "done in beta")]
      (tik! b "set" idb2 "outcome=\"finished\"")
      (quiet-tick! st c (at 100))
      (quiet-tick! st c (at 2000))
      (is (= [ida] (map :ticket @got-a)))
      (is (= [idb2] (map :ticket @got-done)) (str "not " idb)))))

(deftest a_runaway_store_is_rate_limited
  (let [s (signed-store!)
        [got sink] (fake-sink)
        st (with-sink (notifier/new-state {}) [["a" "s" "" sink]])
        c (assoc cfg :stores {"s" (str (:root s))} :rate {:per-ms 60000 :burst 2})]
    (quiet-tick! st c (at 0))
    (dotimes [i 4] (new-ticket! s (str "flood " i)))
    (quiet-tick! st c (at 100))
    (let [err (quiet-tick! st c (at 2000))]
      (is (= 2 (count @got)))
      (is (str/includes? err "rate limit")))))

(deftest a_command_sink_runs_without_a_shell
  (let [dir (h/temp-dir! "notify-cmd")
        out (io/file dir "out")
        sink (notifier/->CommandSink ["sh" "-c" "printf '%s' \"$1\" > \"$2\"" "sh" "{title}" (str out)])]
    (is (true? (notifier/deliver! sink {:store "s" :ticket "t" :title "$(touch pwned)" :from []
                                        :to ["open"] :actor "seb"})))
    (is (= "$(touch pwned)" (slurp out)))
    (is (not (.exists (io/file "pwned"))))))

;; ------------------------------------------------------------ the socket

(defn- no-exit [f]
  (binding [core/*exit-fn* (fn [code] (throw (ex-info "exit" {:code code})))]
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (try (f) nil (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :err (str err))))))))

(defn- short-dir!
  "A private directory with a path short enough for a socket."
  ^java.io.File []
  (.toFile (Files/createTempDirectory (.toPath (io/file "/tmp")) "tn"
                                      (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest the_socket_lives_in_a_private_directory_and_only_we_may_use_it
  (let [dir (short-dir!)]
    (try
      (testing "a directory others may enter is refused"
        (Files/setPosixFilePermissions (.toPath dir) (PosixFilePermissions/fromString "rwxr-xr-x"))
        (let [r (no-exit #(notifier/open-socket! (str (io/file dir "n.sock"))))]
          (is (= 1 (:code r)))
          (is (str/includes? (:err r) "rwxr-xr-x"))))
      (testing "a missing directory is made private, the socket rw-------"
        (let [p (io/file dir "sub" "n.sock")
              ss (notifier/open-socket! (str p))]
          (try
            (is (= "rwx------" (PosixFilePermissions/toString
                                (Files/getPosixFilePermissions (.toPath (.getParentFile p))
                                                               (make-array LinkOption 0)))))
            (is (= "rw-------" (PosixFilePermissions/toString
                                (Files/getPosixFilePermissions (.toPath p) (make-array LinkOption 0)))))
            (testing "a second notifier will not displace a running one"
              (is (= 1 (:code (no-exit #(notifier/open-socket! (str p)))))))
            (finally (.close ss)))))
      (testing "a path too long for sun_path is refused in words"
        (is (= 1 (:code (no-exit #(notifier/open-socket! (str (io/file dir (str/join (repeat 120 "x"))))))))))
      (finally (h/delete-tree! dir)))))

(defn- client [path]
  (let [ch (SocketChannel/open (UnixDomainSocketAddress/of ^String (str path)))]
    {:ch ch
     :out (Channels/newOutputStream ch)
     :in (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))}))

(defn- send! [{:keys [^java.io.OutputStream out]} m]
  (.write out (.getBytes (str (json/generate-string m) "\n") "UTF-8"))
  (.flush out))

(defn- read-json
  "The next JSON line from the notifier, or ::timeout after ten seconds, so a
  hint that never comes fails the test instead of hanging the suite."
  [{:keys [in]}]
  (let [l (deref (future (.readLine ^BufferedReader in)) 10000 ::timeout)]
    (if (string? l) (json/parse-string l) l)))

(deftest a_subscriber_over_the_socket_gets_hints_and_a_foreign_store_is_refused
  (let [s (signed-store!)
        dir (short-dir!)
        path (str (io/file dir "n.sock"))
        c (assoc cfg :stores {"s" (str (:root s))})
        st (notifier/new-state {})
        ss (notifier/open-socket! path)
        base (Instant/now)
        at #(.plusMillis base (long %))]
    (try
      (notifier/accept-loop! st c ss)
      (let [refused (client path)]
        (send! refused {:op "subscribe" :store "somebody-else"})
        (is (= "refused" (get (read-json refused) "op"))))
      (let [bad (client path)]
        (send! bad {:op "subscribe" :store "s" :selector "bogus=1"})
        (is (str/includes? (get (read-json bad) "reason") "bad selector")))
      (quiet-tick! st c (at 0))
      (let [sub (client path)]
        (send! sub {:op "subscribe" :store "s" :ttl_seconds 60})
        (is (= "subscribed" (get (read-json sub) "op")))
        (let [id (new-ticket! s "over the socket")]
          (quiet-tick! st c (at 100))
          (quiet-tick! st c (at 2000))
          (let [m (read-json sub)]
            (is (= {"op" "hint" "store" "s" "ticket" id "title" "over the socket"
                    "from" [] "to" ["open"] "actor" "seb"} m))))
        (testing "the subscription expires"
          (quiet-tick! st c (at (* 2 n/max-ttl-ms)))
          (is (= {"op" "expired"} (read-json sub)))))
      (finally (.close ss) (h/delete-tree! dir)))))

(deftest the_access_check_ignores_the_user_name_property
  (let [dir (short-dir!)
        before (System/getProperty "user.name")]
    (System/setProperty "user.name" "somebody-else")
    (try
      (is (nil? (no-exit #(.close ^java.nio.channels.ServerSocketChannel
                           (notifier/open-socket! (str (io/file dir "n.sock"))))))
          "a property any code may set does not decide who we are")
      (finally (System/setProperty "user.name" before) (h/delete-tree! dir)))))

(deftest the_peer_credentials_name_the_connecting_user
  (let [dir (short-dir!)
        path (str (io/file dir "p.sock"))
        ss (doto (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)
             (.bind (UnixDomainSocketAddress/of ^String path)))]
    (try
      (let [c (SocketChannel/open (UnixDomainSocketAddress/of ^String path))
            a (.accept ss)]
        (is (= (.getName (Files/getOwner (.toPath dir) (make-array LinkOption 0)))
               (notifier/peer-user a)))
        (.close a) (.close c))
      (finally (.close ss) (h/delete-tree! dir)))))

(deftest the_channel_bridge_speaks_only_the_legacy_handshake
  (let [init (notifier/channel-response {"jsonrpc" "2.0" "id" 1 "method" "initialize"
                                         "params" {"protocolVersion" "2025-06-18"}})]
    (is (= {"claude/channel" {}} (get-in init [:result :capabilities :experimental])))
    (is (= "2025-06-18" (get-in init [:result :protocolVersion])))
    (is (str/includes? (get-in init [:result :instructions]) "not evidence")))
  (is (= -32601 (get-in (notifier/channel-response {"id" 2 "method" "server/discover"}) [:error :code]))
      "a modern request is a method this server lacks, so Claude Code falls back to initialize")
  (is (nil? (notifier/channel-response {"method" "notifications/initialized"})))
  (is (nil? (notifier/channel-response "not a map"))))

;; ------------------------------------------------------------ the channel's stdin

(defn- generated-reader
  "A Reader of `n` copies of `c` followed by `tail`, generated as it is
  read: nothing ever holds the whole text."
  ^java.io.Reader [n c ^String tail]
  (let [left (long-array [n])
        tail-at (long-array [0])
        fill! (fn [^chars cbuf off len]
                (let [off (long off) len (long len)
                      body (min len (aget left 0))]
                  (if (pos? body)
                    (do (java.util.Arrays/fill cbuf (int off) (int (+ off body)) (char c))
                        (aset left 0 (- (aget left 0) body))
                        body)
                    (let [at (aget tail-at 0)
                          k (min len (- (count tail) at))]
                      (if (pos? k)
                        (do (.getChars tail (int at) (int (+ at k)) cbuf (int off))
                            (aset tail-at 0 (+ at k))
                            k)
                        -1)))))]
    (proxy [java.io.Reader] []
      (read
        ([] (let [b (char-array 1)] (if (neg? (long (fill! b 0 1))) -1 (int (aget b 0)))))
        ([cbuf] (fill! cbuf 0 (alength ^chars cbuf)))
        ([cbuf off len] (fill! cbuf off len)))
      (close []))))

(defn- channel-answers
  "Every answer `tik notify channel` writes for the text `in` holds, parsed.
  No message here is notifications/initialized, so nothing subscribes."
  [^java.io.Reader in]
  (let [out (with-out-str
              (binding [*in* (clojure.lang.LineNumberingPushbackReader. in)]
                (notifier/cmd-channel {:socket "/nonexistent/tik-notify.sock" :store "s"})))]
    (mapv json/parse-string (remove str/blank? (str/split-lines out)))))

(defn- ping-line
  "A ping with id `id`, padded to exactly `bytes` UTF-8 bytes with `pad`
  characters of `pad-bytes` bytes each."
  [id bytes pad pad-bytes]
  (let [head (str "{\"jsonrpc\":\"2.0\",\"id\":" id ",\"method\":\"ping\",\"params\":{\"pad\":\"")
        tail "\"}}"
        room (- bytes (count head) (count tail))]
    (assert (zero? (mod room pad-bytes)))
    (str head (str/join (repeat (quot room pad-bytes) pad)) tail)))

(def ^:private mib (* 1024 1024))

(defn- too-large? [answer]
  (and (= -32700 (get-in answer ["error" "code"]))
       (nil? (get answer "id"))
       (contains? answer "id")
       (= "too-large" (get-in answer ["error" "data" "rule"]))
       (str/starts-with? (get-in answer ["error" "message"]) "parse error: ")))

(deftest an_endless_stdin_line_answers_a_parse_error_and_the_next_line_is_read
  ;; more characters than any Java string or StringBuilder can hold, so
  ;; reading it whole would fail whatever the heap
  (let [n (+ 1024 (long Integer/MAX_VALUE))
        [a b :as answers] (channel-answers
                           (generated-reader n \x (str "\n" (ping-line 7 64 "p" 1) "\n")))]
    (is (= 2 (count answers)))
    (is (too-large? a))
    (is (= {"jsonrpc" "2.0" "id" 7 "result" {}} b))))

(deftest a_stdin_line_of_exactly_one_mebibyte_is_answered
  (let [[a b c :as answers] (channel-answers
                             (java.io.StringReader.
                              (str (ping-line 1 mib "p" 1) "\n"
                                   (ping-line 2 mib "p" 1) "\r\n"
                                   (ping-line 3 mib "é" 2))))]
    (is (= 3 (count answers)))
    (is (= [1 2 3] (mapv #(get % "id") [a b c])))
    (is (every? #(= {} (get % "result")) answers))))

(deftest a_stdin_line_one_byte_over_the_limit_is_refused_and_the_next_is_read
  (testing "one ASCII character over"
    (let [[a b :as answers] (channel-answers
                             (java.io.StringReader.
                              (str (ping-line 1 (inc mib) "p" 1) "\n" (ping-line 2 64 "p" 1) "\n")))]
      (is (= 2 (count answers)))
      (is (too-large? a))
      (is (= 2 (get b "id")))))
  (testing "within the limit in characters, over it in UTF-8 bytes"
    (let [over (str (ping-line 1 (- mib 2) "p" 1) "\n")
          line (str/replace-first over "\"pad\":\"ppp" "\"pad\":\"ééé")
          [a b :as answers] (channel-answers
                             (java.io.StringReader. (str line (ping-line 2 64 "p" 1))))]
      (is (< (count line) mib))
      (is (= 2 (count answers)))
      (is (too-large? a))
      (is (= 2 (get b "id")))))
  (testing "at end of input without a newline"
    (is (too-large? (first (channel-answers
                            (java.io.StringReader. (ping-line 1 (inc mib) "p" 1)))))))
  (testing "two over-long lines in a row"
    (let [answers (channel-answers
                   (java.io.StringReader.
                    (str (ping-line 1 (* 3 mib) "p" 1) "\n" (ping-line 2 (* 2 mib) "p" 1) "\n"
                         (ping-line 3 64 "p" 1) "\n")))]
      (is (= 3 (count answers)))
      (is (every? too-large? (take 2 answers)))
      (is (= 3 (get (nth answers 2) "id"))))))

(deftest unreadable_stdin_lines_answer_a_parse_error_naming_the_rule_without_quoting
  (let [marker (str (random-uuid))
        answers (channel-answers
                 (java.io.StringReader.
                  (str "{\"id\":1,\"method\":\"" marker "\n"
                       "{\"id\":2,\"id\":3,\"method\":\"ping\"}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}\n")))]
    (is (= 3 (count answers)))
    (is (every? #(= -32700 (get-in % ["error" "code"])) (take 2 answers)))
    (is (every? #(and (contains? % "id") (nil? (get % "id"))) (take 2 answers)))
    (is (= "duplicate-key" (get-in (second answers) ["error" "data" "rule"])))
    (is (string? (get-in (first answers) ["error" "data" "rule"])))
    (is (not (str/includes? (pr-str answers) marker)))
    (is (= 4 (get (nth answers 2) "id")))))

(deftest the_channel_loop_keeps_the_legacy_handshake
  (let [answers (channel-answers
                 (java.io.StringReader.
                  (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"server/discover\"}\n"
                       "\n   \n"
                       "[1,2]\n"
                       "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}\n"
                       "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}\n")))]
    (testing "blank lines, a value that is no object and a notification get no answer"
      (is (= [1 2 3] (mapv #(get % "id") answers))))
    (is (= {"claude/channel" {}} (get-in (first answers) ["result" "capabilities" "experimental"])))
    (is (= -32601 (get-in (second answers) ["error" "code"])))
    (is (= {} (get (nth answers 2) "result")))))

(deftest the_channel_ends_with_its_input
  (is (= [] (channel-answers (java.io.StringReader. ""))))
  (is (= [] (channel-answers (java.io.StringReader. "\n\n")))))
