;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.fake-imap
  "A loopback IMAP server for tests: one mailbox per user, the commands
  the bridge sends (LOGIN, SELECT, UID SEARCH, UID FETCH, FETCH, IDLE,
  DONE, LOGOUT) and nothing else. IDLE pushes `* n EXISTS` the moment a
  test delivers a message, so a watch is tested on the real push path.
  Plaintext by default; give `:ssl-context` to speak implicit TLS."
  (:require [clojure.string :as str])
  (:import (java.io BufferedReader InputStreamReader OutputStream)
           (java.net InetAddress ServerSocket Socket)
           (java.nio.charset StandardCharsets)
           (javax.net.ssl SSLContext)))

(defn- send! [^OutputStream out & lines]
  (locking out
    (doseq [l lines] (.write out (.getBytes (str l "\r\n") StandardCharsets/ISO_8859_1)))
    (.flush out)))

(defn- literal [n uid ^String raw]
  [(str "* " n " FETCH (UID " uid " BODY[] {" (count (.getBytes raw StandardCharsets/ISO_8859_1)) "}")
   (str raw ")")])

(defn- session [{:keys [users boxes log]} ^Socket sock]
  (with-open [sock sock]
    (let [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/ISO_8859_1))
          out (.getOutputStream sock)
          user (atom nil)]
      (send! out "* OK fake IMAP ready")
      (loop []
        (when-let [line (.readLine in)]
          (let [[tag verb & more] (str/split line #" ")
                verb (str/upper-case (str verb))
                box #(get @boxes @user [])]
            (swap! log conj (if (= "LOGIN" verb) "LOGIN <credentials>" (str/join " " (cons verb more))))
            (case verb
              "LOGIN" (let [[u p] (map #(str/replace % "\"" "") more)]
                        (if (= p (get users u))
                          (do (reset! user u) (send! out (str tag " OK LOGIN completed")))
                          (send! out (str tag " NO LOGIN failed"))))
              "SELECT" (if @user
                         (send! out (str "* " (count (box)) " EXISTS") (str tag " OK [READ-WRITE] SELECT completed"))
                         (send! out (str tag " NO not authenticated")))
              "UID" (let [[sub & args] more]
                      (case (str/upper-case sub)
                        "SEARCH" (send! out (str "* SEARCH " (str/join " " (map :uid (box)))) (str tag " OK SEARCH completed"))
                        "FETCH" (let [uid (parse-long (first args))
                                      [n m] (first (keep-indexed #(when (= uid (:uid %2)) [(inc %1) %2]) (box)))]
                                  (apply send! out (concat (when m (literal n uid (:raw m)))
                                                           [(str tag " OK FETCH completed")])))))
              "FETCH" (let [[lo hi] (map parse-long (str/split (first more) #":"))
                            ms (subvec (box) (dec lo) (min hi (count (box))))]
                        (apply send! out (concat (mapcat (fn [i m] (literal (+ lo i) (:uid m) (:raw m)))
                                                         (range) ms)
                                                 [(str tag " OK FETCH completed")])))
              "IDLE" (let [seen (count (box))
                           watcher (str (gensym "idle"))]
                       (send! out "+ idling")
                       (add-watch boxes watcher
                                  (fn [_ _ old new]
                                    (let [n (count (get new @user []))]
                                      (when (> n (count (get old @user [])))
                                        (try (send! out (str "* " n " EXISTS")) (catch Exception _ nil))))))
                       (let [n (count (box))]
                         (when (> n seen) (send! out (str "* " n " EXISTS"))))
                       (let [done (.readLine in)]
                         (remove-watch boxes watcher)
                         (swap! log conj (str done))
                         (send! out (str tag " OK IDLE terminated"))))
              "LOGOUT" (send! out "* BYE" (str tag " OK LOGOUT completed"))
              (send! out (str tag " BAD unknown command")))
            (when-not (= "LOGOUT" verb) (recur))))))))

(defn start!
  "Start the server: {:users {user password}}, optional :ssl-context.
  Returns {:port :deliver! (fn [user raw]) :log atom :stop! (fn [])}."
  [{:keys [users ^SSLContext ssl-context]}]
  (let [loopback (InetAddress/getLoopbackAddress)
        ^ServerSocket ss (if ssl-context
                           (.createServerSocket (.getServerSocketFactory ssl-context) 0 50 loopback)
                           (ServerSocket. 0 50 loopback))
        state {:users users :boxes (atom {}) :log (atom [])}
        uid (atom 0)]
    (future
      (try
        (loop []
          (let [s (.accept ss)]
            (future (try (session state s) (catch Exception _ nil))))
          (recur))
        (catch Exception _ nil)))
    {:port (.getLocalPort ss)
     :log (:log state)
     :deliver! (fn [user raw]
                 (swap! (:boxes state) update user (fnil conj []) {:uid (swap! uid inc) :raw raw}))
     :stop! (fn [] (.close ss))}))
