#!/bin/sh
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Drive a built binary through everything a long-running local install
# uses: `serve` answering the board and MCP in both protocol eras, one
# sweep of each sync bridge (github, gitlab, redmine, odoo) and of
# `bridge imap`, `backend` firing a scheduled bridge sweep, `notify`
# delivering one hint to `notify watch`, one multi-store backend
# serving two stores with their own keys and MCP tokens, and one running
# two bridge pipelines and an IMAP watch as child processes, which never
# overlap a pipeline's runs and report the one that times out. The remote systems are the
# loopback fakes in dev/native-smoke/fakes.clj (run with babashka), so
# nothing real is contacted. native-image carries only what its build
# reached, so a namespace, class or resource the build missed fails here
# and not on somebody's machine. The release workflow runs this against
# target/tik after dev/build-native.sh.
#
#   sh dev/native-smoke.sh [binary]
#
# Prints one ok/FAIL line per check and exits 1 when any check failed.
set -eu
cd "$(dirname "$0")/.."
repo=$(pwd)
bin=${1:-target/tik}
case "${bin}" in /*) ;; *) bin="${repo}/${bin}" ;; esac
[ -x "${bin}" ] || { echo "FAIL ${bin} is not an executable file"; exit 1; }

# Unix socket paths hold at most 108 bytes, so the work directory stays short.
work=$(mktemp -d /tmp/tik-smoke.XXXXXX)
pids=""
cleanup() {
  for p in ${pids}; do kill "${p}" 2>/dev/null || true; done
  wait 2>/dev/null || true
  if [ -n "${TIK_SMOKE_KEEP:-}" ]; then echo "kept ${work}"; else rm -rf "${work}"; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

failures=0
ok() { echo "ok   $1"; }
fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
  shift
  for f in "$@"; do
    [ -f "${f}" ] && sed 's/^/     | /' "${f}" | tail -n 25
  done
  return 0
}

# wait_for <seconds> <command…>: true once the command succeeds.
wait_for() {
  limit=$(( $1 * 10 )); shift
  i=0
  while [ "${i}" -lt "${limit}" ]; do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 0.1
    i=$((i + 1))
  done
  return 1
}

# wait_on <pid> <seconds> <command…>: like wait_for, but false at once when
# the process <pid> has ended, so a verb that dies early fails fast.
wait_on() {
  pid=$1; limit=$(( $2 * 10 )); shift 2
  i=0
  while [ "${i}" -lt "${limit}" ]; do
    if "$@" >/dev/null 2>&1; then return 0; fi
    kill -0 "${pid}" 2>/dev/null || return 1
    sleep 0.1
    i=$((i + 1))
  done
  return 1
}

# babashka runs outside the checkout, whose bb.edn would otherwise load.
bbx() { (cd "${work}" && exec bb "$@"); }

free_port() {
  bbx -e '(with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))] (print (.getLocalPort s)))'
}

# ------------------------------------------------------------- the store

store="${work}/store"
mkdir -p "${store}/processes"
if ! (cd "${store}" && "${bin}" init) > "${work}/init.out" 2>&1; then
  fail "init: no store, so nothing else can run" "${work}/init.out"
  exit 1
fi
ok "init"
for p in ci-failure redmine-issue odoo-task; do
  cp "processes/${p}.edn" "${store}/processes/"
done
ssh-keygen -q -t ed25519 -N "" -C smoke -f "${work}/smoke_key"
ssh-keygen -q -t ed25519 -N "" -C bridge -f "${work}/bridge_key"
{
  echo "smoke namespaces=\"tik-*\" $(cat "${work}/smoke_key.pub")"
  echo "bridge namespaces=\"tik-*\" $(cat "${work}/bridge_key.pub")"
} > "${store}/actors"
# The sample processes name github, gitlab, redmine and odoo as their
# bridge; here one actor signs for every bridge.
echo '{:bridge {:members ["bridge"]}}' > "${store}/roles.edn"
export TIK_ROOT="${store}"
# The notifier keeps its cursor and socket here, never in the caller's.
export XDG_STATE_HOME="${work}/state" XDG_RUNTIME_DIR="${work}/run"
mkdir -p "${XDG_STATE_HOME}" "${XDG_RUNTIME_DIR}"
chmod 700 "${XDG_RUNTIME_DIR}"

as_smoke() { TIK_ACTOR=smoke TIK_KEY="${work}/smoke_key" "${bin}" "$@"; }
as_bridge() { TIK_ACTOR=bridge TIK_KEY="${work}/bridge_key" "${bin}" "$@"; }

if "${bin}" --help > "${work}/help.out" 2>&1; then
  ok "--help"
else
  fail "--help" "${work}/help.out"
fi

# ------------------------------------------------------------- deep EDN

# The EDN reader recurses per collection and per prefix (tag, ##, discard,
# metadata, namespaced map); a chain of any of them past the depth limit
# must be refused as too deep, not overflow the native stack.
chain() { yes "$1" | head -n 200000 | tr -d '\n'; printf '%s' "$2"; }
deep_ok=0
for kind in vector discard meta tag symbolic nsmap comment; do
  f="${work}/deep-${kind}.edn"
  case "${kind}" in
    vector) chain '[' '1' > "${f}" ;;
    discard) chain '#_ ' '{}' > "${f}" ;;
    meta) chain '^:a ' '{}' > "${f}" ;;
    tag) chain '#foo ' '{}' > "${f}" ;;
    symbolic) chain '## ' 'Inf' > "${f}" ;;
    nsmap) chain '#:a{:b ' '1' > "${f}" ;;
    comment) { printf ';"\n'; chain '[' '1'; } > "${f}" ;;
  esac
  if "${bin}" lint "${f}" > "${f}.out" 2>&1; then
    deep_ok=1
    fail "deep EDN: lint accepted the ${kind} chain" "${f}.out"
  elif ! grep -q 'nesting too deep' "${f}.out" || grep -q 'bug in tik' "${f}.out"; then
    deep_ok=1
    fail "deep EDN: the ${kind} chain was not refused as too deep" "${f}.out"
  fi
done
[ "${deep_ok}" -eq 0 ] && ok "deep EDN: every chain past the depth limit is refused as too deep"

# ------------------------------------------------------------- the fakes

# exec, so the pid below is babashka's own and cleanup stops it.
(cd "${work}" && exec bb "${repo}/dev/native-smoke/fakes.clj" "${work}/ports.edn" \
  "${work}/requests.log") > "${work}/fakes.out" 2>&1 &
fakes_pid=$!
pids="${pids} ${fakes_pid}"
if ! wait_on "${fakes_pid}" 60 test -s "${work}/ports.edn"; then
  fail "the fake remotes did not start" "${work}/fakes.out"
  exit 1
fi
http_port=$(bbx -e "(print (:http (clojure.edn/read-string (slurp \"${work}/ports.edn\"))))")
imap_port=$(bbx -e "(print (:imap (clojure.edn/read-string (slurp \"${work}/ports.edn\"))))")
fake="http://127.0.0.1:${http_port}"

# Most secrets arrive through a {:command …} spec, as the local bridge
# configs fetch theirs from a password manager, so resolving one starts a
# process from inside the binary. The Redmine key is a systemd credential,
# the way a unit with LoadCredential= hands one over.
mkdir -p "${work}/credentials"
printf 'rm-smoke-key\n' > "${work}/credentials/redmine-key"
chmod 400 "${work}/credentials/redmine-key"
export CREDENTIALS_DIRECTORY="${work}/credentials"

# `ssh` for the GitLab :tunnel is a loopback forwarder.
mkdir -p "${work}/bin"
cat > "${work}/bin/ssh" <<EOF
#!/bin/sh
cd "${work}" && exec bb "${repo}/dev/native-smoke/fake-ssh.clj" "\$@"
EOF
chmod +x "${work}/bin/ssh"
export FAKE_SSH_LOG="${work}/requests.log"
cat > "${work}/github.edn" <<EOF
{:repos ["acme/widgets"] :api "${fake}/github" :actor "bridge"
 :token {:command "printf %s gh-smoke-token"} :actions {:lookback "P14D"}}
EOF
cat > "${work}/gitlab.edn" <<EOF
{:url "${fake}/gitlab" :projects ["acme/widgets"] :actor "bridge"
 :token {:command ["printf" "%s" "gl-smoke-token"]} :pipelines {:lookback "P14D"}}
EOF
cat > "${work}/gitlab-tunnel.edn" <<EOF
{:url "https://gitlab.invalid/gitlab" :projects ["acme/widgets"] :actor "bridge"
 :tunnel {:ssh "smoke@gitlab.invalid" :target "127.0.0.1:${http_port}"}
 :token {:command "printf %s gl-smoke-token"} :pipelines {:lookback "P14D"}}
EOF
cat > "${work}/redmine.edn" <<EOF
{:url "${fake}/redmine" :actor "bridge" :key {:credential "redmine-key"}
 :query {:assigned_to_id "me"} :users {11 "smoke"}}
EOF
cat > "${work}/odoo.edn" <<EOF
{:url "${fake}/odoo" :db "smoke" :actor "bridge" :process :odoo-task
 :user {:command "printf %s smoke@example.org"}
 :password {:command "printf %s odoo-smoke-pw"}
 :closed-stages ["Done"] :users {45 "smoke"}}
EOF
cat > "${work}/imap.edn" <<EOF
{:imap {:host "127.0.0.1" :port ${imap_port} :tls false
        :user "desk@example.org" :password {:command "printf %s imap-smoke-pw"}}
 :process :track :default-actor "bridge"}
EOF

# ------------------------------------------------------------- bridges

# sweep <bridge> <args…>: one sweep mints at least one ticket, and a
# second sweep over the unchanged remote writes nothing.
sweep() {
  name=$1; shift
  out="${work}/${name}.out"
  if ! as_bridge bridge "$@" > "${out}" 2>&1; then
    fail "bridge ${name}: the sweep exited non-zero" "${out}" "${work}/requests.log"
    return 0
  fi
  if ! grep -q "new ticket" "${out}"; then
    fail "bridge ${name}: the sweep minted no ticket" "${out}" "${work}/requests.log"
    return 0
  fi
  if ! as_bridge bridge "$@" > "${out}.again" 2>&1; then
    fail "bridge ${name}: the second sweep exited non-zero" "${out}.again"
    return 0
  fi
  if grep -q "new ticket\|event(s) ->" "${out}.again"; then
    fail "bridge ${name}: a second sweep over the same remote wrote again" "${out}.again"
    return 0
  fi
  short=$(grep -m1 -o 'new ticket [0-9a-f]*' "${out}" | cut -d' ' -f3)
  if ! "${bin}" status "${short}" > "${out}.status" 2>&1 \
     || ! grep -q '^stage: *[a-z]' "${out}.status"; then
    fail "bridge ${name}: ticket ${short} derives no stage" "${out}.status"
    return 0
  fi
  ok "bridge ${name}: ticket ${short} at $(sed -n 's/^stage: *\([^ ]*\).*/\1/p' "${out}.status")"
}

