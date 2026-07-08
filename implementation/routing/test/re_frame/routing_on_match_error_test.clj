(ns re-frame.routing-on-match-error-test
  "`:on-match` error-handling tests for re-frame.routing (the
  transition→:error flip, `:on-error` dispatch, route attribution, and
  the colliding-same-id non-mis-attribution case). Split from
  routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ============================================================================
;; rf2-ye7sh — :on-match :on-error trap + :transition :error
;; ============================================================================

(deftest on-match-error-flips-transition-to-error
  (testing "an :on-match event throw flips :transition :error and
            populates :rf.route/error (Spec 012 §Per-route error handling)"
    (rf/reg-event :load/throw
                     (fn [{:keys [db]} _]
                       {:db (throw (ex-info "boom" {:reason :test}))}))
    (rf/reg-route :route/dashboard
                  {:on-match [[:load/throw]]} "/dashboard")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/dashboard"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :error (:transition slice))
          ":transition flips to :error on :on-match throw")
      (is (some? (:error slice))
          ":rf.route/error is populated with the structured error map")
      (is (= :load/throw (:event-id (:error slice)))
          ":rf.route/error names the failing event-id")
      (is (= :rf.error/handler-exception (:operation (:error slice)))
          ":rf.route/error carries the Spec 009 error :operation"))))

(deftest on-match-error-dispatches-on-error-when-declared
  (testing "a route's :on-error event dispatches with the error context
            visible via (:error (get-in db [:rf.runtime/routing :current])) (Spec 012 §Per-route
            error handling)"
    (rf/reg-event :load/throw2
                     (fn [{:keys [db]} _]
                       {:db (throw (ex-info "kaboom" {}))}))
    ;; EP-0001 (rf2-vzld77): the route slice (with :error) is durable routing
    ;; runtime-db state, so the :on-error handler reads it off :rf.db/runtime.
    (rf/reg-event :route/cart-load-failed
                     (fn [{:keys [db] rt :rf.db/runtime} _]
                       (let [err (get-in rt [:rf.runtime/routing :current :error])]
                         {:db (assoc db :handled-error err)})))
    (rf/reg-route :route/cart
                  {:on-match [[:load/throw2]]
                   :on-error [:route/cart-load-failed]} "/cart")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/cart"])
    (let [db    (rf/app-db-value :rf/default)
          slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :error (:transition slice))
          ":transition still :error after :on-error ran")
      (is (some? (:handled-error db))
          ":on-error handler ran and read the error from the slice")
      (is (= :load/throw2 (:event-id (:handled-error db)))
          ":on-error handler saw the same structured error as :rf.route/error"))))

(deftest on-match-error-without-on-error-leaves-error-state
  (testing "a route without :on-error still flips :transition :error and
            populates :rf.route/error — views may render an error banner
            without an explicit :on-error policy (Spec 012 §Per-route
            error handling — last paragraph)"
    (rf/reg-event :load/throw3
                     (fn [{:keys [db]} _]
                       {:db (throw (ex-info "x" {}))}))
    (rf/reg-route :route/page
                  {:on-match [[:load/throw3]]} "/page")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/page"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :error (:transition slice))
          ":transition :error even without :on-error declared")
      (is (some? (:error slice))
          ":rf.route/error populated for the view to render"))))

(deftest on-match-error-keyword-on-error-wraps-as-vector
  (testing "an :on-error declared as a bare keyword (rather than a
            vector) dispatches as `[<kw>]` per the spec example"
    (rf/reg-event :load/throw4
                     (fn [{:keys [db]} _]
                       {:db (throw (ex-info "y" {}))}))
    (rf/reg-event :handle/error
                     (fn [{:keys [db]} _]
                       {:db (assoc db :handled? true)}))
    (rf/reg-route :route/p
                  {:on-match [[:load/throw4]]
                   ;; bare keyword form
                   :on-error :handle/error} "/p")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/p"])
    (is (true? (:handled? (rf/app-db-value :rf/default)))
        "bare-keyword :on-error wraps as a vector and dispatches")))

;; ============================================================================
;; rf2-m78lu — :on-match exception attribution rides on the error map
;; ============================================================================

(deftest on-match-error-stamps-route-attribution
  (testing ":rf.route/on-match-id and :rf.route/on-match-frame are
            stamped on the structured error map dispatched into the
            slice's :error slot (Spec 012 §Per-route error handling
            and rf2-m78lu). Same pattern as the flow-attribution slot
            `:rf.flow/failed-id` (rf2-je5p8).
            Tools reading the error from `(:error (get-in db [:rf.runtime/routing :current]))` —
            outside the routing listener's discrimination context —
            can identify the throw as :on-match-attributed without
            re-running the listener logic."
    (rf/reg-event :load/throw-attribute
                     (fn [{:keys [db]} _]
                       {:db (throw (ex-info "attributed-boom" {:why :test}))}))
    (rf/reg-route :route/attributed
                  {:on-match [[:load/throw-attribute]]} "/attributed")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/attributed"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])
          err   (:error slice)]
      (is (= :error (:transition slice))
          ":transition flips to :error on the attributed throw")
      (is (= :rf.error/handler-exception (:operation err))
          ":operation is the Spec 009 handler-exception id")
      (is (= :load/throw-attribute (:rf.route/on-match-id err))
          ":rf.route/on-match-id names the failing :on-match event-id")
      (is (= :rf/default (:rf.route/on-match-frame err))
          ":rf.route/on-match-frame names the dispatching frame"))))

