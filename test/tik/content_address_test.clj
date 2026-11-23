;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.content-address-test
  "Every content address comes from tik.canonical, so the hash a format
  version uses is named in one place (ADR 0006). A second prefix under a
  format-version bump is then one change, not a search through every
  namespace that ever hashed something."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.canonical :as canonical]))

(deftest an-address-is-the-prefix-and-the-hex-of-the-bytes
  (let [b (.getBytes "the bytes" "UTF-8")]
    (is (= (str "sha256-" (canonical/sha256-hex-bytes b)) (canonical/address-of-bytes b)))
    (is (= (canonical/address-of-bytes b) (canonical/address-of-text "the bytes")))
    (is (= (canonical/content-address {:a 1})
           (canonical/address-of-text (canonical/emit {:a 1}))))
    (is (str/starts-with? (canonical/address-of-bytes b) canonical/address-prefix))))

(deftest no-namespace-spells-the-prefix-itself
  (let [offenders (for [dir ["src" "cli"]
                        ^java.io.File f (file-seq (io/file dir))
                        :when (re-find #"\.clj[cs]?$" (.getName f))
                        :when (not= "canonical.cljc" (.getName f))
                        [i line] (map-indexed vector (str/split-lines (slurp f)))
                        :when (re-find #"\(str\s+\"sha256-\"" line)]
                    (str (.getPath f) ":" (inc i)))]
    (testing "an address is built with canonical/address-of-bytes or address-of-text"
      (is (empty? offenders) (str/join "\n" offenders)))))
