;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.link-test
  "A link's value is whatever somebody asserted, so reading one must
  decline nonsense rather than raise on it (ADR 0024)."
  (:require [clojure.test :refer [deftest is testing]]
            [tik.link :as link]))

(def ^:private t #uuid "e26a1d57-6643-4564-99fa-8968ced7afa5")

(deftest a-bare-uuid-is-a-ticket-in-this-store
  (is (= {:ticket t} (link/ref-of t)))
  (is (= {:ticket t} (link/ref-of (str t)))
      "the string form a person types means the same thing")
  (is (nil? (:store (link/ref-of t)))
      "local by omission — a store name only ever locates"))

(deftest a-map-carries-the-head-that-was-observed
  (let [v {:ticket t :head "sha256-abc" :store "releases"}]
    (is (= v (link/ref-of v)))
    (is (= "sha256-abc" (link/observed-head v))
        "which version of the other ticket the claim was made against")))

(deftest a-value-that-is-not-a-reference-is-declined-not-raised
  (doseq [v [42 nil "" "not-a-uuid" {} {:ticket "nope"} [:a] {:head "sha256-x"}]]
    (testing (pr-str v)
      (is (nil? (link/ref-of v))))))

(deftest identity-is-the-ticket-alone
  (is (link/same-target? t {:ticket t :store "elsewhere" :head "sha256-z"})
      "uuids are globally unique, so a store never disambiguates")
  (is (not (link/same-target? t {:ticket #uuid "018f2f6e-7c1a-7000-8000-000000000001"}))))

(deftest foreign-means-a-different-store-was-named
  (is (link/foreign? {:ticket t :store "releases"} "tik"))
  (is (not (link/foreign? {:ticket t :store "tik"} "tik")))
  (is (not (link/foreign? {:ticket t} "tik"))
      "no store named is local by omission"))

(deftest the-short-form-survives-a-round-trip
  (is (= t (link/render {:ticket t}))
      "a local link stays as short as it always was")
  (is (= {:ticket t :store "releases"}
         (link/render {:ticket t :store "releases"}))))

(deftest what-a-person-types
  (is (= {:ticket t} (link/parse-cli (str t))))
  (is (= {:ticket t :store "releases"} (link/parse-cli (str t "@releases"))))
  (is (= {:ticket t :store "releases" :head "sha256-abc"}
         (link/parse-cli (str t "@releases#sha256-abc"))))
  (is (nil? (link/parse-cli "garbage")))
  (is (nil? (link/parse-cli nil))))

(deftest the-reserved-prefix-tells-waiting-apart-from-undone
  (is (link/link-path? [:link :fixed-in]))
  (is (not (link/link-path? [:severity])))
  (is (= :fixed-in (link/link-name [:link :fixed-in])))
  (is (nil? (link/link-name [:severity]))))
