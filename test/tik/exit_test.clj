;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.exit-test
  "The process boundary of the binary: what a command wrote must reach
  its reader before the process ends. These run a real JVM, because
  System/exit is the thing under test and would end the test runner."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is]]))

(defn- run-jvm
  "Evaluate `form` in a fresh JVM on this test run's classpath."
  [form]
  (sh/sh (str (io/file (System/getProperty "java.home") "bin" "java"))
         "-cp" (System/getProperty "java.class.path")
         "clojure.main" "-e" (pr-str form)
         :dir (System/getProperty "user.dir")))

(deftest process-exit-delivers-output-written-without-a-newline
  (let [r (run-jvm '(do (require 'tik.cli-core)
                        (print "a report without a trailing newline")
                        (binding [*out* *err*] (print "and a warning"))
                        (tik.cli-core/process-exit! 3)))]
    (is (= 3 (:exit r)))
    (is (= "a report without a trailing newline" (:out r)))
    (is (= "and a warning" (:err r)))))
