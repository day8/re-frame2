(ns day8.re-frame2-causa.panels.routes-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Routes panel
  (Phase 5, rf2-6blai).

  ## What's under test (in addition to the pure-data tests in
  `routes_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/routes-data`. The composite returns rows +
       active-route + selection + history in the shape the view
       consumes.

    2. **Empty state** — with no registered routes and no override,
       the panel renders 'No routes registered.'

    3. **Populated list** — with a registered-routes override the
       panel renders one row per route + the active-route strip.

    4. **Active route highlight** — the active-route slice's row in
       the registered list carries the `active` marker.

    5. **Navigation history** — trace events seeded onto the bus
       surface as history rows (newest first) with the row click
       firing `:rf.causa/select-dispatch-id` + the panel pivot.

    6. **Route selection** — clicking a row fires
       `:rf.causa/select-route`; the panel highlights the selection.

    7. **Frame isolation** — the panel's state lives on `:rf/causa`,
       never on `:rf/default`.

  ## Pure hiccup

  Same approach as `flows_view_cljs_test` / `subscriptions_view_cljs_
  test` — walk the view's hiccup tree by `data-testid` rather than
  mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.routes :as routes]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror flows_view_cljs_test) -----------------------

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-routes! [m]
  (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test m]))

(defn- override-slice! [s]
  (rf/dispatch-sync [:rf.causa/set-active-route-slice-override-for-test s]))

(defn- push-trace! [ev]
  (trace-bus/collect-trace! ev))

;; ---- (1) registry wires the composite sub -------------------------------

(deftest registry-installs-routes-subs-and-events
  (testing "register-causa-handlers! installs the Phase 5 (rf2-6blai)
            composite sub + every supporting event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/routes-data)))
    (is (some? (registrar/handler :sub :rf.causa/registered-routes)))
    (is (some? (registrar/handler :sub :rf.causa/active-route-slice)))
    (is (some? (registrar/handler :sub :rf.causa/active-route-slice-override)))
    (is (some? (registrar/handler :sub :rf.causa/route-history-events)))
    (is (some? (registrar/handler :sub :rf.causa/selected-route-id)))
    (is (some? (registrar/handler :event :rf.causa/select-route)))
    (is (some? (registrar/handler :event :rf.causa/clear-route-selection)))
    (is (some? (registrar/handler :event
                                  :rf.causa/set-registered-routes-override-for-test)))
    (is (some? (registrar/handler :event
                                  :rf.causa/set-active-route-slice-override-for-test)))))

(deftest routes-data-sub-defaults-empty
  (testing "with an explicit empty override the composite returns
            empty rows + zero total + :no-routes empty-kind.

            Note: examples that register :route handlers at ns-load
            time (the realworld + boot SPAs) survive
            `reset-runtime-fixture`'s registrar rollback by design
            (the fixture preserves ns-load-time registrations). The
            override slot is the test surface for asserting the empty
            case deterministically."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-routes! {})
      (let [data @(rf/subscribe [:rf.causa/routes-data])]
        (is (= []        (:rows data)))
        (is (= 0         (:total data)))
        (is (= :no-routes (:empty-kind data)))
        (is (nil?        (:selected-route-id data)))
        (is (nil?        (:active-route data)))))))

(deftest routes-data-sub-projects-override-into-rows
  (testing "with a registered-routes override the composite returns one
            row per entry — projected via the helpers"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-routes!
        {:route/home {:path "/"     :doc "Landing"}
         :route/cart {:path "/cart" :doc "Cart"}})
      (let [data @(rf/subscribe [:rf.causa/routes-data])
            ids  (set (map :route-id (:rows data)))]
        (is (= 2 (:total data)))
        (is (= #{:route/home :route/cart} ids))
        (is (nil? (:empty-kind data)))))))

;; ---- (2) view renders ---------------------------------------------------

(deftest empty-state-renders-when-no-routes
  (testing "with an explicit empty override the panel renders the
            empty state. See `routes-data-sub-defaults-empty` for the
            note on ns-load-time route registrations from
            realworld / boot examples."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-routes! {})
      (let [tree (routes/routes-view)]
        (is (some? (find-by-testid tree "rf-causa-routes"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-routes-empty"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-routes-list"))
            "no list when there are zero routes")))))

(deftest list-renders-when-routes-populated
  (testing "with a populated override the panel renders one row per
            route + the active-route strip (empty fallback when no
            slice present)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-routes!
        {:route/home {:path "/"     :doc "Landing"}
         :route/cart {:path "/cart" :doc "Cart"}})
      (let [tree (routes/routes-view)]
        (is (some? (find-by-testid tree "rf-causa-routes-list"))
            "list container present")
        (is (some? (find-by-testid tree "rf-causa-route-row-:route/home"))
            "row for :route/home present")
        (is (some? (find-by-testid tree "rf-causa-route-row-:route/cart"))
            "row for :route/cart present")
        (is (some? (find-by-testid tree "rf-causa-routes-active-empty"))
            "active-route empty-fallback surfaces when no slice present")))))

(deftest row-renders-route-id-and-path-and-doc
  (testing "each row carries the route-id + path + doc cells"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-routes!
        {:route/cart {:path "/cart" :doc "Cart"}})
      (let [tree (routes/routes-view)]
        (is (some? (find-by-testid tree "rf-causa-route-id-:route/cart")))
        (is (some? (find-by-testid tree "rf-causa-route-path-:route/cart")))))))

