(ns re-frame.cascade-dispatch-id-test
  "`:rf.trace/dispatch-id` correlation (Spec 009 §Dispatch correlation): every
  trace event emitted while an event runs carries that event's dispatch-id, a
  child dispatch gets a fresh id and names its parent's under
  `:rf.trace/parent-dispatch-id`, and frame-lifecycle emits stay uncorrelated.

  ## Posture split

  No production channel carries the dispatch-id, so every correlation claim
  sits in a `(when rf.interop/debug-enabled? …)` arm. Each cascade test also
  asserts, in both postures, that the cascade it correlates actually ran, so
  under `-Dre-frame.debug=false` it is never an empty pass."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- record-traces
  "Run `body-fn` with a trace listener attached; return the captured events."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (rf/unregister-listener! :trace ::rec)))))

(defn- dispatch-id [ev] (get-in ev [:tags :rf.trace/dispatch-id]))

(defn- dispatched
  "The `:rf.event/dispatched` trace event for event vector `v`."
  [evs v]
  (some #(when (and (= :rf.event/dispatched (:operation %))
                    (= v (get-in % [:tags :rf.event/v])))
           %)
        evs))

(deftest dispatch-id-rides-every-event-in-the-cascade
  (rf/make-frame {:id :test/main})
  (let [fx-fired (atom 0)]
    (rf/reg-fx :test/incr (fn [_ _] (swap! fx-fired inc)))
    (rf/reg-event :seed (fn [_ _] {:db {:n 1} :fx [[:test/incr :go]]}))
    (let [evs        (record-traces #(rf/dispatch-sync [:seed] {:frame :test/main}))
          cascade-id (dispatch-id (dispatched evs [:seed]))]
      (is (= 1 @fx-fired) "the cascade ran")
      (when rf.interop/debug-enabled?
        (is (some? cascade-id))
        (is (= #{cascade-id}
               (->> evs
                    (filter #(contains? #{:event :rf.event/db-changed :rf.fx/do-fx :rf.fx/handled}
                                        (:operation %)))
                    (map dispatch-id)
                    set))
            "every event emitted inside the drain carries the cascade's dispatch-id")))))

(deftest child-dispatch-gets-its-own-dispatch-id-and-parents-the-outer
  (rf/make-frame {:id :test/main})
  (rf/reg-event :parent (fn [_ _] {:fx [[:dispatch [:child]]]}))
  (rf/reg-event :child (fn [{:keys [db]} _] {:db (assoc db :got-child true)}))
  (let [evs    (record-traces #(rf/dispatch-sync [:parent] {:frame :test/main}))
        parent (dispatched evs [:parent])
        child  (dispatched evs [:child])]
    (is (true? (:got-child (rf/app-db-value :test/main))) "the child dispatch committed")
    (when rf.interop/debug-enabled?
      (is (every? some? [(dispatch-id parent) (dispatch-id child)]))
      (is (not= (dispatch-id parent) (dispatch-id child))
          "the child gets its own fresh dispatch-id")
      (is (= (dispatch-id parent) (get-in child [:tags :rf.trace/parent-dispatch-id]))
          "and names the parent's under :rf.trace/parent-dispatch-id"))))

(deftest frame-lifecycle-emits-stay-uncorrelated-under-a-cascade-scope
  ;; A frame created or re-registered from inside another frame's handler must
  ;; not inherit that cascade's id: the epoch capture seam would strand the
  ;; marker in the new frame's buffer for its whole lifetime. This drives
  ;; `rf.trace/emit!` directly, a no-op under the gate, so it is guarded whole.
  (when rf.interop/debug-enabled?
    (let [evs   (record-traces
                  #(rf.trace/with-dispatch-id+call-site 4242 nil
                     (rf.trace/emit! :rf.frame :rf.frame/re-registered {:frame :test/sibling})
                     (rf.trace/emit! :rf.frame :rf.frame/created        {:frame :test/other})
                     (rf.trace/emit! :rf.sub   :rf.sub/run              {:frame :test/sibling
                                                                         :rf.sub/id :x})))
          by-op (fn [op] (some #(when (= op (:operation %)) %) evs))]
      (is (= [nil nil 4242]
             (mapv (comp dispatch-id by-op)
                   [:rf.frame/re-registered :rf.frame/created :rf.sub/run]))
          "frame-lifecycle emits stay uncorrelated; an ordinary emit, the control, carries the id"))))
