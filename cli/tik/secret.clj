;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.secret
  "One place secrets are resolved, everywhere in the porcelain.

  A secret in any config value may be a plain literal string, or a
  single-key map naming WHERE to fetch it at use time:

    {:env \"NAME\"}                 a process environment variable
    {:command [\"pass\" \"show\" \"x\"]}  first stdout line of a local secret
    {:command \"pass show x\"}           manager (a vector runs with NO shell;
                                         a string runs via `sh -c`) — pass,
                                         passage, gopass, `op read`, anything
    {:file \"/run/secrets/x\"}      first line of a file, trimmed (Docker/
                                    Podman/Compose and mounted k8s secrets
                                    land at a fixed path like this)
    {:credential \"NAME\"}          a systemd credential — the file NAME in
                                    $CREDENTIALS_DIRECTORY (LoadCredential=/
                                    LoadCredentialEncrypted=/SetCredential=)

  So an effects.edn or a bridge invocation can be committed and scripted
  with NO secret in it — the environment, a password manager, or a mounted
  file supplies it at the moment of use. Resolution is fail-LOUD: an unset
  variable, a failing command, or a missing file THROWS, because a silent
  blank secret is just a mysterious 401 later.

  `resolve-secrets` walks an arbitrary config value and resolves every
  spec it finds, so ANY field — a webhook :url, a :token, a header value —
  can be sourced this way without each call site knowing about secrets.

  The single-key rule is deliberate: it keeps a spec from colliding with
  an ordinary config map that merely happens to carry a :command key (the
  :command effect sink also has :type, so it is left untouched)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private spec-keys #{:env :command :file :credential})

(defn- getenv
  "Read a process environment variable — a seam so tests can drive the
  :env and :credential branches deterministically."
  [n]
  (System/getenv n))

(defn spec?
  "Is `x` a secret spec — a single-key map whose key names a source?"
  [x]
  (and (map? x) (= 1 (count x)) (boolean (some spec-keys (keys x)))))

(defn- first-line
  "The first line of `s`, trimmed — the universal secret-manager
  convention (pass/gopass put the password on line 1)."
  [s]
  (str/trim (or (first (str/split-lines (str s))) "")))

(defn resolve1
  "Resolve one secret `spec` to its string value; `what` names it for
  errors. A plain string (or nil) passes through unchanged; anything that
  is neither a string nor a valid spec is a loud error."
  [what spec]
  (cond
    (or (nil? spec) (string? spec))
    spec

    (not (spec? spec))
    (throw (ex-info (str what " is not a secret: want a string, {:env …},"
                         " {:command …}, or {:file …}, got " (pr-str spec))
                    {:secret what :spec spec}))

    (contains? spec :env)
    (let [n (:env spec)]
      (or (getenv n)
          (throw (ex-info (str what " wants environment variable " n
                               ", which is not set")
                          {:secret what :env n}))))

    (contains? spec :file)
    (let [f (io/file (:file spec))]
      (if (.isFile f)
        (first-line (slurp f))
        (throw (ex-info (str what " file " (:file spec) " does not exist")
                        {:secret what :file (:file spec)}))))

    (contains? spec :credential)
    ;; systemd credentials (LoadCredential=/LoadCredentialEncrypted=/
    ;; SetCredential=): each credential is a 0400 file, named `n`, in the
    ;; ramfs directory systemd points $CREDENTIALS_DIRECTORY at (encrypted
    ;; ones are already decrypted there by load time). This is the most
    ;; secure source — the secret never sits in a committed file, an env
    ;; var, or argv.
    (let [n (:credential spec)
          dir (getenv "CREDENTIALS_DIRECTORY")]
      (cond
        (str/blank? dir)
        (throw (ex-info (str what " wants systemd credential " (pr-str n)
                             " but $CREDENTIALS_DIRECTORY is not set — this"
                             " process is not running under systemd"
                             " credentials (LoadCredential=/SetCredential=)")
                        {:secret what :credential n}))

        (or (not (string? n)) (str/blank? n) (re-find #"[/\\]|\.\." n))
        (throw (ex-info (str what " credential name " (pr-str n)
                             " must be a bare name (no slashes, no ..)")
                        {:secret what :credential n}))

        :else
        (let [f (io/file dir n)]
          (if (.isFile f)
            (first-line (slurp f))
            (throw (ex-info (str what " systemd credential " (pr-str n)
                                 " is not present in $CREDENTIALS_DIRECTORY"
                                 " — declare it with LoadCredential=" n "…")
                            {:secret what :credential n :dir dir}))))))

    :else                                       ; :command
    (let [c (:command spec)
          argv (cond
                 (string? c) ["sh" "-c" c]
                 (and (sequential? c) (seq c) (every? string? c)) (vec c)
                 :else (throw (ex-info
                               (str what " :command must be a shell string"
                                    " or a non-empty vector of strings, got "
                                    (pr-str c))
                               {:secret what :command c})))
          r (try (apply sh/sh argv)
                 (catch Exception e
                   (throw (ex-info (str what " command " (pr-str c)
                                        " could not be run: " (.getMessage e))
                                   {:secret what :command c} e))))]
      (if (zero? (:exit r))
        (first-line (:out r))
        (throw (ex-info (str what " command " (pr-str c) " failed: "
                             (str/trim (str (:err r))))
                        {:secret what :command c :exit (:exit r)}))))))

(defn- key-name [k] (if (keyword? k) (name k) (str k)))

(defn resolve-secrets
  "Resolve every secret spec anywhere in `x`; all other data passes
  through untouched. Map values are resolved under their key's name, so
  an error names the offending field (\"Authorization\", \"url\", …).
  Leaves any non-spec map (e.g. the :command effect sink) structurally
  intact, recurring into its values."
  ([x] (resolve-secrets nil x))
  ([what x]
   (cond
     (spec? x)   (resolve1 (or what "secret") x)
     (map? x)    (reduce-kv (fn [m k v]
                              (assoc m k (resolve-secrets (key-name k) v)))
                            (empty x) x)
     (vector? x) (mapv #(resolve-secrets what %) x)
     (seq? x)    (doall (map #(resolve-secrets what %) x))
     (set? x)    (into #{} (map #(resolve-secrets what %)) x)
     :else       x)))
