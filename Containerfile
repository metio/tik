# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Runtime image for tik. The build is done OUTSIDE this file — the release
# pipeline compiles the single GraalVM native binary through the nix devShell
# (`nix develop --command sh dev/build-native.sh`, the same toolchain used
# locally) and this image only packages the result. Keeping the heavy
# GraalVM/nix build in the pipeline (not a builder stage here) means the image
# has no build tooling and the binary is byte-identical to the one developers
# test.
#
# The binary is glibc-DYNAMIC (it links only the libc family: libc, libdl,
# libpthread, librt, and the /lib64 loader), so the smallest correct base is
# distroless/base — glibc, no shell, nonroot. distroless/static is for
# static/musl binaries and would lack the loader this binary needs; a
# glibc-static build is rejected because statically-linked glibc breaks NSS/DNS
# resolution, and tik dials named hosts (IMAP/POP3, OIDC, webhooks).
FROM gcr.io/distroless/base-debian12:nonroot

# The store (tickets/, actors, processes/) lives on a mounted volume; default
# TIK_ROOT so the bare image is usable and the Helm chart's mount lines up.
ENV TIK_ROOT=/var/lib/tik

COPY --chown=nonroot:nonroot target/tik /usr/local/bin/tik

# `tik` with no arguments prints usage; the Helm chart supplies `serve …` or
# `bridge imap --watch …` as args.
ENTRYPOINT ["/usr/local/bin/tik"]
