;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.sshsig-test
  "SSHSIG read in process, checked against the tool whose answers it
  reproduces. The signatures here are produced by ssh-keygen, so the
  contract is agreement: for the same bytes, the same verdict — and
  where they could differ, this one refuses.

  A verifier's tests are worth more in the negative than the positive: a
  reader that says yes to everything passes every happy path."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.sign :as sign]
            [tik.sshsig :as sshsig])
  (:import (java.io File)))

(defn- signer!
  "A keypair and the allowed-signers line naming it."
  [dir who]
  (let [key (io/file dir (str who "-key"))]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" who "-f" (str key))
    {:key key
     :line (sign/allowed-signers-line
            who (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str key)))))}))

(defn- sign!
  "Detached SSHSIG over `message`, as ssh-keygen writes it."
  [dir key namespace ^String message]
  ;; a fresh name per call: ssh-keygen writes <file>.sig beside the input,
  ;; and reusing one path silently re-reads the previous signature
  (let [f (File/createTempFile "sshsig-msg" ".txt" (io/file dir))]
    (spit f message)
    (sh/sh "ssh-keygen" "-Y" "sign" "-f" (str key) "-n" namespace (str f))
    (slurp (io/file (str f ".sig")))))

(deftest it_agrees_with_ssh_keygen_on_an_honest_signature
  (let [dir (h/temp-dir! "sshsig")
        {:keys [key line]} (signer! dir "alice")
        msg "the bytes that were endorsed"
        armor (sign! dir key "tik-event" msg)]
    (is (true? (sshsig/verify line (.getBytes msg "UTF-8") armor
                              "alice" "tik-event")))
    (testing "and reports who could have produced it"
      (is (= ["alice"] (sshsig/find-principals line (.getBytes msg "UTF-8")
                                               armor "tik-event"))))))

(deftest every_way_it_must_say_no
  (let [dir (h/temp-dir! "sshsig-neg")
        {:keys [key line]} (signer! dir "alice")
        other (signer! dir "mallory")
        msg "the bytes that were endorsed"
        b (.getBytes msg "UTF-8")
        armor (sign! dir key "tik-event" msg)
        v (fn [text message sig principal ns]
            (sshsig/verify text message sig principal ns))]
    (testing "a message that moved"
      (is (false? (v line (.getBytes "the bytes that were endorsed!" "UTF-8")
                     armor "alice" "tik-event"))))
    (testing "a principal the key is not registered to"
      (is (false? (v line b armor "mallory" "tik-event"))))
    (testing "a namespace the signature was not made for — this is what stops
              an event signature being replayed as a process publication"
      (is (false? (v line b armor "alice" "tik-process"))))
    (testing "a registry that does not name the key"
      (is (false? (v (:line other) b armor "alice" "tik-event")))
      (is (false? (v "" b armor "alice" "tik-event"))))
    (testing "a namespaces= option that excludes it"
      (is (false? (v (str/replace line "tik-*" "other-*") b armor
                     "alice" "tik-event")))
      (is (true? (v (str/replace line "tik-*" "tik-ev*") b armor
                    "alice" "tik-event"))))
    (testing "armor that is not a signature at all"
      (doseq [junk ["" "not a signature" "-----BEGIN SSH SIGNATURE-----\n@@\n"
                    (str/replace armor "-----BEGIN" "-----NOPE")]]
        (is (false? (v line b junk "alice" "tik-event")) (pr-str junk))))
    (testing "a truncated blob is refused rather than read short"
      (let [body (str/split-lines armor)
            cut (str/join "\n" (concat (take 3 body) ["-----END SSH SIGNATURE-----"]))]
        (is (false? (v line b cut "alice" "tik-event")))))
    (testing "and a signature that is valid for someone else"
      (is (false? (v (str line "\n" (:line other)) b
                     (sign! dir (:key other) "tik-event" msg)
                     "alice" "tik-event"))))))

(deftest it_agrees_with_ssh_keygen_over_this_repository
  ;; The strongest evidence available: every signature this store already
  ;; holds, produced by ssh-keygen over months, judged by both readers.
  (let [repo (System/getProperty "user.dir")
        actors (slurp (io/file repo "actors"))
        sidecars (for [^File d (.listFiles (io/file repo "tickets"))
                       :when (.isDirectory d)
                       ^File f (or (.listFiles (io/file d "events")) [])
                       :when (re-find #"\.(sig|witness)\." (.getName f))
                       :let [n (.getName f)
                             ev (io/file (io/file d "events")
                                         (str (first (str/split n #"\.(sig|witness)\."))
                                              ".edn"))]
                       :when (.exists ev)]
                   {:sig f :event ev
                    :ns (if (str/includes? n ".witness.") "tik-witness" "tik-event")
                    :actor (second (re-find #":event/actor \"([^\"]*)\""
                                            (slurp ev)))})
        checked (atom 0)
        disagree (for [{:keys [sig event ns actor]} sidecars
                       :let [msg (.getBytes (slurp event) "UTF-8")
                             theirs (zero? (:exit (sh/sh "ssh-keygen" "-Y" "verify"
                                                         "-f" (str (io/file repo "actors"))
                                                         "-I" actor "-n" ns
                                                         "-s" (str sig)
                                                         :in (slurp event))))
                             ours (sshsig/verify actors msg (slurp sig) actor ns)]
                       :let [_ (swap! checked inc)]
                       :when (not= theirs ours)]
                   {:sig (.getName ^File sig) :ssh-keygen theirs :ours ours})]
    (is (empty? (vec disagree)))
    (is (< 100 @checked) "the store should hold plenty of signatures to check")))