sweep github github --config "${work}/github.edn"
sweep gitlab gitlab --config "${work}/gitlab.edn"
PATH="${work}/bin:${PATH}" sweep gitlab-tunnel gitlab --config "${work}/gitlab-tunnel.edn"
sweep redmine redmine --config "${work}/redmine.edn"
sweep odoo odoo --config "${work}/odoo.edn"
sweep imap imap --config "${work}/imap.edn"

for remote in github gitlab redmine odoo; do
  if ! grep -q "^http [A-Z]* /${remote}/.* 200$" "${work}/requests.log"; then
    fail "the ${remote} fake answered no request with 200" "${work}/requests.log"
  fi
done
if ! grep -q "^ssh -L 127.0.0.1:[0-9]*:127.0.0.1:${http_port} smoke@gitlab.invalid" "${work}/requests.log"; then
  fail "the GitLab tunnel never started ssh" "${work}/requests.log"
fi
if ! grep -q "^imap UID FETCH" "${work}/requests.log"; then
  fail "the IMAP fake served no message" "${work}/requests.log"
fi

if "${bin}" ls --all --edn > "${work}/ls.edn" 2>&1 \
   && [ "$(grep -o ':id ' "${work}/ls.edn" | wc -l)" -ge 6 ]; then
  ok "ls lists the tickets the bridges minted"
