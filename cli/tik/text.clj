;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.text
  "Totality helpers for rendering kernel-unconstrained values —
  porcelain-side, shared by every surface that prints. The kernel does
  not constrain fact-path elements, link rels, config types, or process
  ids to be names, so a renderer that calls `name` raw will eventually
  cast-crash on a hostile-but-valid value.")

(defn safe-name
  "`name`, but total: a string for a keyword/symbol/string, else `str`.
  One odd value must not poison a whole board view or turn a config typo
  into 'a bug in tik'."
  [x]
  (if (or (keyword? x) (symbol? x) (string? x)) (name x) (str x)))
