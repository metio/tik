;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.rederive
  "`tik rederive`: check an evidence bundle and recompute what its facts
  imply — once at a terminal, or over HTTP for as many bundles as ask.

  The service is the command in a loop, and that is the design rather
  than an implementation note. A verification service that becomes the
  authority has re-introduced exactly the trust the bundle removed, so
  every page it renders prints the two commands that reach the same
  answer without it, and it stores nothing whose loss would matter.

  The one thing it keeps is a cache, and the cache's key is what makes
  it legal under ADR 0013: the bundle's content address AND the minute
  the answer was computed for. Content alone would be wrong — a guard
  with a freshness window is satisfied by evidence that is fresh and
  unsatisfied by the same bytes later, so a derivation is a function of
  two inputs and may only be memoized on both. Keyed on both, an entry
  cannot go stale: its inputs are immutable, and the whole cache can be
  dropped at any moment with nothing lost but time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tik.badge :as badge]
            [tik.bundle :as bundle]
            [tik.cli-core :refer [die exit! now]])
  (:import (java.io File)
           (java.net InetAddress URI)
           (java.time Instant)))

;; --------------------------------------------------------------- caching

(def ^:private cache
  "digest+minute -> derivation. Disposable by construction (ADR 0013):
  every input is in the key, so an entry is either exactly right or
  absent, and dropping the lot costs only recomputation."
  (atom {}))

(def ^:private cache-limit 512)

(defn- bucket
  "The minute a derivation was computed for. Coarser than the clock and
  finer than any freshness window a definition can express, so a cached
  answer is at most a minute behind a fresh one — and the page names the
  instant either way."
  [^Instant t]
  (quot (.getEpochSecond t) 60))

(defn cached
  "`(f)` memoized on [digest, minute]. A digest-less input (a directory
  on disk, which can change under us) is never cached."
  [digest ^Instant t f]
  (if-not digest
    (f)
    (let [k [digest (bucket t)]]
      (if-let [hit (get @cache k)]
        hit
        (let [v (f)]
          (swap! cache (fn [c] (assoc (if (< cache-limit (count c)) {} c) k v)))
          v)))))

;; ------------------------------------------------------ fetching a bundle

(def ^:private max-fetch-bytes (* 32 1024 1024))
(def ^:private max-redirects 4)

(defn- public-address?
  "Does every address this host resolves to sit on the public internet?
  A service that fetches a URL for whoever asks is a request forwarder,
  so a name pointing at the loopback, the link-local range, a private
  network or a unique-local IPv6 prefix is refused before a socket is
  opened."
  [^String host]
  (let [addrs (try (seq (InetAddress/getAllByName host)) (catch Exception _ nil))]
    (boolean
     (and addrs
          (every? (fn [^InetAddress a]
                    (let [b (.getAddress a)]
                      (not (or (.isLoopbackAddress a) (.isLinkLocalAddress a)
                               (.isSiteLocalAddress a) (.isAnyLocalAddress a)
                               (.isMulticastAddress a)
                               ;; fc00::/7, which none of the above cover
                               (and (= 16 (alength b))
                                    (= 0xfc (bit-and (aget b 0) 0xfe)))))))
                  addrs)))))

(defn- refuse! [msg]
  ;; never `die`: this runs inside request handling, and a hostile URL
  ;; must answer with a status rather than exit the process everyone else
  ;; is being served by
  (throw (ex-info msg {:reason :fetch/refused})))

(defn- check-url!
  "The URL a fetch is allowed to make, or a refusal naming why."
  ^URI [url]
  (let [^URI uri (try (URI. (str url)) (catch Exception _ nil))]
    (cond
      (nil? uri) (refuse! (str "not a URL: " url))
      (not= "https" (.getScheme uri))
      (refuse! "only https URLs are fetched — a bundle travels over the network")
      (str/blank? (str (.getHost uri))) (refuse! (str "no host in " url))
      (not (public-address? (.getHost uri)))
      (refuse! (str "refusing to fetch " (.getHost uri)
                    " — it resolves inside a private network"))
      :else uri)))