else
  fail "ls does not list the minted tickets" "${work}/ls.edn"
fi

# ------------------------------------------------------------- TLS

# Every real remote speaks HTTPS. openssl s_server answers with a
# self-signed certificate: the binary must refuse it with its default trust
# store, and complete the handshake once -Djavax.net.ssl.trustStore names a
# store holding it, which then fails only on the HTML s_server returns.
if command -v keytool >/dev/null 2>&1; then
  tls_port=$(free_port)
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 \
    -keyout "${work}/tls.key" -out "${work}/tls.crt" -subj /CN=127.0.0.1 \
    -addext subjectAltName=IP:127.0.0.1 >/dev/null 2>&1
  keytool -importcert -noprompt -alias smoke -file "${work}/tls.crt" \
    -keystore "${work}/trust.p12" -storetype PKCS12 -storepass smoke-trust >/dev/null 2>&1
  openssl s_server -quiet -www -accept "${tls_port}" \
    -cert "${work}/tls.crt" -key "${work}/tls.key" > "${work}/tls-server.out" 2>&1 &
  pids="${pids} $!"
  cat > "${work}/redmine-tls.edn" <<EOF
{:url "https://127.0.0.1:${tls_port}" :actor "bridge" :key "k"}
EOF
  if wait_for 30 curl -sk -o /dev/null "https://127.0.0.1:${tls_port}/"; then
    as_bridge bridge redmine --config "${work}/redmine-tls.edn" > "${work}/tls-untrusted.out" 2>&1 || true
    as_bridge -Djavax.net.ssl.trustStore="${work}/trust.p12" -Djavax.net.ssl.trustStorePassword=smoke-trust \
      bridge redmine --config "${work}/redmine-tls.edn" > "${work}/tls-trusted.out" 2>&1 || true
    if grep -q "unreachable at https://127.0.0.1:${tls_port}: SSLHandshakeException" "${work}/tls-untrusted.out" \
       && grep -q "Redmine API returned non-JSON" "${work}/tls-trusted.out"; then
      ok "TLS: an untrusted certificate is refused, a trusted one completes the handshake"
    else
      fail "TLS" "${work}/tls-untrusted.out" "${work}/tls-trusted.out" "${work}/tls-server.out"
    fi
  else
    fail "TLS: openssl s_server did not listen" "${work}/tls-server.out"
  fi
