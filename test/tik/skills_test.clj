;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.skills-test
  "The served skill is the repository's skills/ tree, its manifest
  digests the bytes, and the manifest signature verifies only for the
  bytes and the actor it was made for."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tik.harness :as h]
            [tik.sign :as sign]
            [tik.skills :as skills])
  (:import (java.nio.file Files)
           (java.security MessageDigest)))

(defn- tree
  "relative path -> bytes for every file under `dir`."
  [^java.io.File dir]
  (let [base (.toPath dir)]
    (into (sorted-map)
          (for [^java.io.File f (file-seq dir) :when (.isFile f)]
            [(str/replace (str (.relativize base (.toPath f))) java.io.File/separator "/")
             (Files/readAllBytes (.toPath f))]))))

(defn- served-tree [skill]
  (into (sorted-map) (for [[_ {:keys [path bytes]}] (:files skill)] [path bytes])))

(deftest the-served-skill-equals-skills-tik-byte-for-byte
  (let [served (first skills/catalog)
        local (tree (io/file "skills" "tik"))]
    (is (= 1 (count skills/catalog)) "tik serves exactly its own skill")
    (is (= "tik" (:skill served)))
    (is (= (keys local) (keys (served-tree served))) "the same file set")
    (doseq [[p b] local]
      (is (MessageDigest/isEqual ^bytes b ^bytes (get (served-tree served) p)) p))))

;; Claude Code loads an MCP skill only within these limits; the build
;; fails before a release could ship a skill it would refuse. Counted in
;; decimal kilobytes, the stricter reading of "100 KB".
(def ^:private max-file-bytes 100000)
(def ^:private max-skill-bytes 400000)

(deftest every-served-skill-fits-claude-codes-size-limits
  (doseq [{:keys [skill resources]} skills/catalog]
    (let [total (reduce + (map :size resources))]
      (println (str "skill " skill ": " (count resources) " file(s), " total " bytes; "
                    (str/join ", " (for [{:keys [uri size]} resources] (str uri " " size)))))
      (is (<= total max-skill-bytes) (str skill " is " total " bytes"))
      (doseq [{:keys [uri size]} resources]
        (is (<= size max-file-bytes) (str uri " is " size " bytes"))))))

