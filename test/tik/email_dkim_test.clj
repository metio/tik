;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.email-dkim-test
  "The email bridge gates the From→actor mapping on DKIM: it consumes the
  standard Authentication-Results verdict of the MTA it runs behind (RFC
  8601), pinned to that MTA's authserv-id so a forged verdict is ignored,
  and requires the From domain to be DKIM-aligned before attributing
  events to an actor."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [tik.cli]
            [tik.harness :as h]
            [tik.store.protocol :as store]))

(def ^:private parse-rfc822 @#'tik.cli/parse-rfc822)
(def ^:private ticket-ref-of @#'tik.cli/ticket-ref-of)
(def ^:private dkim-passing-domains @#'tik.cli/dkim-passing-domains)
(def ^:private dkim-aligned? @#'tik.cli/dkim-aligned?)

(deftest passing_domains_only_from_a_trusted_verifier
  (let [ar (fn [& hs] (parse-rfc822
                       (str (str/join (map #(str "Authentication-Results: " % "\n") hs))
                            "From: a@b\n\nx")))]
    (testing "dkim=pass from the trusted verifier yields its header.d domain"
      (is (= #{"customer.example"}
             (dkim-passing-domains
              (ar "mx.trusted; spf=pass; dkim=pass header.d=customer.example header.s=s1")
              #{"mx.trusted"}))))
    (testing "a verifier NOT in the trusted set is ignored (forgery defense)"
      (is (= #{}
             (dkim-passing-domains
              (ar "evil.attacker; dkim=pass header.d=customer.example")
              #{"mx.trusted"}))))
    (testing "dkim=fail / none contributes nothing"
      (is (= #{}
             (dkim-passing-domains
              (ar "mx.trusted; dkim=fail header.d=customer.example")
              #{"mx.trusted"}))))
    (testing "multiple A-R headers: only the trusted-verifier passes count"
      (is (= #{"good.example"}
             (dkim-passing-domains
              (ar "evil; dkim=pass header.d=spoof.example"
                  "mx.trusted; dkim=pass header.d=good.example")
              #{"mx.trusted"}))))))

(deftest alignment_is_exact_or_subdomain
  (is (dkim-aligned? "customer.example" #{"customer.example"}))
  (is (dkim-aligned? "mail.customer.example" #{"customer.example"}) "subdomain aligns")
  (is (not (dkim-aligned? "customer.example" #{"other.example"})))
  (is (not (dkim-aligned? "customer.example.evil.test" #{"customer.example"}))
      "a lookalike parent is not a subdomain match"))

;; ------------------------------------------------ end to end via run-argv

(defn- run-bridge [root cfg email]
  (spit (io/file root "bridge.edn") cfg)
  (h/with-cli-root root
    (fn []
      (binding [*in* (java.io.StringReader. email)]
        (tik.cli/run-argv ["bridge" "email"
                           "--config" (str (io/file root "bridge.edn"))])))))

(def ^:private cfg
  "{:process :track :default-actor \"inbound\"
    :dkim {:require true :authserv-id \"mx.support.example\"}}")

(defn- ticket-count [store]
  (count (store/ticket-ids store)))

(deftest bridge_requires_dkim_before_attributing
  (testing "a trusted, aligned dkim=pass opens the ticket"
    (let [{:keys [root store]} (h/temp-store!)
          r (run-bridge root cfg
                        (str "Authentication-Results: mx.support.example;"
                             " dkim=pass header.d=customer.example\n"
                             "From: alice@customer.example\nSubject: help\n\nbody"))]
      (is (zero? (:exit r)) (:err r))
      (is (= 1 (ticket-count store)))))
  (testing "no Authentication-Results -> refused, nothing written"
    (let [{:keys [root store]} (h/temp-store!)
          r (run-bridge root cfg
                        "From: alice@customer.example\nSubject: help\n\nbody")]
      (is (= 1 (:exit r)))
      (is (re-find #"no dkim=pass" (:err r)))
      (is (zero? (ticket-count store)) "no ticket created for an unverified sender")))
  (testing "a dkim=pass forged by an UNtrusted verifier -> refused"
    (let [{:keys [root store]} (h/temp-store!)
          r (run-bridge root cfg
                        (str "Authentication-Results: evil.attacker.test;"
                             " dkim=pass header.d=customer.example\n"
                             "From: alice@customer.example\nSubject: x\n\nbody"))]
      (is (= 1 (:exit r)))
      (is (zero? (ticket-count store)))))
  (testing "no :dkim config -> unchanged (the gate is opt-in)"
    (let [{:keys [root store]} (h/temp-store!)
          r (run-bridge root "{:process :track :default-actor \"inbound\"}"
                        "From: alice@customer.example\nSubject: help\n\nbody")]
      (is (zero? (:exit r)) (:err r))
      (is (= 1 (ticket-count store)))))
  (testing "a malformed Authentication-Results on the gated path fails
            CLOSED and CLEAN — not a crash, not a bypass"
    (let [{:keys [root store]} (h/temp-store!)
          r (run-bridge root cfg
                        (str "Authentication-Results: ;;;;\n"
                             "From: alice@customer.example\nSubject: x\n\nbody"))]
      (is (= 1 (:exit r)))
      (is (h/clean-output? (str (:out r) (:err r))))
      (is (zero? (ticket-count store))))))

(defspec email_helpers_are_total_over_hostile_input 300
  ;; an inbound email is fully attacker-controlled; parsing, ticket
  ;; association, and the DKIM verdict must each be TOTAL — a value or a
  ;; clean answer, never a raw throw (a header of all semicolons NPE'd
  ;; str/trim on the gate path before this was pinned).
  (prop/for-all [text (gen/one-of
                       [gen/string
                        gen/string-alphanumeric
                        ;; header-shaped garbage
                        (gen/fmap #(str/join "\n" %)
                                  (gen/vector
                                   (gen/elements
                                    ["From:" "From: a@b" "Subject: ;;;"
                                     "Authentication-Results: ;;;;"
                                     "Authentication-Results: mx; dkim=pass header.d="
                                     "X-Tik-Ticket:" "In-Reply-To: <tik.@x>"
                                     "References: <>" " folded" "\t" "" ";=;=;"])
                                   0 8))])]
    (let [m (parse-rfc822 text)]
      (and (map? m)
           (try (let [_ (ticket-ref-of m)
                      _ (dkim-passing-domains m #{"mx"})]
                  (boolean? (dkim-aligned? (:from m) #{"x" "mx"})))
                (catch Throwable _ false))))))