;; ============================================================================
;; rf2-t1lxr / rf2-1ve9h — routing-internal dispatches stamp :source :router
;; ============================================================================

(deftest on-match-error-internal-dispatch-stamps-source-router
  (testing "the routing-internal `:rf.route.internal/on-match-error`
            dispatch stamps the closed-enum functional-origin axis
            `:source :router` on its envelope (visible on the
            `:rf.event/dispatched` trace) so Xray's L2 timeline + filter
            pills discriminate framework-origin events from user-origin
            events. Per rf2-t1lxr; per rf2-1ve9h `:source` is the single
            closed-enum functional-origin axis on the dispatch envelope,
            and `:source :router` is the routing discriminator."
    (rf/reg-event :load/throw-source
                     (fn [{:keys [db]} _]
                       {:db (throw (ex-info "source-boom" {:why :test}))}))
    (rf/reg-route :route/source-attributed
                  {:on-match [[:load/throw-source]]} "/source-attributed")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::on-match-source
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/source-attributed"])
      (rf/unregister-listener! :trace ::on-match-source)
      (let [internal-dispatch
            (some (fn [ev]
                    (and (= :rf.event/dispatched (:operation ev))
                         (= :rf.route.internal/on-match-error
                            (-> ev :tags :rf.event/v first))
                         ev))
                  @traces)]
        (is (some? internal-dispatch)
            "the on-match-error listener dispatched :rf.route.internal/on-match-error")
        ;; `:source` is hoisted to a top-level slot on every trace event
        ;; (re-frame.trace/build-event — Spec 009 §Core fields hoist
        ;; contract), not stamped under `:tags`.
        (is (= :router (:source internal-dispatch))
            ":source :router rides on the routing-internal dispatch envelope")))))

;; ============================================================================
;; rf2-cgh8q — the on-match error-trap must not mis-attribute a NON-routing
;; throw to the loading route. Discrimination is now full event-VECTOR
;; identity against the route's declared :on-match (not bare event-id
;; membership), so a colliding same-id dispatch carrying different args
;; during the loading window does NOT flip the healthy route to :error.
;; ============================================================================

(deftest on-match-error-does-not-mis-attribute-colliding-same-id-throw
  (testing "a throw from an event whose id coincides with the active route's
            :on-match id, but whose FULL vector differs (a button handler's
            `[:app/load-x \"button\"]` vs the route's `[:app/load-x \"route\"]`),
            mid-loading-window, does NOT flip the route slice to :error or
            chain :on-error — rf2-cgh8q. Pre-fix the id-membership check
            mis-attributed it."
    (let [on-error-fired? (atom false)]
      ;; The route's own on-match loader — it dispatches the COLLIDING
      ;; throwing event as a child of its own cascade, so the throw fires
      ;; while the slice is still :loading (the realistic reproduction
      ;; window). The child carries DIFFERENT args than the route's
      ;; declared on-match vector.
      (rf/reg-event :app/load-x
                       (fn [{:keys [db]} [_ origin]]
                         (if (= origin "button")
                           ;; The "button"-shaped dispatch throws — it is
                           ;; NOT the route's on-match vector.
                           (throw (ex-info "button-throw" {:origin origin}))
                           ;; The route's on-match dispatch: fire the
                           ;; colliding throwing child, then no-op.
                           {:db db
                            :fx [[:dispatch [:app/load-x "button"]]]})))
      (rf/reg-event :app/route-on-error
                       (fn [{:keys [db]} _] (reset! on-error-fired? true) {:db db}))
      (rf/reg-route :route/collide
                    {:on-match [[:app/load-x "route"]]
                     :on-error [:app/route-on-error]} "/collide")
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/transitioned "/collide"])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :current])]
        (is (not= :error (:transition slice))
            "the colliding non-routing throw did NOT flip the route to :error")
        (is (nil? (:error slice))
            ":rf.route/error stays empty — no spurious error attribution")
        (is (false? @on-error-fired?)
            ":on-error did NOT chain for the mis-attributed throw")))))

(deftest on-match-error-still-flips-on-genuine-on-match-throw
  (testing "regression guard for rf2-cgh8q — a GENUINE on-match throw
            (the failing event vector IS one of the route's declared
            :on-match vectors) still flips :transition :error and chains
            :on-error. The tightened discrimination must not suppress the
            real error path."
    (let [on-error-fired? (atom false)]
      (rf/reg-event :app/genuine-load
                       (fn [{:keys [db]} [_ arg]]
                         {:db (throw (ex-info "genuine-boom" {:arg arg}))}))
      (rf/reg-event :app/genuine-on-error
                       (fn [{:keys [db]} _] (reset! on-error-fired? true) {:db db}))
      (rf/reg-route :route/genuine
                    {;; on-match carries args — the failing dispatch must
                     ;; match the FULL vector, not just the id.
                     :on-match [[:app/genuine-load 42]]
                     :on-error [:app/genuine-on-error]} "/genuine")
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/transitioned "/genuine"])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :current])]
        (is (= :error (:transition slice))
            "a genuine on-match throw (full-vector match) still flips :error")
        (is (= :app/genuine-load (:rf.route/on-match-id (:error slice)))
            ":rf.route/on-match-id names the genuine failing event-id")
        (is (true? @on-error-fired?)
            ":on-error chains on the genuine on-match throw")))))
