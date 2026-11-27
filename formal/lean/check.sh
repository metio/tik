#!/usr/bin/env bash
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Builds the Lean model, checks that its proofs rest on nothing but Lean's
# three standard axioms, re-checks them with leanchecker, and runs the
# differential oracle against the Clojure kernel.
#
#   bash formal/lean/check.sh [all|toolchain|grep|build|axioms|leanchecker|oracle|controls]
#
# all (the default) runs every step but controls; controls proves that each
# step can fail: a planted sorry must fail grep and axioms, and every mutant
# model must disagree with the kernel.
#
# TIK_LEAN_DIR names another copy of formal/lean to check (the controls use
# it); TIK_LEAN_GENERATED scales the generated oracle cases (default 3000).
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
dir=${TIK_LEAN_DIR:-"${repo}/formal/lean"}
generated=${TIK_LEAN_GENERATED:-3000}
mode=${1:-all}

say() { printf 'lean: %s\n' "$*"; }
fail() { printf 'lean: FAIL %s\n' "$*" >&2; exit 1; }

toolchain() {
  command -v lean > /dev/null || fail "lean is not on PATH (breto installs it)"
  local want have
  want=$(sed -n 's/^leanprover\/lean4:v//p' "${dir}/lean-toolchain")
  have=$(lean --version | sed -n 's/^Lean (version \([^,]*\),.*/\1/p')
  [ -n "${want}" ] || fail "lean-toolchain names no version"
  [ "${want}" = "${have}" ] || fail "lean ${have} on PATH, lean-toolchain pins ${want}"
  say "toolchain ${have}"
}

# Axioms.lean names every escape hatch in its documentation, so it is the one
# file allowed to spell them.
forbidden='\bsorry\b|\badmit\b|\baxiom\b|native_decide|implemented_by|@\[extern|\bunsafe\b|decide +:= +true|ofReduceBool'

grep_check() {
  local hits
  hits=$(cd "${dir}" && grep -rnE --include='*.lean' "${forbidden}" . \
    | grep -v '^\./\.lake/' | grep -v '^\./Axioms\.lean:' || true)
  [ -z "${hits}" ] || fail "forbidden in the proof sources:
${hits}"
  say "no sorry, admit, axiom, native_decide, implemented_by, extern or unsafe"
}

build() {
  (cd "${dir}" && lake build) || fail "lake build"
  say "built"
}

axioms() {
  local out want got bad
  out=$(cd "${dir}" && lake env lean Axioms.lean 2>&1) || fail "Axioms.lean: ${out}"
  want=$(grep -c '^#print axioms' "${dir}/Axioms.lean")
  got=$(printf '%s\n' "${out}" | grep -c 'depends on axioms' || true)
  [ "${want}" = "${got}" ] || fail "Axioms.lean printed ${got} of ${want} axiom lists:
${out}"
  bad=$(printf '%s\n' "${out}" \
    | grep -vE "depends on axioms: \[((propext|Classical\.choice|Quot\.sound)(, )?)*\]$" || true)
  [ -z "${bad}" ] || fail "a theorem rests on more than propext, Classical.choice and Quot.sound:
${bad}"
  say "${got} theorems rest only on propext, Classical.choice and Quot.sound"
}

# --fresh replays TikModel.Proofs and everything it imports, Lean's core
# library included, in leanchecker's own kernel; it takes about a minute and
# a half.
leanchecker_check() {
  (cd "${dir}" && lake env leanchecker --fresh TikModel.Proofs) || fail "leanchecker"
  say "leanchecker replayed TikModel.Proofs from scratch"
}

oracle() {
  local out
  out=$(mktemp -d)
  trap 'rm -rf "${out}"' RETURN
  (cd "${repo}" && clojure -M:dev -m tik.lean-oracle --lean "${dir}/.lake/build/bin/tik-lean" \
    --generated "${generated}" --echo --out "${out}") || fail "the interchange round trip"
  (cd "${repo}" && clojure -M:dev -m tik.lean-oracle --lean "${dir}/.lake/build/bin/tik-lean" \
    --generated "${generated}" --out "${out}") || fail "the model and the kernel disagree"
}

controls() {
  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "${tmp}"' RETURN
  (cd "${dir}" && tar --exclude=./.lake -cf - .) | (cd "${tmp}" && tar -xf -)
  # a theorem proved by sorry, which the build accepts with a warning
  printf '\nnamespace Tik.Counter\ntheorem controlPlanted : False := by sorry\nend Tik.Counter\n' \
    >> "${tmp}/TikModel/Counter.lean"
  printf '#print axioms Tik.Counter.controlPlanted\n' >> "${tmp}/Axioms.lean"
  if TIK_LEAN_DIR="${tmp}" bash "$0" grep > /dev/null 2>&1; then
    fail "control: grep passed a planted sorry"
  fi
  say "control: grep refuses a planted sorry"
  TIK_LEAN_DIR="${tmp}" bash "$0" build > /dev/null 2>&1 || fail "control: the planted copy does not build"
  if TIK_LEAN_DIR="${tmp}" bash "$0" axioms > /dev/null 2>&1; then
    fail "control: axioms passed a theorem proved by sorry"
  fi
  say "control: axioms refuses a theorem proved by sorry"
  local out m
  out=$(mktemp -d)
  for m in chaotic seed-carry no-after keep-retracted; do
    (cd "${repo}" && clojure -M:dev -m tik.lean-oracle --lean "${dir}/.lake/build/bin/tik-lean" \
      --generated "${generated}" --mutant "${m}" --expect-disagreement --out "${out}") \
      || fail "control: the mutant ${m} agreed with the kernel on every case"
    say "control: the mutant ${m} disagrees with the kernel"
  done
  rm -rf "${out}"
}

case "${mode}" in
  toolchain) toolchain ;;
  grep) grep_check ;;
  build) build ;;
  axioms) axioms ;;
  leanchecker) leanchecker_check ;;
  oracle) oracle ;;
  controls) controls ;;
  all) toolchain; grep_check; build; axioms; leanchecker_check; oracle; say "PASS" ;;
  *) fail "unknown mode ${mode}" ;;
esac