else
  fail "TLS: keytool is not on PATH"
fi

# ------------------------------------------------------------- serve

port=$(free_port)
openssl rand -hex 32 > "${work}/mcp-token"
TIK_ACTOR=smoke TIK_KEY="${work}/smoke_key" "${bin}" serve --host 127.0.0.1 --port "${port}" \
  --mcp-token-file "${work}/mcp-token" --surfaces http,mcp > "${work}/serve.out" 2>&1 &
serve_pid=$!
pids="${pids} ${serve_pid}"
base="http://127.0.0.1:${port}"
token=$(cat "${work}/mcp-token")
if wait_on "${serve_pid}" 30 curl -fsS -o /dev/null "${base}/"; then
  if curl -fsS "${base}/tickets.edn" > "${work}/tickets.edn" && grep -q ':id' "${work}/tickets.edn"; then
    ok "serve: the board and /tickets.edn answer"
  else
    fail "serve: /tickets.edn" "${work}/tickets.edn" "${work}/serve.out"
  fi

  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${base}/mcp" \
    -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"ping"}')
  if [ "${code}" = "401" ]; then
    ok "serve: /mcp refuses a request without the bearer token"
  else
    fail "serve: /mcp answered ${code} without a token"
  fi

  mcp() {
    curl -fsS -X POST "${base}/mcp" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer ${token}" "$@"
  }
  legacy_init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
  legacy_call='{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"tik_board","arguments":{}}}'
  if mcp -d "${legacy_init}" > "${work}/mcp-init.json" \
     && jq -e '.result.serverInfo.name' "${work}/mcp-init.json" >/dev/null \
     && mcp -d "${legacy_call}" > "${work}/mcp-board.json" \
     && jq -e '.result.content[0].text | fromjson | length >= 6' "${work}/mcp-board.json" >/dev/null; then
    ok "serve: MCP initialize and tools/call tik_board (legacy era)"
  else
    fail "serve: MCP legacy era" "${work}/mcp-init.json" "${work}/mcp-board.json" "${work}/serve.out"
  fi

  meta='"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}'
  if mcp -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: server/discover' \
       -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{${meta}}}" \
       > "${work}/mcp-discover.json" \
     && jq -e '.result.supportedVersions | index("2026-07-28")' "${work}/mcp-discover.json" >/dev/null \
     && mcp -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: tik_board' \
       -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"tik_board\",\"arguments\":{},${meta}}}" \
       > "${work}/mcp-board2.json" \
     && jq -e '.result.content[0].text | fromjson | length >= 6' "${work}/mcp-board2.json" >/dev/null \
     && mcp -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: skills/list' \
       -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"skills/list\",\"params\":{${meta}}}" \
       > "${work}/mcp-skills.json" \
     && jq -e '.result.skills[0].resources | length >= 1' "${work}/mcp-skills.json" >/dev/null; then
    ok "serve: MCP server/discover, tools/call tik_board and skills/list (2026-07-28)"
  else
    fail "serve: MCP modern era" "${work}/mcp-discover.json" "${work}/mcp-board2.json" \
      "${work}/mcp-skills.json" "${work}/serve.out"
  fi
else
  fail "serve: nothing answered on ${base}" "${work}/serve.out"
fi

# ------------------------------------------------------------- backend