(defn- sha256-hex [^bytes b]
  (str/join (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") b))))

(deftest the-manifest-digests-and-sizes-are-the-bytes
  (doseq [{:keys [resources files uri]} skills/catalog]
    (is (= uri (:uri (first resources))) "SKILL.md comes first")
    (is (= (count files) (count resources)))
    (doseq [{u :uri :keys [digest size]} resources
            :let [b (:bytes (get files u))]]
      (is (= size (count b)) u)
      (is (= digest (str "sha256:" (sha256-hex b))) u))))

(deftest skill-uris-outside-the-grammar-are-refused
  (is (= ["tik" "SKILL.md"] (skills/parse-uri "skill://tik/SKILL.md")))
  (is (= ["tik" "references/a.md"] (skills/parse-uri "skill://tik/references/a.md")))
  (doseq [bad ["skill://tik/../tik/SKILL.md" "skill://tik/./SKILL.md" "skill://../SKILL.md"
               "skill://tik//etc/passwd" "skill:///etc/passwd" "skill://tik/"
               "skill://tik" "skill://tik/%2e%2e/SKILL.md" "skill://tik%2FSKILL.md"
               "skill://tik/a%2Fb" "skill://tik/a\\b" "skill://tik/~x" "skill://tik/a b" "skill://tik/SKILL.md/"
               "file:///etc/passwd" "SKILL://tik/SKILL.md" "skill://tik/x\n" "" nil 42]]
    (is (nil? (skills/parse-uri bad)) (pr-str bad))))

(deftest the-frontmatter-reader-accepts-only-what-it-can-read-faithfully
  (is (= [["name" "x"] ["description" "Does a: thing"]]
         (skills/frontmatter "---\nname: x\ndescription: \"Does a: thing\"\n---\nbody\n")))
  (is (= [["name" "x"] ["description" "it's"]]
         (skills/frontmatter "---\r\nname: x\r\ndescription: 'it''s'\r\n---\r\n")))
  (doseq [bad ["no frontmatter\n" "---\nname: x\n" "---\nname: x\nname: y\n---\n"
               "---\nname: |\n  x\n---\n" "---\nname: [a]\n---\n" "---\nname: x # c\n---\n"
               "---\nname: a: b\n---\n" "---\nflag: true\n---\n" "---\nn: 1.5\n---\n"
               "---\nname:\n---\n" "---\n  nested: x\n---\n" "---\nname: \"open\n---\n"]]
    (is (thrown? clojure.lang.ExceptionInfo (skills/frontmatter bad)) (pr-str bad))))

(defn- b [s] (.getBytes ^String s "UTF-8"))

(def ^:private good-md "---\nname: demo\ndescription: A demo skill.\n---\n# Demo\n")

(deftest an-invalid-skill-is-refused-when-it-is-built
  (is (= "skill://demo/SKILL.md" (:uri (skills/build-skill "demo" {"SKILL.md" (b good-md)}))))
  (testing "references sort after SKILL.md"
    (is (= ["skill://demo/SKILL.md" "skill://demo/a.md" "skill://demo/z/b.md"]
           (map :uri (:resources (skills/build-skill "demo" {"z/b.md" (b "b") "SKILL.md" (b good-md)
                                                             "a.md" (b "a")}))))))
  (doseq [[why skill files] [["no SKILL.md" "demo" {"a.md" (b "a")}]
                             ["name mismatch" "other" {"SKILL.md" (b good-md)}]
                             ["path with a space" "demo" {"SKILL.md" (b good-md) "a b.md" (b "x")}]
                             ["traversal path" "demo" {"SKILL.md" (b good-md) "../x" (b "x")}]
                             ["no description" "demo" {"SKILL.md" (b "---\nname: demo\n---\n")}]
                             ["not UTF-8" "demo" {"SKILL.md" (byte-array [(unchecked-byte 0xff)])}]]]
    (is (thrown? clojure.lang.ExceptionInfo (skills/build-skill skill files)) why)))

(deftest canonical-bytes-are-order-free-and-refuse-ambiguity
  (let [f1 {:uri "skill://demo/SKILL.md" :digest (str "sha256:" (str/join (repeat 64 "a"))) :size 3}
        f2 {:uri "skill://demo/a.md" :digest (str "sha256:" (str/join (repeat 64 "b"))) :size 0}
        entry {:uri "skill://demo/SKILL.md" :resources [f1 f2]}]
    (is (= (str "skill-manifest v1\nskill skill://demo/SKILL.md\n"
                "file sha256:" (str/join (repeat 64 "a")) " 3 skill://demo/SKILL.md\n"
                "file sha256:" (str/join (repeat 64 "b")) " 0 skill://demo/a.md\n")
           (String. ^bytes (skills/canonical-bytes entry) "UTF-8")))
    (is (= (seq (skills/canonical-bytes entry))
           (seq (skills/canonical-bytes (assoc entry :resources [f2 f1])))))
    (doseq [[why e] [["duplicate uri" (assoc entry :resources [f1 f1])]
                     ["SKILL.md missing" (assoc entry :resources [f2])]
                     ["no files" (assoc entry :resources [])]
                     ["another skill's file" (assoc entry :resources [f1 (assoc f2 :uri "skill://x/a.md")])]
                     ["uppercase digest" (assoc entry :resources [(update f1 :digest str/upper-case)])]
                     ["short digest" (assoc entry :resources [(assoc f1 :digest "sha256:ab")])]
                     ["negative size" (assoc entry :resources [(assoc f1 :size -1)])]
                     ["fractional size" (assoc entry :resources [(assoc f1 :size 1.5)])]
                     ["uri with a space" (assoc entry :resources [f1 (assoc f2 :uri "skill://demo/a b")])]
                     ["uri with a newline" (assoc entry :resources [f1 (assoc f2 :uri "skill://demo/a\nfile")])]
                     ["resources not a list" (assoc entry :resources "dynamic")]]]
      (is (nil? (skills/canonical-bytes e)) why))))

;; ---------------------------------------------------------- signatures

(defn- key! [dir name]
  (let [k (io/file dir name)]
    (sh/sh "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-C" name "-f" (str k))
    {:name name :key (str k) :pub (str/trim (:out (sh/sh "ssh-keygen" "-y" "-f" (str k))))}))

(defn- signers [& ks]
  (str/join (for [{:keys [name pub]} ks] (str name " namespaces=\"tik-*\" " pub "\n"))))

(defn- wire
  "A skill entry as a client parses it from JSON."
  [skill signer]
  (json/parse-string (json/generate-string (skills/wire-entry skill signer))))

(deftest a-signed-manifest-verifies-and-any-tampering-fails
  (let [dir (h/temp-dir! "tik-skill-sign")
        alice (key! dir "alice")
        mallory (key! dir "mallory")
        registry (signers alice mallory)
        skill (first skills/catalog)
        entry (wire skill {:key (:key alice) :actor "alice"})
        meta (get-in entry ["_meta" skills/meta-key])]
    (is (= {"format" "skill-manifest-v1" "namespace" "tik-skill-manifest" "signed" true "actor" "alice"}
           (dissoc meta "signature")))
    (is (= {:verdict :verified :actor "alice" :namespace "tik-skill-manifest"}
           (skills/verify-entry registry entry)))
    (testing "a changed digest, size or extra file breaks the signature"
      (is (= :bad-signature (:verdict (skills/verify-entry registry (assoc-in entry ["resources" 0 "digest"]
                                                                              (str "sha256:" (str/join (repeat 64 "0"))))))))
      (is (= :bad-signature (:verdict (skills/verify-entry registry (update-in entry ["resources" 0 "size"] inc)))))
      (is (= :bad-signature (:verdict (skills/verify-entry registry (update entry "resources" conj
                                                                            {"uri" "skill://tik/extra.md"
                                                                             "digest" (str "sha256:" (str/join (repeat 64 "1")))
                                                                             "size" 1}))))))
    (testing "the signature is bound to its actor"
      (is (= :bad-signature (:verdict (skills/verify-entry registry (assoc-in entry ["_meta" skills/meta-key "actor"] "mallory")))))
      (is (= :bad-signature (:verdict (skills/verify-entry (signers mallory) entry))) "alice is not registered"))
    (testing "a signature made for another purpose is refused"
      (let [event-sig (String. ^bytes (sign/sign-bytes (:key alice) (skills/canonical-bytes skill) sign/namespace-event) "UTF-8")]
        (is (= :bad-signature (:verdict (skills/verify-entry registry (assoc-in entry ["_meta" skills/meta-key "signature"] event-sig))))
            "a tik-event signature over the same bytes")
        (is (= :malformed (:verdict (skills/verify-entry registry (assoc-in entry ["_meta" skills/meta-key "namespace"] "tik-event"))))
            "a namespace outside <product>-skill-manifest")))
    (testing "a namespace the actor's key is not granted for is refused"
      (let [kajero-sig (String. ^bytes (sign/sign-bytes (:key alice) (skills/canonical-bytes skill) "kajero-skill-manifest") "UTF-8")]
        (is (= :bad-signature (:verdict (skills/verify-entry registry (-> entry
                                                                          (assoc-in ["_meta" skills/meta-key "signature"] kajero-sig)
                                                                          (assoc-in ["_meta" skills/meta-key "namespace"] "kajero-skill-manifest"))))))))
    (testing "a file whose bytes differ from the manifest is caught"
      (let [uri (get entry "uri")
            bytes (:bytes (skills/file-at skills/catalog uri))]
        (is (nil? (skills/check-file entry uri bytes)))
        (is (some? (skills/check-file entry uri (b (str (String. ^bytes bytes "UTF-8") "x")))))
        (is (some? (skills/check-file entry "skill://tik/other.md" bytes)))))))

(deftest without-a-key-the-entry-says-it-is-unsigned
  (let [entry (wire (first skills/catalog) {:key nil :actor "agent"})
        meta (get-in entry ["_meta" skills/meta-key])]
    (is (= #{"format" "signed" "reason"} (set (keys meta))) "the shared unsigned marker, nothing more")
    (is (false? (get meta "signed")))
    (is (str/includes? (get meta "reason") "TIK_KEY"))
    (is (= :unsigned (:verdict (skills/verify-entry "" entry))))
    (is (= :missing (:verdict (skills/verify-entry "" (dissoc entry "_meta")))))
    (is (= :malformed (:verdict (skills/verify-entry "" (assoc-in entry ["_meta" skills/meta-key "format"] "v0")))))
    (is (= :malformed (:verdict (skills/verify-entry "" (assoc-in entry ["_meta" skills/meta-key "signed"] "yes")))))))

(deftest a-key-that-cannot-sign-is-reported-not-hidden
  (let [meta (skills/sign-manifest (first skills/catalog) {:key "/nonexistent/key" :actor "a"})]
    (is (false? (get meta "signed")))
    (is (str/includes? (get meta "reason") "failed"))))
