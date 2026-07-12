#!/usr/bin/env bash
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Check the machine-checked Lean proofs. Kept OUT of the default gate:
# the Lean toolchain is ~2.8GB, too heavy to force on every contributor
# and CI run. Run it deliberately when touching the proofs.
#
#   proofs/check.sh                 # uses `lean` on PATH, else `nix run`
#   LEAN=/path/to/lean proofs/check.sh
set -eu
cd "$(dirname "$0")"

if [ -n "${LEAN:-}" ]; then
  runner=("$LEAN")
elif command -v lean >/dev/null 2>&1; then
  runner=(lean)
elif command -v nix >/dev/null 2>&1; then
  # unpinned but reproducible enough for an optional aux check
  runner=(nix run nixpkgs#lean4 --)
else
  echo "lean not found — install it or: nix run nixpkgs#lean4"
  echo "(skipping: the Lean toolchain is optional and not part of the gate)"
  exit 0
fi

fail=0
for f in *.lean; do
  echo "== $f"
  if "${runner[@]}" "$f"; then
    echo "   ok (checked)"
  else
    echo "   FAIL — Lean rejected the proof"
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then echo "proofs: PASS"; else echo "proofs: FAIL"; fi
exit "$fail"
