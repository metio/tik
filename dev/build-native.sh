#!/bin/sh
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Build the single-binary tik: uberjar, then GraalVM native-image.
# Run with the toolchain .breto.edn names: breto run sh dev/build-native.sh
#
# The result links only the libc family (libc/libdl/libpthread/librt),
# so it runs on any glibc distro with no JVM and no babashka. Alpine
# users run the uberjar with babashka instead.
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

echo "==> built:"
ls -lh target/tik
