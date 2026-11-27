#!/bin/sh
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Drive a built binary through everything a long-running local install
# uses: `serve` answering the board and MCP in both protocol eras, one
# sweep of each sync bridge (github, gitlab, redmine, odoo) and of
# `bridge imap`, `backend` firing a scheduled bridge sweep, and `notify`
# delivering one hint to `notify watch`. The remote systems are the
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

if [ "${failures}" -gt 0 ]; then
  echo "${failures} check(s) failed"
  exit 1
fi
echo "all checks passed"
