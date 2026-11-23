;; SPDX-FileCopyrightText: The tik Authors
;; SPDX-License-Identifier: 0BSD
(ns tik.whatif-heads-test
  "A hypothetical step descends from every head of the log, as a real
  write does, so whatif on a forked ticket predicts what tik set derives."
  (:require [clojure.test :refer [deftest is]]
            [tik.design :as design]
            [tik.event :as event]
            [tik.reduce :as red])
  (:import (java.time Instant)))

(def ^:private tid #uuid "018f2f6e-7c1a-7000-8000-00000000a7f1")
(defn- at [s] (Instant/parse s))

(deftest a-hypothetical-write-supersedes-every-branch
  (let [c (event/create-ticket {:ticket tid :actor "seb" :at (at "2026-07-08T10:00:00Z")
                                :title "w" :process :p})
        e1 (event/assert-fact {:ticket tid :actor "seb" :at (at "2026-07-08T10:01:00Z")
                               :parents #{(:event/id c)} :path [:x] :value "old"})
        e2 (event/assert-fact {:ticket tid :actor "ana" :at (at "2026-07-08T10:02:00Z")
                               :parents #{(:event/id c)} :path [:y] :value "v"})
        events (vec (red/ordered [c e1 e2]))
        st (design/apply-step {:events events :now (at "2026-07-08T11:00:00Z") :actor "seb"}
                              [:set [:x] "new"])
        fs (red/fact-status (red/ticket-state (:events st)) [:x])]
    (is (= :present (:status fs)) (pr-str fs))
    (is (= "new" (:value fs)))))
