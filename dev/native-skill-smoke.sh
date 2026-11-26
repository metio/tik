#!/bin/sh
# SPDX-FileCopyrightText: The tik Authors
# SPDX-License-Identifier: 0BSD
#
# Check that a built binary serves the agent skills in skills/ byte for
# byte over MCP: every file the repository holds is listed with its
# digest, and resources/read returns exactly those bytes. The native
# image only carries what its build captured, so this runs against
# target/tik after dev/build-native.sh.
#
#   sh dev/native-skill-smoke.sh [binary]
set -eu
cd "$(dirname "$0")/.."
bin=${1:-target/tik}
work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT

meta='"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}'
{
  printf '{"jsonrpc":"2.0","id":0,"method":"skills/list","params":{%s}}\n' "${meta}"
  for f in $(cd skills && find . -type f | sed 's|^\./||' | sort); do
    printf '{"jsonrpc":"2.0","id":"%s","method":"resources/read","params":{"uri":"skill://%s",%s}}\n' "${f}" "${f}" "${meta}"
  done
} > "${work}/in.jsonl"
TIK_ROOT="${work}" "${bin}" mcp < "${work}/in.jsonl" > "${work}/out.jsonl"

status=0
want_list=$(cd skills && find . -type f | sed 's|^\./|skill://|' | sort)
got_list=$(jq -r 'select(.id==0) | .result.skills[].resources[].uri' "${work}/out.jsonl" | sort)
if [ "${want_list}" != "${got_list}" ]; then
  echo "FAIL the listed files are not the files in skills/:"
  echo "${want_list}" > "${work}/want"
  echo "${got_list}" > "${work}/got"
  diff "${work}/want" "${work}/got" || true
  status=1
fi
for f in $(cd skills && find . -type f | sed 's|^\./||' | sort); do
  want="sha256:$(sha256sum "skills/${f}" | cut -d' ' -f1)"
  listed=$(jq -r --arg u "skill://${f}" \
    'select(.id==0) | .result.skills[].resources[] | select(.uri==$u) | .digest' "${work}/out.jsonl")
  served="sha256:$(jq -j --arg id "${f}" 'select(.id==$id) | .result.contents[0].text' "${work}/out.jsonl" \
    | sha256sum | cut -d' ' -f1)"
  if [ "${listed}" = "${want}" ] && [ "${served}" = "${want}" ]; then
    echo "ok   skill://${f} ${want}"
  else
    echo "FAIL skill://${f}: repository ${want}, listed ${listed:-none}, served ${served}"
    status=1
  fi
done
exit "${status}"
