(ns two-frame-isolation.core
  "Two-frame isolation testbed (rf2-6qgbs.1, repointed under rf2-wa8my) —
  THE canonical multi-frame isolation surface for Xray.

  ## Shape

  One page, two columns of attention:

    ┌──────────────────────────┬──────────────┐
    │  ABOVE frame (:above)    │              │
    │  ┌────────────────────┐  │   Xray      │
    │  │ standard-epochs    │  │   (inline)   │
    │  │ button ladder      │  │   frame      │
    │  └────────────────────┘  │   picker     │
    │  BELOW frame (:below)    │   switches   │
    │  ┌────────────────────┐  │   :above /   │
    │  │ standard-epochs    │  │   :below     │
    │  │ button ladder      │  │              │
    │  └────────────────────┘  │              │
    └──────────────────────────┴──────────────┘

  ONE app code path — the `standard-epochs.core/root` button ladder —
  mounted in TWO frame-providers (`:above` and `:below`) on the LEFT,
  with a Xray instance on the RIGHT (auto-mounted inline via the
  preload). The exercise IS observing the two frames diverge as the user
  drives each independently.

  ## Why standard-epochs ×2

  The earlier shape mounted the (now-deleted) shared `testdeck.*`
  tabs-as-routes panel. That coupled this testbed to a bespoke
  multi-module app whose tabs-as-routes machinery doubled as a manual
  multi-frame ROUTING demo. The routing surface now has its own home
  (`routes_epochs`, the gate's Routing-panel deck); the multi-frame
  isolation gate assertion lives on the framework `testbeds/multi_frame`
  surface. This testbed's single job is therefore the cleanest possible
  per-frame ISOLATION proof: mount the SAME deck twice and watch the two
  reactive contexts stay independent.

  `standard-epochs.core` registers every event / sub / view / cofx / fx /
  flow / schema ONCE globally at namespace load (requiring it below is
  what installs them); its `root` button-ladder view is the shared app
  code path both frames render. We do NOT call `standard-epochs.core/run`
  — that would mount the deck once into `#app` on the default frame. We
  reuse only its registrations + its `root` Var, and supply our own
  two-frame harness + mount here.

  ## What this proves — per-frame isolation

  Each `frame-provider`-rooted subtree is a fully isolated reactive
  context (Spec 006 §The cache is held inside the frame container): its
  own `app-db`, its own sub-cache, its own epoch history. Handlers and
  subs are registered ONCE globally; they resolve against whichever frame
  the dispatch envelope targets. The `reg-view`-injected `dispatch` /
  `subscribe` close over the surrounding frame-provider's frame id via
  React context, so the SAME view source (`standard-epochs.core/root`)
  produces two independent reactive contexts.

  The load-bearing rules this testbed exhibits:

    - Trace, events, and epochs are scoped to their frame: pressing a
      button in `:above` moves ONLY `:above`'s `:baseline` / `:views` /
      `:shapes`; `:below` stays put (and vice-versa).
    - Subs do NOT reach across frames — every sub reads the current
      frame's `app-db` only. There is no cross-frame projection helper,
      no shared root state. The cross-frame anti-pattern is structurally
      impossible: the injected accessors only ever see the current frame.

  ## Test surface, not tutorial

  No deliberate bugs, no teaching layers, no anti-pattern demos
  (feedback_testbeds_are_test_surfaces). The exception / slow-fx / schema
  buttons in the standard-epochs ladder are FEATURES being exercised —
  they light up Xray's Issues surface legitimately, per frame.

  Per rf2-8cevm this testbed carries no spec.cjs; regression coverage for
  multi-frame isolation lives in the substrate contract tests
  (`npm run test:cljs`) + the Xray feature-matrix gate's `multi-frame
  isolation substrate` scenario (on the framework `testbeds/multi_frame`
  surface)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Requiring `standard-epochs.core` installs every handler /
            ;; sub / view / cofx / fx / flow / schema ONCE globally and
            ;; gives us its `root` button-ladder view. We mount that view
            ;; twice below; we do NOT call its `run` (which would mount it
            ;; once into `#app` on the default frame).
            [standard-epochs.core :as se]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; rf2-6jyf6 — Xray's `configure!` to seed `:project-root`
            ;; so the Event lens 'open' chip resolves a classpath-relative
            ;; `:file` slot to an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FRAME IDS
;; ============================================================================

(def frame-above :above)
(def frame-below :below)

;; ============================================================================
;; ROOT VIEW — the standard-epochs ladder mounted twice, one per frame-provider
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
     ;; The SHARED app code path: the standard-epochs button ladder. The
     ;; reg-view-injected dispatch / subscribe close over THIS
     ;; frame-provider's frame id, so the same view source drives two
     ;; independent reactive contexts.
     [se/root]]))

(reg-view root []
  [:div {:data-testid "two-frame-isolation-root"
         :style {:font-family "system-ui, sans-serif"
                 :padding     "1em"
                 :max-width   "820px"}}
   [:header {:style {:margin-bottom "1em"}}
    [:h2 {:style {:margin 0}} "Two-frame isolation"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "Same app — the "
     [:code "standard-epochs"]
     " button ladder — mounted in two isolated reactive contexts. Each "
     [:code "frame-provider"]
     " below renders the same view source against a separate "
     [:code "app-db"] " + sub-cache + epoch history. Drive one frame; the "
     "other stays put. Switch frames in Xray (right) with the frame "
     "picker to compare trace / events / epochs per frame."]]
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

;; rf2-5dphw — open-in-editor project-root derived from the build
;; environment (the build-time `re-frame.testbed.config/repo-root`
;; goog-define joined with this testbed's tool-relative subdir), not a
;; hardcoded personal path. `?project-root=<path>` still overrides per
;; session. See `re-frame.testbed.config` for the cross-platform mechanism.
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads
  ;; the right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Register the two frames. There is no routing in the standard-epochs
  ;; deck, so neither frame owns the URL — both are plain isolated
  ;; contexts. Each `:on-create` seeds its app-db via the standard-epochs
  ;; reset event, synchronously, against that frame's empty db. The
  ;; `:standard-epochs/reset` handler is registered once globally and
  ;; resolves against whichever frame the dispatch envelope targets, so
  ;; per-frame state evolution is automatic.
  (rf/reg-frame frame-above {:on-create [:standard-epochs/reset]})
  (rf/reg-frame frame-below {:on-create [:standard-epochs/reset]})
  (rdc/render react-root [root]))
