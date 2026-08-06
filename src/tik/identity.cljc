;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.identity
  "The identity lens: key bindings as evidence (PLAN §9 rung 2, ADR 0010).

  A registry ticket accumulates `:identity` attestations, each claiming
  that an IdP subject presented a token and that this actor's public key
  belongs to them. This namespace reads that log and answers one
  question: WHICH KEYS MAY SIGN AS WHICH ACTOR?

  Two properties make the answer safe to build a trust base on.

  **A binding is a claim, not an authority.** Anyone may append an
  attestation saying their key is actor `seb`; nothing about writing it
  makes it true. What makes a binding count is that its id-token
  verifies against the issuer's signing keys — so trust flows from the
  IdP's signature, not from having reached the log. The check is
  injected (`trusted?`) exactly as tik.oid4vci injects its verifier:
  parsing and selection stay pure and testable here, and the porcelain
  supplies the cryptography and the pinned key material.

  **Verification never calls the IdP.** The id-token rides inside the
  attestation and the issuer's keys are pinned in the store, so a
  binding is checkable offline, years later, on a machine that has
  never heard of the issuer — the same promise the rest of the ladder
  makes. An IdP that disappears does not invalidate history.

  Bindings accumulate rather than replace: a rotated key is a new
  binding, and the old one keeps saying what was true when it was
  written. Nothing here decides whether a key is CURRENT, because
  nothing here knows when the signature it is judging was made — that
  question belongs to the caller holding the event's own timestamp."
  (:require [tik.reduce :as red]))

(def claim-type
  "The attestation body's :claim value that marks a key binding."
  :identity)

(defn bindings
  "Every key binding claimed on a registry ticket's log, in fold order.

  Reads `:attestation/add` events only — attestations are lens material
  and `ticket-state` ignores them on purpose, so this walks the ordered
  event set itself. Each entry carries the event that made the claim, so
  a caller can say WHICH signed event a trust decision rests on."
  [events]
  (into []
        (comp (filter #(= :attestation/add (:event/type %)))
              (filter #(= claim-type (get-in % [:event/body :claim])))
              (map (fn [e]
                     (let [b (:event/body e)]
                       {:actor (:identity/actor b)
                        :public-key (:identity/public-key b)
                        :issuer (:identity/issuer b)
                        :subject (:identity/subject b)
                        :username (:identity/username b)
                        :id-token (:identity/id-token b)
                        :event (:event/id e)
                        :at (:event/at e)}))))
        (red/ordered events)))

(defn- usable?
  "A binding missing any of the three fields a trust decision needs is
  not a weak claim, it is not a claim: there is nothing to check.

  The `map?` guard is load-bearing rather than defensive noise. These
  functions are public and take whatever a caller hands them, and map
  destructuring of a SEQ routes through kwargs support — which throws on
  an odd element count instead of returning nil. A trust base must not
  raise on garbage it is asked to judge; it must decline it."
  [b]
  (and (map? b)
       (let [{:keys [actor public-key id-token]} b]
         (and (string? actor) (seq actor)
              (string? public-key) (seq public-key)
              (string? id-token) (seq id-token)))))

(defn verified
  "The bindings `trusted?` accepts — the ones whose id-token really came
  from the issuer they name.

  `trusted?` is one predicate over a binding map, injected so this stays
  a pure function of the log: the porcelain passes one that checks the
  JWT against pinned JWKS, and a test passes one that answers from a
  set. A predicate that throws is treated as a refusal, because a
  binding that cannot be checked has not been verified — failing open
  here would let a malformed token widen the trust base."
  [bindings trusted?]
  (into [] (filter (fn [b]
                     (and (usable? b)
                          (try (boolean (trusted? b))
                               (catch #?(:clj Throwable :cljs :default) _
                                 false)))))
        (when (seqable? bindings) bindings)))

(defn signing-keys
  "actor -> #{public-key} over verified bindings: who may sign as whom.

  The shape a verifier wants — an actor with no verified binding is
  absent rather than present-and-empty, so a caller cannot mistake
  \"nobody vouched for this actor\" for \"this actor has no keys\"."
  [verified-bindings]
  (reduce (fn [acc b]
            ;; same reason as `usable?`: anything that is not a map is
            ;; declined rather than destructured
            (if-let [{:keys [actor public-key]} (when (map? b) b)]
              (update acc actor (fnil conj #{}) public-key)
              acc))
          {}
          (when (seqable? verified-bindings) verified-bindings)))
