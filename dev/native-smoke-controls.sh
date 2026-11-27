#!/bin/sh
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Positive controls for dev/native-smoke.sh: it must pass a working tik,
# fail a binary that does nothing, and pin a failure on the one verb that
# is broken. Without these, a smoke script whose checks could never fail
# would read as a healthy binary.
#
#   sh dev/native-smoke-controls.sh [binary]
#
# Without a binary the working tik is this checkout run through babashka,
# so the controls need no native build.
set -eu
cd "$(dirname "$0")/.."
repo=$(pwd)
work=$(mktemp -d /tmp/tik-controls.XXXXXX)
trap 'rm -rf "${work}"' EXIT

if [ -n "${1:-}" ]; then
  case "$1" in /*) good=$1 ;; *) good="${repo}/$1" ;; esac
else
  # babashka takes -D system properties before the task, as the binary does
  # before its verb.
  good="${work}/tik-bb"
  cat > "${good}" <<WRAP
#!/bin/sh
props=""
while [ \$# -gt 0 ]; do
  case "\$1" in -D*) props="\${props} \$1"; shift ;; *) break ;; esac
done
# shellcheck disable=SC2086
exec bb \${props} --config "${repo}/bb.edn" tik "\$@"
WRAP
  chmod +x "${good}"
fi

printf '#!/bin/sh\nexit 1\n' > "${work}/tik-exit-1"
chmod +x "${work}/tik-exit-1"

cat > "${work}/tik-no-notify" <<WRAP
#!/bin/sh
[ "\$1" = notify ] && { echo "notify is broken in this control" >&2; exit 1; }
exec "${good}" "\$@"
WRAP
chmod +x "${work}/tik-no-notify"

status=0

if sh dev/native-smoke.sh "${good}" > "${work}/good.out" 2>&1; then
  echo "ok   a working tik passes every check"
else
  echo "FAIL a working tik does not pass"
  sed 's/^/     | /' "${work}/good.out"
  status=1
fi

if sh dev/native-smoke.sh "${work}/tik-exit-1" > "${work}/exit-1.out" 2>&1; then
  echo "FAIL a binary that exits 1 passed"
  status=1
elif grep -q '^ok ' "${work}/exit-1.out"; then
  echo "FAIL a binary that exits 1 passed a check:"
  grep '^ok ' "${work}/exit-1.out" | sed 's/^/     | /'
  status=1
else
  echo "ok   a binary that exits 1 fails, and passes no check"
fi

if sh dev/native-smoke.sh "${work}/tik-no-notify" > "${work}/no-notify.out" 2>&1; then
  echo "FAIL a tik without notify passed"
  status=1
else
  failed=$(grep '^FAIL ' "${work}/no-notify.out" | grep -v 'check(s) failed' || true)
  case "${failed}" in
    "FAIL notify:"*)
      if ! printf '%s\n' "${failed}" | grep -qv '^FAIL notify:'; then
        echo "ok   a tik without notify fails the notify checks and no other"
      else
        echo "FAIL a tik without notify failed more than the notify checks:"
        printf '%s\n' "${failed}" | sed 's/^/     | /'
        status=1
      fi
      ;;
    *)
      echo "FAIL a tik without notify did not fail the notify check:"
      sed 's/^/     | /' "${work}/no-notify.out"
      status=1
      ;;
  esac
fi
exit "${status}"
