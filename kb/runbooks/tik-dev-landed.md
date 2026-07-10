---
type: runbook
title: tik-dev / :landed
---

# Runbook: tik-dev / :landed

Assert `gate=:green` only after the FULL local gate: kaocha, kondo
(0/0), eastwood, splint, cljfmt, tla, process tests, reuse, typos,
markdown. Not the one check your change touched — all of them.
Sticky: a later gate dispute does not un-land; file a new bug ticket
instead. If the gate is red, assert `gate=:red` honestly and fix
forward.
