---
title: MCP server
description: The frontier as an agent's gated tool surface, and tik's agent skill, over stdio or HTTP.
tags: [mcp, agents, authorization]
---

```sh
TIK_ACTOR=bot TIK_KEY=~/.config/tik/bot_ed25519 tik mcp
```

`tik mcp` speaks the Model Context Protocol over stdio and exposes one
store as a tool surface. The tools an agent is offered are exactly the
steps the frontier admits for its role at that moment, so the tool list
*is* the authorization boundary.

## One shared server over HTTP

```sh
TIK_ACTOR=bot TIK_KEY=~/.config/tik/bot_ed25519 \
  tik serve --host 127.0.0.1 --port 7777 --mcp-token-file ~/.local/state/tik/mcp-token
```

`tik serve` also speaks MCP on `/mcp` when given a token file, so every
client shares one long-running server instead of starting its own
process. It answers one JSON-RPC message per POST with JSON and opens no
event stream. A request needs the token as `Authorization: Bearer …`, and
one whose `Origin` is not the server's own is refused, which keeps a web
page from driving the server through DNS rebinding. The token must be at
least 32 characters; `openssl rand -hex 32` makes one.

Without `--host`, a server offering MCP listens on 127.0.0.1 only, because
`/mcp` writes over plain HTTP; put a TLS proxy in front before offering it
beyond the machine. The board alone keeps listening on every address.

`--surfaces` picks what the server offers: `http` for the read-only board
and its EDN, `mcp` for the tools, or `http,mcp`. Without it, the server
offers `http`, and `mcp` too when a token file is given.

## Agent skill

The server serves tik's agent skill, the repository's `skills/tik/` tree
as built into the binary, through the MCP Skills extension
(`io.modelcontextprotocol/skills`):

- `skills/list` and `skills/get` return the skill's entry: its
  frontmatter and a manifest listing every file with its sha256 digest
  and size.
- `resources/read` serves the files under `skill://tik/<path>`. A client
  without the extension sees them as ordinary resources, and the server
  instructions name `skill://tik/SKILL.md`.
- A URI with a `..` or `.` segment, an empty segment or an escaped
  character is refused, as is any file the manifest does not list.

The extension's digests only show that a file matches the manifest, and
whoever runs the server writes both. tik therefore signs the manifest
with `TIK_KEY` as `TIK_ACTOR`, in the SSHSIG namespace
`tik-skill-manifest`, and puts the signature in the entry's `_meta` under
`wtf.metio/skill-manifest`. Without a key, that marker says
`"signed": false` and why. `tik skill verify` checks a server against the
store's `actors` file:

```sh
tik skill verify http://127.0.0.1:7777/mcp --token-file ~/.local/state/tik/mcp-token
```

It checks the signature, then reads every file and compares its bytes,
and the SKILL.md frontmatter, with the manifest. It exits 1 for an
unsigned manifest, a signature no registered actor made, or any file
that differs. Given a saved `skills/list` or `skills/get` response
instead of a URL, it checks the signature alone.

The signed bytes are the manifest in a fixed form, one line each:
`skill-manifest v1`, `skill <SKILL.md URI>`, then
`file <digest> <size> <URI>` per file in URI order, each line ending in
a newline. The form leaves out the frontmatter, which a client compares
against the signed SKILL.md bytes, and binds no server version, so an
older signed manifest still verifies until its signer is removed from
`actors`.

The server speaks both MCP eras: the `initialize` handshake of revisions
up to 2025-06-18, and revision 2026-07-28, whose every request carries
its version and capabilities in `_meta`. Claude Code lists a server's
skills only over 2026-07-28. Over HTTP, a 2026-07-28 request must repeat
its version, method and resource or tool name in the
`MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers, and is
refused with `400` when they differ from the body.

A message is one JSON object of at most 1 MiB, read strictly: a key named
twice, text after the value or a body over the limit answers a parse
error (`-32700`) whose `data.rule` names the rule, without quoting the
input. A request without `"jsonrpc": "2.0"`, a string method and a string
or integer `id` answers `-32600`, an unknown method `-32601`, and an
unknown tool or `arguments` that are not an object `-32602`. A tool that
runs and fails, such as a refused assertion or a missing argument,
answers in its result with `isError`. A notification, a message without
an `id`, runs nothing and gets no answer (`202` over HTTP). Over stdio, a
line over the limit is refused without being held in memory, and the
next line is read as usual.

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
