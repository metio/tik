#!/bin/sh
# SPDX-FileCopyrightText: The tik-processes Authors
# SPDX-License-Identifier: 0BSD
# Derives renovate-migration evidence from the repository's own
# contents. cwd is the repo; stdout key=value lines become signed
# facts (quote prose). Run with: tik probe
set -eu

# the renovate config an auditor can open, pinned to its last commit
for f in renovate.json renovate.json5 .github/renovate.json \
         .github/renovate.json5 .renovaterc.json; do
  if [ -f "$f" ]; then
    sha=$(git log -1 --format=%h -- "$f" 2>/dev/null || echo untracked)
    echo "renovate.config.ref=\"$f@$sha\""
    break
  fi
done

# "dependabot fully gone" is the LIVE bar, re-derived every run. Two
# signals, because neither alone is enough: (1) the config FILE by PATH
# — .github/dependabot.yml's own content is just "version/updates" and
# never contains the word "dependabot", so a content grep misses the
# primary artifact; (2) any tracked file that REFERENCES dependabot by
# content — a leftover automerge workflow, a mergify stanza, a script.
# Historical mentions in CHANGELOGs and markdown are excluded (a
# "migrated from Dependabot" note is not unfinished work).
config=$(ls .github/dependabot.yml .github/dependabot.yaml 2>/dev/null || true)
refs=$(git grep -Il dependabot -- ':!CHANGELOG*' ':!*.md' 2>/dev/null || true)
remaining=$(printf '%s\n%s\n' "$config" "$refs" | grep -v '^$' | sort -u || true)
if [ -z "$remaining" ]; then
  echo "dependabot.status=absent"
  removed=$(git log -1 --format=%h -- .github/dependabot.yml .github/dependabot.yaml 2>/dev/null)
  echo "removal.commit=\"${removed:-never-had-dependabot}\""
else
  echo "dependabot.status=present"
  # name the specific files still referencing dependabot, so `tik
  # explain` says WHY a ticket regressed, not just that it did
  files=$(echo "$remaining" | tr '\n' ' ' | sed 's/[[:space:]]*$//')
  echo "dependabot.remaining=\"$files\""
fi
