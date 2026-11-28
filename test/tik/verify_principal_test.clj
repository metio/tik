;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.verify-principal-test
  "Verifying a signature AS somebody needs a somebody. A principal that is
  not a non-blank string names nobody, so it verifies nothing: neither in
  process, nor through the ssh-keygen fallback, where `-I \"\"` matches a
  hand-written `*` line, nor in `tik verify` for an event stored without
  an :event/actor."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.canonical :as canonical]
            [tik.delegation-fixture :as f]
            [tik.event :as event]
            [tik.harness :as h]
            [tik.sign :as sign]
            [briko.sshsig :as sshsig]
            [tik.store.protocol :as store]))

(def ^:private not-principals [nil "" "   " :alice 42])

(defn- keypair
  "A key of `key-type`, its public line, and a signature over `msg`."
  [dir who key-type ^String msg]
  (let [k (f/keygen! dir who key-type)
        pub (str/join " " (take 2 (str/split (str/trim (slurp (str k ".pub"))) #"\s+")))]
    {:key k :pub pub
     :sig (sign/sign-bytes k (.getBytes msg "UTF-8") sign/namespace-event)}))

(defn- registries
  "allowed-signers texts that grant `pub`: one naming alice, and one
  whose principal is the pattern `*`, which ssh-keygen matches against
  any name, the empty one included."
  [pub]
  {"alice" (str "alice " pub "\n")
   "*" (str "* " pub "\n")})

(deftest sshsig-verify-refuses-a-principal-that-names-nobody
  (let [dir (h/temp-dir! "principal-sshsig")
        msg "endorsed bytes"
        b (.getBytes msg "UTF-8")
        {:keys [pub sig]} (keypair dir "alice" "ed25519" msg)]
    (is (true? (sshsig/verify (str "alice " pub) b sig "alice" sign/namespace-event))
        "positive control")
    (doseq [[label text] (registries pub)
            p not-principals]
      (is (false? (sshsig/verify text b sig p sign/namespace-event))
          (str label " registry, principal " (pr-str p))))
    (testing "find-principals still searches every line"
      (is (= ["alice"] (sshsig/find-principals (str "alice " pub) b sig
                                               sign/namespace-event))))))

(deftest sign-verify-refuses-a-principal-that-names-nobody-in-process
  (let [dir (h/temp-dir! "principal-sign")
        msg "endorsed bytes"
        b (.getBytes msg "UTF-8")
        {:keys [pub sig]} (keypair dir "alice" "ed25519" msg)]
    (doseq [[label text] (registries pub)]
      (let [file (io/file dir (str "actors-" (if (= "*" label) "star" label)))]
        (spit file text)
        (when (= "alice" label)
          (is (true? (sign/verify-bytes file b sig "alice")) "positive control, file")
          (is (true? (sign/verify-text text b sig "alice" sign/namespace-event))
              "positive control, text"))
        (doseq [p not-principals]
          (is (false? (sign/verify-bytes file b sig p))
              (str "verify-bytes, " label " registry, principal " (pr-str p)))
          (is (false? (sign/verify-text text b sig p sign/namespace-event))
              (str "verify-text, " label " registry, principal " (pr-str p))))))))

(deftest the-ssh-keygen-fallback-refuses-a-principal-that-names-nobody
  ;; ecdsa is a key type briko.sshsig does not read, so every verdict here
  ;; comes from ssh-keygen
  (let [dir (h/temp-dir! "principal-fallback")
        msg "endorsed bytes"
        b (.getBytes msg "UTF-8")
        {:keys [pub sig]} (keypair dir "alice" "ecdsa" msg)]
    (is (= ::sshsig/unsupported
           (sshsig/verify (str "alice " pub) b sig "alice" sign/namespace-event))
        "the in-process reader cannot judge this key")
    (testing "ssh-keygen itself grants an empty principal against a * line,
              which is why the refusal has to come before it is asked"
      (let [af (io/file dir "star-signers")
            sf (io/file dir "msg.sig")]
        (spit af (str "* " pub "\n"))
        (io/copy sig sf)
        (is (zero? (:exit (sh/sh "ssh-keygen" "-Y" "verify" "-f" (str af) "-I" ""
                                 "-n" sign/namespace-event "-s" (str sf) :in msg))))))
    (doseq [[label text] (registries pub)]
      (let [file (io/file dir (str "actors-" (if (= "*" label) "star" label)))]
        (spit file text)
        (when (= "alice" label)
          (is (true? (sign/verify-bytes file b sig "alice")) "positive control, file")
          (is (true? (sign/verify-text text b sig "alice" sign/namespace-event))
              "positive control, text"))
        (doseq [p not-principals]
          (is (false? (sign/verify-bytes file b sig p))
              (str "verify-bytes, " label " registry, principal " (pr-str p)))
          (is (false? (sign/verify-text text b sig p sign/namespace-event))
              (str "verify-text, " label " registry, principal " (pr-str p))))))))

(defn- actorless-event!
  "Append to the review ticket of fixture `fx` a fact assertion whose
  stored bytes carry no :event/actor, signed by seb's registered key. The
  writer refuses such an event, so it is placed the way a hand-edited or
  synced store would hold it."
  [{:keys [s ticket head seb]}]
  (let [minted (event/assert-fact {:ticket ticket :actor "seb" :at (f/now)
                                   :parents [head] :path [:review :verdict]
                                   :value :approved})
        body (dissoc minted :event/id :event/actor)
        e (assoc body :event/id (canonical/content-address body))]
    (f/append-signed! s e seb)
    e))

(deftest tik-verify-credits-no-signature-to-an-event-without-an-actor
  (let [fx (f/fixture)
        e (actorless-event! fx)
        id-prefix (subs (:event/id e) 0 15)
        r (h/run-tik! {:root (:root fx) :actor "seb"} "verify" (str (:ticket fx)))
        out (str (:out r) (:err r))
        line (first (filter #(and (str/includes? % id-prefix)
                                  (str/includes? % "signed by"))
                            (str/split-lines out)))]
    (is (some? (store/events (:s fx) (:ticket fx))))
    (is (some? line) out)
    (is (str/includes? (str line) "FAIL") (str "L1 line for the actor-less event: " line))
    (is (str/includes? out "verify: FAIL") out)))
