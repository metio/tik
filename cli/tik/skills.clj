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
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.canonical :as canonical]
            [tik.sign :as sign])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.util Base64)))

(def extension-id "io.modelcontextprotocol/skills")

(def manifest-format
  "The format tag of the `_meta` marker; the canonical bytes open with
  `skill-manifest v1`."
  "skill-manifest-v1")

(def meta-key
  "The `_meta` key of a skill entry that carries the manifest signature,
  shared with kajero so one verifier checks both (reverse DNS of
  metio.wtf, as MCP's `_meta` prefix rule asks)."
  "wtf.metio/skill-manifest")

(def signature-namespace
  "The SSHSIG namespace of manifest signatures. `actors` lines grant
  `tik-*`, so the serving actor's registered key covers it, and no event,
  definition or witness signature can pass as a manifest signature."
  "tik-skill-manifest")

(def namespace-pattern
  "Namespaces a verifier accepts: one per product (tik, kajero), so one
  verifier checks both while a signature made for any other purpose is
  refused before the key is looked at."
  #"[a-z][a-z0-9]*(-[a-z0-9]+)*-skill-manifest")

(def ^:private skill-file "SKILL.md")

;; the extension's own ceilings per skill (servers SHOULD NOT exceed them)
(def max-files 512)
(def max-skill-bytes (* 16 1024 1024))

;; ------------------------------------------------------------ uri grammar

(def ^:private segment-re
  "One path segment of a skill URI: letters, digits, `.`, `_` and `-`,
  the characters the shared manifest format allows, so a segment means
  the same thing escaped or not and `%`, `\\`, spaces and control
  characters never reach a lookup."
  #"[A-Za-z0-9._-]+")

(defn- valid-segment? [s]
  (and (string? s) (re-matches segment-re s) (not (#{"." ".."} s))))

(defn valid-relative-path?
  "Is `p` a path inside a skill: non-empty segments of unreserved
  characters, none of them `.` or `..`, no leading or trailing slash?"
  [p]
  (and (string? p) (not (str/blank? p))
       (every? valid-segment? (str/split p #"/" -1))))

(defn parse-uri
  "`skill://<skill>/<path>` as [skill path], or nil for anything else: a
  foreign scheme, a traversal segment, an absolute or empty path, an
  escaped character. A URI this refuses can never name a served file,
  whatever the lookup behind it does."
  [uri]
  (when (and (string? uri) (str/starts-with? uri "skill://"))
    (let [rest (subs uri (count "skill://"))
          i (str/index-of rest "/")]
      (when (and i (pos? i))
        (let [skill (subs rest 0 i)
              path (subs rest (inc i))]
          (when (and (valid-segment? skill) (valid-relative-path? path))
            [skill path]))))))

(defn skill-uri [skill path] (str "skill://" skill "/" path))

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

(defn digest [^bytes b] (str "sha256:" (canonical/sha256-hex-bytes b)))

(defn build-skill
  "One skill from its name and a map of relative path -> bytes: the entry
  `skills/list` returns (SKILL.md first, then the files in URI order) and
  the files `resources/read` serves. Refuses a skill the extension would
  call invalid: no SKILL.md, a frontmatter name other than the directory,
  no description, a path outside the URI grammar, or the extension's
  file and size ceilings exceeded."
  [skill files]
  (when-not (valid-segment? skill)
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

(def ^:private digest-re #"sha256:[0-9a-f]{64}")

(defn canonical-bytes
  "The bytes a manifest signature covers, or nil when the manifest
  cannot be encoded without ambiguity:

      skill-manifest v1\\n
      skill <uri of SKILL.md>\\n
      file <digest> <size> <uri>\\n   one per file, sorted by URI

  Every field is restricted so no value can carry a space or a line
  break: URIs to the skill grammar, digests to sha256 hex, sizes to
  non-negative integers. Files are sorted, so the order a server lists
  them in changes nothing, and a duplicate URI is refused."
  [{:keys [uri resources]}]
  (let [files (when (sequential? resources) (vec resources))
        ok-file? (fn [{u :uri d :digest s :size}]
                   (and (parse-uri u) (string? d) (re-matches digest-re d)
                        (integer? s) (not (neg? s))))]
    (when (and (parse-uri uri) (seq files) (every? map? files) (every? ok-file? files)
               (apply distinct? (map :uri files))
               (some #(= uri (:uri %)) files)
               (every? #(= (first (parse-uri uri)) (first (parse-uri (:uri %)))) files))
      (.getBytes (str "skill-manifest v1\n"
                      "skill " uri "\n"
                      (str/join (for [{u :uri d :digest s :size} (sort-by :uri files)]
                                   (str "file " d " " s " " u "\n"))))
                 StandardCharsets/UTF_8))))

(defn sign-manifest
  "The `_meta` value for `entry`: signed with `key` as `actor`, or an
  explicit unsigned marker naming `reason` when no key is configured or
  signing failed."
  [entry {:keys [key actor]}]
  (let [unsigned (fn [reason] {"format" manifest-format "signed" false "reason" reason})]
    (if (str/blank? key)
      (unsigned "no signing key is configured (TIK_KEY is unset)")
      (try
        (let [sig (sign/sign-bytes key (canonical-bytes entry) signature-namespace)]
          {"format" manifest-format "signed" true "namespace" signature-namespace "actor" actor
           "signature" (String. ^bytes sig StandardCharsets/UTF_8)})
        (catch Exception e
          (unsigned (str "signing with TIK_KEY failed: " (or (ex-message e) (.getName (class e))))))))))

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

(defn- keywordize-entry
  "A skill entry as parsed from JSON with string keys, in the shape
  canonical-bytes reads."
  [entry]
  (when (map? entry)
    {:uri (get entry "uri")
     :resources (let [r (get entry "resources")]
                  (when (sequential? r)
                    (mapv (fn [f] (if (map? f)
                                    {:uri (get f "uri") :digest (get f "digest") :size (get f "size")}
                                    f))
                          r)))
     :meta (get-in entry ["_meta" meta-key])}))

(defn verify-entry
  "Judge one skill entry (string keys, as parsed from JSON) against the
  allowed-signers text `signers`:

    {:verdict :verified :actor a}
    {:verdict :unsigned :reason r}   the server said it holds no signature
    {:verdict :missing}              no manifest signature in _meta at all
    {:verdict :malformed :reason r}  the manifest or the marker cannot be read
    {:verdict :bad-signature}        the signature does not verify for its actor

  Only :verified passes."
  [signers entry]
  (let [{:keys [meta] :as e} (keywordize-entry entry)
        bytes (when e (canonical-bytes e))
        ns (when (map? meta) (get meta "namespace"))]
    (cond
      (nil? e) {:verdict :malformed :reason "not a skill entry"}
      (nil? meta) {:verdict :missing}
      (not (map? meta)) {:verdict :malformed :reason "the manifest marker is not an object"}
      (not= manifest-format (get meta "format"))
      {:verdict :malformed :reason (str "unknown manifest format " (pr-str (get meta "format")))}
      (nil? bytes) {:verdict :malformed :reason "the manifest cannot be encoded canonically"}
      (false? (get meta "signed")) {:verdict :unsigned :reason (str (get meta "reason"))}
      (not (true? (get meta "signed"))) {:verdict :malformed :reason "signed is neither true nor false"}
      (not (and (string? ns) (re-matches namespace-pattern ns)))
      {:verdict :malformed :reason (str "not a skill-manifest namespace: " (pr-str ns))}
      (not (and (string? (get meta "actor")) (string? (get meta "signature"))))
      {:verdict :malformed :reason "a signed marker needs actor and signature"}
      :else
      (let [actor (get meta "actor")
            ok (try (sign/verify-text signers bytes
                                      (.getBytes ^String (get meta "signature") StandardCharsets/UTF_8)
                                      actor ns)
                    (catch Exception _ false))]
        (if (true? ok)
          {:verdict :verified :actor actor :namespace ns}
          {:verdict :bad-signature :actor actor})))))

(defn check-file
  "Do `content-bytes` match the manifest line for `uri` in `entry`
  (string keys)? nil when they do, else the reason."
  [entry uri ^bytes content-bytes]
  (let [line (some #(when (= uri (get % "uri")) %) (get entry "resources"))]
    (cond
      (nil? line) (str uri " is not in the manifest")
      (not= (get line "size") (count content-bytes))
      (str uri ": " (count content-bytes) " bytes, manifest says " (get line "size"))
      (not= (get line "digest") (digest content-bytes))
      (str uri ": digest " (digest content-bytes) ", manifest says " (get line "digest")))))

(defn content-bytes
  "The raw bytes of one `resources/read` contents item (string keys):
  `text` as UTF-8, or `blob` base64-decoded."
  ^bytes [item]
  (cond
    (string? (get item "text")) (.getBytes ^String (get item "text") StandardCharsets/UTF_8)
    (string? (get item "blob")) (.decode (Base64/getDecoder) ^String (get item "blob"))
    :else (byte-array 0)))

(defn read-contents
  "The `resources/read` contents for a served file: text when it is
  valid UTF-8, else a base64 blob, so the bytes a client decodes are the
  bytes the manifest digests."
  [uri {:keys [bytes mime]}]
  (if-let [text (utf8-text bytes)]
    {:uri uri :mimeType mime :text text}
    {:uri uri :mimeType mime :blob (.encodeToString (Base64/getEncoder) bytes)}))
