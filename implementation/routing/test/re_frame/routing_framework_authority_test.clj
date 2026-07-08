(ns re-frame.routing-framework-authority-test
  "EP-0001 (rf2-3939ig) — generalized framework-authority minting.

  Routing is one of the legitimate runtime-db writers Spec 002 §Write
  authority names (alongside machines / elision / ssr). Its event handlers
  (`:rf.route/navigate`, `:rf.route/transitioned` / `:rf.route/handle-url-change`,
  `:rf/url-requested` / `:rf.route/continue` / `:rf.route/cancel`,
  `:rf.route.internal/settle-transition`) read AND return the reserved
  `:rf.db/runtime` route slice. Before the fix `assemble-initial-ctx` minted
  framework-write authority from `:rf/machine?` ONLY, so every navigation
  tripped the `:rf.warning/app-handler-runtime-effect` ownership diagnostic
  in dev — polluting the Xray Issues lens and training users that the
  warning is noise.

  The fix introduces the general `:rf/framework-authority? true` registration-
  meta key (stamped by the routing façade's `reg-event` registrations);
  the router reads the general key (folding in the `:rf/machine?` implication
  via `events/framework-authority?`).

  These tests assert that a representative set of REAL framework navigations
  emit ZERO `:rf.warning/app-handler-runtime-effect`, while preserving the
  POSITIVE half of the diagnostic: an ordinary app handler returning
  `:rf.db/runtime` still warns. The broad cross-subsystem conformance sweep
  is a separate bead; this is the routing-focused regression."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-runtime-warnings!
  "Register a trace listener under `listener-id` that captures every
  `:rf.warning/app-handler-runtime-effect` event. Returns the capture atom."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace
      listener-id
      (fn [ev]
        (when (and (= :warning (:op-type ev))
                   (= :rf.warning/app-handler-runtime-effect (:operation ev)))
          (swap! a conj ev))))
    a))

(defn- stub-push-url! []
  (rf/reg-fx :rf.nav/push-url
             {:platforms #{:server :client}}
             (fn [_ _] nil)))

;; ===========================================================================
;; Framework navigations do NOT fire the ownership diagnostic
;; ===========================================================================

(deftest navigate-event-mints-framework-authority
  (testing ":rf.route/navigate returns :rf.db/runtime without tripping the diagnostic"
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (stub-push-url!)
    (let [warns (record-runtime-warnings! ::navigate)]
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "intro"}])
      ;; The slice actually changed (proves the :rf.db/runtime effect applied).
      (is (= :route/article (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                    [:rf.runtime/routing :current :route-id]))
          "the navigate handler wrote the route slice (:rf.db/runtime applied)")
      (is (empty? @warns)
          ":rf.route/navigate is a framework-authority writer — no ownership diagnostic"))))

(deftest url-change-event-mints-framework-authority
  (testing ":rf.route/transitioned (URL-driven) does not trip the diagnostic"
    (rf/reg-route :route/search {} "/search")
    (stub-push-url!)
    (let [warns (record-runtime-warnings! ::url-change)]
      (rf/dispatch-sync [:rf.route/transitioned "/search?q=widgets"])
      (is (= :route/search (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                   [:rf.runtime/routing :current :route-id]))
          "the url-change handler wrote the route slice")
      (is (empty? @warns)
          ":rf.route/transitioned is a framework-authority writer — no diagnostic"))))

(deftest can-leave-continue-and-cancel-mint-framework-authority
  (testing "the pending-nav protocol (url-requested / continue / cancel) stays silent"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty
                     (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (stub-push-url!)
    (let [warns (record-runtime-warnings! ::can-leave)]
      ;; Land on the guarded route, dirty it, attempt to leave → blocked
      ;; (:rf/url-requested writes the pending slot via :rf.db/runtime).
      (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
      (rf/dispatch-sync [:editor/dirty true])
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (is (some? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                         [:rf.runtime/routing :pending-navigation]))
          ":rf/url-requested wrote the pending-navigation slot")
      ;; CANCEL clears the slot (a :rf.db/runtime write).
      (rf/dispatch-sync [:rf.route/cancel "pn-1"])
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/routing :pending-navigation]))
          ":rf.route/cancel cleared the pending slot")
      ;; Re-block, then CONTINUE (a :rf.db/runtime write + completion).
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (rf/dispatch-sync [:rf.route/continue "pn-2"])
      (is (= :route/cart (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/routing :current :route-id]))
          ":rf.route/continue completed the navigation")
      (is (empty? @warns)
          "url-requested / continue / cancel are framework-authority writers — no diagnostic"))))

(deftest settle-transition-mints-framework-authority
  (testing ":rf.route.internal/settle-transition (per-route data-load settle) stays silent"
    ;; A route with :on-match runs the FIFO settle handler, which writes the
    ;; :transition state into the runtime-db slice via :rf.db/runtime.
    (rf/reg-event :load/noop (fn [{:keys [db]} _] {:db db}))
    (rf/reg-route :route/loaded
                  {:on-match [[:load/noop]]} "/loaded")
    (stub-push-url!)
    (let [warns (record-runtime-warnings! ::settle)]
      (rf/dispatch-sync [:rf.route/transitioned "/loaded"])
      (is (= :route/loaded (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                   [:rf.runtime/routing :current :route-id]))
          "the :on-match route settled onto the slice")
      (is (empty? @warns)
          "the settle-transition path is a framework-authority writer — no diagnostic"))))

;; ===========================================================================
;; The diagnostic still fires for a genuine non-framework writer (control)
;; ===========================================================================

(deftest ordinary-app-handler-returning-runtime-db-still-warns
  (testing "an ordinary app handler returning :rf.db/runtime DOES fire the diagnostic"
    ;; Proves the listener + diagnostic are live in this fixture — the
    ;; framework-quiet assertions above are not vacuously empty.
    (rf/reg-event :app/sneaky-runtime-write
                     (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :hijacked}}}}))
    (let [warns (record-runtime-warnings! ::app-sneaky)]
      (rf/dispatch-sync [:app/sneaky-runtime-write])
      (is (= 1 (count @warns))
          "a non-framework handler writing :rf.db/runtime trips exactly one diagnostic")
      (is (= :app/sneaky-runtime-write (-> @warns first :tags :rf.trace/event-id))
          "the diagnostic names the offending app event-id"))))

;; ===========================================================================
;; Machines still mint authority (the :rf/machine? implication is preserved)
;; ===========================================================================

(deftest machine-handler-still-mints-authority
  (testing "a :rf/machine? true handler returning :rf.db/runtime stays silent (no regression)"
    (rf/reg-event :machine/runtime-write
                     {:doc "framework-authority" :rf/machine? true}
                     (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
    (let [warns (record-runtime-warnings! ::machine)]
      (rf/dispatch-sync [:machine/runtime-write])
      (is (empty? @warns)
          ":rf/machine? still implies framework-write authority via framework-authority?"))))
