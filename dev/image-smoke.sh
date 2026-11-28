#!/bin/sh
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Check the container image beyond tik itself: mkfifo sits on the PATH,
# owned by root and mode 0755, creates an owner-only named pipe the way
# briko.process asks for one (`mkfifo -m 600 PATH`), refuses a bad mode
# without creating anything, and the image still has no shell. The pipes
# are made in a host directory mounted into the container, so their type
# and mode are read on the host; the image has nothing to read them with.
#
#   sh dev/image-smoke.sh IMAGE [--with-tik]
#
# --with-tik also runs `tik --help`, for an image built from a real
# target/tik. CONTAINER names the runtime (default docker; podman works).
set -eu
image=${1:?usage: dev/image-smoke.sh IMAGE [--with-tik]}
with_tik=${2:-}
engine=${CONTAINER:-docker}
work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT
# the image's user is not the caller's, and must be able to create entries
chmod 0777 "${work}"

failures=0
check() {
  name=$1
  shift
  if "$@"; then
    echo "ok: ${name}"
  else
    echo "FAIL: ${name}"
    failures=$((failures + 1))
  fi
}

run() {
  "${engine}" run --rm --network none -v "${work}:/w:z" "$@"
}

mkfifo_in_image() {
  run --entrypoint /usr/local/bin/mkfifo "${image}" "$@"
}

which_mkfifo() {
  # resolved through the image's PATH, as briko's ProcessBuilder does
  run --entrypoint mkfifo "${image}" -m 600 /w/via-path >/dev/null 2>&1 \
    && [ -p "${work}/via-path" ]
}

binary_root_0755() {
  # Read from the image's filesystem export, without running anything in it.
  # Rootless podman's export maps root to another id, so the owner is
  # compared with /etc/passwd's, which distroless ships owned by root.
  cid=$("${engine}" create "${image}")
  listing=$("${engine}" export "${cid}" \
    | tar --numeric-owner -tvf - etc/passwd usr/local/bin/mkfifo) || listing=
  "${engine}" rm "${cid}" >/dev/null
  echo "${listing}" | awk -v out="${work}/owners" '
    $NF == "etc/passwd" { root = $2 }
    $NF == "usr/local/bin/mkfifo" { mode = $1; owner = $2 }
    END { ok = (root != "" && owner == root && owner != "65532/65532" && mode == "-rwxr-xr-x")
          if (!ok) print "mkfifo " mode " " owner ", /etc/passwd " root > "/dev/stderr"
          exit !ok }'
}

owner_only_fifo() {
  mkfifo_in_image -m 600 /w/a /w/b \
    && [ -p "${work}/a" ] && [ "$(stat -c '%a' "${work}/a")" = 600 ] \
    && [ -p "${work}/b" ] && [ "$(stat -c '%a' "${work}/b")" = 600 ]
}

# mkfifo's own refusal exits 1; a runtime that cannot start the binary
# answers 125 to 127, which must not pass for a refusal.
exits_one() {
  status=0
  mkfifo_in_image "$@" 2>/dev/null || status=$?
  [ "${status}" -eq 1 ] || { echo "mkfifo $* exited ${status}"; return 1; }
}

refuses_bad_mode() {
  for mode in 999 4755 u=rw; do
    exits_one -m "${mode}" "/w/bad-${mode}" || return 1
    if [ -e "${work}/bad-${mode}" ]; then
      echo "mode ${mode} left a file behind"
      return 1
    fi
  done
}

refuses_existing_path() {
  : > "${work}/taken"
  exits_one -m 600 /w/taken && [ -f "${work}/taken" ]
}

no_shell() {
  for sh in /bin/sh /bin/bash /bin/busybox; do
    if run --entrypoint "${sh}" "${image}" -c true >/dev/null 2>&1; then
      echo "${sh} runs"
      return 1
    fi
  done
}

tik_help() {
  run "${image}" --help >/dev/null
}

check "mkfifo on the PATH" which_mkfifo
check "mkfifo owned by root, mode 0755" binary_root_0755
check "mkfifo -m 600 makes owner-only named pipes" owner_only_fifo
check "mkfifo refuses a bad mode and creates nothing" refuses_bad_mode
check "mkfifo refuses an existing path" refuses_existing_path
check "the image has no shell" no_shell
if [ "${with_tik}" = --with-tik ]; then
  check "tik --help" tik_help
fi

if [ "${failures}" -gt 0 ]; then
  echo "${failures} check(s) failed"
  exit 1
fi
echo "all image checks passed"
