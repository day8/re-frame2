(ns panel-gallery.gallery-routing
  "Story coverage for the **Routes tab** of the 7-tab Causa chrome
  (rf2-nrbs9, reshaped per rf2-lq0ef).

  The Routes tab body is the `routing/Panel` view. The panel reads:

    - `:rf.causa/registered-routes`     — default `(rf/registrations
                                          :route)`; test override slot
                                          exists.
    - `:rf.causa/current-route-slice`   — default target-frame's
                                          `:rf/route`; test override
                                          slot exists.
    - `:rf.causa/cascades`              — drives FROM/TO detection.
    - `:rf.causa/focus`                 — the spine's focused
                                          dispatch-id.
    - `:rf.causa.routing/query`         — substring filter input.
    - `:rf.causa.routing/sim-url`       — Simulate-URL input.

  Each variant seeds the override slots via the test-only events
  (`:rf.causa/set-registered-routes-override-for-test`,
  `:rf.causa/set-current-route-slice-override-for-test`) and the
  trace-buffer via `:rf.causa/sync-trace-buffer` +
  `:rf.causa/focus-cascade` (for the FROM/TO variant). Search +
  Simulate-URL variants set the UI-state events directly."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-routing :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Routes tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-routing
    {:axis :feature
     :doc  "Causa Routes tab — flat catalogue of registered routes with
            substring search + Simulate-URL plus HERE/FROM/TO markers
            driven by the focused cascade per spec/016 §Routes tab +
            spec/018 §5.6. Decorative URL-path tree dropped per
            rf2-lq0ef (audit verdict B)."})

  (story/reg-story :story.causa.routing
    {:doc        "Visual gallery of the Causa Routes tab under varying
                 registrar shapes + nav cascades + UI inputs. Each
                 variant seeds the registered-routes + current-slice
                 override slots via test-only events; search +
                 Simulate-URL variants set the UI-state events
                 directly."
     :component  :panel-gallery.routing/Panel
     :tags       #{:dev :feature/causa-routing}
     :substrates #{:reagent}})

  ;; ----- 1. no routes registered (silent) ----------------------------
  (story/reg-variant :story.causa.routing/no-routes
    {:doc        "Host app has no routes registered. Panel renders
                 the silent empty-state — terse one-liner, no list,
                 no search, no Simulate-URL. Honours silent-by-
                 default per rf2-g3ghh."
     :events     [[:rf.causa/set-registered-routes-override-for-test {}]
                  [:rf.causa/set-current-route-slice-override-for-test nil]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. current route only (◆ HERE) ------------------------------
  (story/reg-variant :story.causa.routing/current-route-only
    {:doc        "Routes registered + a current slice; focused
                 cascade did NOT navigate. The current row carries
                 the ◆ HERE marker — orientation only. Metadata
                 badges (M / L / T / P) decorate routes carrying
                 :on-match / :can-leave / :tags / :parent."
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
                 destination surface below the catalogue."
     :events     [[:rf.causa/set-registered-routes-override-for-test
                   fixtures/cart-routes]
                  [:rf.causa/set-current-route-slice-override-for-test
                   fixtures/cart-slice]
                  [:rf.causa/sync-trace-buffer
                   (fixtures/nav-buffer 1 :route/confirm "nav-1")]
                  [:rf.causa/focus-cascade 1 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. search filter narrows the catalogue ----------------------
  (story/reg-variant :story.causa.routing/search-filter
    {:doc        "Larger registrar with the substring search input
                 populated — only routes whose route-id / path / doc
                 contains `api` render. Demonstrates the substring
                 filter contract per `routing-helpers/filter-rows`."
     :events     [[:rf.causa/set-registered-routes-override-for-test
                   fixtures/docs-routes]
                  [:rf.causa/set-current-route-slice-override-for-test
                   fixtures/docs-api-detail-slice]
                  [:rf.causa.routing/set-query "api"]
                  [:rf.causa/sync-trace-buffer
                   (fixtures/no-nav-buffer 1 [:docs/refresh])]
                  [:rf.causa/focus-cascade 1 nil]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 5. Simulate-URL surfaces ranked candidates ------------------
  (story/reg-variant :story.causa.routing/simulate-url-winner
    {:doc        "Paste a URL into Try URL; the panel ranks every
                 matching route by its 6-rule :rf.route/rank tuple
                 and highlights the winner. The load-bearing
                 interactive surface that exposes the structural
                 match contract per spec/012 §Route ranking algorithm."
     :events     [[:rf.causa/set-registered-routes-override-for-test
                   fixtures/docs-routes]
                  [:rf.causa.routing/set-sim-url "/blog/post"]
                  [:rf.causa/sync-trace-buffer
                   (fixtures/no-nav-buffer 1 [:noop])]
                  [:rf.causa/focus-cascade 1 nil]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- variants-grid workspace ------------------------------------
  (story/reg-workspace :workspace.causa.routing/all
    {:doc      "All five Routes tab variants in one auto-grid.
                Scroll to see the panel's response across no-routes /
                current-route-only / from-to-transition /
                search-filter / simulate-url-winner."
     :layout   :variants-grid
     :story    :story.causa.routing
     :columns  2
     :tags     #{:dev}}))

(register-all!)
