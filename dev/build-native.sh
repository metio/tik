#!/bin/sh
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Build the single-binary tik: uberjar, then GraalVM native-image.
# Run inside the dev shell: nix develop --command sh dev/build-native.sh
#
# The result links only the libc family (libc/libdl/libpthread/librt),
# so after the interpreter patch it runs on any glibc distro with no
# JVM, no babashka, no nix. musl fully-static is blocked by nixpkgs'
# graalvm (stripped CAP cache + a musl-gcc that leaks glibc objects);
# Alpine users run the uberjar with babashka instead.
set -eu
cd "$(dirname "$0")/.."

echo "==> uberjar (tools.build)"
clojure -T:build uber

echo "==> native-image"
native-image \
  -jar target/tik.jar \
  -o target/tik \
  --no-fallback \
  --install-exit-handlers \
  --enable-http --enable-https \
  --enable-native-access=ALL-UNNAMED \
  -march=compatibility \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --initialize-at-build-time=com.fasterxml.jackson \
  -J-Xmx6g

# nix's toolchain stamps a /nix/store interpreter; point it at the
# standard one so the binary runs outside any nix environment
echo "==> interpreter patch"
nix shell nixpkgs#patchelf --command \
  patchelf --set-interpreter /lib64/ld-linux-x86-64.so.2 \
           --remove-rpath target/tik

echo "==> built:"
ls -lh target/tik
