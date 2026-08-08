<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD

Library-specific prose for the generated /processes/ index. `tik gallery
--intro` splices it in, so regenerating the catalog never discards it.
-->
These pages are generated from the definitions themselves with `tik gallery`,
so what you read is what a ticket derives under. Each carries the content
address it was generated from — the value a ticket pins, and the one a
consumer checks against.

Some of these run tik's own work; the rest are here to be taken. Every role
ships empty either way: who is in one is organisation state, and it lives in a
store's register rather than in the definition a ticket pinned.

Rows marked *(template)* ask a few questions instead of arriving finished:
`tik adopt` prompts for each, then expands and lints your answers into a plain
definition. Their pages show the shape with every option on, and say which
stages depend on which answer.

## Taking one without cloning anything

Every definition is served here by its own address. Fetch it, hash it, compare
— you either have the definition the page describes or you have nothing, and
no trust in this site is required for that to hold:

```sh
HASH=sha256-…                                    # the address on its page
curl -fsSLO https://tik.projects.metio.wtf/processes/by-hash/${HASH}.edn
[ "sha256-$(sha256sum "${HASH}.edn" | cut -d' ' -f1)" = "${HASH}" ] || exit 1
tik adopt "${HASH}.edn"
```

The publication signature is served beside it, and
[`actors`](/processes/actors) carries the key it checks against, so you can
confirm who stands behind a definition with OpenSSH alone:

```sh
curl -fsSLO https://tik.projects.metio.wtf/processes/by-hash/${HASH}.sig.<fpr>
curl -fsSLO https://tik.projects.metio.wtf/processes/actors
ssh-keygen -Y verify -f actors -I seb -n tik-process \
  -s "${HASH}.sig.<fpr>" < "${HASH}.edn"
```

Adopting this way brings the definition alone. `tik adopt` from a clone of the
library brings its runbooks too — the how-to each stage's `:hint` points at,
which is what makes a process legible to somebody who did not write it.
