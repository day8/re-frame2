(ns two-frame-isolation.core
  "Two-frame isolation testbed (rf2-6qgbs.1) — THE canonical multi-frame
  isolation surface for Xray. Replaces the parallel-frames prototype.

  ## Shape

  One page, three columns of attention:

    ┌──────────────────────────┬──────────────┐
    │  ABOVE frame (:above)    │              │
    │  ┌────────────────────┐  │   Xray      │
    │  │ tabs: Counter …    │  │   (inline)   │
    │  └────────────────────┘  │   frame      │
    │  BELOW frame (:below)    │   picker     │
    │  ┌────────────────────┐  │   switches   │
    │  │ tabs: Counter …    │  │   :above /   │
    │  └────────────────────┘  │   :below     │
    └──────────────────────────┴──────────────┘

  ONE app code path (the shared `testdeck.panel`), mounted in TWO frame-
  providers — `:above` and `:below` — on the LEFT, with a Xray instance
  on the RIGHT (auto-mounted inline via the preload). The exercise IS
  observing the two frames diverge as the user drives each independently.

  ## What this proves — per-frame isolation

  Each `frame-provider`-rooted subtree is a fully isolated reactive
  context (Spec 006 §The cache is held inside the frame container): its
  own `app-db`, its own router queue + route slot, its own sub-cache,
  its own machine snapshot. Handlers and subs are registered ONCE
  globally (in the `testdeck.*` modules); they resolve against whichever
  frame the dispatch envelope targets. The `reg-view`-injected
  `dispatch` / `subscribe` close over the surrounding frame-provider's
  frame id via React context, so the same view source produces two
  independent reactive contexts.

  The load-bearing rules this testbed exhibits:

    - Trace, events, issues, and cascades are scoped to their frame.
    - Subs do NOT reach across frames — every sub reads the current
      frame's `app-db` only. There is no cross-frame projection helper,
      no 'route data home' pattern, no shared root state. The
      cross-frame anti-pattern is structurally impossible: the injected
      accessors only ever see the current frame.
    - Tabs are routes (`testdeck.routes`): a tab click is
      `:rf.route/navigate`, scoped to the clicked frame — the ABOVE
      frame can sit on Counter while BELOW sits on Machine.

  ## URL ownership across two frames

  A browser has ONE address bar but this page has TWO routed frames.
  Per Spec 012 §Multi-frame routing only one frame may own the URL.
  Both `:above` and `:below` are registered NON-url-bound, so each
  frame's `:rf/route` slot updates on tab nav while the browser-history
  push no-ops (no two-frame URL race). The route SLOT — the isolation
  point — stays per-frame; only the shared browser-URL sync is
  suppressed.

  ## Test surface, not tutorial

  No deliberate bugs, no teaching layers, no anti-pattern demos
  (feedback_testbeds_are_test_surfaces). The slow async fetch
  (`testdeck.async`, ~600ms) and the clean handler-exception path
  (`testdeck.counter`) are FEATURES being exercised — they light up
  Xray's Issues lens legitimately. Regression coverage lives in the
  substrate contract tests + the Xray feature-matrix gate; this
  testbed is test-free (rf2-8cevm)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Loading these namespaces installs their late-bind hooks
            ;; and registers every testdeck handler / sub / route / view
            ;; ONCE globally. The panel is the shared app code path both
            ;; testdeck surfaces mount.
            [re-frame.routing]
            [testdeck.routes]
            [testdeck.counter]
            [testdeck.machine]
            [testdeck.async]
            [testdeck.panel :as panel]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; rf2-6jyf6 — Xray's `configure!` to seed `:project-root`
            ;; so the Event lens 'open' chip resolves a classpath-relative
            ;; `:file` slot to an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FRAME IDS
;; ============================================================================

(def frame-above :above)
(def frame-below :below)

;; ============================================================================
;; ROOT VIEW — the panel mounted twice, one per frame-provider
;; ============================================================================

(reg-view frame-card [frame-label]
  (let [accent (case frame-label
                 "above" "#2b7"
                 "below" "#36c"
                 "#444")]
    [:section {:data-testid (str frame-label "-frame")
               :style {:border        (str "1px solid " accent)
                       :border-radius "6px"
                       :padding       "0.75em 1em"
                       :background    (case frame-label
                                        "above" "#f7fff9"
                                        "below" "#f5f8ff"
                                        "#fafafa")
                       :margin        "0.5em 0"}}
     [:header {:style {:display "flex" :justify-content "space-between"
                       :align-items "baseline" :margin-bottom "0.5em"}}
      [:h3 {:style {:margin 0 :color accent :text-transform "uppercase"}}
       frame-label " frame "
       [:small {:style {:color "#666" :font-weight "normal"
                        :text-transform "none"}}
        "(" frame-label ")"]]
      [:span {:style {:font-size "11px" :color "#888"}}
       "isolated reactive context"]]
     [panel/panel frame-label]]))

(reg-view root []
  [:div {:data-testid "two-frame-isolation-root"
         :style {:font-family "system-ui, sans-serif"
                 :padding     "1em"
                 :max-width   "760px"}}
   [:header {:style {:margin-bottom "1em"}}
    [:h2 {:style {:margin 0}} "Two-frame isolation"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "Same app, two isolated reactive contexts. Each "
     [:code "frame-provider"]
     " below mounts the same view source against a separate "
     [:code "app-db"] " + sub-cache + route slot. Drive one frame; the "
     "other stays put. Switch frames in Xray (right) with the frame "
     "picker to compare trace / events / issues / cascades per frame."]]
   [:div {:style {:display "flex" :flex-direction "column"}}
    [rf/frame-provider {:frame frame-above}
     [frame-card "above"]]
    [rf/frame-provider {:frame frame-below}
     [frame-card "below"]]]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(def ^:private default-project-root
  "C:/Users/miket/code/re-frame2/tools/xray/testbeds")

(defn- query-param
  "Return the named URL query param as a string, or nil when absent /
  blank. Pure-data helper — kept private to this testbed since the
  query-string override is a per-host knob (not a Xray-API surface)."
  [param-name]
  (when (exists? js/window)
    (let [params (-> js/window .-location .-search (js/URLSearchParams.))
          v      (.get params param-name)]
      (when (and (string? v) (seq v)) v))))

(defn- resolve-project-root []
  (or (query-param "project-root") default-project-root))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads
  ;; the right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Register the two frames as NON-url-bound (Spec 012 §Multi-frame
  ;; routing): each gets its own route slot, but neither fights over the
  ;; single browser URL. Each `:on-create` seeds its app-db for all four
  ;; tabs and lands it on the default tab — synchronously, against that
  ;; frame's empty db. The `:testdeck/initialise` handler is registered
  ;; once globally and resolves against whichever frame the dispatch
  ;; envelope targets, so per-frame state evolution is automatic.
  (rf/reg-frame frame-above {:url-bound? false
                             :on-create  [:testdeck/initialise]})
  (rf/reg-frame frame-below {:url-bound? false
                             :on-create  [:testdeck/initialise]})
  (rdc/render react-root [root]))
