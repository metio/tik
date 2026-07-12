;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.canonical-test
  "The golden tests. If any of these break, you have changed the canonical
  format: every existing event id and signature in every store is now
  unverifiable. Bump tik.canonical/format-version and think very hard."
  (:require [clojure.edn]
            [clojure.test :refer [deftest is testing]]
            [tik.canonical :as c])
  (:import (java.time Instant)))

(def fixture
  {:event/actor "seb"
   :event/at (Instant/parse "2026-07-08T12:00:00Z")
   :event/body {:fact/path [:category] :fact/value :billing}
   :event/ticket #uuid "018f2f6e-7c1a-7000-8000-000000000000"
   :event/type :fact/assert})

(def golden-canonical
  (str "{:event/actor \"seb\""
       " :event/at #inst \"2026-07-08T12:00:00Z\""
       " :event/body {:fact/path [:category] :fact/value :billing}"
       " :event/ticket #uuid \"018f2f6e-7c1a-7000-8000-000000000000\""
       " :event/type :fact/assert}"))

;; sha256 of golden-canonical, computed independently (sha256sum)
(def golden-hash
  "sha256-9dc5480de9f151e82a5408ec072dcd5016b6d60792da2c0f4cf3c61815f399ce")

(deftest golden-string
  (is (= golden-canonical (c/emit fixture))))

(deftest golden-content-address
  (is (= golden-hash (c/content-address fixture))))

(deftest key-order-independence
  (let [a (array-map :b 2 :a 1 :c #{3 1 2})
        b (array-map :c #{2 3 1} :a 1 :b 2)]
    (is (= (c/emit a) (c/emit b)))
    (is (= (c/content-address a) (c/content-address b)))))

(deftest instant-normalized-to-millis
  (is (= (c/emit (Instant/parse "2026-07-08T12:00:00.123456789Z"))
         (c/emit (Instant/parse "2026-07-08T12:00:00.123Z")))))

(deftest default-reader-insts-are-rejected-like-any-unsupported-type
  ;; one time type: the canonical readers produce Instant, the printer
  ;; accepts only Instant — the java.util.Date the DEFAULT #inst reader
  ;; yields must fail at emit, not silently alias
  (is (thrown? clojure.lang.ExceptionInfo
               (c/emit (clojure.edn/read-string
                        "#inst \"2026-07-08T12:00:00Z\"")))))

(deftest unsupported-types-rejected
  (testing "floats are not byte-stable across runtimes"
    (is (thrown? clojure.lang.ExceptionInfo (c/emit 1.5)))
    (is (thrown? clojure.lang.ExceptionInfo (c/emit {:a 1/3})))))
