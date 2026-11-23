;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.hash-policy-test
  "The hash a format version uses is a row in one table (ADR 0006,
  ADR 0036 point 8). A verifier knows the hashes of the versions it
  implements and nothing else, so an id's prefix never chooses one."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.canonical :as canonical]))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(deftest format-version-1-is-sha-256
  (is (= {:prefix "sha256-" :algorithm "SHA-256"} (canonical/hash-spec 1)))
  (is (= canonical/address-prefix (:prefix (canonical/hash-spec canonical/format-version))))
  (is (= "sha256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (canonical/address-of-bytes (utf8 "abc"))
         (canonical/address-of-text "abc"))))

(deftest sha-512-is-implemented-and-used-by-no-version
  (let [spec {:prefix "sha512-" :algorithm "SHA-512"}]
    (is (= (str "sha512-ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f")
           (canonical/address-with spec (utf8 "abc"))))
    (is (not-any? #{"SHA-512"} (map :algorithm (vals canonical/hash-of-version))))))

(deftest an-unknown-version-or-algorithm-is-refused
  (testing "a version this build does not implement"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"format version"
                          (canonical/hash-spec 2))))
  (testing "an algorithm outside the implemented set, even if the JDK has it"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hash algorithm"
                          (canonical/address-with {:prefix "md5-" :algorithm "MD5"}
                                                  (utf8 "abc"))))))

(deftest every-row-names-an-implemented-algorithm-and-a-distinct-prefix
  (let [rows (vals canonical/hash-of-version)]
    (is (every? (comp canonical/implemented-algorithms :algorithm) rows))
    (is (apply distinct? (map :prefix rows)))))
