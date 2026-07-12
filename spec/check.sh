#!/usr/bin/env bash
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Model-check the TLA+ specs. Merge and SweepFixpoint must PASS;
# ChaoticFixpoint must FAIL (TLC exhibits the order-dependence that
# synchronous-sweep evaluation exists to rule out) — a passing chaotic
# model would mean the counterexample this spec documents has been lost.
set -u
cd "$(dirname "$0")"
meta="$(mktemp -d)"
trap 'rm -rf "$meta"' EXIT
fail=0

expect_pass() {
  echo "== $1 (expect: pass)"
  if tlc -metadir "$meta/$1" -workers auto -deadlock -config "$1.cfg" "$1.tla" >"$meta/$1.log" 2>&1; then
    echo "   ok"
  else
    echo "   FAIL — TLC found a violation:"
    tail -30 "$meta/$1.log"
    fail=1
  fi
}

expect_violation() {
  echo "== $1 (expect: violation)"
  if tlc -metadir "$meta/$1" -workers auto -deadlock -config "$1.cfg" "$1.tla" >"$meta/$1.log" 2>&1; then
    echo "   FAIL — expected TLC to find the order-dependence counterexample"
    fail=1
  elif grep -q "Invariant UniqueFixpoint is violated" "$meta/$1.log"; then
    echo "   ok (counterexample found, as documented)"
  else
    echo "   FAIL — TLC failed for an unexpected reason:"
    tail -30 "$meta/$1.log"
    fail=1
  fi
}

expect_pass Merge
expect_pass SweepFixpoint
expect_pass MonotonePositive
expect_pass PlanReadiness
expect_violation ChaoticFixpoint

if [ "$fail" -eq 0 ]; then echo "tla: PASS"; else echo "tla: FAIL"; fi
exit "$fail"
