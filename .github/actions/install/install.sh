#!/usr/bin/env bash
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Install a released tik, verifying it first.
#
# The order matters and is the whole point: the checksums file carries a
# cosign keyless signature naming the workflow that produced it, so that
# signature is checked BEFORE the checksums are believed, and the checksums
# are checked before the binary is run. An installer for a tool whose job is
# verification cannot ask anyone to trust an unverified download.
set -euo pipefail

repo="${TIK_REPO:?the action must pass TIK_REPO}"
dest="${TIK_DEST:?the action must pass TIK_DEST}"
version="${TIK_VERSION:-}"
verify="${TIK_VERIFY:-true}"
ref="${TIK_REF:-}"

# A ref that looks like a calendar release tag IS the version to install:
# pinning `@2026.8.7062815` then installing something else would make the
# pin a lie. Anything else (a branch, a moving tag, a local checkout) falls
# through to the latest release.
if [ -z "${version}" ] && [[ "${ref}" =~ ^[0-9]{4}\.[0-9]{1,2}\.[0-9]+$ ]]; then
  version="${ref}"
fi

if [ -z "${version}" ]; then
  # Read the tag off the redirect rather than the API: no token, no rate
  # limit shared with every other job on the runner.
  latest=$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
    "https://github.com/${repo}/releases/latest")
  version="${latest##*/}"
fi
if [ -z "${version}" ] || [ "${version}" = "latest" ]; then
  echo "::error::cannot work out which tik to install from ${repo}"
  exit 1
fi

case "${RUNNER_OS}:${RUNNER_ARCH}" in
  Linux:X64) asset="tik-linux-amd64-glibc" ;;
  Windows:*)
    echo "::error::tik has no Windows build yet — run it on Linux, or in a container"
    exit 1
    ;;
  # Every other platform runs the portable uberjar. It needs a JDK the
  # caller has already set up, which is stated plainly rather than
  # discovered at the first invocation.
  *) asset="tik.jar" ;;
esac

work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT
base="https://github.com/${repo}/releases/download/${version}"

echo "::group::Downloading tik ${version} (${asset})"
curl -fsSL -o "${work}/${asset}" "${base}/${asset}"
curl -fsSL -o "${work}/SHA256SUMS" "${base}/SHA256SUMS"
if [ "${verify}" = "true" ]; then
  curl -fsSL -o "${work}/SHA256SUMS.bundle" "${base}/SHA256SUMS.bundle"
fi
echo "::endgroup::"

if [ "${verify}" = "true" ]; then
  echo "::group::Verifying the release signature"
  # The identity is derived from the repository this action came from, so a
  # fork checks against its own release workflow instead of ours.
  cosign verify-blob \
    --bundle "${work}/SHA256SUMS.bundle" \
    --certificate-identity \
      "https://github.com/${repo}/.github/workflows/release.yml@refs/heads/main" \
    --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
    "${work}/SHA256SUMS"
  echo "::endgroup::"
else
  echo "::warning::signature verification is off — the checksums are being taken on trust"
fi

echo "::group::Checking the download against the signed checksums"
(
  cd "${work}"
  # Only the line for the asset we downloaded: `sha256sum -c` fails on a
  # missing file, and a release carries more assets than any one platform
  # installs.
  if ! grep -E "[[:space:]]${asset}\$" SHA256SUMS > wanted.sha256; then
    echo "::error::${asset} is not listed in the release checksums"
    exit 1
  fi
  sha256sum -c wanted.sha256
)
echo "::endgroup::"

mkdir -p "${dest}"
if [ "${asset}" = "tik.jar" ]; then
  if ! command -v java > /dev/null 2>&1; then
    echo "::error::no java on PATH — the portable build needs a JDK on ${RUNNER_OS}/${RUNNER_ARCH} (use actions/setup-java first)"
    exit 1
  fi
  install -m 0644 "${work}/tik.jar" "${dest}/tik.jar"
  printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "${dest}/tik.jar" > "${dest}/tik"
  chmod 0755 "${dest}/tik"
else
  install -m 0755 "${work}/${asset}" "${dest}/tik"
fi

echo "${dest}" >> "${GITHUB_PATH}"
{
  echo "version=${version}"
  echo "path=${dest}/tik"
} >> "${GITHUB_OUTPUT}"
echo "tik ${version} installed at ${dest}/tik"
