(ns re-frame.trace-listener-post-drain-mutation-test
  "A trace listener runs at the post-drain boundary, so a registrar mutation it
  makes on a drain-owned emit is equivalent to making it on the line after
  `dispatch-sync` returns. The probe is `:spawn-all` child install: the form of
  the type reference it stamps depends on whether the registrar had diverged
  by the moment of install."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private booting-child
  ;; The spawn bootstrap moves :idle -> :ready; a live child then takes :go.
  {:initial :idle
   :states  {:idle    {:on {:rf.machine.spawn/spawned :ready}}
             :ready   {:on {:go :working}}
             :working {}}})

(defn- observe
  "Send the child one later `:go`, then read: whether its installed type
  reference is a pinned definition map, its state, and the
  `:rf.error/no-such-handler` errors it raised."
  [child-id]
  (rf/dispatch-sync [child-id [:go]])
  {:pinned?         (map? (:rf/machine-type (rf.machines.test-support/snapshot child-id)))
   :state           (rf.machines.test-support/machine-state child-id)
   :no-such-handler (count (filterv #(= child-id (get-in % [:tags :rf.trace/event-id]))
                                    (rf.machines.test-support/events-of :rf.error/no-such-handler)))})

(defn- run-arm
  "Register `type-kw` and a `:spawn-all` parent over it, fork, and observe the
  child. `during` runs from a listener on the drain-owned
  `:rf.machine.spawn-all/started` emit; `after` runs on the line after
  `dispatch-sync`."
  [type-kw parent-kw {:keys [during after]}]
  (rf/reg-machine type-kw booting-child)
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle    {:on {:start :forking}}
               :forking {:spawn-all {:children        [{:id :c :machine-id type-kw}]
                                     :join            :all
                                     :on-all-complete [:all/done]}
                         :on        {:all/done :ready}}
               :ready   {}}})
  (when during
    (rf.trace.tooling/register-listener!
      ::arm
      (fn [ev] (when (= :rf.machine.spawn-all/started (:operation ev)) (during)))))
  (try
    (rf/dispatch-sync [parent-kw [:start]])
    (when after (after))
    (finally
      (when during (rf.trace.tooling/unregister-listener! ::arm))))
  (observe (get-in (rf.machines.test-support/runtime-db)
                   [:rf.runtime/machines :spawned parent-kw [:forking] :children :c])))

(deftest mid-drain-listener-unregister-is-equivalent-to-unregistering-after-the-drain
  ;; The install saw an intact registrar and kept the revertible keyword, so the
  ;; post-drain unregister strands the later :go.
  (let [stranded {:pinned? false :state :ready :no-such-handler 1}]
    (is (= [stranded stranded]
           [(run-arm :pd/a1 :pd/sup-a1 {:during #(rf.registrar/unregister! :event :pd/a1)})
            (run-arm :pd/a2 :pd/sup-a2 {:after  #(rf.registrar/unregister! :event :pd/a2)})]))))

(deftest mid-drain-listener-reregister-is-equivalent-to-reregistering-after-the-drain
  ;; The live child follows the successor definition: ordinary hot reload.
  (let [v2       (-> booting-child
                     (assoc-in [:states :ready :on :go] :hot-reloaded)
                     (assoc-in [:states :hot-reloaded] {}))
        reloaded {:pinned? false :state :hot-reloaded :no-such-handler 0}]
    (is (= [reloaded reloaded]
           [(run-arm :pd/b1 :pd/sup-b1 {:during #(rf/reg-machine :pd/b1 v2)})
            (run-arm :pd/b2 :pd/sup-b2 {:after  #(rf/reg-machine :pd/b2 v2)})]))))
