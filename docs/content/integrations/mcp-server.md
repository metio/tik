---
title: MCP server
description: The frontier as an agent's gated tool surface, over stdio.
tags: [mcp, agents, authorization]
---

```sh
TIK_ACTOR=bot TIK_KEY=~/.config/tik/bot_ed25519 tik mcp
```

`tik mcp` speaks the Model Context Protocol over stdio and exposes one
store as a tool surface. The tools an agent is offered are exactly the
steps the frontier admits for its role at that moment, so the tool list
*is* the authorization boundary.

## Why the boundary holds

An agent's permission to act is derived from the same guards a person's
is. A step whose `:signed-by` names a role the agent is not in never
appears as an available tool, and calling it anyway is refused with the
derived reason rather than a generic denial.

That matters because the alternative — telling a model in its prompt what
it may not do — is enforcement by cooperation. Here the check happens
where the answer is computed, and it produces the same reason string a
person would see.

## Accountability

Every write the agent makes is an ordinary signed event with the agent's
own actor identity. The log therefore distinguishes what a person claimed
from what an agent claimed, permanently and without a separate audit
trail. `tik causal` names the events behind each reached stage, so a
conclusion an agent contributed to can be traced to the evidence it
supplied and the key that signed it.

## Task specifications from guards

An agent asking "what should I do?" gets the same structured answer every
other lens renders:

```sh
tik explain 3184 --actor bot --edn
```

Each missing step carries its path, its schema, and who may satisfy it —
which makes an acceptance criterion out of a guard, rather than out of a
sentence somebody wrote in a ticket description.
