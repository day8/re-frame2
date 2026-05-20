(ns day8.re-frame2-causa.panels.routing-cljs-test
  "CLJS-side wiring + view tests for Causa's Runtime Routing tab —
  the topology-plus-overlay shape (rf2-3kjlo, refining rf2-o5f5f.3).

  ## Scope (post-rf2-3kjlo)

  The Runtime Routing tab is a topology-plus-overlay surface per
  spec/021 §7: the FULL routing tree is always visible (registered
  routes nested by `:parent` meta); the focused epoch's nav activity
  overlays as a `:to` / `:from` / `:here` marker on the relevant
  nodes. A 'This epoch' detail block below the tree surfaces
  Phase / From / To / Match / Events; when the focused cascade
  carries no routing activity, the tree still renders with `:here`
  only and the detail block reads 'No route activity in this epoch.'.

  The browse + search + Simulate-URL surface lives on the Static
  Routes panel (see `static/routes/panel_cljs_test.cljs`).

  ## What's under test

    1. **Registry wires the subs** — every sub the panel reads gets
       installed by `register-causa-handlers!`. The promoted browse /
       search / Simulate-URL slots (now under
       `:rf.causa.static.routes/*`) are NOT registered by
       `routing/install!` anymore.

    2. **Topology always renders** — when routes are registered, the
       topology root + each route row render regardless of focused-
       epoch activity.

    3. **Per-epoch overlay** — when the focused cascade carries a
       `:rf.route.nav-token/allocated` emit, the destination row
       gets a `:to` marker; the prior route (when distinct) gets a
       `:from` marker; the 'This epoch' block surfaces the phase.

    4. **No-activity branch** — when focused cascade has no routing
       trace events, 'This epoch' reads the empty caption and the
       topology still renders.

    5. **Silent state** — when no routes registered, panel renders the
       silent-by-default caption (no topology, no detail block).

    6. **Frame isolation** — every read targets `:rf/causa`'s frame."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.palette.subs :as palette-subs]
            [day8.re-frame2-causa.panels.routing :as routing]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror issues_ribbon_view_cljs_test) ---------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- all-by-testid [tree testid]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (= testid (:data-testid (second node)))))
          (hiccup-seq tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- fixture builders ---------------------------------------------------

(def cart-routes
  {:route/cart      {:path "/cart"      :doc "cart"}
   :route/checkout  {:path "/checkout"  :doc "checkout"}
   :route/payment   {:path "/checkout/payment"
                     :parent :route/checkout}
   :route/confirm   {:path "/checkout/confirm"
                     :parent :route/checkout
                     :on-match [:confirm/load]}})

(defn- nav-allocated [route-id]
  {:id        99
   :op-type   :event
   :operation :rf.route.nav-token/allocated
   :tags      {:route-id route-id :nav-token "nav-1"}})

;; ---- (1) registry wiring + tab inventory --------------------------------

(deftest registry-installs-routing-subs
  (testing "register-causa-handlers! installs the topology-plus-overlay subs"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/registered-routes))
        ":rf.causa/registered-routes sub registered (shared with Static)")
    (is (some? (registrar/handler :sub :rf.causa/registered-routes-override))
        "test-only override sub registered")
    (is (some? (registrar/handler :sub :rf.causa/current-route-slice))
        ":rf.causa/current-route-slice sub registered")
    (is (some? (registrar/handler :sub :rf.causa/current-route-slice-override))
        "test-only override sub registered")
    (is (some? (registrar/handler :sub :rf.causa/routing-tab-data))
        "view-facing topology-plus-overlay composite sub registered")
    (is (some? (registrar/handler :event :rf.causa/set-registered-routes-override-for-test))
        "test-only override event registered")
    (is (some? (registrar/handler :event :rf.causa/set-current-route-slice-override-for-test))
        "test-only override event registered"))
  (testing "rf2-o5f5f.3 — browse + search + Simulate-URL slots NO LONGER live
            under :rf.causa.routing/* (promoted to :rf.causa.static.routes/*)"
    (registry/register-causa-handlers!)
    (is (nil? (registrar/handler :sub :rf.causa.routing/query))
        ":rf.causa.routing/query removed (moved to static.routes/query)")
    (is (nil? (registrar/handler :sub :rf.causa.routing/sim-url))
        ":rf.causa.routing/sim-url removed (moved to static.routes/sim-url)")
    (is (nil? (registrar/handler :sub :rf.causa.routing/expanded))
        ":rf.causa.routing/expanded removed (moved to static.routes/expanded)")
    (is (nil? (registrar/handler :event :rf.causa.routing/set-query))
        ":rf.causa.routing/set-query removed (moved to static.routes/set-query)")
    (is (nil? (registrar/handler :event :rf.causa.routing/set-sim-url))
        ":rf.causa.routing/set-sim-url removed (moved to static.routes/set-sim-url)")
    (is (nil? (registrar/handler :event :rf.causa.routing/toggle-row))
        ":rf.causa.routing/toggle-row removed (moved to static.routes/toggle-row)")))

(deftest palette-includes-routing
  (testing "the palette's canonical panel list carries the :routing entry"
    (let [panels (palette-subs/palette-panels)
          ids    (set (map :id panels))]
      (is (contains? ids :routing) ":routing in palette-panels")
      (is (= 9 (count panels))
          "exactly 9 entries — Event / App DB / Views / Trace / Machines / Machines Canvas / Routing / Issues / Chrome A11y (rf2-mkpnb + rf2-5r2yj)"))))

;; ---- (2) topology base layer (always visible) ---------------------------

(deftest panel-renders-topology-when-routes-registered
  (testing "topology base layer renders for every registered route"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-causa-routing-header"))
            "header always renders")
        (is (some? (find-by-testid tree "rf-causa-routing-topology"))
            "topology always renders when routes are registered")
        ;; Each route gets a topology-row entry.
        (doseq [rid (keys cart-routes)]
          (is (some? (find-by-testid tree
                       (str "rf-causa-routing-topology-row-" (name rid))))
              (str "topology row rendered for " rid)))
        (is (some? (find-by-testid tree "rf-causa-routing-this-epoch"))
            "'This epoch' detail block always renders")))))

(deftest panel-renders-silent-when-no-routes
  (testing "no routes registered → silent caption + no topology"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test {}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-causa-routing-header"))
            "header still renders")
        (is (some? (find-by-testid tree "rf-causa-routing-silent"))
            "silent caption rendered for empty registrar")
        (is (nil? (find-by-testid tree "rf-causa-routing-topology"))
            "topology NOT rendered when no routes registered")
        (is (nil? (find-by-testid tree "rf-causa-routing-this-epoch"))
            "'This epoch' block NOT rendered when silent")))))

;; ---- (3) no-activity branch (focused epoch with no routing trace) -------

(deftest panel-renders-no-activity-when-cascade-has-no-routing
  (testing "focused cascade with no routing trace events → topology renders + 'No route activity'"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing-topology"))
            "topology renders unconditionally")
        (is (some? (find-by-testid tree "rf-causa-routing-no-activity"))
            "'No route activity' caption rendered")
        ;; current-route highlight as `:here`
        (is (some? (find-by-testid tree "rf-causa-routing-topology-marker-here"))
            "current route gets :here marker when no nav this epoch")
        (is (nil? (find-by-testid tree "rf-causa-routing-detail-phase"))
            "phase detail NOT rendered when no activity")))))

;; ---- (4) per-epoch overlay (focused cascade with nav-token emit) --------

(deftest panel-paints-to-marker-when-cascade-navigated
  (testing "focused cascade with nav-token emit → :to marker + Phase :on-match"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id     :route/confirm
                          :params {:order-id "ord-1234"}
                          :query  {:source "cart"}}]
                        {:frame :rf/causa})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :event :operation :event/dispatched
                     :tags {:dispatch-id 99
                            :event [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :dispatch-id 99))]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer]
                          {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa/focus-cascade 99 nil] {:frame :rf/causa}))
      (let [tree (routing/Panel)]
        ;; Topology still renders.
        (is (some? (find-by-testid tree "rf-causa-routing-topology")))
        ;; :to marker present on the destination row.
        (is (some? (find-by-testid tree "rf-causa-routing-topology-marker-to"))
            ":to marker rendered on destination route in the topology")
        ;; Header summary surfaces the TO id.
        (is (some? (find-by-testid tree "rf-causa-routing-nav-summary"))
            "header carries → TO summary chip")
        ;; This-epoch detail block surfaces Phase :on-match.
        (is (some? (find-by-testid tree "rf-causa-routing-detail-phase"))
            "'Phase' value rendered in This-epoch block")
        (is (some? (find-by-testid tree "rf-causa-routing-detail-to"))
            "'To' value rendered in This-epoch block")
        (is (nil? (find-by-testid tree "rf-causa-routing-no-activity"))
            "empty-state caption NOT rendered when activity present")))))

(deftest panel-paints-from-and-to-when-prior-slice-differs
  (testing "focused cascade with distinct prior slice → both :from and :to markers"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      ;; Prior slice = :route/cart; navigate to :route/confirm.
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :event :operation :event/dispatched
                     :tags {:dispatch-id 99
                            :event [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :dispatch-id 99))]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer]
                          {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa/focus-cascade 99 nil] {:frame :rf/causa}))
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing-topology-marker-from"))
            ":from marker rendered on origin route")
        (is (some? (find-by-testid tree "rf-causa-routing-topology-marker-to"))
            ":to marker rendered on destination route")
        (is (some? (find-by-testid tree "rf-causa-routing-detail-from"))
            "'From' value rendered in This-epoch block")))))
