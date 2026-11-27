#!/usr/bin/env bash
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Model-check the TLA+ specs with TLC and prove their inductive invariants
# with Apalache.
#
# TLC explores the concrete models: Merge, SweepFixpoint, MonotonePositive
# and PlanReadiness must PASS; ChaoticFixpoint must FAIL (TLC exhibits the
# order-dependence that synchronous-sweep evaluation exists to rule out) —
# a passing chaotic model would mean the counterexample this spec documents
# has been lost.
#
# Apalache proves, for each *Inductive module and each constant
# initialisation named below, that Init implies IndInv, that every step
# from a state satisfying IndInv keeps it, and that the listed properties
# hold in every such state or step. A CInit over symbolic constants covers
# every process or graph of that size at once; CInitTLC pins the constants
# of the TLC model. Each proof is accompanied by vacuity controls (some
# state satisfies IndInv, some step leaves one), and the refutations below
# must each find a violation: invariants that are true but not inductive,
# the chaotic evaluator, and ChaoticFixpoint itself.
#
# Usage: spec/check.sh [all|tlc|apalache] [MODULE]
#
# The mode defaults to all. MODULE restricts the run to the checks of one
# module; a MODULE no check names fails rather than passing empty.
set -u
cd "$(dirname "$0")" || exit 2
mode="${1:-all}"
only="${2:-}"
case "$mode" in
  all | tlc | apalache) ;;
  *)
    echo "usage: $0 [all|tlc|apalache] [MODULE]" >&2
    exit 2
    ;;
esac
meta="$(mktemp -d)"
trap 'rm -rf "$meta"' EXIT
# The apalache-mc launcher creates ./tmp when TMPDIR is unset.
mkdir -p "$meta/tmp"
export TMPDIR="$meta/tmp"
fail=0
checks=0

# selected MODULE: whether MODULE's checks run, counting the ones that do.
selected() {
  if [ -n "$only" ] && [ "$1" != "$only" ]; then return 1; fi
  checks=$((checks + 1))
}

expect_pass() {
  selected "$1" || return 0
  echo "== $1 (expect: pass)"
  if tlc -metadir "$meta/$1" -workers auto -deadlock -config "$PWD/$1.cfg" "$PWD/$1.tla" >"$meta/$1.log" 2>&1; then
    echo "   ok"
  else
    echo "   FAIL — TLC found a violation:"
    tail -30 "$meta/$1.log"
    fail=1
  fi
}

expect_violation() {
  selected "$1" || return 0
  echo "== $1 (expect: violation)"
  if tlc -metadir "$meta/$1" -workers auto -deadlock -config "$PWD/$1.cfg" "$PWD/$1.tla" >"$meta/$1.log" 2>&1; then
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

# apalache MODULE LOG ARGS... runs one check and returns apalache-mc's exit
# code: 0 when no violation exists, 12 when one was found, anything else
# when the check could not run.
run=0
apalache() {
  local module="$1" log="$2"
  shift 2
  run=$((run + 1))
  apalache-mc check --out-dir="$meta/apalache-out" --no-deadlock "$@" \
    "$PWD/$module.tla" >"$log" 2>&1
}

holds() {
  local label="$1" module="$2"
  shift 2
  selected "$module" || return 0
  local log="$meta/apalache-$run.log"
  if apalache "$module" "$log" "$@"; then
    echo "   ok   $label"
  else
    echo "   FAIL $label — Apalache found a violation or could not run:"
    tail -30 "$log"
    fail=1
  fi
}

refuted() {
  local label="$1" module="$2"
  shift 2
  selected "$module" || return 0
  local log="$meta/apalache-$run.log" code=0
  apalache "$module" "$log" "$@" || code=$?
  if [ "$code" -eq 12 ] && grep -q "invariant .* violated" "$log"; then
    echo "   ok   $label (violation found, as expected)"
  elif [ "$code" -eq 0 ]; then
    echo "   FAIL $label — expected Apalache to find a violation"
    fail=1
  else
    echo "   FAIL $label — Apalache failed for an unexpected reason:"
    tail -30 "$log"
    fail=1
  fi
}

# section MODULE TEXT prints a heading for MODULE's checks that follow.
section() {
  if [ -z "$only" ] || [ "$1" = "$only" ]; then echo "== $1 ($2)"; fi
}

# prove MODULE CINIT PROPERTIES: PROPERTIES is a comma-separated list of
# state and step properties, checked from every state satisfying IndInv.
prove() {
  local module="$1" cinit="$2" props="$3"
  section "$module" "$cinit, expect: inductive"
  holds "Init => IndInv" "$module" --cinit="$cinit" --init=Init --inv=IndInv --length=0
  holds "IndInv /\\ Next => IndInv', $props" "$module" \
    --cinit="$cinit" --init=IndInv --inv="IndInv,$props" --length=1
  refuted "IndInv is satisfiable" "$module" --cinit="$cinit" --init=IndInv --inv=NoState --length=0
  refuted "a step leaves IndInv states" "$module" --cinit="$cinit" --init=IndInv --inv=NoStep --length=1
}

if [ "$mode" != apalache ]; then
  expect_pass Merge
  expect_pass SweepFixpoint
  expect_pass MonotonePositive
  expect_pass PlanReadiness
  expect_violation ChaoticFixpoint
fi

if [ "$mode" != tlc ]; then
  if ! command -v apalache-mc >/dev/null 2>&1; then
    echo "== apalache: FAIL — apalache-mc is not on PATH (it comes from .breto.edn)"
    fail=1
  else
    prove MergeInductive CInitTLC "Settled,GrowOnly,Approaches,StaysConverged"
    prove MergeInductive CInit50 "Settled,GrowOnly,Approaches,StaysConverged"

    prove SweepFixpointInductive CInitTLC "UniqueFixpoint,Bounded,Monotone"
    prove SweepFixpointInductive CInit6 "UniqueFixpoint,Bounded,Monotone"
    section SweepFixpointInductive "expect: violations"
    refuted "WeakInv is not inductive" SweepFixpointInductive \
      --cinit=CInit4 --init=WeakInv --inv=WeakInv --length=1
    refuted "UniqueFixpoint as written is not inductive" SweepFixpointInductive \
      --cinit=CInit4 --init=WrittenInv --inv=WrittenInv --length=1
    refuted "chaotic iteration diverges on some stratified process" SweepFixpointInductive \
      --cinit=CInit4 --init=Init --next=ChaoticNext --inv=UniqueFixpoint --length=5

    prove MonotonePositiveInductive CInitTLC "UniqueFixpoint,ModelIsFull,Monotone"
    prove MonotonePositiveInductive CInit6 "UniqueFixpoint,Monotone"
    section MonotonePositiveInductive "expect: violation"
    refuted "UniqueFixpoint as written is not inductive" MonotonePositiveInductive \
      --cinit=CInit6 --init=WrittenInv --inv=WrittenInv --length=1

    prove PlanReadinessInductive CInitTLC "NoTrapSettles,DeadlockIsExact,Monotone"
    prove PlanReadinessInductive CInit10 "NoTrapSettles,DeadlockIsExact,Monotone"

    section ChaoticFixpoint "expect: violation"
    refuted "UniqueFixpoint fails under Apalache too" ChaoticFixpoint \
      --inv=UniqueFixpoint --length=5
  fi
fi

if [ "$checks" -eq 0 ]; then
  echo "== no check ran${only:+ for module $only}"
  fail=1
fi

if [ "$fail" -eq 0 ]; then echo "tla: PASS"; else echo "tla: FAIL"; fi
exit "$fail"
