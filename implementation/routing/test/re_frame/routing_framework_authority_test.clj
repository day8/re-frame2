(ns re-frame.routing-framework-authority-test
  "Framework-authority minting for routing's runtime-db writes (EP-0001).

  Routing is one of the legitimate runtime-db writers Spec 002 §Write
  authority names: its event handlers (`:rf.route/navigate`,
  `:rf.route/handle-url-change`, `:rf.route/url-requested` /
  `:rf.route/continue` / `:rf.route/cancel`) read AND return the reserved
  `:rf.db/runtime` route slice. Their registrations carry the general
  `:rf/framework-authority? true` meta key, so a real navigation emits no
  `:rf.warning/app-handler-runtime-effect`, which would otherwise fire on every
  navigation in dev and teach users the warning is noise. Five deftests assert
  that silence, across the navigation doors and the `:rf/machine?`
  implication; the sixth is the control proving the diagnostic is live: an
  ordinary app handler returning `:rf.db/runtime` warns.

  ## Posture split

  The warning rides `trace/emit!`, gated on `rf.interop/debug-enabled?`. Under
  `-Dre-frame.debug=false` the five `(is (empty? @warns))` legs would pass
  vacuously and the control could not run, so all six sit inside
  `(when rf.interop/debug-enabled? …)` arms. What runs under the gate is the
  navigation scaffolding each case drives — the route commits, the pending
  slot fills and clears, `:rf.route/continue` completes — which is real
  runtime-db state, asserted outside the arms. Under the production gate this
  namespace therefore contributes routing semantics, not the ownership
  contract it is named for."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- record-runtime-warnings!
  "Capture every `:rf.warning/app-handler-runtime-effect` under `listener-id`."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace
      listener-id
      (fn [ev]
        (when (and (= :warning (:op-type ev))
                   (= :rf.warning/app-handler-runtime-effect (:operation ev)))
          (swap! a conj ev))))
    a))

(defn- routing [k]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing k]))

;; ---- framework navigations do NOT fire the ownership diagnostic -----------

(deftest navigate-event-mints-framework-authority
  (testing ":rf.route/navigate writes the route slice without the diagnostic"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [warns (record-runtime-warnings! ::navigate)]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "intro"}}])
      (is (= :route/article (:route-id (routing :current))))
      (when rf.interop/debug-enabled?
        (is (empty? @warns))))))

(deftest url-change-event-mints-framework-authority
  (testing ":rf.route/handle-url-change writes the route slice without the diagnostic"
    (rf/reg-route :route/search {} "/search")
    (let [warns (record-runtime-warnings! ::url-change)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/search?q=widgets" {:rf.route/cause :link}])
      (is (= :route/search (:route-id (routing :current))))
      (when rf.interop/debug-enabled?
        (is (empty? @warns))))))

(deftest can-leave-continue-and-cancel-mint-framework-authority
  (testing "the pending-nav protocol (url-requested / cancel / continue) stays silent"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty
                  (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (let [warns (record-runtime-warnings! ::can-leave)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor/articles/A" {:rf.route/cause :link}])
      (rf/dispatch-sync [:editor/dirty true])
      (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
      (is (some? (routing :pending-navigation)) "the blocked request filled the slot")
      (rf/dispatch-sync [:rf.route/cancel "pn-1"])
      (is (nil? (routing :pending-navigation)) "cancel cleared it")
      (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
      (rf/dispatch-sync [:rf.route/continue "pn-2"])
      (is (= :route/cart (:route-id (routing :current))) "continue completed the navigation")
      (when rf.interop/debug-enabled?
        (is (empty? @warns))))))

(deftest on-match-navigation-mints-framework-authority
  (testing "committing a route with :on-match stays silent"
    (rf/reg-event :load/noop (fn [{:keys [db]} _] {:db db}))
    (rf/reg-route :route/loaded {:on-match [[:load/noop]]} "/loaded")
    (let [warns (record-runtime-warnings! ::on-match)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/loaded" {:rf.route/cause :link}])
      (is (= :route/loaded (:route-id (routing :current))))
      (when rf.interop/debug-enabled?
        (is (empty? @warns))))))

;; ---- the control: a genuine non-framework writer warns ---------------------

(deftest ordinary-app-handler-returning-runtime-db-still-warns
  (testing "an ordinary app handler returning :rf.db/runtime warns once, naming its event"
    (rf/reg-event :app/sneaky-runtime-write
                  (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :hijacked}}}}))
    (let [warns (record-runtime-warnings! ::app-sneaky)]
      (rf/dispatch-sync [:app/sneaky-runtime-write])
      (when rf.interop/debug-enabled?
        (is (= [:app/sneaky-runtime-write] (mapv #(-> % :tags :rf.trace/event-id) @warns)))))))

;; ---- machines mint authority (the :rf/machine? implication) ----------------

(deftest machine-handler-still-mints-authority
  (testing "a :rf/machine? true handler returning :rf.db/runtime stays silent"
    (rf/reg-event :machine/runtime-write
                  {:doc "framework-authority" :rf/machine? true}
                  (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
    (let [warns (record-runtime-warnings! ::machine)]
      (rf/dispatch-sync [:machine/runtime-write])
      (when rf.interop/debug-enabled?
        (is (empty? @warns))))))
