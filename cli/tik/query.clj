;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.query
  "The whole-board read-only lenses: ls (tickets with derived stages, a
  selector language over them), search, dupes (near-title lookalikes),
  board (the entire board as one self-contained HTML file), plan (the
  dependency-link roadmap — ready/blocked/done/cyclic, critical path),
  and roles (who gates what). Every view is a rendering of the same
  derivation; porcelain over tik.cli-core and the row helpers there."
  (:require [clojure.string :as str]
            [tik.args :refer [parse-value]]
            [tik.cli-core :refer [all-ticket-ctx cache-flush! die display-title
                                  link-facts link-lines now open-ticket-rows
                                  resolve-id-soft signed-event-ids store-established?
                                  the-store ticket-row]]
            [tik.dupe :as dupe]
            [tik.explain :as explain]
            [tik.guard :as guard]
            [tik.next :as next-lens]
            [tik.plan :as plan]
            [tik.process :as process]
            [tik.reduce :as red]
            [tik.render :refer [emit-data paint-stage sid tint]]
            [tik.select :as select]
            [tik.stage :as stage]
            [tik.store.protocol :as store]))

(defn plan-graph
  "The cross-ticket dependency graph from the store as tik.plan input:
  edges keyed by ticket id -> the set of resolved `:depends-on` target
  ids, the settled set, and a display-title per node. An unresolvable
  target is kept as its raw string — a node with no prerequisites that
  is never settled, so it blocks conservatively (matching `next`)."
  [s t]
  (let [ctx (into {} (map (juxt :id identity)) (all-ticket-ctx s))
        ids (vec (keys ctx))
        edges (into {}
                    (for [id ids]
                      [id (->> (link-facts (:state (ctx id)))
                               (filter #(= :depends-on (:rel %)))
                               (map #(or (resolve-id-soft s (:target %))
                                         (:target %)))
                               set)]))
        settled (set
                 (for [id ids
                       :let [{:keys [process events roles]} (ctx id)]
                       :when (next-lens/settled? process events t roles)]
                   id))
        title (fn [n] (if-let [c (ctx n)]
                        (display-title (:state c))
                        (str n)))]
    {:edges edges :settled settled :title title}))