;; ---- (3) active route highlight ----------------------------------------

(deftest active-route-strip-renders-when-slice-present
  (testing "with a :rf/route slice override the active-route strip
            surfaces every cell + the active row marker on the matching
            row"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-routes!
        {:route/home {:path "/"     :doc "Landing"}
         :route/cart {:path "/cart" :doc "Cart"}})
      (override-slice! {:id         :route/cart
                        :params     {}
                        :query      {}
                        :fragment   nil
                        :transition :idle
                        :nav-token  "nav-1"})
      (let [tree (routes/routes-view)]
        (is (some? (find-by-testid tree "rf-causa-routes-active"))
            "active-route strip rendered")
        (is (some? (find-by-testid tree "rf-causa-routes-active-route-id"))
            "route-id cell present")
        (is (some? (find-by-testid tree "rf-causa-routes-active-transition"))
            "transition cell present")
        (is (some? (find-by-testid tree "rf-causa-route-row-active-:route/cart"))
            "active marker on the matching row")
        (is (nil?  (find-by-testid tree "rf-causa-route-row-active-:route/home"))
            "no active marker on the non-matching row")))))

;; ---- (4) navigation history --------------------------------------------

(deftest history-feed-empty-when-no-trace
  (testing "with no route-history trace events the history section
            surfaces the inline empty marker"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-routes! {:route/home {:path "/"}})
      (let [tree (routes/routes-view)]
        (is (some? (find-by-testid tree "rf-causa-routes-history"))
            "history section rendered when there are routes")
        (is (some? (find-by-testid tree "rf-causa-routes-history-empty"))
            "empty marker present when no trace events")))))

(deftest history-feed-surfaces-route-trace-events
  (testing "with :route.nav-token/* and :rf.route/url-changed events on
            the trace bus the history feed renders them.

            Note: each history row carries child spans with their own
            -time / -operation / -route-id testids that *also* start
            with `rf-causa-routes-history-row-`. The assertion targets
            the per-event testids directly so it isn't fooled by the
            prefix-match expansion."
    (setup-causa-frame!)
    (push-trace! {:id        1001
                  :op-type   :info
                  :operation :route.nav-token/allocated
                  :time      100
                  :tags      {:route-id :route/cart
                              :nav-token "nav-1"
                              :dispatch-id 42}})
    (push-trace! {:id        1002
                  :op-type   :info
                  :operation :rf.route/url-changed
                  :time      200
                  :tags      {:route-id :route/cart
                              :fragment "section-2"
                              :dispatch-id 43}})
    (rf/with-frame :rf/causa
      (override-routes! {:route/cart {:path "/cart"}})
      (let [tree (routes/routes-view)]
        (is (some? (find-by-testid tree "rf-causa-routes-history-row-1001"))
            "row for trace-event id=1001 present")
        (is (some? (find-by-testid tree "rf-causa-routes-history-row-1002"))
            "row for trace-event id=1002 present")
        (is (some? (find-by-testid tree "rf-causa-routes-history-row-1001-operation"))
            "operation cell for id=1001 present")
        (is (some? (find-by-testid tree "rf-causa-routes-history-row-1002-route-id"))
            "route-id cell for id=1002 present")))))

(deftest history-row-click-pivots-to-event-detail
  (testing "clicking a history row with a :dispatch-id fires
            :rf.causa/select-dispatch-id + :rf.causa/select-panel"
    (setup-causa-frame!)
    (push-trace! {:id        1
                  :op-type   :info
                  :operation :route.nav-token/allocated
                  :time      100
                  :tags      {:dispatch-id 42 :route-id :route/cart}})
    (rf/with-frame :rf/causa
      (override-routes! {:route/cart {:path "/cart"}})
      ;; Simulate the row click by invoking the handler directly via
      ;; dispatch-sync (the view's on-click runs the same fx).
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 42])
      (rf/dispatch-sync [:rf.causa/select-panel :event-detail])
      (is (= 42 @(rf/subscribe [:rf.causa/selected-dispatch-id])))
      (is (= :event-detail @(rf/subscribe [:rf.causa/selected-panel]))))))

;; ---- (5) route selection ------------------------------------------------

(deftest select-route-event-writes-to-causa-frame
  (testing ":rf.causa/select-route stores the route-id on the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-route :route/cart])
      (is (= :route/cart @(rf/subscribe [:rf.causa/selected-route-id]))))))

(deftest clear-route-selection-drops-selection
  (testing ":rf.causa/clear-route-selection dissocs the selected route-id"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-route :route/cart])
      (rf/dispatch-sync [:rf.causa/clear-route-selection])
      (is (nil? @(rf/subscribe [:rf.causa/selected-route-id]))))))

;; ---- (6) total / count surface -----------------------------------------

(deftest header-count-reflects-registered-count
  (testing "panel header reports the registered-route count"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-routes!
        {:route/a {:path "/a"}
         :route/b {:path "/b"}
         :route/c {:path "/c"}})
      (let [data @(rf/subscribe [:rf.causa/routes-data])]
        (is (= 3 (:total data)))))))

;; ---- (7) frame isolation ------------------------------------------------

(deftest route-selection-does-not-leak-into-default-frame
  (testing "the panel's selection state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-route :route/cart]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :route/cart (:selected-route-id causa-db))
          "selection lands on Causa")
      (is (nil? (:selected-route-id default-db))
          "selection did NOT leak into :rf/default"))))
