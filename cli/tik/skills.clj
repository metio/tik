;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.skills
  "The agent skills tik serves over MCP (the Skills extension,
  io.modelcontextprotocol/skills), and the signed manifest that lets our
  own tooling check what an agent received.

  The one source is the repository's `skills/` tree, which is a classpath
  root: `skills/tik/SKILL.md` is the resource `tik/SKILL.md` on babashka,
  on the JVM and in the uberjar. The file list is taken from the
  directory when this namespace is compiled, and the bytes are read when
  it loads; the native image initializes it at build time, so the bytes
  live in the image heap and no resource is read at run time.

  A manifest lists every file of a skill with its sha256 digest and size,
  as the extension demands. Those digests only say the bytes match the
  manifest, so the manifest is also signed: the canonical bytes below are
  signed with the serving actor's key in the SSHSIG namespace
  `tik-skill-manifest`, and the signature travels in the skill entry's
  `_meta` under `wtf.metio/skill-manifest`. Without a key the entry
  says it is unsigned and why, so a missing signature is never silent."
  (:require [briko.json :as json]
            [briko.skill-manifest :as sm]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.util Base64)))

(def extension-id "io.modelcontextprotocol/skills")

(def manifest-format
  "The format tag of the `_meta` marker; the canonical bytes open with
  `skill-manifest v1`."
  sm/manifest-format)

