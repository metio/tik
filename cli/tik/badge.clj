;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.badge
  "Renderings of a re-derived bundle: a line of text, an SVG badge, and
  the page the badge is a doorway to.

  A badge is the one place tik's law bites hardest, because a badge is a
  derived conclusion shown as if it were a fact. What keeps it honest is
  what it SAYS: never 'compliant' — a word that means nothing until it
  names a policy, and the failure mode every badge scheme is remembered
  for — but the derivation itself. Which stages the pinned rules grant,
  which definition judged them, and the instant the answer was computed,
  because a guard with a freshness window answers differently tomorrow
  on the same evidence.

  Pure: every function takes a `tik.bundle/re-derive` result and returns
  a string. Nothing here reads a clock, a store, or a network."
  (:require [clojure.string :as str]
            [tik.explain :as explain]))

;; ------------------------------------------------------------------ text

(defn- names [stages] (str/join ", " (map str (sort stages))))

(defn headline
  "The one line a badge, a log and a page all agree on."
  [{:keys [verified? current process process-hash]}]
  (if-not verified?
    "unverified"
    (str (if (seq current) (names current) "no stage reached")
         (when process-hash
           (str " · " (name (or process :process))
                "@" (subs process-hash 7 15))))))

(defn text
  "The terminal rendering: the checks, then the derivation, then how to
  reach the same answer without us."
  [{:keys [ticket title process process-hash derived-at events reached
           current stages missing checks verified? signers digest format]}]
  (with-out-str
    (println (str "bundle " (or digest "(directory)")))
    (println (str "  format    " (:format format) " version " (:version format)
                  (when-not (:declared? format)
                    " (no manifest — read as the version-1 baseline)")))
    (println (str "  ticket    " ticket "  " (pr-str title)))
    (println (str "  events    " events))
    (println (str "  judged by " (name (or process :process)) " " process-hash))
    (println)
    (println "verification")
    (doseq [{:keys [ok? note? msg]} checks]
      (println (str "  " (cond ok? "ok   " note? "note " :else "FAIL ") msg)))
    (println (str "  => " (if verified? "the bytes are what they claim to be"
                              "THIS BUNDLE DOES NOT VERIFY")))
    (println)
    (println "who may sign here")
    (doseq [{:keys [actor earned-by]} signers]
      (println (str "  " actor
                    (if earned-by
                      (str " — bound to its key by " (:subject earned-by)
                           ", per " (:issuer earned-by))
                      " — listed in the registry; verify that key out of band"))))
    (println)
    (println (str "derivation at " derived-at))
    (println (str "  reached   " (names reached)))
    (println (str "  current   " (names current)))
    (doseq [{:keys [stage sticky? holds-now? guards]} stages]
      (println (str "  " stage
                    (when sticky? " (sticky — carried once reached)")
                    (when-not holds-now?
                      " — reached earlier; its guards no longer hold")))
      (doseq [{:keys [guard satisfied?]} guards]
        (println (str "      " (if satisfied? "✓" "✗") " " (pr-str guard)))))
    (when (seq missing)
      (println)
      (println "not reached")
      (doseq [{:keys [stage missing]} missing]
        (println (str "  " stage))
        (doseq [m missing]
          (println (str "      " (explain/reason->text m))))))))

;; ------------------------------------------------------------------- SVG

(defn- esc [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- text-width
  "Rendered width of a label at 11px in the badge's font, near enough for
  layout. Badges are laid out without measuring the glyphs anywhere."
  [s]
  (int (+ 10 (* 6.4 (count (str s))))))

(def ^:private ink
  {:label "#3f3f46"
   :derived "#3f4a8a"      ; a derivation, not an approval — never green
   :unverified "#a4262c"})

(defn svg
  "The badge. Left names what judged the ticket, right names what the
  rules granted — or that the bundle does not verify at all. There is no
  green here on purpose: green reads as approval, and this states a
  derivation, which is a different kind of claim."
  [{:keys [verified? process process-hash current]}]
  (let [left (if process
               (str (name process)
                    (when process-hash (str "@" (subs process-hash 7 15))))
               "tik bundle")
        right (cond
                (not verified?) "unverified"
                (empty? current) "no stage reached"
                :else (str/join " · " (map str (sort current))))
        lw (text-width left)
        rw (text-width right)
        w (+ lw rw)
        fill (if verified? (:derived ink) (:unverified ink))]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" w
         "\" height=\"20\" role=\"img\" aria-label=\"" (esc left) ": "
         (esc right) "\">"
         "<title>" (esc left) ": " (esc right) "</title>"
         "<rect width=\"" lw "\" height=\"20\" rx=\"3\" fill=\"" (:label ink) "\"/>"
         "<rect x=\"" lw "\" width=\"" rw "\" height=\"20\" rx=\"3\" fill=\""
         fill "\"/>"
         "<rect x=\"" lw "\" width=\"4\" height=\"20\" fill=\"" fill "\"/>"
         "<g fill=\"#fff\" text-anchor=\"middle\""
         " font-family=\"DejaVu Sans,Verdana,Geneva,sans-serif\""
         " font-size=\"11\">"
         "<text x=\"" (quot lw 2) "\" y=\"14\">" (esc left) "</text>"
         "<text x=\"" (+ lw (quot rw 2)) "\" y=\"14\">" (esc right) "</text>"
         "</g></svg>")))

