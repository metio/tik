#!/usr/bin/env bash
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# H2 across two environments connected only by the git protocol over TCP.
#
#   ./dev/h2-two-machines.sh            # machine B = podman container
#   MODE=remote REMOTE=user@host ./dev/h2-two-machines.sh   # B = real box
#
# Flow (mirrors test/tik/sync_test.clj, but across environments):
#   A creates a ticket and publishes; B clones over git://, categorizes;
#   A categorizes CONCURRENTLY (without pulling); both merge — union, no
#   file conflicts; both derive :conflicted with both claimants; A
#   resolves observing both heads; B pulls and agrees.
set -euo pipefail
cd "$(dirname "$0")/.."
# tik + git-daemon run through the flake devshell; podman must run on the
# host (it cannot start inside the nix-portable sandbox on this machine)
RUN="${RUN:-nix develop --command}"
TIK_SRC="$PWD"
H2="$(mktemp -d)"
PORT="${PORT:-9418}"
MODE="${MODE:-podman}"

say() { printf '\n== %s\n' "$*"; }

git -C "$H2" init -q --bare origin
git clone -q "$H2/origin" "$H2/a" 2>/dev/null || true
git -C "$H2/a" switch -qc main
cp -r processes "$H2/a/"

say "machine A: create + publish"
TIK_ROOT="$H2/a" TIK_ACTOR=seb $RUN bb tik new support-request --title 'two-machine H2' > "$H2/ticket-id"
TICKET="$(cat "$H2/ticket-id")"
git -C "$H2/a" add -A
git -C "$H2/a" -c user.email=a@h2 -c user.name=machine-a commit -qm 'A: create'
git -C "$H2/a" push -q origin main

say "serving origin over git:// on 127.0.0.1:$PORT"
$RUN git daemon --base-path="$H2" --export-all --reuseaddr \
  --listen=127.0.0.1 --port="$PORT" --enable=receive-pack \
  --detach --pid-file="$H2/daemon.pid"
trap 'kill "$(cat "$H2/daemon.pid")" 2>/dev/null || true' EXIT
sleep 1

write_b_script() { # $1 = host reachable from machine B
  cat > "$H2/bscript.sh" <<EOS
set -euo pipefail
git config --global user.email b@h2
git config --global user.name machine-b
git clone -q "git://$1:$PORT/origin" /work/b
export TIK_ROOT=/work/b TIK_ACTOR=billing
cd /tiksrc
bb tik set "$TICKET" category=:billing
cd /work/b
git add -A
git commit -qm "B: categorize (concurrent)"
git push -q origin main
EOS
}

case "$MODE" in
  podman)
    say "machine B: podman container (own rootfs/clock/user, TCP only)"
    write_b_script 127.0.0.1
    mkdir -p "$H2/bsrc" && cp -r src cli bb.edn deps.edn processes "$H2/bsrc/"
    cp "$H2/bscript.sh" "$H2/bsrc/"
    podman run --rm --network=host \
      -v "$H2/bsrc:/tiksrc:z" -v metio-nix:/nix \
      -e NIX_CONFIG='experimental-features = nix-command flakes' \
      docker.io/nixos/nix:latest \
      sh -c "mkdir -p /work && nix shell nixpkgs#babashka nixpkgs#jdk21_headless nixpkgs#git -c bash /tiksrc/bscript.sh"
    ;;
  remote)
    say "machine B: $REMOTE over ssh (expects git+bb+jdk and the tik sources at ~/tiksrc)"
    write_b_script "$(hostname)"
    ssh "$REMOTE" 'bash -s' < "$H2/bscript.sh"
    ;;
esac

say "machine A: concurrent categorize (has NOT pulled B's claim)"
TIK_ROOT="$H2/a" TIK_ACTOR=seb $RUN bb tik set "$TICKET" category=:technical
git -C "$H2/a" add -A
git -C "$H2/a" -c user.email=a@h2 -c user.name=machine-a commit -qm 'A: categorize (concurrent)'

say "merge on A: union, no file conflicts"
git -C "$H2/a" -c user.email=a@h2 -c user.name=machine-a pull -q --no-rebase --no-edit
git -C "$H2/a" push -q origin main

say "A derives the disagreement"
TIK_ROOT="$H2/a" $RUN bb tik status "$TICKET" | grep -i conflict
TIK_ROOT="$H2/a" $RUN bb tik verify "$TICKET" | tail -1

say "A resolves observing both heads; the log carries the judgment"
TIK_ROOT="$H2/a" TIK_ACTOR=seb $RUN bb tik set "$TICKET" category=:technical
git -C "$H2/a" add -A
git -C "$H2/a" -c user.email=a@h2 -c user.name=machine-a commit -qm 'A: resolve'
git -C "$H2/a" push -q origin main
TIK_ROOT="$H2/a" $RUN bb tik status "$TICKET" | head -5

say "H2 two-environment flow: PASS (store at $H2)"