(def meta-key
  "The `_meta` key of a skill entry that carries the manifest signature,
  shared with kajero so one verifier checks both (reverse DNS of
  metio.wtf, as MCP's `_meta` prefix rule asks)."
  sm/meta-key)

(def signature-namespace
  "The SSHSIG namespace of manifest signatures. `actors` lines grant
  `tik-*`, so the serving actor's registered key covers it, and no event,
  definition or witness signature can pass as a manifest signature."
  "tik-skill-manifest")

(def accepted-namespaces
  "Namespaces a verifier accepts: one per product (tik, kajero), so one
  verifier checks both while a signature made for any other purpose is
  refused before the key is looked at."
  #{"tik-skill-manifest" "kajero-skill-manifest"})

(def ^:private skill-file "SKILL.md")

;; the extension's own ceilings per skill (servers SHOULD NOT exceed them)
(def max-files 512)
(def max-skill-bytes (* 16 1024 1024))

(def limits
  "What a manifest may declare, in signing and in verifying: the
  extension's ceilings, with one file allowed to be the whole skill."
  {:max-files max-files :max-file-bytes max-skill-bytes :max-skill-bytes max-skill-bytes})

;; ------------------------------------------------------------ uri grammar
;; The grammar is the shared manifest format's (briko.skill-manifest):
;; skill names are lowercase letters, digits and single inner hyphens;
;; path segments letters, digits, `.`, `_` and `-`, never `.` or `..`, so
;; a segment means the same thing escaped or not and `%`, `\\`, spaces
;; and control characters never reach a lookup.

(def valid-relative-path?
  "Is `p` a path inside a skill: non-empty segments of unreserved
  characters, none of them `.` or `..`, no leading or trailing slash?"
  sm/valid-relative-path?)

(def parse-uri
  "`skill://<skill>/<path>` as [skill path], or nil for anything else: a
  foreign scheme, a skill name outside the grammar, a traversal segment,
  an absolute or empty path, an escaped character. A URI this refuses
  can never name a served file, whatever the lookup behind it does."
  sm/parse-uri)

(def skill-uri sm/skill-uri)

;; ------------------------------------------------------------ frontmatter

(def ^:private plain-typed
  "Plain scalars YAML would read as something other than a string."
  #"(?i)(true|false|yes|no|on|off|null|~|[-+]?(\.?[0-9].*|\.inf|\.nan))")

(defn- scalar
  "One frontmatter value of the YAML subset we accept: a plain scalar, or
  a single- or double-quoted one on one line. Anything richer (block
  scalars, flow collections, anchors, tags, comments, a plain scalar YAML
  would type) is refused, so the frontmatter we serve is never a
  misreading of the file."
  [k v]
  (let [refuse #(throw (ex-info (str "SKILL.md frontmatter " k ": " %)
                                {:reason :skills/frontmatter :key k}))]
    (cond
      (str/blank? v) (refuse "an empty or nested value is not supported")
      (str/starts-with? v "\"")
      (if (and (< 1 (count v)) (str/ends-with? v "\""))
        (let [parsed (try (json/parse-string v)
                          (catch Exception _ ::bad))]
          (if (string? parsed) parsed (refuse "a malformed double-quoted string")))
        (refuse "an unterminated double-quoted string"))
      (str/starts-with? v "'")
      (let [inner (when (and (< 1 (count v)) (str/ends-with? v "'"))
                    (subs v 1 (dec (count v))))]
        (if (and inner (not (re-find #"(?<!')'(?!')" (str/replace inner "''" ""))))
          (str/replace inner "''" "'")
          (refuse "a malformed single-quoted string")))
      (re-find #"^[-?:,\[\]{}#&*!|>%@`]" v) (refuse "an indicator character starts the value")
      (or (str/includes? v ": ") (str/includes? v " #")) (refuse "a plain value holds ': ' or ' #'")
      (re-matches plain-typed v) (refuse "a plain value YAML reads as a non-string")
      :else v)))

(defn frontmatter
  "The YAML frontmatter at the start of a SKILL.md as an ordered vector
  of [key value] string pairs. Refuses a file without one, a duplicate
  key and every YAML construct `scalar` does not accept."
  [^String text]
  (let [text (str/replace text "\r\n" "\n")
        _ (when-not (str/starts-with? text "---\n")
            (throw (ex-info "SKILL.md must start with a '---' line" {:reason :skills/frontmatter})))
        body (subs text 4)
        end (str/index-of body "\n---\n")
        _ (when-not end
            (throw (ex-info "SKILL.md frontmatter has no closing '---' line" {:reason :skills/frontmatter})))
        lines (str/split-lines (subs body 0 end))
        pairs (vec (for [line lines]
                     (if-let [[_ k v] (re-matches #"([A-Za-z0-9_-]+):(?: (.*))?" line)]
                       [k (scalar k (str/trim (or v "")))]
                       (throw (ex-info (str "SKILL.md frontmatter line not supported: " (pr-str line))
                                       {:reason :skills/frontmatter})))))]
    (when-let [dupe (some (fn [[k n]] (when (< 1 n) k)) (frequencies (map first pairs)))]
      (throw (ex-info (str "SKILL.md frontmatter repeats " dupe) {:reason :skills/frontmatter})))
    pairs))

;; ------------------------------------------------------------ the catalog

(defn- utf8-text
  "The bytes as a String when they are valid UTF-8, else nil."
  [^bytes b]
  (try (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                       (.onMalformedInput CodingErrorAction/REPORT)
                       (.onUnmappableCharacter CodingErrorAction/REPORT))
                     (ByteBuffer/wrap b)))
       (catch CharacterCodingException _ nil)))

(defn mime-type [path]
  (let [ext (some-> (re-find #"\.([^./]+)$" path) second str/lower-case)]
    (case ext
      ("md" "markdown") "text/markdown"
      "txt" "text/plain"
      "edn" "application/edn"
      "json" "application/json"
      ("yaml" "yml") "application/yaml"
      "sh" "text/x-shellscript"
      "application/octet-stream")))

(def digest
  "`sha256:` and the lowercase hex sha256 of a byte array."
  sm/digest)

(defn build-skill
  "One skill from its name and a map of relative path -> bytes: the entry
  `skills/list` returns (SKILL.md first, then the files in URI order) and
  the files `resources/read` serves. Refuses a skill the extension would
  call invalid: no SKILL.md, a frontmatter name other than the directory,
  no description, a path outside the URI grammar, or the extension's
  file and size ceilings exceeded."
  [skill files]
  (when-not (sm/valid-skill-name? skill)
    (throw (ex-info (str "skill name not usable in a URI: " (pr-str skill)) {:reason :skills/name})))
  (doseq [p (keys files)]
    (when-not (valid-relative-path? p)
      (throw (ex-info (str "skill " skill " holds a path outside the URI grammar: " (pr-str p))
                      {:reason :skills/path}))))
  (let [skill-md (or (get files skill-file)
                     (throw (ex-info (str "skill " skill " has no " skill-file) {:reason :skills/no-skill-md})))
        fm (frontmatter (or (utf8-text skill-md)
                            (throw (ex-info (str skill-file " is not UTF-8") {:reason :skills/frontmatter}))))
        fm-map (into {} fm)
        total (reduce + (map count (vals files)))]
    (when-not (= skill (get fm-map "name"))
      (throw (ex-info (str "frontmatter name " (pr-str (get fm-map "name"))
                           " does not match the directory " skill)
                      {:reason :skills/name})))
    (when (str/blank? (get fm-map "description"))
      (throw (ex-info "frontmatter needs a description" {:reason :skills/frontmatter})))
    (when (< max-files (count files))
      (throw (ex-info (str "skill " skill " exceeds " max-files " files") {:reason :skills/too-big})))
    (when (< max-skill-bytes total)
      (throw (ex-info (str "skill " skill " exceeds " max-skill-bytes " bytes") {:reason :skills/too-big})))
    (let [paths (cons skill-file (sort (remove #{skill-file} (keys files))))]
      {:skill skill
       :uri (skill-uri skill skill-file)
       :frontmatter fm
       :resources (vec (for [p paths
                             :let [b (get files p)]]
                         {:uri (skill-uri skill p) :digest (digest b) :size (count b)}))
       :files (into {} (for [p paths
                             :let [b (get files p)]]
                         [(skill-uri skill p) {:path p :bytes b :mime (mime-type p)}]))})))

(defmacro ^:private tree-paths
  "The relative paths of every file under the classpath directory
  `skill`, listed when this namespace is compiled. Compilation always
  sees the directory itself (babashka and the JVM read `skills/` from the
  checkout, the uberjar build compiles before it copies the skills in),
  so the list holds no matter how the bytes are later read.

  The skill `tik` shares its classpath prefix with the tik namespaces. A
  directory that also holds this namespace's own source is a merged
  class directory, and listing it would serve the sources as skill
  files, so compilation stops there instead."
  [skill]
  (let [url (or (io/resource (str skill "/" skill-file))
                (throw (ex-info (str "skills/" skill "/" skill-file " is not on the classpath")
                                {:reason :skills/not-on-classpath})))
        dir (.getParentFile (io/as-file url))
        base (.toPath dir)]
    (when (.exists (io/file dir "skills.clj"))
      (throw (ex-info (str dir " holds both the skill " skill " and tik's sources;"
                           " copy skills/ into a class directory only after compiling")
                      {:reason :skills/merged-directory})))
    (vec (sort (for [^java.io.File f (file-seq dir)
                     :when (.isFile f)]
                 (str/replace (str (.relativize base (.toPath f))) java.io.File/separator "/"))))))

(defn- read-resource ^bytes [path]
  (with-open [in (io/input-stream (or (io/resource path)
                                      (throw (ex-info (str "resource missing: " path)
                                                      {:reason :skills/not-on-classpath}))))]
    (.readAllBytes in)))

(defn- load-classpath-skill [skill paths]
  (build-skill skill (into {} (for [p paths] [p (read-resource (str skill "/" p))]))))

(def catalog
  "Every skill this build serves, in URI order."
  [(load-classpath-skill "tik" (tree-paths "tik"))])

(defn by-uri
  "The skill whose SKILL.md is `uri`, from `skills`."
  [skills uri]
  (some #(when (= uri (:uri %)) %) skills))

(defn file-at
  "The served file at `uri` as {:path :bytes :mime}, or nil."
  [skills uri]
  (some #(get (:files %) uri) skills))

;; --------------------------------------------------------- the manifest

(defn canonical-bytes
  "The bytes a manifest signature covers, or nil when the manifest
  cannot be encoded without ambiguity under `limits`:

      skill-manifest v1\n
      skill <uri of SKILL.md>\n
      file <digest> <size> <uri>\n   one per file, sorted by URI

  The encoding is briko.skill-manifest's, shared with kajero."
  [entry]
  (when-not (sm/problem entry limits)
    (sm/canonical-bytes entry limits)))

(defn sign-manifest
  "The `_meta` value for `entry`: signed with `key` as `actor`, or an
  explicit unsigned marker naming `reason` when no key is configured or
  signing failed."
  [entry {:keys [key actor]}]
  (if (str/blank? key)
    (sm/unsigned "no signing key is configured (TIK_KEY is unset)")
    (try
      (sm/sign entry {:key-file key :actor actor :namespace signature-namespace :limits limits})
      (catch Exception e
        (sm/unsigned (str "signing with TIK_KEY failed: " (or (ex-message e) (.getName (class e)))))))))

(def ^:private signed-cache (atom {}))

(defn manifest-meta
  "sign-manifest, remembered per entry, key and actor: the server signs
  each manifest once, not on every listing."
  [entry signer]
  (let [k [(:uri entry) (:resources entry) (:key signer) (:actor signer)]]
    (or (get @signed-cache k)
        (let [m (sign-manifest entry signer)]
          (swap! signed-cache assoc k m)
          m))))

(defn wire-entry
  "A skill as `skills/list` and `skills/get` put it on the wire."
  [skill signer]
  {:uri (:uri skill)
   :frontmatter (into (array-map) (:frontmatter skill))
   :resources (mapv #(select-keys % [:uri :digest :size]) (:resources skill))
   :_meta {meta-key (manifest-meta skill signer)}})

;; --------------------------------------------------------- verification

(defn verify-entry
  "Judge one skill entry (string keys, as parsed from JSON) against the
  allowed-signers text `signers`:

    {:verdict :verified :actor a :namespace ns}
    {:verdict :unsigned :reason r}   the server said it holds no signature
    {:verdict :missing}              no manifest signature in _meta at all
    {:verdict :malformed :reason r}  the manifest or the marker cannot be
                                     read, or names a namespace that is
                                     not one of `accepted-namespaces`
    {:verdict :bad-signature}        the signature does not verify for its
                                     actor, or no reader could judge it

  Only :verified passes."
  [signers entry]
  (let [v (sm/verify signers entry {:namespaces accepted-namespaces :limits limits})]
    (case (:verdict v)
      :wrong-namespace {:verdict :malformed
                        :reason (str "not an accepted skill-manifest namespace: " (pr-str (:namespace v)))}
      :unchecked {:verdict :bad-signature :actor (:actor v)}
      v)))

(def check-file
  "nil when the bytes match the manifest line for `uri` in `entry`
  (string keys), else the reason."
  sm/check-file)

(defn content-bytes
  "The raw bytes of one `resources/read` contents item (string keys):
  `text` as UTF-8, `blob` base64-decoded, or no bytes when it holds
  neither."
  ^bytes [item]
  (or (sm/content-bytes item) (byte-array 0)))

(defn read-contents
  "The `resources/read` contents for a served file: text when it is
  valid UTF-8, else a base64 blob, so the bytes a client decodes are the
  bytes the manifest digests."
  [uri {:keys [bytes mime]}]
  (if-let [text (utf8-text bytes)]
    {:uri uri :mimeType mime :text text}
    {:uri uri :mimeType mime :blob (.encodeToString (Base64/getEncoder) bytes)}))
