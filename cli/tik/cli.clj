(ns tik.cli
  "The tik CLI. babashka-first, JVM-compatible.

  Work commands:      new set dispute attach comment status explain log ls verify
  Process developer:  lint

  Conventions:
  - store root:  $TIK_ROOT or the current directory
  - actor:       --actor, else $TIK_ACTOR, else the OS user
  - fact keys:   dotted paths -> keyword vectors
  - fact values: parsed as EDN; bare words become keywords"
  (:require [tik.args :refer [parse-args]]
            [tik.adopt :as adopt]
            [tik.admin :as admin]
            [tik.agent :as agent]
            [tik.audit :as audit]
            [tik.authoring :as authoring]
            [tik.bridge :as bridge]
            [tik.cli-core :refer [*exit-fn* exit!]]
            [tik.design :as design]
            [tik.effects :as effects]
            [tik.inspect :as inspect]
            [tik.linting :as linting]
            [tik.query :as query]
            [tik.serve :as serve]
            [tik.storeops :as storeops]
            [tik.workcmd :as workcmd]
            [tik.write :as write]))

(def ^:private usage
  "tik — a process system, not a ticket system

  start here:
    tik author                       answer questions; tik writes the process
    tik new expense-approval         mint a ticket against it
    tik set <id> amount=120          record facts; the stage derives itself
    tik explain <id>                 what is missing, who can act

  tik init [--sqlite] [--hidden]                mark this directory as a store
                                                (--sqlite: single-file tik.db backend;
                                                default is the file/git store. --hidden:
                                                everything inside .tik/, e.g. above many repos).
  tik store migrate --to sqlite|file            convert this store's backend in place
                                                Commands find the store git-style:
                                                TIK_ROOT, else the nearest ancestor
                                                with .tik/ or tickets/, else here
  tik adopt <process|template.edn>              bring a library process into this store:
            [--params <p.edn>]                  a plain definition is copied; a template
                                                (:tik/params) is filled — interactively by
                                                default, its spec driving typed prompts —
                                                expanded, linted, and written with its
                                                runbooks. The expanded EDN is authoritative
  tik new <process> [--title T] [--actor A]     mint a ticket (pins process hash);
                                                created beneath a store it inherits
                                                context as signed facts: repo=<name>
                                                from the enclosing git repo, plus any
                                                .tik-facts.edn maps on the way down
                                                (nearest wins; explicit beats auto)
  tik set <id> k=v [k=v ...] [--actor A]        assert facts (dotted keys nest)
  tik retract <id> <k> [--reason R]             withdraw a fact (no replacement)
  tik dispute <id> <k> [--reason R]             reject a fact; stage regresses by derivation
  tik diff <id> [n]                             evidence gained over the last n events
  tik attach <id> <file>                        attach an artifact (stored by hash)
  tik comment <id> <text...>                    add a comment (a text blob, attached by hash)
  tik status <id> [--at <instant>]              derived stage, facts, what's next
                  [--links :stage]              (--at: the state at ANY moment;
                                                --links: only that stage's links —
                                                the header still counts them all)
  tik explain <id> [--actor A] [--edn]          what is needed to advance
                                                (--actor: only what THIS person can
                                                act on, with a count of the rest;
                                                --edn: the ADR 0016 data contract —
                                                stable plumbing; text is never stable)
  tik log <id>                                  the event history
  tik causal <id> [--edn]                       which signed events made each reached
                                                stage true (negations and time say so
                                                honestly) — the auditor's \"prove it\"
  tik ls [--all] [--long] [--where SELECTOR]    tickets with derived stages (open by
                                                default; --all includes settled);
                                                --long adds description + links;
                                                --where filters by a selector
  tik search <text...>                          ALL tickets whose title/facts hold
                                                every word (sugar for --all --where '~w …')
  tik dupes [--threshold 0.4]                   near-title lookalike report over open
                                                tickets (selection is `ls --where`)

  A SELECTOR is space-separated terms, all ANDed, each optionally `not`:
    stage=:blocked   fact:severity   fact:severity=:high   actor=seb
    disputed   conflicted   unsigned   derived-from=<hash>   ~text   (bare word)
    e.g.  stage=:blocked and fact:severity=:high and not disputed

  Machine output — any lens: --edn (short for --format edn) | --format json | human
  tik whatif <id> k=v|retract:k|+PT48H ...      counterfactual: stage diff, nothing
                                                written — e.g. tik whatif 3184 sev=:low
                                                +P2D (two days pass) retract:category
  tik debug <process> [<id>]                    the fixpoint with its working shown
  tik board [<file.html>]                       the whole board as ONE dependency-free
                                                HTML file — mail it, archive it
  tik serve [--port N]                          the live board over HTTP (read-only;
                                                /tickets.edn + /explain/<id>.edn for tools)
  tik mcp                                        MCP server over stdio: the frontier as an
                                                agent's gated tool surface (TIK_ACTOR/TIK_KEY)
  tik bridge email [--config F] < msg           mail in: sender->actor per config;
                                                [tik <id>] comments that ticket and
                                                tik> key=value lines become facts;
                                                else a new ticket
  tik bridge oidc [--registry ID] [--actor A]   identity rung 2: device-flow login ->
                  [--user U]                    a signed key-binding attestation on the
                  [--password-command C |       registry ticket; verification never
                   --password-file F]           calls the IdP. Give the password via a
                                                secret manager / file / TIK_OIDC_PASSWORD,
                                                not a literal --password (argv is public);
                                                fetches require HTTPS (loopback excepted)
  tik bridge oid4vci --credential vc.jwt        ingest a verifiable credential: verify
                  --registry ID                 the issuer signature against its JWKS,
                  [--jwks-url U | --jwks FILE]   mint it as a bridge-signed attestation
                                                (a VC is an attestation with an external
                                                issuer); offline-verifiable thereafter
  tik effects run [--config F] [--dry-run]      alerts out: derived transitions to
                                                slack|discord|matrix|teams|mattermost|
                                                rocketchat|googlechat|ntfy|gotify|
                                                pushover|telegram|opsgenie|alertmanager|
                                                pagerduty|webhook|email|command sinks
                                                (ADR 0019). Per-sink :template puts the
                                                message in your words ({{title}}
                                                {{stage}} {{short}} {{ticket}}). ANY sink
                                                field (a :url, :token, a :headers value)
                                                may be a secret: a literal, {:env \"NAME\"},
                                                {:command [\"pass\" \"show\" \"x\"]} (or a
                                                \"shell string\"), {:file \"/path\"}, or
                                                {:credential \"NAME\"} (systemd) — resolved
                                                at send time, so no secret sits in
                                                effects.edn; :command pipes the JSON to ANY
                                                program; email renders explain and its
                                                tik> replies close the loop
  tik rollout <process> [--parent <id>]         one ticket per git repo under the store
              [--parent-title T]                (context-tagged), wired as link facts on
                                                a parent: a checklist whose checkmarks
                                                derive from each child's evidence.
                                                Idempotent; re-runs report coverage
  tik probe [<id>] [--command C]                auto-derive facts from the world: run
                                                each repo ticket's probe (the :probe
                                                script of its process; any executable
                                                printing key=value) with cwd in that
                                                repo; changed values land as signed
                                                facts and stages derive. Idempotent
  tik next [--actor A] [--role :r] [--all]      the inbox: what unlocks the most work,
                                                quiet tickets rising; --actor = what I
                                                can do, --role = what a role is being
                                                waited on for (--all adds settled)
  tik pack [<id>]                               consolidate settled tickets into one
                                                content-addressed pack file each —
                                                fewer inodes and git objects; verify
                                                checks every packed slice against its
                                                id; later events land loose and merge
  tik plan [<file.html>]                        the dependency-link roadmap: ready /
                                                blocked / done / cyclic, the critical
                                                path, and each item's unlock impact —
                                                derived, never stale. .html arg writes
                                                the fancy self-contained page
  tik gc [--apply]                              remove archived process definitions no
                                                ticket pins (migrated-away versions);
                                                dry-run by default, verify stays PASS,
                                                only historical --at degrades
  tik verify [<id>] [--changed]                 the verify ladder; no id = whole store
                                                (--changed: skip unchanged heads —
                                                drift check, not the full audit)
  tik root [--witness] [--anchor]               ONE hash committing to the entire
                                                store; --witness countersigns it;
                                                --anchor adds a third-party timestamp
                                                (needs the external ots tool; optional)
  tik process sign <name> [--key K]             publish: sign the archived definition
  tik agent actions|set|attest <id> --actor A   the GATED agent surface: only what the
                                                frontier admits for the role; everything
                                                else is refused with the derived reason
  tik witness <id> [--key K]                    countersign the head(s): one signature
                                                timestamps the entire ancestry
  tik attest <id> <claim-edn>                   record a signed claim (kernel ignores
                                                semantics; :attested-within reads it)
  tik work record|week|cost                     work evidence (H6): telemetry claims in,
                                                machine-drafted human-signed records out,
                                                usage totals derived — never stored
  tik actor add <name> <key.pub>                register a signer (identity rung 1)
  tik sign <id> [--key K]                       sign your events (or set TIK_KEY to
                                                sign every write as it happens)
  tik reprocess <id> <new.edn> [--reason R]     re-pin a ticket to a new definition;
                [--apply]                       derived-stage diff, dry-run unless --apply
  tik export <dir>                              materialize any store as the file/git
                                                format (the audit interchange)
  tik bundle <id> [--out file.tgz]              ONE ticket as a portable evidence
                                                bundle: events, signatures, witness
                                                marks, pinned ruleset, verify.sh —
                                                checkable with coreutils + ssh-keygen,
                                                no tik required
  tik author [--from answers.edn] [--force]     guided interview -> a linted process
             [--template bug|change-request|    definition + test skeleton; no EDN
              purchase-approval] [--name N]     knowledge needed; templates are
                                                finished interviews to edit from;
                                                `author prompt` prints an LLM recipe
                                                that yields an answers.edn;
                                                `author check <answers.edn>` lints it
                                                without writing (schema + smells)
  tik roles [--edn]                             who gates what: every role on the open
                                                board, its members, and the stages
                                                waiting on its signature
  tik lint [<process.edn>]                      lint a process definition; with no
                                                argument, lint the STORE — open tickets
                                                missing descriptions/titles/signatures
  tik show <process|file.edn> [<id>]            draw the process as a vertical stage
                                                graph — stages, guards, forks, joins;
                                                with a ticket, overlay its progress
                                                (✓ reached ◆ actionable · blocked)
  tik sim <process.edn>                         live process design (scratch ticket,
                                                auto-reloading definition)
  tik test <tests.edn>                          run scripted process tests (steps in,
                                                expected stages out)")

(defn- dispatch [cmd parsed]
  (case cmd
      "init"    (storeops/cmd-init parsed)
      "store"   (storeops/cmd-store parsed)
      "rollout" (storeops/cmd-rollout parsed)
      "probe"   (storeops/cmd-probe parsed)
      "pack"    (storeops/cmd-pack parsed)
      "gc"      (storeops/cmd-gc parsed)
      "plan"    (query/cmd-plan parsed)
      "show"    (linting/cmd-show parsed)
      "new"     (write/cmd-new parsed)
      "adopt"   (adopt/cmd-adopt parsed)
      "set"     (write/cmd-set parsed)
      "retract" (write/cmd-retract parsed)
      "dispute" (write/cmd-dispute parsed)
      "diff"    (write/cmd-diff parsed)
      "attach"  (write/cmd-attach parsed)
      "comment" (write/cmd-comment parsed)
      "status"  (inspect/cmd-status parsed)
      "explain" (inspect/cmd-explain parsed)
      "log"     (inspect/cmd-log parsed)
      "causal"  (inspect/cmd-causal parsed)
      "ls"      (query/cmd-ls parsed)
      "next"    (inspect/cmd-next parsed)
      "search"  (query/cmd-search parsed)
      "dupes"   (query/cmd-dupes parsed)
      "whatif"  (design/cmd-whatif parsed)
      "debug"   (design/cmd-debug parsed)
      "board"   (query/cmd-board parsed)
      "serve"   (serve/cmd-serve parsed)
      ;; the MCP stdio loop lives in tik.mcp (which requires this ns);
      ;; resolve it lazily to avoid the require cycle — tik.main force-
      ;; requires tik.mcp so the native image can resolve it here too.
      "mcp"     ((requiring-resolve 'tik.mcp/-main))
      "bridge"  (bridge/cmd-bridge parsed)
      "effects" (effects/cmd-effects parsed)
      "verify"  (audit/cmd-verify parsed)
      "root"    (audit/cmd-root parsed)
      "author"  (authoring/cmd-author parsed)
      "roles"   (query/cmd-roles parsed)
      "bundle"  (audit/cmd-bundle parsed)
      "lint"    (linting/cmd-lint parsed)
      "actor"   (admin/cmd-actor parsed)
      "attest"  (admin/cmd-attest parsed)
      "work"    (workcmd/cmd-work parsed)
      "witness" (audit/cmd-witness parsed)
      "agent"   (agent/cmd-agent parsed)
      "process" (admin/cmd-process parsed)
      "sign"    (admin/cmd-sign parsed)
      "reprocess" (admin/cmd-reprocess parsed)
      "export"  (audit/cmd-export parsed)
      "sim"     (design/cmd-sim parsed)
      "test"    (design/cmd-test parsed)
      ("help" "--help" "-h" nil) (println usage)
      (do (println (str "tik: '" cmd "' is not a command — the full list:\n"))
          (println usage))))

(defn- dispatch-guarded
  "dispatch with the standard escape handling every entry point shares:
  an ex-info becomes its own one-line message (the kernel already words
  its rejections), anything else a this-is-a-bug line — both exit 1 via
  exit!, so the caller (binary vs in-process runner) decides whether
  that ends the process or is captured. The exit SIGNAL that exit! may
  throw in-process is re-thrown untouched, never reworded as a command
  error. Argument parsing runs INSIDE the guard, so a hostile argv
  (a non-string element from an embedder) fails well like any other
  input, on both entry points. TIK_DEBUG=1 rethrows for developers."
  [argv]
  (letfn [(run [] (let [[cmd & more] argv]
                    (dispatch cmd (parse-args (vec more)))))]
    (if (System/getenv "TIK_DEBUG")
      (run)
      (try
        (run)
        (catch clojure.lang.ExceptionInfo e
          (if (contains? (ex-data e) ::exit)
            (throw e)
            (do (binding [*out* *err*]
                  (println (str "tik: " (ex-message e)))
                  (when-let [file (:file (ex-data e))]
                    (println (str "  in: " file))))
                (exit! 1))))
        (catch Throwable e
          (binding [*out* *err*]
            (println (str "tik: unexpected error ("
                          (.getSimpleName (class e)) "): "
                          (or (ex-message e) "no message")))
            (println "  this is a bug in tik — TIK_DEBUG=1 shows the trace"))
          (exit! 1))))))

(defn -main
  "The binary entry point: dispatch with the shared escape handling,
  every exit! terminating the process (the default *exit-fn*)."
  [& args]
  (dispatch-guarded (vec args)))

(defn run-argv
  "The IN-PROCESS entry point beside -main: run one CLI invocation with
  the exact same dispatch and escape handling, but stdout/stderr are
  captured and every exit! — a command's own die/refuse, or the escape
  handler's — is trapped into an exit CODE instead of terminating.
  Returns {:exit int :out string :err string}. The MCP server (and any
  embedder) reuse the whole CLI through this, no subprocess.

  argv must be a sequence of strings; a non-string element is a caller
  error answered as a clean exit-1 result, never a raw NPE from the
  parser (the fail-well contract reaches the embedding seam too)."
  [argv]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        code (volatile! 0)]
    (binding [*out* out
              *err* err
              *exit-fn* (fn [c]
                          (vreset! code c)
                          (throw (ex-info "tik exit" {::exit c})))]
      (if-not (and (sequential? argv) (every? string? argv))
        (do (vreset! code 1)
            (binding [*out* *err*]
              (println "tik: run-argv requires a sequence of string arguments")))
        (try
          (dispatch-guarded argv)
          (catch clojure.lang.ExceptionInfo e
            ;; the only ex-info that reaches here is the exit signal —
            ;; dispatch-guarded handles every real command escape and
            ;; re-throws this one; @code already carries the exit value
            (when-not (contains? (ex-data e) ::exit)
              (vreset! code 1))))))
    {:exit @code :out (str out) :err (str err)}))