cat > "${store}/pipelines.edn" <<EOF
{:pipelines [{:id :redmine :every "PT1H" :as "bridge"
              :run ["bridge" "redmine" "--config" "${work}/redmine.edn"]}]}
EOF
TIK_KEY="${work}/bridge_key" "${bin}" backend > "${work}/backend.out" 2>&1 &
backend_pid=$!
pids="${pids} ${backend_pid}"
if wait_on "${backend_pid}" 60 grep -q '^\[redmine\] bridge redmine .* -> ok' "${work}/backend.out"; then
  ok "backend: the scheduled redmine sweep ran as bridge"
else
  fail "backend: the scheduled pipeline did not report ok" "${work}/backend.out"
fi
kill "${backend_pid}" 2>/dev/null || true

# ------------------------------------------------------------- notify

sock="${XDG_RUNTIME_DIR}/tik/notify.sock"
cat > "${work}/notify.edn" <<EOF
{:stores {"smoke" "${store}"} :socket "${sock}" :window "PT1S"}
EOF
"${bin}" notify --config "${work}/notify.edn" > "${work}/notify.out" 2>&1 &
notify_pid=$!
pids="${pids} ${notify_pid}"
if wait_on "${notify_pid}" 30 test -S "${sock}"; then
  "${bin}" notify watch --socket "${sock}" --store smoke > "${work}/watch.out" 2>"${work}/watch.err" &
  watch_pid=$!
  pids="${pids} ${watch_pid}"
  sleep 2
  id=$(as_smoke new track --title "a hint for the smoke test" 2>/dev/null | head -n 1)
  as_smoke set "${id}" 'outcome="shipped"' > "${work}/set.out" 2>&1 || true
  if wait_on "${watch_pid}" 30 grep -q "\"ticket\":\"${id}\"" "${work}/watch.out"; then
    ok "notify: notify watch received the hint for ${id}"
  else
    fail "notify: no hint reached notify watch" "${work}/notify.out" "${work}/watch.out" \
      "${work}/watch.err" "${work}/set.out"
  fi
else
  fail "notify: the socket never appeared" "${work}/notify.out"
fi

# ------------------------------------------------------------- multi-store backend

# A multi-store backend refuses the store variables and the bridges' secret
# variables in its environment, and labels its lines for the journal when
# JOURNAL_STREAM says it writes to one; none of them belong to this script.
backend_env() {
  env -u TIK_ROOT -u TIK_KEY -u TIK_ACTOR -u TIK_PQ_KEY -u JOURNAL_STREAM \
    -u GITHUB_TOKEN -u GITHUB_WEBHOOK_SECRET -u GITLAB_TOKEN -u REDMINE_API_KEY -u ODOO_PASSWORD \
    "${bin}" "$@"
}

# One backend serves two stores, each signing with its own key as its own
# claude, each answering MCP on its own path with its own token (ADR 0041).
# Store alpha's key arrives through {:command …} and lands in a private
# runtime file; beta's is a plain path. The backend refuses TIK_ROOT in
# its environment, so it runs without the one this script exports.
fpr() { awk '{print $2}' "$1" | base64 -d | sha256sum | cut -c1-16; }
for s in alpha beta; do
  mkdir -p "${work}/ms/${s}"
  (cd "${work}/ms/${s}" && "${bin}" init) > "${work}/ms-init-${s}.out" 2>&1 \
    || fail "multi-store: init ${s}" "${work}/ms-init-${s}.out"
  ssh-keygen -q -t ed25519 -N "" -C "claude-${s}" -f "${work}/ms-${s}-claude"
  {
    echo "claude namespaces=\"tik-*\" $(cat "${work}/ms-${s}-claude.pub")"
    echo "smoke namespaces=\"tik-*\" $(cat "${work}/smoke_key.pub")"
  } > "${work}/ms/${s}/actors"
  # the maintainer (smoke) publishes track, so claude may mint it over MCP
  TIK_ROOT="${work}/ms/${s}" as_smoke new track --title "seed ${s}" --no-repo > /dev/null 2>&1 || true
  TIK_ROOT="${work}/ms/${s}" as_smoke process sign track > "${work}/ms-sign-${s}.out" 2>&1 \
    || fail "multi-store: publish track in ${s}" "${work}/ms-sign-${s}.out"
  openssl rand -hex 32 > "${work}/ms-${s}-token"
  chmod 600 "${work}/ms-${s}-token"
