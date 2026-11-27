;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.context
  "The store a stretch of work acts on, when one process serves several.

  A single-store invocation reads its store, key and actor from the
  environment: TIK_ROOT, TIK_KEY, TIK_PQ_KEY and TIK_ACTOR. A multi-store
  backend (ADR 0041) instead binds `*store*` around everything it runs for
  one store, and every reader of those four names asks `env`, which then
  answers from the bound store and never from the environment. A store
  context therefore cannot pick up another store's key from a variable
  the process happened to inherit, and the binding is thread-local, so
  two requests for two stores running at once each see their own.

  `strict!` closes the remaining gap: once a multi-store backend has
  started, reading one of the four names outside any store context
  throws instead of falling back to the environment or to the working
  directory, so a code path that forgot to bind a store fails loudly
  rather than writing into whatever store the cwd discovers.")

(def ^:dynamic *store*
  "The store this thread acts on inside a multi-store process:
  {:name :root :key :actor :pq-key}, or nil outside one."
  nil)

(def ^:private names->keys
  {"TIK_ROOT" :root "TIK_KEY" :key "TIK_PQ_KEY" :pq-key "TIK_ACTOR" :actor})

(def store-variables
  "The environment variables a store context replaces."
  (set (keys names->keys)))

(defonce ^:private strict (atom false))

(defn strict!
  "Refuse, from now on, to read a store variable outside a store context.
  `on?` false lifts it again, which only tests do."
  ([] (strict! true))
  ([on?] (reset! strict (boolean on?))))

(defn strict? [] @strict)

(defn env
  "The value of the store variable `n` (TIK_ROOT, TIK_KEY, TIK_PQ_KEY or
  TIK_ACTOR) for this thread: the bound store's when there is one, else
  the environment's. Throws in a strict process outside a store context."
  [n]
  (let [k (or (names->keys n)
              (throw (ex-info (str n " is not a store variable") {:reason :context/not-a-store-variable})))]
    (cond
      *store* (get *store* k)
      @strict (throw (ex-info (str "this process serves several stores, and " n
                                   " was read outside any of them; nothing was written")
                              {:reason :context/no-store :variable n}))
      :else (System/getenv n))))

(defn child-env
  "The environment for a subprocess started on behalf of this thread's
  store: the process environment with the store variables replaced by the
  bound store's (and dropped where the store has none), so a probe or a
  scope script run for one store can never inherit another's key. Outside
  a store context it is the process environment unchanged."
  []
  (let [base (into {} (System/getenv))]
    (if-let [s *store*]
      (reduce-kv (fn [m n k]
                   (if-let [v (get s k)] (assoc m n (str v)) (dissoc m n)))
                 base names->keys)
      base)))

(defmacro with-store
  "Run `body` acting on the store `s` ({:name :root :key :actor :pq-key})."
  [s & body]
  `(binding [*store* ~s] (let [res# (do ~@body)] res#)))
