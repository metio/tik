# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Runtime image for tik. tik itself is built OUTSIDE this file — the release
# pipeline compiles the single GraalVM native binary with the tools .breto.edn
# names (`sh dev/build-native.sh`, the same toolchain used locally) and this
# image only packages the result. Keeping the heavy GraalVM build in the
# pipeline (not a builder stage here) means the image
# has no build tooling and the binary is byte-identical to the one developers
# test.
#
# The binary links glibc dynamically and everything else statically
# (`--static-nolibc`), so the smallest correct base is distroless/base — glibc,
# no shell, nonroot. distroless/static is for
# static/musl binaries and would lack the loader this binary needs; a
# glibc-static build is rejected because statically-linked glibc breaks NSS/DNS
# resolution, and tik dials named hosts (IMAP/POP3, OIDC, webhooks).
# mkfifo is the one tool the image carries besides tik: briko.process creates
# its owner-only output pipes with `mkfifo -m 600`, and distroless ships no
# coreutils. It is a single-purpose program of our own (container/mkfifo),
# statically linked with CGO disabled, so it adds no shell, no multi-call
# toolbox and no library to the image. The builder is pinned by digest, runs
# on the build platform and cross-compiles for the target; its tests and vet
# run before the build, so a broken mkfifo fails the image build. They run on
# a tmpfs because they read the modes of the pipes they make, and the overlay
# of a rootless podman build ignores the umask for mknod.
FROM --platform=$BUILDPLATFORM docker.io/library/golang:1.27.4@sha256:5f1b98fa02fe396ebce2a0227a0e08309e11792915b9dd2e1f71053d7e7e6666 AS mkfifo
ENV GOTOOLCHAIN=local CGO_ENABLED=0 GOFLAGS=-trimpath
WORKDIR /src
COPY container/mkfifo/ ./
ARG TARGETOS
ARG TARGETARCH
RUN --mount=type=tmpfs,target=/tmp test -z "$(gofmt -l .)" && go vet ./... && go test ./... \
 && GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -ldflags="-s -w -buildid=" -o /out/mkfifo .

FROM gcr.io/distroless/base-debian12:nonroot@sha256:7f0c72cd138b442ae0deeb69c08b1acf5525439ba251a49ad93c320a061567e5

# The store (tickets/, actors, processes/) lives on a mounted volume; default
# TIK_ROOT so the bare image is usable and the Helm chart's mount lines up.
ENV TIK_ROOT=/var/lib/tik

COPY --chown=nonroot:nonroot target/tik /usr/local/bin/tik
# Owned by root and not writable by the uid tik runs as, so tik cannot
# replace the program it runs to create its pipes.
COPY --from=mkfifo --chown=0:0 --chmod=0755 /out/mkfifo /usr/local/bin/mkfifo

# The uid behind distroless's `nonroot`, stated rather than inherited from the
# base tag: choosing a base is a live decision here (see the glibc reasoning
# above), and a tag without `:nonroot` would otherwise hand tik root without a
# line of this file changing. It is the uid the binary above is chowned to. A
# pod securityContext still wins where one is set — the Helm chart runs tik
# under its own uid — so this is what the bare image runs as.
USER 65532:65532

# `tik` with no arguments prints usage; the Helm chart supplies `serve …` or
# `bridge imap --watch …` as args.
ENTRYPOINT ["/usr/local/bin/tik"]
