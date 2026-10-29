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

(def ^:private parent-id :rf.spawn-egress/supervisor)

(def ^:private child-error
  {:rf.error/id    :rf.error/machine-action-exception
   :exception-data {:card "4111-1111-1111-1111" :token "secret-child-jwt"}})

(defn- inner-error-event [] [:rf.machine.spawn/error invoke-id child-error])

(defn- leaks-child-payload? [x]
  (boolean (re-find #"4111-1111-1111-1111|secret-child-jwt" (pr-str x))))

(deftest spawn-error-payload-redacted-on-every-parent-trace
  ;; One row per slot a parent's traces carry the failure under, in the shape
  ;; the runtime puts there: `:event-received` and the router's `:rf.event/v`
  ;; carry the ROUTED carrier `[<parent-id> <inner>]`; the transition echo and
  ;; guard/action `:input` carry the inner event.
  (doseq [[op path ev] [[:rf.machine/event-received [:event]
                         [parent-id (inner-error-event)]]
                        [:rf.event/dispatched      [:rf.event/v]
                         [parent-id (inner-error-event)]]
                        [:rf.machine/transition    [:event]
                         (inner-error-event)]
                        [:rf.machine/action-ran    [:input :event]
                         (inner-error-event)]]]
    (testing op
      (let [out    (rf.classification/project-trace-event
                     {:operation op
                      :tags      (assoc-in {:actor-id parent-id :frame :rf/default} path ev)})
            inner  (cond-> (get-in out (into [:tags] path))
                     (not= :rf.machine.spawn/error (first ev)) second)]
        (is (= [:rf.machine.spawn/error invoke-id] (take 2 inner))
            "the reserved event-id and invoke-id survive, so the trace is still locatable")
        (is (not (leaks-child-payload? out))
            "no raw child payload egresses")))))

(deftest spawn-error-carrier-redacted-at-the-event-chokepoint
  ;; The always-on error records and fx-arg recursion read events through the
  ;; same chokepoint, so the routed carrier redacts there with no trace at all.
  (let [out (rf.classification/redact-event-by-registration [parent-id (inner-error-event)])]
    (is (= [parent-id [:rf.machine.spawn/error invoke-id]] [(first out) (vec (take 2 (second out)))]))
    (is (not (leaks-child-payload? out)))))
