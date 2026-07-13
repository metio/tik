;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.author
  "The authoring lens (H11): a guided interview that produces a linted
  process definition without the author ever writing EDN.

  Composition-first split:
  - `build-process` is a pure function of an answers map — every guard
    and fact declaration is derived from plain answers, so the whole
    lens is testable without a terminal.
  - `interview` is the question flow with the reader/printer INJECTED;
    the terminal wrapper in the CLI passes read-line/println, tests
    pass scripted lines.
  - `tests-skeleton` derives a starter .tests.edn from the definition
    itself, one case per terminal stage, so the author's first `tik
    test` run has something honest to fail against.

  The interview speaks the author's language (information, signature,
  file, waiting) and compiles to the closed guard basis; it never
  extends the vocabulary — the ceiling stays PLAN §18's authoring
  ceiling."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; ------------------------------------------------------------ checking

(def Need
  [:multi {:dispatch :kind}
   [:fact [:map [:kind [:= :fact]] [:path [:vector :keyword]]
           [:type {:optional true} [:= :number]]]]
   [:choice [:map [:kind [:= :choice]] [:path [:vector :keyword]]
             [:values [:vector :keyword]]]]
   [:signature [:map [:kind [:= :signature]] [:role :keyword]
                [:over {:optional true} [:vector :keyword]]]]
   [:file [:map [:kind [:= :file]] [:prefix :string]]]
   [:waited [:map [:kind [:= :waited]] [:duration :string]]]])

(def Answers
  "The authoring answers format — what the interview produces, what
  --from consumes, what the LLM prompt asks a model to emit."
  [:map
   [:name [:re #"^[a-z][a-z0-9]*(-[a-z0-9]+)*$"]]
   [:purpose {:optional true} :string]
   [:stages [:vector
             [:map
              [:name [:re #"^[a-z][a-z0-9]*(-[a-z0-9]+)*$"]]
              [:purpose {:optional true} :string]
              [:after {:optional true} [:vector :string]]
              [:needs {:optional true} [:vector Need]]]]]
   [:roles {:optional true} [:map-of :string [:vector :string]]]])

(def default-rules
  "The naming rules as DATA — one source of truth that `check` applies
  and `prompt` teaches. Each rule: :id (disable handle), :on
  (:fact-name | :stage-name), :match (regex source), :level, :msg
  (shown on findings), :teach (shown in the LLM prompt). Org rules
  from the store's authoring-rules.edn merge over these."
  [{:id :flag-facts
    :on :fact-name
    :match "(?:created|removed|deleted|added|adjusted|updated|migrated|done|completed?|enabled|disabled|configured|installed|merged|fixed)$|^(?:is|has|was|uses)-"
    :level :warning
    :msg "reads like a yes/no flag — record the thing itself instead (a reference, an address, a value) or demand the evidence with {:kind :file}; ask: what would an auditor want to SEE?"
    :teach "NEVER name a fact like a checkbox (config-created, yaml-removed, uses-x). Record the thing itself — a reference, an address, a value — or demand the evidence file. Ask: what would an auditor want to SEE?"}
   {:id :activity-stages
    :on :stage-name
    :match "^(?:in-progress|wip|doing|working|started|ongoing)$"
    :level :warning
    :msg "is named for activity, not evidence — name stages for what has become TRUE (configured, approved, verified)"
    :teach "Stages are states of evidence, not tasks; name them for what has become TRUE (submitted, approved, paid) — never in-progress/wip/doing."}])

(defn merge-rules
  "default-rules with the org's file applied: {:disable [ids…]
  :rules [more…]}. Disabling unknown ids is harmless; org rules with
  a known id REPLACE the built-in of that id."
  [org]
  (let [disabled (set (:disable org))
        override (into {} (map (juxt :id identity)) (:rules org))]
    (vec (concat (for [r default-rules
                       :when (not (disabled (:id r)))]
                   (get override (:id r) r))
                 (remove (comp (set (map :id default-rules)) :id)
                         (:rules org))))))

(defn- apply-rule [{:keys [on match level msg]} answers]
  (let [re (re-pattern match)]
    (case on
      :fact-name
      (for [{:keys [name needs]} (:stages answers)
            {:keys [kind path]} (or needs [])
            :when (and (= :fact kind)
                       (re-find re (clojure.core/name (last path))))]
        {:level level
         :msg (str "stage '" name "': fact " path " " msg)})
      :stage-name
      (for [{:keys [name]} (:stages answers)
            :when (re-find re name)]
        {:level level :msg (str "stage '" name "' " msg)})
      [])))

(defn check
  "Findings for an answers map: schema errors first (level :error),
  then structural smells, then every naming rule in `rules` (defaults
  to default-rules; pass (merge-rules org) to apply an org's file).
  Empty = clean."
  ([answers] (check answers default-rules))
  ([answers rules]
   (if-not (m/validate Answers answers)
     [{:level :error
       :msg (str "not a valid answers map: "
                 (pr-str (me/humanize (m/explain Answers answers))))}]
     (let [stage-names (set (map :name (:stages answers)))]
       (vec
        (concat
         (for [{:keys [name after]} (:stages answers)
               missing (remove stage-names (or after []))]
           {:level :error
            :msg (str "stage '" name "' comes after unknown stage '"
                      missing "'")})
         (for [{:keys [name after needs]} (:stages answers)
               :when (and (seq after) (empty? needs))]
           {:level :warning
            :msg (str "stage '" name "' has no needs — it derives the"
                      " instant its prerequisites do and adds no"
                      " information; give it a requirement or remove it")})
         (for [[role members] (:roles answers)
               :when (or (some #{role "change-me"} members)
                         (empty? members))]
           {:level :warning
            :msg (str "role '" role "' has placeholder members "
                      (pr-str members)
                      " — put real actor names in, or signatures can"
                      " never be satisfied")})
         (mapcat #(apply-rule % answers) rules)))))))

;; ---------------------------------------------------------------- pure

(defn parse-duration
  "Friendly durations: 30m, 48h, 7d — or any ISO-8601 form passed
  through unchanged."
  [s]
  (let [s (str/trim s)
        n (re-find #"\d+" s)]
    (condp re-matches s
      ;; an ISO-8601 form passes through — but VALIDATED, so "Pfoo" is
      ;; rejected (nil) rather than emitted into a definition that then
      ;; fails its own lint. Duration/parse is exactly what :elapsed-since
      ;; consumes, so accepting it here matches the guard.
      #"(?i)P.*" (let [up (str/upper-case s)]
                   (try (java.time.Duration/parse up) up
                        (catch Exception _ nil)))
      #"(\d+)\s*m" (str "PT" n "M")
      #"(\d+)\s*h" (str "PT" n "H")
      #"(\d+)\s*d" (str "P" n "D")
      nil)))

(defn parse-path
  "Dotted fact path: amount.currency -> [:amount :currency]"
  [s]
  (mapv keyword (str/split (str/trim s) #"\.")))

(defn- decision-path
  "A bare 'this role signs off' compiles to a decision FACT the role
  asserts and signs — facts over flags, and :signed-by gets the fact
  path it requires."
  [role]
  [:approval role])

(defn- need->guards [{:keys [kind path role over prefix duration]}]
  (case kind
    :fact      [[:fact path]]
    :choice    [[:fact path]]
    :signature (if over
                 [[:signed-by role over]]
                 (let [p (decision-path role)]
                   [[:fact= p :approved] [:signed-by role p]]))
    :file      [[:artifact prefix]]
    :waited    [[:elapsed-since :ticket/create duration]]))

(defn- need->fact-decl [{:keys [kind path values type role over]}]
  (case kind
    :fact [path (if (= :number type) :int [:string {:min 1}])]
    :choice [path (into [:enum] values)]
    :signature (when-not over
                 [(decision-path role) [:enum :approved :rejected]])
    nil))

(defn build-process
  "Pure: answers -> a process definition map. Answers shape:
  {:name \"expense-approval\" :purpose \"...\"
   :stages [{:name \"submitted\" :purpose \"...\" :after [\"...\"]
             :needs [{:kind :fact|:choice|:signature|:file|:waited ...}]}]
   :roles {\"approver\" [\"alice\"]}}"
  [{:keys [name purpose stages roles]}]
  (let [needs (mapcat :needs stages)
        fact-decls (into {} (keep need->fact-decl needs))
        role-decls (into {}
                         (for [[r members] roles]
                           [(keyword r) {:members (vec members)}]))]
    (cond-> {:process/id (keyword name)
             :process/version 1
             :process/guard-vocab 1}
      purpose (assoc :process/purpose purpose)
      (seq role-decls) (assoc :process/roles role-decls)
      (seq fact-decls) (assoc :process/facts fact-decls)
      true (assoc :process/stages
                  (vec (for [{:keys [name purpose after needs]} stages]
                         (cond-> {:stage/id (keyword name)}
                           purpose (assoc :purpose purpose)
                           (seq after) (assoc :after (mapv keyword after))
                           true (assoc :guards
                                       (vec (mapcat need->guards needs))))))))))

(defn- need->prose [{:keys [kind path role over prefix duration values]}]
  (case kind
    :fact (str "Record `" (str/join "." (map name path)) "`.")
    :choice (str "Record `" (str/join "." (map name path)) "` — one of "
                 (str/join ", " (map name values)) ".")
    :signature (if over
                 (str "A member of the `" (name role) "` role signs `"
                      (str/join "." (map name over)) "`.")
                 (str "A member of the `" (name role)
                      "` role records and signs their approval."))
    :file (str "Attach the file(s) under `" prefix "`.")
    :waited (str "Time does this one: " duration
                 " must have passed since the ticket was created.")))

