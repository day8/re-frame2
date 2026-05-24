(ns testdeck.routes
  "Testdeck route table (rf2-6qgbs.1) — the feature tabs ARE routes.

  The testdeck's four feature surfaces — Counter, Machine, Routing,
  Async&errors — are not a local `:active-tab` atom toggle; each is a
  registered route. Switching tabs is route navigation
  (`:rf.route/navigate`), so the address bar, the back button, and
  Xray's routing lens all observe tab movement the same way they
  observe any other navigation. This is the locked Xray-panel tab
  set rendered as a router.

  Shared across BOTH testdeck surfaces: the two-frame isolation
  testbed (`two-frame-isolation.core`, rf2-6qgbs.1) and the
  single-frame step-deck (rf2-6qgbs.2). Both mount the same panel
  (`testdeck.panel`) driven by this same table — the step-deck reuses
  this namespace verbatim.

  ## Per-frame routing

  Each frame is an isolated reactive context (Spec 006 §The cache is
  held inside the frame container). The two-frame testbed mounts two
  frame-providers; the route slot (`:rf.route/id`) lives in each
  frame's own `app-db`, so the ABOVE frame can sit on the Counter tab
  while the BELOW frame sits on the Machine tab — there is no shared
  route state and no cross-frame navigation. The `route-tab` view
  below dispatches `:rf.route/navigate` through the surrounding
  frame-provider's injected `dispatch`, so a tab click navigates ONLY
  the frame it was clicked in."
  (:require [re-frame.core :as rf]
            ;; Routing ships in day8/re-frame2-routing. Requiring
            ;; re-frame.routing triggers its load-time hook + the
            ;; `:rf.route/*` sub registrations; without it the
            ;; `rf/reg-route` calls below throw
            ;; :rf.error/routing-artefact-missing.
            [re-frame.routing]))

;; ============================================================================
;; TAB INVENTORY — the locked Xray-panel tab set, in display order
;; ============================================================================
;;
;; One entry per feature surface. `:id` is the route id; `:label` is the
;; tab caption; `:path` is the URL the tab navigates to. The panel view
;; renders one tab button per entry and switches its body on
;; `:rf.route/id`.

(def tabs
  "Ordered tab inventory. Each map: {:id <route-id> :label <string>
  :path <url>}. Consumed by `testdeck.panel` to render the tab bar and
  to switch the active body."
  [{:id :testdeck/counter :label "Counter"       :path "/counter"}
   {:id :testdeck/machine :label "Machine"       :path "/machine"}
   {:id :testdeck/routing :label "Routing"       :path "/routing"}
   {:id :testdeck/async   :label "Async & errors" :path "/async"}])

(def default-tab
  "The tab a fresh frame lands on. Counter is the simplest surface and
  the natural entry point."
  :testdeck/counter)

;; ============================================================================
;; ROUTES — one per tab
;; ============================================================================
;;
;; Registered once globally (routes are not per-frame; the route TABLE
;; is shared, the active route SLOT is per-frame). No `:on-match` side
;; effects: each tab's feature handlers seed their own slice of app-db
;; at frame `:on-create`, so navigation is pure URL ↔ route-id mapping.

(rf/reg-route :testdeck/counter
  {:doc  "Counter tab — L2 sub create/destroy, changeable-arg view, now cofx."
   :path "/counter"})

(rf/reg-route :testdeck/machine
  {:doc  "Machine tab — interactive websocket connection state machine."
   :path "/machine"})

(rf/reg-route :testdeck/routing
  {:doc  "Routing tab — the route table that drives tab navigation."
   :path "/routing"})

(rf/reg-route :testdeck/async
  {:doc  "Async & errors tab — async fx + error surfacing."
   :path "/async"})

;; ============================================================================
;; ACTIVE-TAB SUB
;; ============================================================================
;;
;; Reads the per-frame `:rf.route/id` slot. Resolves against whichever
;; frame the subscription runs in — so two frame-providers report two
;; independent active tabs. Falls back to `default-tab` before the
;; router has settled the first URL (the initial paint window).

(rf/reg-sub :testdeck/active-tab
  :<- [:rf.route/id]
  (fn [route-id _]
    (or route-id default-tab)))
