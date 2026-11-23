;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.delegation-fixture
  "A store with seb in `actors`, an identity registry and a ticket to
  review, plus helpers that write a delegation and a delegated verdict
  the way `tik review --batch` does (ADR 0037)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.dag :as dag]
            [tik.event :as event]
            [tik.harness :as h]
            [tik.sign :as sign]
            [tik.sk-fixture :as sk]
            [tik.store.protocol :as store]
            [tik.store.file :as fstore])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)))

(def ^:private repo (System/getProperty "user.dir"))

(defn now ^Instant [] (.truncatedTo (Instant/now) ChronoUnit/MILLIS))

(defn keygen!
  ([root name] (keygen! root name "ed25519"))
  ([root name key-type]
   (let [k (io/file root name)]
     (sh/sh "ssh-keygen" "-q" "-t" key-type "-N" "" "-C" name "-f" (str k))
     k)))

(defn bytes-of ^bytes [e] (.getBytes (canonical/emit (dissoc e :event/id)) "UTF-8"))

(defn append-signed! [s e key]
  (store/append! s e)
  (when key
    (store/put-sidecar! s (:event/ticket e)
                        (str (:event/id e) ".sig." (sign/fingerprint (sign/pubkey key)))
                        (sign/sign-bytes key (bytes-of e)))))

(defn fixture
  "A store with seb in `actors`, a registry ticket and a ticket to review.
  Returns the pieces a case needs to write its own delegation and verdict.
  `:key-type` is the ssh-keygen type of seb's key, ed25519 by default."
  ([] (fixture {}))
  ([{:keys [key-type] :or {key-type "ed25519"}}]
   (let [root (h/temp-dir! "tik-delegation")
         seb (keygen! root "seb" key-type)
         throwaway (keygen! root "throwaway")
         env {"TIK_KEY" (str seb)}
         run (fn [& args] (:out (apply h/tik! {:root root :actor "seb" :env env} args)))]
     (doseq [p ["identity-registry" "support-request"]]
       (io/copy (io/file repo "processes" (str p ".edn"))
                (io/file (doto (io/file root "processes") (.mkdirs)) (str p ".edn"))))
     (run "actor" "add" "seb" (str seb ".pub"))
     (let [registry (java.util.UUID/fromString (str/trim (run "new" "identity-registry" "--title" "registry")))
           ticket (java.util.UUID/fromString (str/trim (run "new" "support-request" "--title" "review me")))
           s (fstore/file-store (str root))]
       {:root root :s s :seb seb :throwaway throwaway
        :registry registry :ticket ticket
        :registry-head (first (dag/heads (store/events s registry)))
        :head (first (dag/heads (store/events s ticket)))}))))

(defn delegate! [{:keys [s registry registry-head ticket head throwaway]} key
                 & {:keys [at not-after scope facts]}]
  (let [at (or at (now))
        e (event/add-attestation
           {:ticket registry :actor "seb" :at at :parents [registry-head]
            :claim {:claim :delegation
                    :delegation/public-key (sign/pubkey throwaway)
                    :delegation/not-after (or not-after (.plusSeconds ^Instant at 240))
                    :delegation/scope (or scope #{[ticket head]})
                    :delegation/facts (or facts #{[:review :verdict]})}})]
    (append-signed! s e key)
    e))

(defn verdict! [{:keys [s ticket head throwaway]} & {:keys [at path actor]}]
  (append-signed! s (event/assert-fact {:ticket ticket :actor (or actor "seb")
                                        :at (or at (now)) :parents [head]
                                        :path (or path [:review :verdict]) :value :approved})
                  throwaway))

(defn delegate-with-security-key! [f signer flags]
  (spit (io/file (:root f) "actors")
        (str (sign/allowed-signers-line "seb" (sk/public-line signer)) "\n")
        :append true)
  (let [e (delegate! f nil)]
    (store/put-sidecar! (:s f) (:registry f) (str (:event/id e) ".sig.sk")
                        (.getBytes ^String (sk/sign signer sign/namespace-event (bytes-of e)
                                                    :flags flags)
                                   "UTF-8"))
    e))

