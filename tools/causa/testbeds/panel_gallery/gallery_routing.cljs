(ns panel-gallery.gallery-routing
  "Story coverage for the **Routing tab** of the 7-tab Causa chrome
  (rf2-nrbs9 — the new 7th L3 tab promoted from 'lives in App-db +
  Trace').

  The Routing tab body is the `routing/Panel` view. The panel reads:

    - `:rf.causa/registered-routes`     — default `(rf/registrations
                                          :route)`; test override slot
                                          exists.
    - `:rf.causa/current-route-slice`   — default target-frame's
                                          `:rf/route`; test override
                                          slot exists.
    - `:rf.causa/cascades`              — drives FROM/TO detection.
    - `:rf.causa/focus`                 — the spine's focused
                                          dispatch-id.

  Each variant seeds the override slots via the test-only events
  (`:rf.causa/set-registered-routes-override-for-test`,
  `:rf.causa/set-current-route-slice-override-for-test`) and the
  trace-buffer via `:rf.causa/sync-trace-buffer` +
  `:rf.causa/focus-cascade` (for the FROM/TO variant)."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-routing :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Routing tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-routing
    {:axis :feature
     :doc  "Causa Routing tab — lens over the registered-routes tree
            with HERE/FROM/TO markers driven by the focused cascade
            per spec/016 §Routing tab + spec/018 §5.6."})

  (story/reg-story :story.causa.routing
    {:doc        "Visual gallery of the Causa Routing tab under varying
                 registrar shapes + nav cascades. Each variant seeds
                 the registered-routes + current-slice override slots
                 via test-only events and the trace-buffer via
                 :rf.causa/sync-trace-buffer."
     :component  :panel-gallery.routing/Panel
     :tags       #{:dev :feature/causa-routing}
     :substrates #{:reagent}})

  ;; ----- 1. no routes registered (silent) ----------------------------
  (story/reg-variant :story.causa.routing/no-routes
    {:doc        "Host app has no routes registered. Panel renders
                 the silent empty-state — terse one-liner, no tree.
                 Honours silent-by-default per rf2-g3ghh."
     :events     [[:rf.causa/set-registered-routes-override-for-test {}]
                  [:rf.causa/set-current-route-slice-override-for-test nil]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. current route only (◆ HERE) ------------------------------
  (story/reg-variant :story.causa.routing/current-route-only
    {:doc        "Routes registered + a current slice; focused
                 cascade did NOT navigate. The current row carries
                 the ◆ HERE marker — orientation only."
     :events     [[:rf.causa/set-registered-routes-override-for-test
                   fixtures/cart-routes]
                  [:rf.causa/set-current-route-slice-override-for-test
                   fixtures/cart-slice]
                  [:rf.causa/sync-trace-buffer
                   (fixtures/no-nav-buffer 1 [:cart/refresh])]
                  [:rf.causa/focus-cascade 1 nil]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. FROM → TO transition (◆ FROM / ◆ TO) ---------------------
  (story/reg-variant :story.causa.routing/from-to-transition
    {:doc        "Focused cascade carried a nav-token allocation —
                 the panel renders ◆ FROM on the prior route and
                 ◆ TO on the destination. Params/query for the
                 destination surface below the tree."
     :events     [[:rf.causa/set-registered-routes-override-for-test
                   fixtures/cart-routes]
                  ;; Slice still on the FROM route so the helper can
                  ;; derive FROM ≠ TO. (Production: the slice writes
                  ;; the TO value during the cascade, but the panel
                  ;; can read it mid-flight — the lens shows both
                  ;; markers when the snapshot is split.)
                  [:rf.causa/set-current-route-slice-override-for-test
                   fixtures/cart-slice]
                  [:rf.causa/sync-trace-buffer
                   (fixtures/nav-buffer 1 :route/confirm "nav-1")]
                  [:rf.causa/focus-cascade 1 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. nested route tree (depth 3+) -----------------------------
  (story/reg-variant :story.causa.routing/nested-route-tree
    {:doc        "Deeper nesting exercises the indentation behaviour
                 at depth 3+ (`/docs/api/events/detail` lands at
                 depth 4). HERE marks the active deeply-nested
                 route; params + query surface below the tree."
     :events     [[:rf.causa/set-registered-routes-override-for-test
                   fixtures/deep-routes]
                  [:rf.causa/set-current-route-slice-override-for-test
                   fixtures/docs-api-detail-slice]
                  [:rf.causa/sync-trace-buffer
                   (fixtures/no-nav-buffer 1 [:docs/refresh])]
                  [:rf.causa/focus-cascade 1 nil]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- variants-grid workspace ------------------------------------
  (story/reg-workspace :workspace.causa.routing/all
    {:doc      "All four Routing tab variants in one auto-grid.
                Scroll to see the panel's response across no-routes /
                current-route-only / from-to-transition /
                nested-route-tree."
     :layout   :variants-grid
     :story    :story.causa.routing
     :columns  2
     :tags     #{:dev}}))

(register-all!)
