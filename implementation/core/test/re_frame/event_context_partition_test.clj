(ns re-frame.event-context-partition-test
  "Writes against the frame's two partitions (Spec 002 §Event context threads
  both partitions; Conventions §The legacy :rf/runtime root):

    - an ordinary handler's `:rf.db/runtime` effect applies, and fires the
      dev diagnostic `:rf.warning/app-handler-runtime-effect` — reserved by
      convention, not enforced;
    - a `:rf/runtime` root in app-db is rejected whole, whether the handler
      returned it or an `:after` interceptor inserted it into the final
      effects, and the drain carries on.

  The commits and rejections hold in every posture; the traces that report
  them are dev-only and sit behind `rf.interop/debug-enabled?`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (when-let [clear-schemas! (rf.late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
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

(deftest db-handler-returning-legacy-runtime-root-surfaces-hard-error
  (testing "a handler whose :db effect carries a :rf/runtime root is rejected:
            the root never lands in app-db"
    (rf/make-frame {:id :ctx/legacy-db})
    (let [recorded (record-traces! ::legacy-db)]
      (rf/reg-event :ctx/writes-legacy-root
        (fn [{:keys [db]} _] {:db (assoc db :rf/runtime {:rf.runtime/machines {:m 1}})}))
      (rf/dispatch-sync [:ctx/writes-legacy-root] {:frame :ctx/legacy-db})
      (is (not (contains? (rf/app-db-value :ctx/legacy-db) :rf/runtime)))
      (when rf.interop/debug-enabled?
        ;; The in-chain guard throws inside the handler wrapper, so the
        ;; rejection surfaces as a captured handler-exception.
        (is (= [:rf.error/legacy-runtime-root]
               (mapv #(:rf.error/id (ex-data (get-in % [:tags :exception])))
                     (trace-events recorded :error :rf.error/handler-exception))))))))

(deftest after-interceptor-legacy-runtime-root-is-rejected
  (testing "an :after interceptor inserting :rf/runtime into the final :db effect
            is rejected whole at the final boundary, and the drain survives"
    (rf/make-frame {:id :ctx/after-legacy})
    (let [recorded (record-traces! ::after-legacy)]
      (rf/reg-interceptor ::legacy
        (rf.interceptor/->interceptor*
          :id    ::legacy
          :after (fn [ctx]
                   (let [db (rf.interceptor/get-effect ctx :db)]
                     (rf.interceptor/assoc-effect
                       ctx :db (assoc db :rf/runtime {:rf.runtime/machines {}}))))))
      (rf/reg-event :ctx/clean-db
        {:interceptors [::legacy]}
        (fn [{:keys [db]} _] {:db (assoc db :user/id 7)}))
      (rf/reg-event :ctx/after-legacy-downstream
        (fn [{:keys [db]} _] {:db (assoc db :downstream? true)}))
      (rf/dispatch-sync [:ctx/clean-db] {:frame :ctx/after-legacy})
      (rf/dispatch-sync [:ctx/after-legacy-downstream] {:frame :ctx/after-legacy})
      ;; Neither the root nor the legal :user/id beside it committed, and the
      ;; next event still ran.
      (is (= {:downstream? true} (rf/app-db-value :ctx/after-legacy)))
      (when rf.interop/debug-enabled?
        (is (= [:rf/runtime]
               (mapv #(get-in % [:tags :offending-key])
                     (trace-events recorded :error :rf.error/legacy-runtime-root))))))))
