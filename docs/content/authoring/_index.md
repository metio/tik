---
title: Authoring processes
description: Designing a process worth deriving — the one design law, and the tools that check you kept it.
---

A process definition is plain EDN describing stages and the evidence each
one requires. Tickets pin it by content hash, so a definition is a document
with an identity rather than a mutable setting.

- [Writing a definition](/authoring/writing-a-definition/) — the shape, the
  guard vocabulary, and the design law that separates a process from a task
  list.
- [Lint, simulate, test](/authoring/lint-simulate-test/) — proving the
  definition does what you meant.
- [Roles and authority](/authoring/roles-and-authority/) — who may sign
  what, and keeping membership current.

Start from an interview rather than a blank file:

```sh
tik author                      # answer questions; tik writes the definition
tik author --template bug       # start from a finished interview
tik author prompt               # an LLM recipe that yields the answers file
```