done
ms_port=$(free_port)
ms_sock="${work}/run/ms/notify.sock"
cat > "${work}/backend.edn" <<EOF
{:stores {:alpha {:root "${work}/ms/alpha" :org "alpha" :actor "claude"
                  :key {:command ["cat" "${work}/ms-alpha-claude"]}
                  :mcp-token-file "${work}/ms-alpha-token"}
          :beta {:root "${work}/ms/beta" :org "beta" :actor "claude"
                 :key "${work}/ms-beta-claude"
                 :mcp-token-file "${work}/ms-beta-token"}}
 :serve {:host "127.0.0.1" :port ${ms_port}}
 :notify {:socket "${ms_sock}" :window "PT1S"}
 :pipelines [{:id :weekly :store :beta :every "PT1H" :jitter "PT0S" :run ["recur" "track"] :period :iso-week}]}
EOF
backend_env backend --config "${work}/backend.edn" > "${work}/ms-backend.out" 2>&1 &
ms_pid=$!
pids="${pids} ${ms_pid}"
ms_base="http://127.0.0.1:${ms_port}"
# ms_mcp <store> <whose token> <json>: prints the HTTP status; the body
# lands in ms-<store>-<whose token>.json
ms_mcp() {
  curl -sS -o "${work}/ms-$1-$2.json" -w '%{http_code}' -X POST "${ms_base}/s/$1/mcp" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $(cat "${work}/ms-$2-token")" -d "$3"
}
mint() {
  printf '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tik_new","arguments":{"process":"track","title":"%s"}}}' "$1"
}
tickets_in() { find "${work}/ms/$1/tickets" -mindepth 1 -maxdepth 1 -type d | wc -l; }
if wait_on "${ms_pid}" 60 curl -fsS -o /dev/null "${ms_base}/s/alpha/" \
   && grep -q '2 store(s) (alpha, beta)' "${work}/ms-backend.out"; then
  ok "multi-store: one backend serves alpha and beta"
  ms_watch_pid=""
  if wait_on "${ms_pid}" 30 test -S "${ms_sock}"; then
    "${bin}" notify watch --socket "${ms_sock}" --store alpha > "${work}/ms-watch.out" 2>"${work}/ms-watch.err" &
    ms_watch_pid=$!
    pids="${pids} ${ms_watch_pid}"
    sleep 2
  fi
  a_code=$(ms_mcp alpha alpha "$(mint 'written to alpha')")
  b_code=$(ms_mcp beta beta "$(mint 'written to beta')")
  fa=$(fpr "${work}/ms-alpha-claude.pub")
  fb=$(fpr "${work}/ms-beta-claude.pub")
  if [ "${a_code}" = 200 ] && [ "${b_code}" = 200 ] \
     && jq -e '.result.isError == false' "${work}/ms-alpha-alpha.json" >/dev/null \
     && jq -e '.result.isError == false' "${work}/ms-beta-beta.json" >/dev/null \
     && [ -n "$(find "${work}/ms/alpha" -name "*.sig.${fa}")" ] \
     && [ -n "$(find "${work}/ms/beta" -name "*.sig.${fb}")" ] \
     && [ -z "$(find "${work}/ms/alpha" -name "*.sig.${fb}")" ] \
     && [ -z "$(find "${work}/ms/beta" -name "*.sig.${fa}")" ]; then
    ok "multi-store: MCP writes to each store, each signed with its own store's key only"
  else
    fail "multi-store: MCP writes (alpha ${a_code}, beta ${b_code})" "${work}/ms-alpha-alpha.json" \
      "${work}/ms-beta-beta.json" "${work}/ms-backend.out"
  fi
  before=$(tickets_in beta)
  x_code=$(ms_mcp beta alpha "$(mint 'alpha token on beta')")
  if [ "${x_code}" = 401 ] && [ "$(tickets_in beta)" = "${before}" ]; then
    ok "multi-store: alpha's token cannot write to beta"
  else
    fail "multi-store: a cross-store write answered ${x_code}" "${work}/ms-beta-alpha.json"
  fi
  # a run is a child process, so its line may trail the MCP writes
  wait_on "${ms_pid}" 30 grep -q '^\[beta/weekly\] recur track .* -> ok' "${work}/ms-backend.out" || true
  if TIK_ROOT="${work}/ms/alpha" "${bin}" verify > "${work}/ms-verify-alpha.out" 2>&1 \
     && TIK_ROOT="${work}/ms/beta" "${bin}" verify > "${work}/ms-verify-beta.out" 2>&1 \
     && curl -fsS "${ms_base}/s/alpha/tickets.edn" > "${work}/ms-alpha.edn" \
     && grep -q 'written to alpha' "${work}/ms-alpha.edn" \
     && ! grep -q 'written to beta' "${work}/ms-alpha.edn" \
     && grep -q '^\[beta/weekly\] recur track .* -> ok' "${work}/ms-backend.out"; then
    ok "multi-store: both stores verify, each board shows its own store, beta's pipeline ran in beta"
  else
    fail "multi-store: verify, boards or pipeline" "${work}/ms-verify-alpha.out" "${work}/ms-verify-beta.out" \
      "${work}/ms-alpha.edn" "${work}/ms-backend.out"
  fi
  if [ -n "${ms_watch_pid}" ] && wait_on "${ms_watch_pid}" 30 grep -q '"store":"alpha"' "${work}/ms-watch.out"; then
    ok "notify: the multi-store backend's notifier sent a hint for alpha"
  else
    fail "notify: no hint from the multi-store backend's notifier" "${work}/ms-backend.out" "${work}/ms-watch.out" \
      "${work}/ms-watch.err"
  fi
