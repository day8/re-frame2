(ns re-frame.event-context-partition-test
  "Writes against the frame's two partitions (Spec 002 §Event context threads
  both partitions; Conventions §There is no :rf/runtime app-db root):

    - an ordinary handler's `:rf.db/runtime` effect applies, and fires the
      dev diagnostic `:rf.warning/app-handler-runtime-effect` — reserved by
      convention, not enforced;
    - a `:rf/runtime` key in app-db is ordinary app data.

  The commits hold in every posture; the traces that report them are
  dev-only and sit behind `rf.interop/debug-enabled?`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- trace-events [recorded op-type operation]
  (filterv #(and (= op-type (:op-type %)) (= operation (:operation %))) @recorded))

(deftest app-handler-runtime-effect-warns
  (testing "an ordinary handler's :rf.db/runtime effect applies, and fires the dev diagnostic"
    (rf/make-frame {:id :ctx/app-runtime})
    (let [recorded (record-traces! ::app-warn)]
      (rf/reg-event :ctx/app-emits-runtime
        (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {}}}))
      (rf/dispatch-sync [:ctx/app-emits-runtime] {:frame :ctx/app-runtime})
      ;; :recovery :warned: the write is not gated, so it lands in every posture.
      (is (= {:rf.runtime/routing {}}
             (:rf.db/runtime (rf/frame-state-value :ctx/app-runtime))))
      (when rf.interop/debug-enabled?
        (let [warns (trace-events recorded :warning :rf.warning/app-handler-runtime-effect)]
          (is (= [{:rf.trace/event-id :ctx/app-emits-runtime
                   :rf.event/v        [:ctx/app-emits-runtime]
                   :frame             :ctx/app-runtime}]
                 (mapv #(select-keys (:tags %) [:rf.trace/event-id :rf.event/v :frame]) warns)))
          (is (= :warned (:recovery (first warns)))))))))

(deftest a-rf-runtime-key-in-app-db-is-ordinary-data
  (testing "a :db effect carrying a top-level :rf/runtime key commits like any
            other app data"
    (rf/make-frame {:id :ctx/app-runtime-key})
    (rf/reg-event :ctx/writes-runtime-key
      (fn [{:keys [db]} _] {:db (assoc db :rf/runtime {:m 1})}))
    (rf/dispatch-sync [:ctx/writes-runtime-key] {:frame :ctx/app-runtime-key})
    (is (= {:rf/runtime {:m 1}} (rf/app-db-value :ctx/app-runtime-key)))))
