#!/usr/bin/env bash
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Controls for spec/check.sh: every model is covered, and the script exits
# non-zero when a model is broken. Each mutation runs check.sh on a copy of
# spec/ with one deliberate defect, so a check.sh that passed regardless
# would fail here.
set -u
cd "$(dirname "$0")" || exit 2
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail=0

ok() { echo "   ok   $1"; }
bad() {
  echo "   FAIL $1"
  fail=1
}

echo "== coverage"
for tla in *.tla; do
  m="${tla%.tla}"
  if grep -Eq "^[[:space:]]*(expect_pass|expect_violation|prove) $m( |$)" check.sh ||
    grep -Eq "^[[:space:]]*refuted \"[^\"]*\" $m( |$)" check.sh; then
    ok "$m is checked"
  else
    bad "$m is not checked by check.sh"
  fi
  case "$m" in
    *Inductive | ChaoticFixpoint) ;;
    *)
      if grep -Eq "^[[:space:]]*prove ${m}Inductive CInit[0-9]+ " check.sh; then
        ok "$m has an inductive proof over symbolic constants"
      else
        bad "$m has no ${m}Inductive proved over a symbolic CInit"
      fi
      ;;
  esac
done

# mutant NAME FILE FROM TO MODE MODULE EXPECT...: check.sh on a copy of
# spec/ whose FILE has FROM replaced by TO must exit non-zero and print
# every EXPECT, which names the failure the defect has to cause, so a
# mutant that merely breaks parsing does not count.
mutant() {
  local name="$1" file="$2" from="$3" to="$4" mode="$5" module="$6"
  shift 6
  local dir="$work/$name"
  mkdir -p "$dir"
  cp ./*.tla ./*.cfg check.sh "$dir/"
  if ! grep -qF "$from" "$dir/$file"; then
    bad "$name: the text to mutate is missing from $file"
    return
  fi
  FROM="$from" TO="$to" perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/g' "$dir/$file"
  local code=0
  bash "$dir/check.sh" "$mode" "$module" >"$dir/out.log" 2>&1 || code=$?
  local missing=""
  for expect in "$@"; do
    grep -qF -- "$expect" "$dir/out.log" || missing="$expect"
  done
  if [ "$code" -ne 0 ] && [ -z "$missing" ]; then
    ok "$name: check.sh exits $code"
  else
    [ -z "$missing" ] || echo "   missing from the output: $missing"
    bad "$name: check.sh exited $code"
    tail -30 "$dir/out.log"
  fi
}

echo "== mutants (expect: check.sh fails)"
mutant "TLC finds a sync that drops events" Merge.tla \
  "SyncA == a' = a \cup b" "SyncA == a' = b" tlc Merge \
  "FAIL — TLC found a violation" "GrowOnly is violated"
mutant "Apalache rejects an invariant too weak to imply the property" PlanReadinessInductive.tla \
  "IndInv == TypeOK /\ settled \cap Trap = {}" "IndInv == TypeOK" apalache PlanReadinessInductive \
  "FAIL IndInv /\ Next => IndInv'" "invariant 1 violated"
mutant "Apalache rejects a non-inductive invariant" SweepFixpointInductive.tla \
  "reached = {s \in Model : Depth[s] < sweeps}" "reached \subseteq Model" apalache SweepFixpointInductive \
  "FAIL IndInv /\ Next => IndInv'" "violated"
mutant "a lost counterexample fails the chaotic refutation" ChaoticFixpoint.tla \
  "Fire == \E s \in Stages : Enabled(s) /\ reached' = reached \cup {s}" \
  "Fire == reached' = reached \cup {s \in Stages : Enabled(s)} /\ reached' /= reached" apalache ChaoticFixpoint \
  "FAIL UniqueFixpoint fails under Apalache too — expected Apalache to find a violation"
mutant "an unknown module runs no check" Merge.tla "x" "x" all NoSuchModule \
  "no check ran for module NoSuchModule"

if [ "$fail" -eq 0 ]; then echo "tla controls: PASS"; else echo "tla controls: FAIL"; fi
exit "$fail"