else
  fail "multi-store: the backend did not serve both stores" "${work}/ms-backend.out"
fi
kill "${ms_pid}" 2>/dev/null || true

# ------------------------------------------------------------- bridge pipelines

# The local estate's shape: bridges and a mailbox as pipelines of one
# multi-store backend, each run a child process signing as its own
# delegate. :slow sweeps a Redmine whose first listing takes 2.5 s every
# second, so its runs would overlap without the guard; :stuck sweeps a
# GitHub that never answers, so its timeout must kill it and the failure
# report must fire; :mail watches an IMAP mailbox over IDLE with DKIM
# required at a pinned verifier, records each new ticket's sender as a
# pseudonym and refuses a blocked sender.
bp="${work}/bp/smoke"
mkdir -p "${bp}"
(cd "${bp}" && "${bin}" init) > "${work}/bp-init.out" 2>&1 || fail "pipelines: init" "${work}/bp-init.out"
ssh-keygen -q -t ed25519 -N "" -C bp-claude -f "${work}/bp-claude"
ssh-keygen -q -t ed25519 -N "" -C bp-mail -f "${work}/bp-mail"
{
  echo "claude namespaces=\"tik-*\" $(cat "${work}/bp-claude.pub")"
  echo "bridge namespaces=\"tik-*\" $(cat "${work}/bridge_key.pub")"
  echo "mail namespaces=\"tik-*\" $(cat "${work}/bp-mail.pub")"
} > "${bp}/actors"
cp "${store}/roles.edn" "${bp}/roles.edn"
for p in ci-failure redmine-issue; do
  mkdir -p "${bp}/processes"
  cp "processes/${p}.edn" "${bp}/processes/"
done
cat > "${bp}/redmine-slow.edn" <<EOF
{:url "${fake}/slow-redmine" :actor "bridge" :key {:credential "redmine-key"}
 :query {:assigned_to_id "me"} :users {11 "smoke"}}
EOF
cat > "${work}/github-stuck.edn" <<EOF
{:repos ["acme/widgets"] :api "${fake}/hang-github" :actor "bridge"
 :token {:command "printf %s gh-smoke-token"} :actions {:lookback "P14D"}}
EOF
cat > "${bp}/processes/mailed-report.edn" <<EOF
{:process/id :mailed-report :process/version 1 :process/guard-vocab 2
 :process/purpose "a report mailed to the smoke desk"
 :process/facts {[:reporter :id] [:re "^hmac-sha256:[0-9a-f]{64}\$"]}
 :process/stages [{:stage/id :reported :guards [[:fact [:reporter :id]]]}]}
EOF
openssl rand -hex 32 > "${work}/reporter.key"
cat > "${bp}/imap.edn" <<EOF
{:dkim {:require true :authserv-id "mx.smoke.test"}
 :default-actor "mail"
 :reporter {:key {:file "${work}/reporter.key"} :table "${work}/reporters"}
 :block {:addresses ["pest@example.org"]}
 :mailboxes [{:id "support-smoke" :process :mailed-report
              :imap {:host "127.0.0.1" :port ${imap_port} :tls false :poll-ms 2000
                     :user "support@smoke.test" :password {:command ["printf" "%s" "support-smoke-pw"]}}}]}
