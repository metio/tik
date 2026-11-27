;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.process-tree
  "The processes a process started, so a run that is killed takes its
  password manager or ssh tunnel with it. A namespace of its own because
  babashka has no ProcessHandle: `tik.pipeline-run` resolves it lazily
  and goes without on babashka, while the native image requires it in
  `tik.main`."
  (:import (java.lang ProcessHandle)))

(defn descendants-of
  "Every live process below `proc`."
  [^Process proc]
  (vec (.toArray (.descendants proc))))

(defn destroy!
  "Ask each of `handles` to stop, or force it when `force?`."
  [handles force?]
  (doseq [^ProcessHandle h handles]
    (if force? (.destroyForcibly h) (.destroy h))))
