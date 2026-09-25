(ns panel-gallery.gallery-routing
  "Story coverage for the **Routes tab** of the Xray 4-layer chrome.

  The Routes tab body is the `routing/Panel` view: CURRENT ROUTE,
  NAVIGATION THIS EPOCH and the registered ROUTE TABLE. The panel reads
  the `:rf.xray/routing-tab-data` composite over:

    - `:rf.xray/registered-routes`     — default `(rf/registrations
                                          :route)`; test override slot
                                          exists.
    - `:rf.xray/current-route-slice`   — default target-frame's
                                          `[:rf.runtime/routing
                                           :current]` runtime-db slice; test
                                          override slot exists.
    - `:rf.xray/event-bundles`              — drives FROM/TO detection.
    - `:rf.xray/focus`                 — the spine's focused
                                          dispatch-id.

  Each variant seeds the override slots via the test-only events
  (`:rf.xray/set-registered-routes-override-for-test`,
  `:rf.xray/set-current-route-slice-override-for-test`, installed by
  `panel-gallery.core/register-handlers!`) and the trace-buffer via
  `:rf.xray/sync-trace-buffer` + `:rf.xray/focus-event`. Route search and
  Simulate-URL live on the Static Routes lens, not this panel, so no
  variant here exercises them."
  (:require [re-frame.story :as rf.story]
            [panel-gallery.fixtures-routing :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Routes tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (rf.story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (rf.story/reg-tag :feature/xray-routing
    {:axis :feature
     :doc  "Xray Routes tab — the current route, the focused
            event-bundle's navigation, and the registered route table
            with the current row and FROM/TO markers per spec/016
            §Routes tab + spec/018 §5.6."})

  (rf.story/reg-story :story.xray.routing
    {:doc        "Visual gallery of the Xray Routes tab under varying
                 registrar shapes + nav event-bundles. Each variant
                 seeds the registered-routes + current-slice override
                 slots via test-only events."
     :component  :panel-gallery.routing/Panel
     :tags       #{:dev :feature/xray-routing}
     :substrates #{:reagent}})

  ;; ----- 1. no routes registered (silent) ----------------------------
  (rf.story/reg-variant :story.xray.routing/no-routes
    {:doc        "Host app has no routes registered. Panel renders
                 the silent empty-state — a terse caption and no
                 sections. Honours silent-by-default."
     :setup     [[:rf.xray/set-registered-routes-override-for-test {}]
                  [:rf.xray/set-current-route-slice-override-for-test nil]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. current route only (◀ current) ---------------------------
  (rf.story/reg-variant :story.xray.routing/current-route-only
    {:doc        "Routes registered + a current slice; focused
                 event-bundle did NOT navigate. CURRENT ROUTE shows the
                 cart route, NAVIGATION THIS EPOCH reads its quiet
                 caption, and the route table highlights the current
                 row with the `◀ current` marker."
     :setup     [[:rf.xray/set-registered-routes-override-for-test
                   fixtures/cart-routes]
                  [:rf.xray/set-current-route-slice-override-for-test
                   fixtures/cart-slice]
                  [:rf.xray/sync-trace-buffer
                   (fixtures/no-nav-buffer 1 [:cart/refresh])]
                  [:rf.xray/focus-event 1 nil]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. FROM → TO transition (◇ FROM / ◉ TO) ---------------------
  (rf.story/reg-variant :story.xray.routing/from-to-transition
    {:doc        "Focused event-bundle carried a nav-token allocation —
                 NAVIGATION THIS EPOCH reads cart ──► confirm, and the
                 route table paints ◇ FROM on the prior route and ◉ TO
                 on the destination. CURRENT ROUTE carries the
                 destination's params, query and fragment."
     :setup     [[:rf.xray/set-registered-routes-override-for-test
                   fixtures/cart-routes]
                  ;; Live slice is the post-nav value (confirm). FROM is
                  ;; read off the event-bundle's :rf.route/deactivated emit
                  ;; (prior route = cart), NOT the live slice.
                  [:rf.xray/set-current-route-slice-override-for-test
                   fixtures/confirm-slice]
                  [:rf.xray/sync-trace-buffer
                   (fixtures/nav-buffer 1 :route/confirm "nav-1" :route/cart)]
                  [:rf.xray/focus-event 1 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- variants-grid workspace ------------------------------------
  (rf.story/reg-workspace :Workspace.xray.routing/all
    {:doc      "All three Routes tab variants in one auto-grid.
                Scroll to see the panel's response across no-routes /
                current-route-only / from-to-transition."
     :layout   :variants-grid
     :for      :story.xray.routing
     :columns  2
     :tags     #{:dev}}))

(register-all!)