(defn fetch!
  "Download a bundle to a temp file. Redirects are followed by hand, one
  hop at a time, because a redirect into a private network is exactly how
  an allowlist checked only on the first URL is defeated."
  ^File [url]
  ;; the URL is judged before an HTTP client is even loaded: a refusal
  ;; must not depend on anything the fetch would need
  (let [start (check-url! url)
        get* (requiring-resolve 'babashka.http-client/get)]
    (loop [uri start hops 0]
      (when (< max-redirects hops)
        (refuse! (str "too many redirects fetching " url)))
      (let [resp (try (get* (str uri) {:as :bytes :throw false
                                       :follow-redirects :never
                                       :timeout 20000})
                      (catch Exception e
                        (refuse! (str "cannot fetch " uri ": " (ex-message e)))))
            status (:status resp)]
        (cond
          (and (<= 300 status) (< status 400))
          (if-let [loc (get-in resp [:headers "location"])]
            (recur (check-url! (str (.resolve uri (str loc)))) (inc hops))
            (refuse! (str "redirect with no location fetching " uri)))

          (not= 200 status)
          (refuse! (str "fetching " uri " answered " status))

          :else
          (let [^bytes body (:body resp)]
            (when (< max-fetch-bytes (alength body))
              (refuse! (str "the bundle at " uri " is larger than "
                            max-fetch-bytes " bytes")))
            (let [f (File/createTempFile "tik-fetched" ".tgz")]
              (.deleteOnExit f)
              (io/copy body f)
              f)))))))

;; ---------------------------------------------------------- the HTTP face

(defn- derivation-for
  "The derivation of one submitted bundle, cached on content and minute."
  [^File f t]
  (let [d (bundle/digest f)]
    (cached d t #(bundle/read-bundle f t))))

(def ^:private index-html
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>Re-derive a tik evidence bundle</title>"
       "<style>" badge/page-css "</style></head><body><main>"
       "<h1>Re-derive an evidence bundle</h1>"
       "<p class=\"lede\">Hand this service a "
       "<a href=\"https://tik.projects.metio.wtf/evidence/bundle-format/\">tik"
       " evidence bundle</a> and it recomputes, per request, what the"
       " bundle's own hash-pinned rules make of its signed facts.</p>"
       "<div class=\"card\"><p><strong>You do not need this service.</strong>"
       " It decides nothing and keeps nothing. Every answer it gives, you"
       " can reach with <code>./verify.sh</code> from inside the bundle and"
       " <code>tik rederive</code> — which is the point of the format, and"
       " the reason a service that had to be trusted would be a step"
       " backwards.</p></div>"
       "<h2>Endpoints</h2><pre>"
       "POST /rederive                     the bundle as the request body -&gt; EDN\n"
       "GET  /derivation?bundle=&lt;url&gt;      fetch it, re-derive, render the page\n"
       "GET  /badge.svg?bundle=&lt;url&gt;       the same, as a badge</pre>"
       "<h2>What a badge says</h2>"
       "<p>The stages the pinned definition grants, and which definition"
       " judged them — never the word <em>compliant</em>, which means"
       " nothing until it names a policy. And never more assurance than"
       " there is: a guard checks that a trusted attester said something,"
       " it does not read the SBOM.</p>"
       "</main></body></html>"))

(defn- html [status body]
  {:status status :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn handler
  "One request, one derivation. A server request must never reach a
  die/exit path — a hostile GET would take the service down for everyone
  — so refusals answer with words and a status."
  [{:keys [uri request-method body query-string]}]
  (try
    (let [t (now)
          param (fn [k]
                  (some (fn [pair]
                          (let [[a b] (str/split pair #"=" 2)]
                            (when (= a k)
                              (java.net.URLDecoder/decode (str b) "UTF-8"))))
                        (str/split (or query-string "") #"&")))
          from-url (fn [render content-type]
                     (if-let [u (param "bundle")]
                       (let [f (fetch! u)]
                         (try {:status 200
                               :headers {"Content-Type" content-type
                                         ;; a minute, matching the cache: a
                                         ;; badge nobody re-derives is a
                                         ;; badge that has stopped being one
                                         "Cache-Control" "public, max-age=60"}
                               :body (render (derivation-for f t) {:source u})}
                              (finally (.delete ^File f))))
                       (html 400 "<p>pass ?bundle=&lt;https url&gt;</p>")))]
      (case [request-method uri]
        [:get "/"] (html 200 index-html)

        [:get "/derivation"]
        (from-url (fn [r opts] (badge/page r opts)) "text/html; charset=utf-8")

        [:get "/badge.svg"]
        (from-url (fn [r _] (badge/svg r)) "image/svg+xml; charset=utf-8")

        [:post "/rederive"]
        (if-not body
          {:status 400 :body "tik: POST the bundle as the request body\n"}
          (let [f (File/createTempFile "tik-posted" ".tgz")]
            (try (io/copy body f)
                 {:status 200
                  :headers {"Content-Type" "application/edn"}
                  :body (pr-str (derivation-for f t))}
                 (finally (.delete f)))))

        {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body "tik: not found — GET / lists what this serves\n"}))
    (catch Throwable e
      {:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "tik: " (or (ex-message e) (.getName (class e))) "\n")})))

(defn- serve! [opts]
  (let [run-server (requiring-resolve 'org.httpkit.server/run-server)
        port (if-let [p (:port opts)]
               (or (parse-long (str p))
                   (die (str "serve --port must be a number, got " p)))
               7788)]
    (run-server handler {:port port :max-body (* 64 1024 1024)})
    (println (str "tik rederive live at http://127.0.0.1:" port
                  "  (stateless; ctrl-c stops)"))
    (println "  POST /rederive          a bundle as the body -> EDN")
    (println "  GET  /derivation?bundle=<url>")
    (println "  GET  /badge.svg?bundle=<url>")
    @(promise)))

(defn cmd-rederive
  "rederive <bundle.tgz|dir|https url> [--edn]: check an evidence bundle
  and recompute what its facts imply. With --serve, the same over HTTP.

  This is the half of a bundle that `verify.sh` cannot do. The script
  proves the bytes are genuine with coreutils alone; deciding what they
  ADD UP TO means running the pinned definition, which is what tik is.
  Exit 1 when the bundle does not verify — a derivation over bytes that
  are not what they claim to be is worth nothing.

  `--expect-stage` and `--expect-definition` turn it into a gate a
  consumer's own CI can stand on: fail unless this bundle reaches these
  stages under the definition I pinned. The two belong together — a
  supplier chooses the rules that judge their own ticket, so a stage
  assertion is a claim about a process only once the reader names WHICH
  process."
  [{:keys [pos opts]}]
  (if (:serve opts)
    (serve! opts)
    (let [src (or (first pos)
                  (die "usage: tik rederive <bundle.tgz|dir|https url>"))
          expected {:definition (:expect-definition opts)
                    :stages (when-let [s (:expect-stage opts)]
                              (remove str/blank? (str/split (str s) #",")))}
          fetched (when (str/starts-with? src "https://")
                    (try (fetch! src)
                         (catch clojure.lang.ExceptionInfo e
                           (die (ex-message e)))))
          result (try (bundle/read-bundle (or fetched src) (now))
                      (catch clojure.lang.ExceptionInfo e
                        (die (str "not a readable evidence bundle: "
                                  (ex-message e))))
                      (finally (some-> ^File fetched .delete)))
          asserted (try (bundle/expectations result expected)
                        (catch clojure.lang.ExceptionInfo e
                          (die (ex-message e))))]
      (if (:edn opts)
        (prn (cond-> result (seq asserted) (assoc :expectations asserted)))
        (do (print (badge/text result))
            (when (seq asserted)
              (println)
              (println "what you asked for")
              (doseq [{:keys [ok? msg]} asserted]
                (println (str "  " (if ok? "ok   " "FAIL ") msg))))))
      (flush)
      (when-not (and (:verified? result) (every? :ok? asserted))
        (exit! 1)))))
