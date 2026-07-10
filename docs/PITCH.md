<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

# What tik does, end to end

An email arrives. The bridge maps the sender to an actor and opens a
ticket — *signed*. A triager categorizes it; the stage isn't set, it
**derives**, and Slack gets paged because an effect observed the
derivation. The customer replies to the thread; it lands as a comment,
content-addressed.

A second replica on another machine categorizes the same ticket
differently at the same moment — nothing breaks, nothing is silently
overwritten: the disagreement surfaces as **conflicted, with both names
on it**, until a human who saw both claims signs a resolution.

The process demands the CI attestation be **fresher than 24 hours** — a
replayed one from last month fails with the honest reason and the
last-seen timestamp. It demands the reviewer and approver be
**different people** — four eyes, derived, not policied.

When someone's stuck, `explain` says exactly what's missing and *what
to set it to*, the runbook one hint away says how, `next` ranks it
against everything else they could do, and `whatif` answers "what would
happen if" without writing a byte. When a process author writes a guard
that can never be satisfied, **lint proves it before a single ticket
suffers it**.

And when the auditor comes — this year or in ten — one command
re-derives *everything*: every event byte-exact to its hash, every
signature to its registered key, every definition to its publication
signature, every stage to its pinned rules. Offline. With coreutils, if
they don't trust our code. And `--at 2026-03-01` shows them what was
true *then*, because time travel was free the whole time.

Underneath: **7 event types, 12 guard operators, 5 fact statuses.**
That's the entire semantic surface — small enough to hold in your head,
proven by four independent oracles (golden bytes, a conformance corpus,
property laws against a reference kernel, TLA+ models including one
*required* to fail), versioned with actual teeth, and running
identically on two storage backends and two runtimes.

One more thing, for the skeptics: tik's own development history is
inside it. Every feature landed by asserting facts on a ticket; two
design decisions were reversed *by evidence the dogfood produced*; and
the hypothesis that any of this is worth anything sits on the board
with its kill criterion signed — exactly as falsifiable as everything
else.

Most software asks you to trust it. **This one hands you the math and
dares you to check.**

git answers "what changed?" tik answers **"what is true, why is it
true, who proved it, and can anyone reproduce the conclusion?"**