(defn runbook-path [pname stage-name]
  (str "kb/runbooks/" pname "-" stage-name ".md"))

(defn with-runbook-hints
  "Point every stage at its runbook stub."
  [definition pname]
  (update definition :process/stages
          (fn [stages]
            (mapv #(assoc % :hint (runbook-path pname (name (:stage/id %))))
                  stages))))

(defn runbook-stubs
  "One markdown stub per stage, seeded from the interview's own words:
  the stage purpose plus each need in prose. {path content}."
  [{:keys [name stages]}]
  (into {}
        (for [{stage :name :keys [purpose needs]} stages]
          [(runbook-path name stage)
           (str "<!--\nSPDX-FileCopyrightText: The tik Authors\n"
                "SPDX-License-Identifier: 0BSD\n-->\n\n"
                "# " name ": " stage "\n\n"
                purpose "\n\n## How a ticket gets here\n\n"
                (if (seq needs)
                  (str/join "\n" (map #(str "- " (need->prose %)) needs))
                  "- Nothing to do — this stage derives on its own.")
                "\n")])))

(defn terminal-stages
  "Stages nothing points :after — the process's outcomes."
  [{:process/keys [stages]}]
  (let [referenced (set (mapcat :after stages))]
    (remove referenced (map :stage/id stages))))

(defn tests-skeleton
  "A starter .tests.edn: one case per terminal stage, steps left for
  the author to fill in. The skeleton FAILS honestly until the author
  states how each outcome is reached — that failure prints explain,
  which is the teaching surface."
  [definition file-name]
  {:test/process file-name
   :test/cases
   (vec (for [t (terminal-stages definition)]
          {:case/name (str "how does a ticket reach " t "?")
           :case/steps [[:actor "someone"]]
           :case/expect {:includes #{t}}}))})

(defn- ask [in out prompt]
  (out prompt)
  (some-> (in) str/trim))

(defn- ask-needs [in out stage-name]
  (loop [needs []]
    (let [choice (ask in out (str "\n  what must be true for '" stage-name "'?\n"
                                  "    i  a piece of information (a fact)\n"
                                  "    c  a choice from fixed options\n"
                                  "    s  a signature by a role\n"
                                  "    f  a file must be attached\n"
                                  "    w  waiting time must have passed\n"
                                  "    <enter>  nothing more — done with this stage\n  > "))]
      (case choice
        ("" nil) needs
        "i" (let [path (ask in out "  name of the information (dot for nesting, e.g. amount or customer.email): ")
                  num? (ask in out "  is it a number? [y/N]: ")]
              (recur (conj needs (cond-> {:kind :fact :path (parse-path path)}
                                   (= "y" (str/lower-case num?)) (assoc :type :number)))))
        "c" (let [path (ask in out "  name of the choice (e.g. category): ")
                  vals (ask in out "  the allowed options, comma-separated (e.g. travel,equipment): ")]
              (recur (conj needs {:kind :choice :path (parse-path path)
                                  :values (mapv (comp keyword str/trim)
                                                (str/split vals #","))})))
        "s" (let [role (ask in out "  which role must sign (e.g. approver): ")
                  over (ask in out "  which information do they sign off on (dotted name, or empty for the stage itself): ")]
              (recur (conj needs (cond-> {:kind :signature :role (keyword role)}
                                   (seq over) (assoc :over (parse-path over))))))
        "f" (let [prefix (ask in out "  file path or prefix (e.g. receipts/): ")]
              (recur (conj needs {:kind :file :prefix prefix})))
        "w" (let [d (ask in out "  how long since the ticket was created (e.g. 30m, 48h, 7d): ")
                  parsed (parse-duration d)]
              (if parsed
                (recur (conj needs {:kind :waited :duration parsed}))
                (do (out (str "  '" d "' is not a duration I understand — try 30m, 48h, 7d or ISO-8601 (PT48H)\n"))
                    (recur needs))))
        (do (out (str "  '" choice "' is not one of the options — i, c, s, f, w, or enter\n"))
            (recur needs))))))

(defn interview
  "Run the interview over injected IO: `in` returns one line per call,
  `out` prints. Returns the answers map `build-process` consumes."
  [in out]
  (out "Let's describe your process. Plain words — tik writes the definition.\n\n")
  (let [name (ask in out "process name (kebab-case, e.g. expense-approval): ")
        purpose (ask in out "one line: what is this process for? ")
        stages (loop [stages [] prev nil]
                 (let [sname (ask in out (str "\nstage " (inc (count stages))
                                              " name (e.g. submitted; empty when done): "))]
                   (if (str/blank? sname)
                     stages
                     (let [spurpose (ask in out "  one line: what does reaching this stage mean? ")
                           after (if prev
                                   (let [a (ask in out (str "  comes after which stage(s)? (comma-separated; enter for '" prev "'; 'none' for a fresh start): "))]
                                     (cond
                                       (str/blank? a) [prev]
                                       (= "none" a) []
                                       :else (mapv str/trim (str/split a #","))))
                                   [])
                           needs (ask-needs in out sname)]
                       (recur (conj stages {:name sname :purpose spurpose
                                            :after after :needs needs})
                              sname)))))
        role-names (distinct (keep :role (mapcat :needs stages)))
        roles (into {}
                    (for [r role-names]
                      [(clojure.core/name r)
                       (let [m (ask in out (str "\nwho is in the role '" (clojure.core/name r)
                                                "'? (actor names, comma-separated): "))]
                         (mapv str/trim (str/split m #",")))]))]
    {:name name :purpose purpose :stages stages :roles roles}))