EOF
cat > "${work}/bp-backend.edn" <<EOF
{:stores {:bp {:root "${bp}" :org "smoke" :actor "claude" :key "${work}/bp-claude"}}
 :on-failure {:run ["touch" "${work}/failed-{store}-{pipeline}"]}
 :pipelines [{:id :slow :store :bp :every "PT1S" :jitter "PT0S" :timeout "PT30S" :as "bridge"
              :key "${work}/bridge_key" :config "redmine-slow.edn" :run ["bridge" "redmine"]}
             {:id :stuck :store :bp :every "PT1H" :jitter "PT0S" :timeout "PT2S" :as "bridge"
              :key "${work}/bridge_key" :config "${work}/github-stuck.edn" :run ["bridge" "github"]}
             {:id :mail :store :bp :watch true :as "mail" :key "${work}/bp-mail"
              :run ["bridge" "imap" "--watch"]}]}
EOF
backend_env backend --config "${work}/bp-backend.edn" > "${work}/bp-backend.out" 2>&1 &
bp_pid=$!
pids="${pids} ${bp_pid}"
slow_runs() { grep -c '^\[bp/slow\] bridge redmine --actor bridge -> ok' "${work}/bp-backend.out"; }
if wait_on "${bp_pid}" 90 sh -c "[ \$(grep -c '^\[bp/slow\] bridge redmine --actor bridge -> ok' '${work}/bp-backend.out') -ge 2 ]" \
   && grep -q '^\[bp/mail\] support-smoke: uid 1: new ticket' "${work}/bp-backend.out"; then
  ok "pipelines: two bridge pipelines and an IMAP watch ran as children of one backend"
else
  fail "pipelines: the bridge pipelines or the mailbox did not run" "${work}/bp-backend.out"
fi
if [ "$(slow_runs)" -ge 2 ] \
   && grep -q '^\[bp/slow\] skipped: the previous run is still running' "${work}/bp-backend.out" \
   && grep -q '^slow-redmine inflight=1' "${work}/requests.log" \
   && ! grep -q '^slow-redmine inflight=[2-9]' "${work}/requests.log"; then
  ok "pipelines: a pipeline whose runs outlast its interval never overlaps itself"
else
  fail "pipelines: overlap" "${work}/bp-backend.out" "${work}/requests.log"
fi
if wait_on "${bp_pid}" 30 test -f "${work}/failed-bp-stuck" \
   && grep -q '^\[bp/stuck\] FAILED: timed out after 2 s and was killed' "${work}/bp-backend.out" \
   && [ ! -f "${work}/failed-bp-slow" ]; then
  ok "pipelines: a run past its timeout is killed and the failure report names it"
else
  fail "pipelines: the timeout or the failure report" "${work}/bp-backend.out"
fi
if wait_on "${bp_pid}" 30 grep -q '^\[bp/mail\] support-smoke: uid 4: new ticket' "${work}/bp-backend.out" \
   && grep -q '^\[bp/mail\] support-smoke: skip uid 2: refusing boss@example.org' "${work}/bp-backend.out" \
   && grep -q '^imap IDLE' "${work}/requests.log"; then
  ok "pipelines: the mailbox took pushed mail over IDLE and refused a verdict from another verifier"
else
  fail "pipelines: the mailbox watch" "${work}/bp-backend.out" "${work}/requests.log"
fi
if grep -q '^\[bp/mail\] support-smoke: uid 1: new ticket [0-9a-f]* from hmac-sha256:[0-9a-f]\{8\}' "${work}/bp-backend.out" \
   && grep -q '^\[bp/mail\] support-smoke: skip uid 3: refusing a blocked sender: address #1 of :block :addresses' "${work}/bp-backend.out" \
   && ! grep -q 'pest@example.org' "${work}/bp-backend.out" \
   && grep -rq 'Please send help' "${bp}" \
   && ! grep -rq 'alice@example.org' "${bp}" \
   && [ "$(stat -c %a "${work}/reporters")" = 700 ] \
   && (cd "${bp}" && env -u TIK_ROOT "${bin}" reporter stats --table "${work}/reporters") > "${work}/bp-reporters.out" 2>&1 \
   && grep -q '^ *1  hmac-sha256:[0-9a-f]\{64\}  alice@example.org$' "${work}/bp-reporters.out" \
   && grep -q '^ *1  hmac-sha256:[0-9a-f]\{64\}  carol@example.org$' "${work}/bp-reporters.out"; then
  ok "pipelines: the mailbox recorded senders as pseudonyms outside the store and refused a blocked one"
else
  fail "pipelines: the reporter pseudonym or the block list" "${work}/bp-backend.out" "${work}/bp-reporters.out"
fi
kill "${bp_pid}" 2>/dev/null || true

if [ "${failures}" -gt 0 ]; then
  echo "${failures} check(s) failed"
  exit 1
fi
echo "all checks passed"
