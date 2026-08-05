---
title: Logo
description: The mark, the files, and the rules for using them.
tags: [brand, logo, design]
---

<!--
SPDX-FileCopyrightText: The tik Authors
SPDX-License-Identifier: 0BSD
-->

A checkmark inside a hexagonal seal, whose long arm crosses the boundary and
terminates outside it. Every other verification badge encloses its proof; this
one lets the proof leave — which is the offline re-derivation claim in a shape.
The bead at the lower vertex is the signature that closes the seal.

## Files

| File | Use |
| --- | --- |
| [`tik-mark.svg`](/images/tik-mark.svg) | Master mark. Two-tone. Anything 32 px and up. |
| [`tik-mark-mono.svg`](/images/tik-mark-mono.svg) | Single colour. Terminals, print, laser, embroidery, anywhere the accent can't survive. |
| [`tik-mark-small.svg`](/images/tik-mark-small.svg) | Optical size variant for 24 px and below. Seal closed, tick heavier, bead removed. Also the favicon. |
| [`tik-lockup.svg`](/images/tik-lockup.svg) | Horizontal mark + wordmark. Default for READMEs, docs headers, and slides. |

## Colour

Ink is `currentColor` in every file — the mark inherits the surrounding text
colour, so it works on light and dark without a second copy. Set the colour on
a parent element or on the `<svg>` itself.

The accent is `var(--tik-accent, #A8752B)` — seal brass. Override it by
defining `--tik-accent` on any ancestor. It is deliberately the only colour in
the system; if you add a second, the bead stops meaning "signature".

## Rules

- **Minimum size**: 32 px for [`tik-mark.svg`](/images/tik-mark.svg). Below that the pierce gap closes
  and the exiting arm reads as a nick, so switch to [`tik-mark-small.svg`](/images/tik-mark-small.svg).
- **Clear space**: one hexagon radius (46 units on the 128 grid) on all sides.
  The exiting arm needs air or it reads as a collision with adjacent content.
- **Don't** close the seal on the master mark, recolour the ink to the accent,
  put the mark on a busy background, or rotate it. The tick's angle is the only
  thing keeping it from reading as an arrow.
- The wordmark is drawn as outlined paths, not live text. There is no font
  dependency and nothing to substitute.

## Licence

The files carry `SPDX-License-Identifier: 0BSD`, matching the rest of the
repository, so the mark may be used on your own software — including a fork.
