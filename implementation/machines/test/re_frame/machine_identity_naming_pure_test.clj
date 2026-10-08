(ns re-frame.machine-identity-naming-pure-test
  "The pure reducer's spawn fx: an explicit `:fixed-actor-id` is the spawned
  instance's address verbatim, while `:rf/invoke-id` stays the invocation path."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]))

(deftest fixed-actor-id-pins-instance-distinct-from-invoke-path
  (let [spec             {:initial :idle
                          :data    {}
                          :states  {:idle    {:on {:go :working}}
                                    :working {:spawn {:machine-id     :idn/worker
                                                      :fixed-actor-id :idn/pinned}}}}
        {[[_ args]] :fx} (rf.machines/machine-transition spec {:state :idle :data {}} [:go])]
    (is (= {:rf/spawned-id :idn/pinned :rf/invoke-id [:working]}
           (select-keys args [:rf/spawned-id :rf/invoke-id])))))