(defn short-title
  "A node label for one line: 8-char id + trimmed title."
  [title n]
  (let [t (some-> (title n) str)
        s (str n)]
    (str (if (re-matches #"[0-9a-f-]{8,}" s) (sid s) s)
         (when (and (seq t) (not= t s))
           (str " " (subs t 0 (min 32 (count t))))))))

(defn plan-html
  "One self-contained HTML page: the plan as a roadmap — a critical-path
  track, then Ready / Blocked / Done columns of cards, cycles flagged in
  red. No scripts, no network; a rendering of tik.plan/summary."
  [{:keys [edges settled title]} summary]
  (let [{:keys [ready blocked cyclic critical-path unlocks status]} summary
        esc (fn [x] (-> (str x) (str/replace "&" "&amp;")
                        (str/replace "<" "&lt;") (str/replace ">" "&gt;")))
        card (fn [n]
               (let [st (status n)
                     u (get unlocks n 0)
                     deps (remove settled (get edges n))]
                 (str "<div class='card " (name st) "'>"
                      "<div class='t'>" (esc (title n)) "</div>"
                      "<div class='id'>" (esc (sid n)) "</div>"
                      (when (pos? u) (str "<div class='u'>unlocks " u "</div>"))
                      (when (seq deps)
                        (str "<div class='w'>waiting on "
                             (str/join ", " (map #(esc (sid %)) deps))
                             "</div>"))
                      "</div>")))
        column (fn [label ns]
                 (str "<section><h2>" label " <span class='n'>" (count ns) "</span></h2>"
                      (str/join (map card (sort-by str ns))) "</section>"))]
    (str "<!doctype html><meta charset=utf-8><title>tik plan</title>"
         "<style>"
         ":root{--bg:#fff;--fg:#111;--dim:#666;--line:#e2e2e2;--ready:#128a3a;"
         "--blocked:#b7791f;--done:#888;--cyc:#c0392b;--cp:#2456c9}"
         "@media(prefers-color-scheme:dark){:root{--bg:#14161a;--fg:#e8e8e8;"
         "--dim:#9aa;--line:#2a2e35;--ready:#3ddc84;--blocked:#e0b050;"
         "--done:#777;--cyc:#ff6b5e;--cp:#6aa1ff}}"
         "body{margin:0;font:15px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg)}"
         "header{padding:20px 28px;border-bottom:1px solid var(--line)}"
         "h1{font-size:18px;margin:0}"
         ".sum{color:var(--dim);margin-top:6px;font-size:13px}"
         ".cyc-banner{background:var(--cyc);color:#fff;padding:10px 28px;font-weight:600}"
         ".cp{padding:16px 28px;border-bottom:1px solid var(--line)}"
         ".cp h2{font-size:12px;text-transform:uppercase;letter-spacing:.06em;color:var(--dim);margin:0 0 10px}"
         ".track{display:flex;flex-wrap:wrap;gap:8px;align-items:center}"
         ".step{background:color-mix(in srgb,var(--cp) 15%,transparent);border:1px solid var(--cp);"
         "border-radius:8px;padding:6px 12px;font-weight:600}"
         ".arr{color:var(--cp)}"
         ".cols{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:20px;padding:24px 28px}"
         "section h2{font-size:13px;text-transform:uppercase;letter-spacing:.05em;color:var(--dim);margin:0 0 12px}"
         "section h2 .n{color:var(--fg);opacity:.6}"
         ".card{border:1px solid var(--line);border-left:4px solid var(--done);"
         "border-radius:8px;padding:10px 12px;margin-bottom:10px;background:color-mix(in srgb,var(--fg) 2%,transparent)}"
         ".card.ready{border-left-color:var(--ready)}"
         ".card.blocked{border-left-color:var(--blocked)}"
         ".card.cyclic{border-left-color:var(--cyc)}"
         ".card .t{font-weight:600}"
         ".card .id{color:var(--dim);font-size:12px;font-family:ui-monospace,monospace}"
         ".card .u{color:var(--ready);font-size:12px;margin-top:4px}"
         ".card .w{color:var(--blocked);font-size:12px;margin-top:4px}"
         "</style>"
         "<header><h1>tik plan</h1><div class='sum'>"
         (count ready) " ready · " (count blocked) " blocked · "
         (count settled) " done · "
         (if (seq cyclic) (str (count cyclic) " in a cycle") "no cycles")
         "</div></header>"
         (when (seq cyclic)
           (str "<div class='cyc-banner'>⚠ dependency cycle — "
                (str/join ", " (map #(esc (title %)) (sort-by str cyclic)))
                " can never proceed; break an edge</div>"))
         (when (seq critical-path)
           (str "<div class='cp'><h2>critical path — " (count critical-path)
                " step(s) remaining</h2><div class='track'>"
                (str/join "<span class='arr'>→</span>"
                          (map #(str "<span class='step'>" (esc (title %)) "</span>")
                               critical-path))
                "</div></div>"))
         "<div class='cols'>"
         (column "Ready" ready)
         (column "Blocked" blocked)
         (column "Done" settled)
         (when (seq cyclic) (column "Cyclic" cyclic))
         "</div>")))

(defn cmd-plan
  "plan [<file.html>]: the dependency-link roadmap — a derived reading of
  every ticket, its `:depends-on` edges, and which are settled: what is
  ready, blocked, done, or caught in a cycle, plus the critical path and
  each item's downstream unlock impact. Not a scheduler (§19): it
  re-derives from current facts every read, so it never goes stale. With
  a .html argument, writes the fancy self-contained roadmap page."
  [{:keys [pos opts]}]
  (let [s (the-store)
        t (now)
        {:keys [edges settled title] :as g} (plan-graph s t)
        {:keys [ready blocked cyclic critical-path unlocks] :as summary}
        (plan/summary edges settled)]
    (cond
      (first pos)
      (do (spit (first pos) (plan-html g summary))
          (println (str "wrote " (first pos) " — the plan as a roadmap page")))
      (emit-data opts summary) nil
      :else
      (do
        (println (str (tint "1" "PLAN") "  "
                      (tint "32" (str (count ready) " ready")) " · "
                      (tint "33" (str (count blocked) " blocked")) " · "
                      (tint "2" (str (count settled) " done"))
                      (when (seq cyclic)
                        (str " · " (tint "31" (str (count cyclic) " in a cycle"))))))
        (when (seq cyclic)
          (println (tint "31" (str "\n⚠ dependency cycle (deadlock — break an edge): "
                                   (str/join ", " (map title (sort-by str cyclic)))))))
        (when (seq critical-path)
          (println (str "\ncritical path (" (count critical-path)
                        " step(s) remaining):"))
          (println (str "  " (str/join (tint "34" " → ")
                                       (map title critical-path)))))
        (when (seq ready)
          (println "\nready now:")
          (doseq [n (sort-by #(- (get unlocks % 0)) ready)]
            (println (str "  " (tint "32" "▸") " " (short-title title n)
                          (when (pos? (get unlocks n 0))
                            (tint "2" (str "   unlocks " (get unlocks n 0))))))))
        (when (seq blocked)
          (println "\nblocked:")
          (doseq [n (sort-by str blocked)
                  :let [deps (remove settled (get edges n))]]
            (println (str "  " (tint "33" "⏳") " " (short-title title n)
                          (tint "2" (str "   waiting on "
                                         (str/join ", " (map #(short-title title %) deps))))))))
        (when (and (empty? ready) (empty? blocked) (empty? cyclic))
          (println "\nno dependency links yet — `tik set <id> link.depends-on=<other>`"))))))

(defn selector-row
  "A ticket's row for both selection and listing: the display fields plus
  every field a tik.select predicate reads (see that ns's row shape).
  One fold per ticket — the slow path a `--where` selector takes."
  [t {:keys [id events state process roles store]}]
  (let [reached (stage/effective-reached process events t roles)
        facts (guard/fact-map state)
        fstatus #(:status (red/fact-status state %))
        fkeys (keys (:facts state))]
    {:id id
     :title (display-title state)
     :current (stage/current-stages process reached)
     :reached reached
     :describe (some facts [[:description] [:summary] [:statement]])
     :links (vec (link-facts state))
     :state state
     :settled? (next-lens/settled-reached? process reached)
     ;; --- fields tik.select predicates read ---
     :facts facts
     :actors (into #{} (map :event/actor) events)
     :derived-from (into #{} (keep #(get-in % [:event/body
                                               :artifact/derived-from]))
                         events)
     :haystack (str/lower-case (str (display-title state) " " (pr-str facts)))
     :disputed? (boolean (some #(= :disputed (fstatus %)) fkeys))
     :conflicted? (boolean (some #(= :conflicted (fstatus %)) fkeys))
     :unsigned? (let [signed (signed-event-ids store id)]
                  (boolean (some #(not (contains? signed (:event/id %)))
                                 events)))}))

(defn compiled-selector
  "Compile a `--where` value into a row predicate, dying with the usage
  message on a bad term. A missing selector (nil) matches everything."
  [expr]
  (when (true? expr)
    (die "usage: tik ls --where <selector>  (e.g. stage=:blocked and disputed)"))
  (try (select/compile (or expr ""))
       (catch clojure.lang.ExceptionInfo e (die (ex-message e)))))

(defn selector-rows
  "A selector-row per ticket, over the already-isolated whole-store
  opener; a ticket whose DEEPER derivation (the stage fixpoint, facts)
  throws is skipped and named too, so one poison never hides a `--where`
  match. (ticket-ctx failures are already isolated by all-ticket-ctx.)"
  [s t]
  (keep (fn [ctx]
          (try (selector-row t ctx)
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*]
                   (println (str "skipping " (sid (:id ctx)) ": " (ex-message e))))
                 nil)))
        (all-ticket-ctx s)))

(defn cmd-ls
  "Open tickets by default; settled ones (sticky terminal reached —
  :landed, :closed, :validated, :killed) hide behind --all. `--where`
  takes a selector (tik.select) — stage, facts, actors, disputes, text,
  composed — and reads every ticket's log; without it the cached fast
  path lists open tickets with zero event reads."
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        rows (if-not (:where opts)
               ;; the cached fast path: one directory listing per
               ;; unchanged ticket, zero event reads
               (for [id (store/ticket-ids s)
                     :let [row (ticket-row s t id)]]
                 (assoc row
                        :id id
                        :current (set (:current row))
                        :stale-ms (max 0 (- (inst-ms t)
                                            (:last-event-ms row 0)))))
               (filter (compiled-selector (:where opts))
                       (selector-rows s t)))
        visible (if (:all opts) rows (remove :settled? rows))]
    (when-not (emit-data opts
                         (mapv #(select-keys % [:id :title :current :describe :settled?])
                               visible))
            (doseq [{:keys [id current title describe settled? links error]} visible]
    (println (tint "2" (sid id))
             (if error
               (tint "31" (format "%-24s" "error"))
               (paint-stage (format "%-24s" (str/join "," (map name current)))
                            settled? (contains? current :parked)))
             title)
    (when (and (:long opts) describe)
      (println (tint "2" (str "         " describe))))
    (when (and (:long opts) (seq links))
      (doseq [line (link-lines s t links)]
        (println (tint "2" (str "         ↳ " line))))))
      (when (empty? visible)
        (if (empty? rows)
          (println
           (str (if (store-established?)
                  "no tickets yet — start with:\n"
                  (str "no tik store here yet — establish one, then start:\n"
                       "  tik init                    make this directory a store\n"
                       "  tik init --hidden           or keep it in .tik/ (e.g. above many repos)\n"))
                "  tik author                  describe your process; tik writes the definition\n"
                "  tik author --template bug   or start from a known-good shape\n"
                "  tik new track --title ...   or skip process design: just track a thing\n"
                "  tik set <id> key=value      record what is true; the stage derives itself"))
          (println "no matching tickets")))
      (let [hidden (- (count rows) (count visible))]
        (when (pos? hidden)
          (println (str "settled: " hidden
                        " finished ticket(s) hidden (--all shows)")))))
    (cache-flush!)))

(defn cmd-search
  "tik search <text…> = ls over everything whose haystack holds every
  word — sugar for `ls --where '~w1 ~w2 …' --all`."
  [{:keys [pos opts]}]
  (cmd-ls {:opts (assoc opts
                        :where (str/join " " (map #(str "~" %) pos))
                        :all true)}))

(defn cmd-dupes
  "dupes [--threshold 0.4]: pairwise near-title similarity across open
  tickets — the lookalike report. (Selecting tickets by a predicate is
  `tik ls --where '…'`, or `tik ls --all --where '…'` to include settled
  ones — one selector grammar, one board lens.)"
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        threshold (or (some-> (:threshold opts) parse-value double) 0.4)
        pairs (dupe/lookalikes (open-ticket-rows s t) threshold)]
    (when-not (emit-data opts (mapv #(select-keys % [:a :b :score]) pairs))
      (doseq [{:keys [a b score]} pairs]
        (println (str (sid a) " ~ " (sid b)
                      "  " (int (* 100 score)) "% similar")))
      (println (count pairs) "lookalike pair(s) at >="
               (str (int (* 100 threshold)) "%")))))

(defn html-escape [x]
  (-> (str x)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn cmd-board
  "One self-contained HTML file: the whole board, stage-colored, with
  facts and explain per ticket. No dependencies, no scripts, no network
  — mail it, archive it, open it anywhere. A rendering of the same
  derivation as every other lens."
  [{:keys [pos]}]
  (let [s (the-store)
        t (now)
        rows (for [{:keys [id events state process roles]} (all-ticket-ctx s)
                   :let [reached (stage/effective-reached process events t roles)
                         current (stage/current-stages process reached)
                         settled? (next-lens/settled-reached? process reached)]]
               {:id id :title (display-title state) :process (:process state)
                :current current :settled? settled?
                :parked? (contains? current :parked)
                :facts (sort-by (comp pr-str key) (guard/fact-map state))
                :blocks (explain/explain process events t roles reached)})
        chip (fn [{:keys [settled? parked?]}]
               (cond settled? "chip done" parked? "chip parked" :else "chip live"))
        html
        (str
         "<!doctype html><html><head><meta charset=\"utf-8\">"
         "<title>tik board</title><style>"
         "body{font:15px/1.5 system-ui;margin:2rem auto;max-width:60rem;padding:0 1rem;background:#fff;color:#1a1a1a}"
         "@media(prefers-color-scheme:dark){body{background:#14161a;color:#d5d9de}.card{background:#1b1e24!important;border-color:#2a2e36!important}}"
         ".card{border:1px solid #ddd;border-radius:8px;padding:.8rem 1rem;margin:.6rem 0;background:#fafafa}"
         ".chip{display:inline-block;border-radius:99px;padding:.05rem .6rem;font-size:.8rem;margin-right:.5rem;color:#fff}"
         ".live{background:#0e7490}.done{background:#15803d}.parked{background:#b45309}"
         ".id{opacity:.55;font-family:monospace;font-size:.85rem}"
         "ul{margin:.3rem 0 .1rem 1.2rem;padding:0}li{margin:.1rem 0}"
         ".miss{color:#b91c1c}.sat{color:#15803d}"
         "code{font-size:.85em;opacity:.85}"
         "h1{font-size:1.3rem}small{opacity:.6}"
         "</style></head><body>"
         "<h1>tik board</h1><small>derived " (html-escape t)
         " — every line f(events, now); nothing stored</small>"
         (str/join
                (for [{:keys [id title current facts blocks] :as row} rows]
                  (str "<div class=\"card\">"
                       "<span class=\"" (chip row) "\">"
                       (html-escape (str/join ", " (map name current))) "</span>"
                       "<b>" (html-escape title) "</b> "
                       "<span class=\"id\">" (html-escape (sid id)) "</span>"
                       (when (seq facts)
                         (str "<ul>"
                              (str/join (for [[p v] facts]
                                           (str "<li><code>" (html-escape (pr-str p))
                                                " = " (html-escape (pr-str v))
                                                "</code></li>")))
                              "</ul>"))
                       (str/join
                              (for [{:keys [stage missing]} blocks]
                                (str "<div><small>to reach <b>"
                                     (html-escape (name stage)) "</b>:</small><ul>"
                                     (str/join
                                            (for [r missing]
                                              (str "<li class=\"miss\">✗ "
                                                   (html-escape (explain/reason->text r))
                                                   "</li>")))
                                     "</ul></div>")))
                       "</div>")))
         "</body></html>")]
    (if-let [out (first pos)]
      (do (spit out html) (println "wrote" out))
      (print html))))

(defn cmd-roles
  "Who gates what: every role on the open board with its members and
  the stages waiting on its signature — the admin's inverse of
  next --role, derived from the definitions open tickets actually pin."
  [{:keys [opts]}]
  (let [s (the-store)
        t (now)
        procs (distinct
               (for [{:keys [events process roles]} (all-ticket-ctx s)
                     :when (not (next-lens/settled? process events t roles))]
                 process))
        ;; distinct on the RENDERED row: several archived versions of
        ;; one process may be pinned; identical gating collapses
        rows (distinct
              (for [p procs
                    [role {:keys [members stages]}] (process/roles-gating p)]
                {:role role :process (:process/id p)
                 :members members :stages stages}))
        by-role (group-by :role rows)]
    (when-not (emit-data opts (vec rows))
            (doseq [[role entries] (sort-by (comp str key) by-role)]
        (println (str (tint "1" (str role)) " — "
                      (let [ms (distinct (mapcat :members entries))]
                        (if (seq ms) (str/join ", " ms)
                            (tint "31" "NO MEMBERS — nothing can satisfy this role")))))
        (doseq [{:keys [process stages]} (sort-by (comp str :process) entries)]
          (println (str "  " process ": "
                        (if (seq stages)
                          (str "gates " (str/join ", " (map str (sort-by str stages))))
                          "declared, gates no stage by signature")))))
      (when (empty? by-role)
        (println "no roles on the open board")))))
