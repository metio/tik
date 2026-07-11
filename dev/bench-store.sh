#!/bin/sh
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Macro benchmark: an N-ticket store, timed cold (cache build) and
# warm through the native binary. bb bench-store [N]
set -eu
cd "$(dirname "$0")/.."
N="${1:-2000}"
S="$(mktemp -d /tmp/tik-bench.XXXXXX)"
trap 'rm -rf "$S"' EXIT
mkdir -p "$S/processes"
cp processes/track.edn "$S/processes/"
echo "==> synthesizing $N tickets"
bb --config bb.edn -f dev/synth_store.clj "$N" "$S"
BIN="${TIK_BIN:-target/tik}"
[ -x "$BIN" ] || { echo "no $BIN — bb build-native first (or TIK_BIN=...)"; exit 1; }
cd "$S"
for run in "ls COLD" "ls WARM" "ls-long WARM" "next WARM"; do
  cmd=$(echo "$run" | cut -d' ' -f1 | tr '-' ' --')
  /usr/bin/time -f "%es  tik $run" "$OLDPWD/$BIN" $cmd >/dev/null || true
done
