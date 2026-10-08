(ns re-frame.machine-spawn-trace-egress-test
  "Child-owned spawn payloads are redacted at the trace-egress chokepoint
  (`re-frame.classification/project-trace-event`) whatever the parent's
  classification: the `:start` on `:rf.machine.spawn/spawned`, and the error
  in the synthetic `[:rf.machine.spawn/error <invoke-id> <error>]` a parent's
  machine traces carry. The routing slots survive."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.classification :as rf.classification]))

(def ^:private invoke-id [:working])

(deftest spawned-start-payload-redacted-in-egress
  (let [out (rf.classification/project-trace-event
              {:operation :rf.machine.spawn/spawned
               :tags      {:frame      :rf/default
                           :machine-id :rf.spawn-egress/worker-type
                           :spawned-id :rf.spawn-egress/worker#1
                           :invoke-id  invoke-id
                           :start      [:begin {:token "secret-start-jwt" :password "hunter2"}]}})]
    (is (= [:rf.spawn-egress/worker#1 invoke-id] ((juxt :spawned-id :invoke-id) (:tags out)))
        "the slots that locate the spawn survive")
    (is (not (re-find #"secret-start-jwt|hunter2" (pr-str out))) "no raw :start payload egresses")))

(deftest spawn-error-payload-redacted-on-every-parent-machine-trace
  ;; One row per slot the parent's machine traces carry the event under:
  ;; :event (event-received, transition) and [:input :event] (guard-evaluated,
  ;; action-ran).
  (doseq [[op path] [[:rf.machine/event-received [:event]]
                     [:rf.machine/action-ran     [:input :event]]]]
    (testing op
      (let [error {:rf.error/id    :rf.error/machine-action-exception
                   :exception-data {:card "4111-1111-1111-1111" :token "secret-child-jwt"}}
            out   (rf.classification/project-trace-event
                    {:operation op
                     :tags      (assoc-in {:actor-id :rf.spawn-egress/supervisor :frame :rf/default}
                                          path [:rf.machine.spawn/error invoke-id error])})]
        (is (= [:rf.machine.spawn/error invoke-id] (take 2 (get-in out (into [:tags] path))))
            "the reserved event-id and invoke-id survive, so the trace is still locatable")
        (is (not (re-find #"4111-1111-1111-1111|secret-child-jwt" (pr-str out)))
            "no raw child payload egresses")))))