;; ------------------------------------------------------------------ HTML

(def page-css
  "Self-contained styling for the page — light and dark, no external
  font, no request to anywhere. A page about not needing to trust a
  service should not ask the reader to fetch a stylesheet from one."
  "
:root{--bg:#fbfbfd;--fg:#1c1c22;--dim:#5a5a68;--line:#dededf;--card:#fff;
      --ok:#2f6f3e;--no:#a4262c;--acc:#3f4a8a}
@media (prefers-color-scheme:dark){:root{--bg:#131318;--fg:#e8e8ee;--dim:#a0a0b0;
  --line:#2c2c36;--card:#1b1b22;--ok:#7bbd8b;--no:#e4868c;--acc:#9aa5e8}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 system-ui,sans-serif}
main{max-width:52rem;margin:0 auto;padding:2rem 1.25rem 5rem}
h1{font-size:1.5rem;margin:0 0 .25rem}
h2{font-size:1.05rem;margin:2.5rem 0 .75rem;letter-spacing:.02em;
   text-transform:uppercase;color:var(--dim)}
p{margin:.6rem 0}
a{color:var(--acc)}
.lede{color:var(--dim)}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;
      padding:1rem 1.1rem;margin:.75rem 0}
.kv{display:grid;grid-template-columns:9rem 1fr;gap:.35rem 1rem;margin:0}
.kv dt{color:var(--dim)}
.kv dd{margin:0;overflow-wrap:anywhere}
code,pre{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.87em}
pre{background:var(--card);border:1px solid var(--line);border-radius:6px;
    padding:.8rem 1rem;overflow-x:auto}
ul{margin:.4rem 0;padding-left:1.1rem}
li{margin:.15rem 0}
.ok{color:var(--ok)}.no{color:var(--no)}.dim{color:var(--dim)}
.stage{border-left:3px solid var(--acc);padding-left:.9rem;margin:1rem 0}
.stage h3{margin:0;font-size:1rem}
.warn{border-left:3px solid var(--no)}
footer{margin-top:3rem;color:var(--dim);font-size:.9rem;
       border-top:1px solid var(--line);padding-top:1rem}
")

(defn- guard-list [guards]
  (str "<ul>"
       (str/join (for [{:keys [guard satisfied?]} guards]
                    (str "<li><span class=\"" (if satisfied? "ok" "no") "\">"
                         (if satisfied? "✓" "✗") "</span> <code>"
                         (esc (pr-str guard)) "</code></li>")))
       "</ul>"))

(defn page
  "The page the badge is a doorway to: what held, what did not, and the
  two commands that reach the same answer without this service running.

  The caveat leads rather than trails. A reader who takes 'released' to
  mean 'audited' has been misled by us, and the gap — that a guard checks
  a trusted attester SAID so, and never reads the SBOM itself — is
  exactly where badge schemes earn their reputation."
  [{:keys [ticket title process process-hash derived-at events reached
           stages missing checks verified? signers digest format]}
   {:keys [source]}]
  (let [fails (remove #(or (:ok? %) (:note? %)) checks)]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>" (esc title) " — re-derived</title>"
         "<style>" page-css "</style></head><body><main>"

         "<h1>" (esc title) "</h1>"
         "<p class=\"lede\">Re-derived at " (esc derived-at)
         " from the bundle itself. Nothing was stored: this page is the"
         " answer to running the pinned rules over the signed facts, and"
         " it is computed again every time it is asked for.</p>"

         "<div class=\"card\"><p><strong>What this does not say.</strong>"
         " A guard checks that a trusted attester said something — that an"
         " SBOM was attested, that a scan reported clean. It never reads"
         " the SBOM. The derivation below is exactly as good as the"
         " attesters named in it, and no better.</p></div>"

         "<h2>What was checked</h2>"
         "<dl class=\"kv\">"
         "<dt>bundle</dt><dd><code>" (esc (or digest "(directory)")) "</code></dd>"
         "<dt>format</dt><dd>" (esc (:format format)) " version "
         (esc (:version format))
         (if (:declared? format) ""
             " <span class=\"dim\">(no manifest — read as the version-1 baseline)</span>")
         "</dd>"
         "<dt>ticket</dt><dd><code>" (esc ticket) "</code></dd>"
         "<dt>events</dt><dd>" events "</dd>"
         "<dt>judged by</dt><dd><code>" (esc (name (or process :process)))
         "</code> <code>" (esc process-hash) "</code></dd>"
         "</dl>"
         (if verified?
           (str "<p class=\"ok\">Every stored file hashes to its own name,"
                " every signature verifies as the actor its event names, and"
                " every referenced parent is present ("
                (count checks) " checks).</p>")
           (str "<p class=\"no\"><strong>This bundle does not verify.</strong>"
                " Nothing derived from it should be believed.</p><ul>"
                (str/join (for [f fails]
                             (str "<li class=\"no\">" (esc (:msg f)) "</li>")))
                "</ul>"))

         "<h2>Who may sign here</h2>"
         "<p class=\"dim\">A binding says that whoever built this bundle held"
         " a token the named issuer signed, for the named subject, at the"
         " moment it was written. The issuer's key travels in the archive so"
         " the check runs offline; recognizing it as that issuer's key is"
         " yours to do, exactly as it is for a registry line.</p><ul>"
         (str/join (for [{:keys [actor earned-by]} signers]
                      (str "<li><code>" (esc actor) "</code> — "
                           (if earned-by
                             (str "bound to its key by <code>"
                                  (esc (:subject earned-by))
                                  "</code>, per <code>"
                                  (esc (:issuer earned-by)) "</code>")
                             (str "listed in the registry that travels with"
                                  " the bundle; verify that key out of band"))
                           "</li>")))
         "</ul>"

         "<h2>What the rules grant</h2>"
         "<p>Reached: <code>" (esc (names reached)) "</code></p>"
         (str/join
                (for [{:keys [stage sticky? holds-now? guards]} stages]
                  (str "<div class=\"stage" (if holds-now? "" " warn") "\">"
                       "<h3><code>" (esc stage) "</code>"
                       (when sticky?
                         " <span class=\"dim\">sticky — carried once reached</span>")
                       "</h3>"
                       (when-not holds-now?
                         (str "<p class=\"no\">Reached earlier; these guards"
                              " no longer hold at the instant above.</p>"))
                       (guard-list guards) "</div>")))

         (if (seq missing)
           (str "<h2>What is not reached</h2>"
                (str/join
                       (for [{:keys [stage missing]} missing]
                         (str "<div class=\"stage warn\"><h3><code>" (esc stage)
                              "</code></h3><ul>"
                              (str/join (for [m missing]
                                           (str "<li>"
                                                (esc (explain/reason->text m))
                                                "</li>")))
                              "</ul></div>"))))
           "")

         "<h2>Reach the same answer without this service</h2>"
         "<p>This service is disposable. It holds nothing, decides nothing,"
         " and is not part of the trust chain — if it disappears the bundle"
         " still checks out, which is the whole reason the bundle exists.</p>"
         "<pre>"
         (when source (str "curl -LO " (esc source) "\n"))
         "mkdir bundle &amp;&amp; tar xzf &lt;bundle&gt;.tgz -C bundle &amp;&amp; cd bundle\n"
         "./verify.sh          # coreutils + ssh-keygen: are the bytes genuine?\n"
         "tik rederive .       # and what do they add up to, right now?"
         "</pre>"

         "<footer>Derivation is a pure function of these files and the"
         " instant you ask. A guard with a freshness window — a scan"
         " attested within a day, an approval within a week — is satisfied"
         " by today's evidence and unsatisfied by the same evidence next"
         " month. That is not drift; that is the evidence aging, said out"
         " loud.</footer>"
         "</main></body></html>")))
