;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns fake-ssh
  "Stands in for `ssh -N -L 127.0.0.1:<port>:<host>:<hport> <dest>` in
  dev/native-smoke.sh, so the GitLab bridge's :tunnel runs end to end
  without an SSH server: it listens on the local end and copies bytes to
  the target, which is one of the loopback fakes. It accepts only targets
  on loopback and appends one line to $FAKE_SSH_LOG per invocation."
  (:require [clojure.string :as str])
  (:import (java.io InputStream OutputStream)
           (java.net InetAddress ServerSocket Socket)))

(defn- pipe! [^InputStream in ^OutputStream out]
  (future
    (try
      (let [buf (byte-array 8192)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.write out buf 0 n)
              (.flush out)
              (recur)))))
      (catch Exception _ nil)
      (finally (try (.close out) (catch Exception _ nil))))))

(defn -main [& args]
  (let [spec (second (drop-while #(not= "-L" %) args))
        [bind lport host hport] (str/split (str spec) #":")]
    (when-let [log (System/getenv "FAKE_SSH_LOG")]
      (spit log (str "ssh -L " spec " " (last args) "\n") :append true))
    (when-not (and (= "127.0.0.1" bind) (= "127.0.0.1" host) lport hport)
      (binding [*out* *err*] (println "fake ssh: want -L 127.0.0.1:<port>:127.0.0.1:<port>, got" spec))
      (System/exit 255))
    (let [ss (ServerSocket. (parse-long lport) 50 (InetAddress/getLoopbackAddress))]
      (loop []
        (let [^Socket client (.accept ss)
              upstream (Socket. "127.0.0.1" (int (parse-long hport)))]
          (pipe! (.getInputStream client) (.getOutputStream upstream))
          (pipe! (.getInputStream upstream) (.getOutputStream client)))
        (recur)))))

(apply -main *command-line-args*)
